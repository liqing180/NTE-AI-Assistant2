package com.nte.auction.helper

import com.nte.auction.solver.BoundedCombinationSearch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

enum class HelperRedEstimateMode { CONSERVATIVE, NEUTRAL, AGGRESSIVE }

data class HelperRegionSpec(
    val width: Int,
    val height: Int,
    val candidatePrices: Set<Long> = emptySet(),
)

data class HelperAnalyzeRequest(
    val goldAverage: Double,
    val totalItems: Int? = null,
    val purpleCount: Int = 0,
    val goldTotalCells: Int? = null,
    val knownGoldCount: Int? = null,
    val knownRedCount: Int? = null,
    val minGoldCount: Int = 0,
    val minRedCount: Int = 0,
    val knownGoldPrices: List<Long> = emptyList(),
    val knownRedPrices: List<Long> = emptyList(),
    val unknownRedCount: Int = 0,
    val goldRegions: List<HelperRegionSpec> = emptyList(),
    val unconfirmedRedRegions: List<HelperRegionSpec> = emptyList(),
    val doubleGold: Boolean = false,
    val redEstimateMode: HelperRedEstimateMode = HelperRedEstimateMode.NEUTRAL,
)

data class HelperComboDetail(
    val prices: List<Long>,
    val names: List<String>,
    val sizes: List<Int>,
    val totalCells: Int,
)

data class HelperRegionEstimate(
    val width: Int,
    val height: Int,
    val candidateCount: Int,
    val lowValue: Long?,
    val estimatedValue: Long?,
    val highValue: Long?,
)

data class HelperAnalysisRow(
    val goldCount: Int,
    val redCount: Int,
    val totalValue: Long,
    val lowValue: Long,
    val highValue: Long,
    val estimated: Boolean,
    val comboCount: Int,
    val combos: List<HelperComboDetail>,
    val truncated: Boolean,
)

data class HelperAnalysisResult(
    val rows: List<HelperAnalysisRow>,
    val redMean: Long,
    val unknownRedMean: Long,
    val unconfirmedRedSum: Long,
    val unconfirmedRedRegionEstimates: List<HelperRegionEstimate>,
    val warning: String? = null,
) {
    val lowestEstimate: Long? get() = rows.minOfOrNull { it.totalValue }
}

class NteHelperAnalyzer(
    private val combinationSearch: BoundedCombinationSearch = BoundedCombinationSearch(),
) {
    fun analyze(
        request: HelperAnalyzeRequest,
        memory: HelperMemorySnapshot = HelperMemorySnapshot(),
    ): HelperAnalysisResult {
        require(request.goldAverage > 0.0) { "金色均价必须大于 0" }
        require(request.purpleCount >= 0) { "紫色件数不能为负数" }

        val redModel = HelperRedProbabilityModel.build(memory)
        val conditioned = HelperRedProbabilityModel.conditionOnKnown(redModel, request.knownRedPrices)
        val regionEstimates = estimateUnconfirmedRegions(conditioned, request.unconfirmedRedRegions)
        val unconfirmedRedSum = regionEstimates.sumOf { it.estimatedValue ?: 0L }
        val unknownRedMean = conditioned.mean.toLong()
        val minimumRed = maxOf(
            request.minRedCount,
            request.knownRedPrices.size + request.unknownRedCount + request.unconfirmedRedRegions.size,
        )
        val normalizedKnownGold = request.knownGoldPrices.map { normalizeGoldPrice(it, request.doubleGold) }
        val minimumGold = maxOf(request.minGoldCount, normalizedKnownGold.size)
        val requiredGoldCounts = resolveGoldCounts(request, minimumGold)
        val rows = mutableListOf<HelperAnalysisRow>()
        var anyTruncated = false

        for (goldCount in requiredGoldCounts) {
            val redCount = request.totalItems?.let { it - request.purpleCount - goldCount }
                ?: maxOf(request.knownRedCount ?: 0, minimumRed)
            if (goldCount <= 0 || redCount < minimumRed) continue

            val range = estimateTotalRange(
                request = request,
                goldCount = goldCount,
                redCount = redCount,
                conditionedRedModel = conditioned,
                regionEstimates = regionEstimates,
            )

            if (goldCount > MAX_EXACT_GOLD_COUNT) {
                rows += HelperAnalysisRow(
                    goldCount = goldCount,
                    redCount = redCount,
                    totalValue = range.mid,
                    lowValue = range.low,
                    highValue = range.high,
                    estimated = true,
                    comboCount = 0,
                    combos = emptyList(),
                    truncated = false,
                )
                continue
            }

            val effectivePrices = NteHelperCatalog.gold.map { effectiveGoldPrice(it.price, request.doubleGold) }
            val low = floor(goldCount * (request.goldAverage - ERROR_MARGIN)).toLong()
            val high = ceil(goldCount * (request.goldAverage + ERROR_MARGIN)).toLong() + 1L
            val rawCombos = combinationSearch.search(
                values = effectivePrices,
                count = goldCount,
                low = low,
                high = high,
                maxRepeat = MAX_REPEAT,
                maxResults = MAX_COMBOS + 1,
            )
            val truncated = rawCombos.size > MAX_COMBOS
            anyTruncated = anyTruncated || truncated
            val combos = rawCombos.take(MAX_COMBOS)
                .filter { comboMatches(it, request, normalizedKnownGold) }
            if (combos.isEmpty()) continue

            rows += HelperAnalysisRow(
                goldCount = goldCount,
                redCount = redCount,
                totalValue = range.mid,
                lowValue = range.low,
                highValue = range.high,
                estimated = false,
                comboCount = combos.size,
                combos = combos.take(DISPLAY_COMBOS).map { comboDetail(it, request.doubleGold) },
                truncated = truncated,
            )
        }

        return HelperAnalysisResult(
            rows = rows.sortedBy { it.goldCount },
            redMean = redModel.mean.toLong(),
            unknownRedMean = unknownRedMean,
            unconfirmedRedSum = unconfirmedRedSum,
            unconfirmedRedRegionEstimates = regionEstimates,
            warning = if (anyTruncated && normalizedKnownGold.isNotEmpty()) "包含指定金价的组合可能因结果上限而未显示" else null,
        )
    }

    private fun resolveGoldCounts(request: HelperAnalyzeRequest, minimumGold: Int): List<Int> {
        request.knownGoldCount?.takeIf { it > 0 }?.let { return listOf(it) }
        if ((request.knownRedCount ?: 0) > 0 && request.totalItems != null) {
            val count = request.totalItems - request.purpleCount - request.knownRedCount!!
            return if (count > 0) listOf(count) else emptyList()
        }
        if (request.totalItems != null) {
            val maximum = request.totalItems - request.purpleCount
            return if (maximum > 0) (maxOf(1, minimumGold)..maximum).toList() else emptyList()
        }
        return (maxOf(1, minimumGold)..MAX_LIST_SIZE).toList()
    }

    private fun comboMatches(
        effectiveCombo: List<Long>,
        request: HelperAnalyzeRequest,
        normalizedKnownGold: List<Long>,
    ): Boolean {
        val baseCombo = effectiveCombo.map { normalizeGoldPrice(it, request.doubleGold) }
        if (!containsMultiset(baseCombo, normalizedKnownGold)) return false

        val regionSets = request.goldRegions.mapNotNull { region ->
            val candidates = if (region.candidatePrices.isNotEmpty()) {
                region.candidatePrices.map { normalizeGoldPrice(it, request.doubleGold) }.toSet()
            } else {
                NteHelperCatalog.candidates(com.nte.auction.domain.Quality.GOLD, region.width, region.height)
                    .map { it.price }.toSet()
            }
            candidates.takeIf { it.isNotEmpty() }
        }
        if (regionSets.any { candidates -> baseCombo.none { it in candidates } }) return false

        request.goldTotalCells?.takeIf { it > 0 }?.let { requiredCells ->
            val cells = baseCombo.sumOf { NteHelperCatalog.goldByPrice(it)?.cells ?: 0 }
            if (cells != requiredCells) return false
        }
        return true
    }

    private fun containsMultiset(haystack: List<Long>, needles: List<Long>): Boolean {
        if (needles.isEmpty()) return true
        val have = haystack.groupingBy { it }.eachCount()
        val need = needles.groupingBy { it }.eachCount()
        return need.all { (price, count) -> (have[price] ?: 0) >= count }
    }

    private fun comboDetail(effectiveCombo: List<Long>, doubled: Boolean): HelperComboDetail {
        val items = effectiveCombo.mapNotNull { effective ->
            NteHelperCatalog.goldByPrice(normalizeGoldPrice(effective, doubled))
        }
        return HelperComboDetail(
            prices = effectiveCombo,
            names = items.map { it.name },
            sizes = items.map { it.cells },
            totalCells = items.sumOf { it.cells },
        )
    }

    private fun estimateUnconfirmedRegions(
        conditioned: HelperRedModel,
        regions: List<HelperRegionSpec>,
    ): List<HelperRegionEstimate> = regions.map { region ->
        val candidates = if (region.candidatePrices.isNotEmpty()) {
            region.candidatePrices.filter { it <= 1_000_000L }.toSet()
        } else {
            NteHelperCatalog.candidates(com.nte.auction.domain.Quality.RED, region.width, region.height)
                .map { it.price }.filter { it <= 1_000_000L }.toSet()
        }
        val restricted = HelperRedProbabilityModel.restrictTo(conditioned, candidates)
        if (restricted == null) {
            HelperRegionEstimate(region.width, region.height, candidates.size, null, null, null)
        } else {
            val low = percentile(restricted.probabilities, 0.25)
            val mid = restricted.mean.toLong()
            val high = percentile(restricted.probabilities, 0.75)
            HelperRegionEstimate(region.width, region.height, candidates.size, low, mid, high)
        }
    }

    private data class ValueRange(val low: Long, val mid: Long, val high: Long)

    private fun estimateTotalRange(
        request: HelperAnalyzeRequest,
        goldCount: Int,
        redCount: Int,
        conditionedRedModel: HelperRedModel,
        regionEstimates: List<HelperRegionEstimate>,
    ): ValueRange {
        val goldTotal = (request.goldAverage * goldCount).toLong()
        val knownRedSum = request.knownRedPrices.sum()
        val regionLow = regionEstimates.sumOf { it.lowValue ?: 0L }
        val regionMid = regionEstimates.sumOf { it.estimatedValue ?: 0L }
        val regionHigh = regionEstimates.sumOf { it.highValue ?: 0L }
        val remaining = (redCount - request.knownRedPrices.size - request.unconfirmedRedRegions.size).coerceAtLeast(0)
        val unknown = estimateUnknownRedSum(conditionedRedModel.probabilities, remaining, request.redEstimateMode)
        return ValueRange(
            low = goldTotal + knownRedSum + regionLow + unknown.low,
            mid = goldTotal + knownRedSum + regionMid + unknown.mid,
            high = goldTotal + knownRedSum + regionHigh + unknown.high,
        )
    }

    private fun estimateUnknownRedSum(
        pmf: Map<Long, Double>,
        count: Int,
        mode: HelperRedEstimateMode,
    ): ValueRange {
        if (count <= 0) return ValueRange(0, 0, 0)
        val distribution = if (count <= RED_EXACT_LIMIT) exactSumDistribution(pmf, count) else sampleSumDistribution(pmf, count)
        val low = percentile(distribution, 0.25)
        val high = percentile(distribution, 0.75)
        val mid = when (mode) {
            HelperRedEstimateMode.CONSERVATIVE -> low
            HelperRedEstimateMode.AGGRESSIVE -> high
            HelperRedEstimateMode.NEUTRAL -> distribution.entries.sumOf { (value, probability) -> value * probability }.toLong()
        }
        return ValueRange(low, mid, high)
    }

    private fun exactSumDistribution(single: Map<Long, Double>, count: Int): Map<Long, Double> {
        var current = mapOf(0L to 1.0)
        repeat(count) {
            val next = mutableMapOf<Long, Double>()
            current.forEach { (sum, p1) ->
                single.forEach { (value, p2) -> next[sum + value] = (next[sum + value] ?: 0.0) + p1 * p2 }
            }
            current = next
        }
        return current
    }

    private fun sampleSumDistribution(single: Map<Long, Double>, count: Int): Map<Long, Double> {
        val prices = single.keys.sorted()
        val weights = prices.map { single[it] ?: 0.0 }
        val cumulative = mutableListOf<Double>()
        var running = 0.0
        weights.forEach { running += it; cumulative += running }
        val random = Random(20260818)
        val counts = mutableMapOf<Long, Int>()
        repeat(8_000) {
            var sum = 0L
            repeat(count) {
                val needle = random.nextDouble() * running
                val index = cumulative.indexOfFirst { needle <= it }.let { if (it < 0) cumulative.lastIndex else it }
                sum += prices[index]
            }
            counts[sum] = (counts[sum] ?: 0) + 1
        }
        return counts.mapValues { (_, hits) -> hits / 8_000.0 }
    }

    private fun percentile(distribution: Map<Long, Double>, q: Double): Long {
        var cumulative = 0.0
        distribution.toSortedMap().forEach { (value, probability) ->
            cumulative += probability
            if (cumulative >= q - 1e-12) return value
        }
        return distribution.keys.maxOrNull() ?: 0L
    }

    private fun normalizeGoldPrice(price: Long, doubled: Boolean): Long {
        if (!doubled) return price
        return if (NteHelperCatalog.gold.any { it.price == price }) price else price / 2L
    }

    private fun effectiveGoldPrice(basePrice: Long, doubled: Boolean): Long = if (doubled) basePrice * 2L else basePrice

    private companion object {
        const val ERROR_MARGIN = 1.0
        const val MAX_EXACT_GOLD_COUNT = 7
        const val MAX_LIST_SIZE = 14
        const val MAX_REPEAT = 2
        const val MAX_COMBOS = 500
        const val DISPLAY_COMBOS = 10
        const val RED_EXACT_LIMIT = 6
    }
}
