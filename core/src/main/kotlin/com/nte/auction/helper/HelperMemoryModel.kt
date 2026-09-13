package com.nte.auction.helper

import kotlin.math.max

data class HelperMemoryRecord(
    val prices: List<Long>,
    val timestamp: String = "",
    val source: String? = null,
)

data class HelperMemoryGroup(
    val name: String,
    val created: String = "",
    val records: List<HelperMemoryRecord> = emptyList(),
    val weight: Double = 1.0,
    val priceWeights: List<Double>? = null,
)

data class HelperMemorySnapshot(
    val groups: List<HelperMemoryGroup> = listOf(HelperMemoryGroup(DEFAULT_GROUP)),
    val activeGroups: Set<String> = setOf(DEFAULT_GROUP),
    val currentGroup: String = DEFAULT_GROUP,
) {
    companion object {
        const val DEFAULT_GROUP = "v1.3"
    }
}

data class HelperRedModel(
    val probabilities: Map<Long, Double>,
    val weightedCounts: Map<Long, Double>,
) {
    val mean: Double get() = probabilities.entries.sumOf { (price, probability) -> price * probability }
}

object HelperRedProbabilityModel {
    private const val MIN_RED_SAMPLES = 10
    private const val BLEND_WEIGHT = 0.5

    fun build(snapshot: HelperMemorySnapshot): HelperRedModel {
        val prices = NteHelperCatalog.redEstimationPool.map { it.price }
        val basePrior = normalizeWeights(prices, NteHelperCatalog.redPriorWeights)
        val selected = snapshot.groups.filter { it.name in snapshot.activeGroups }
            .ifEmpty { snapshot.groups.filter { it.name == snapshot.currentGroup }.take(1) }
            .ifEmpty { snapshot.groups.take(1) }

        if (selected.isEmpty()) {
            return HelperRedModel(basePrior, pseudoCounts(basePrior))
        }

        val combined = prices.associateWith { 0.0 }.toMutableMap()
        val combinedCounts = prices.associateWith { 0.0 }.toMutableMap()
        var totalMixWeight = 0.0
        var hasData = false

        for (group in selected) {
            val mixWeight = group.weight.coerceIn(0.0, 10.0)
            if (mixWeight <= 0.0) continue
            val groupModel = buildGroup(group, prices, basePrior)
            prices.forEach { price ->
                combined[price] = combined.getValue(price) + mixWeight * groupModel.probabilities.getValue(price)
                combinedCounts[price] = combinedCounts.getValue(price) + mixWeight * groupModel.weightedCounts.getValue(price)
            }
            totalMixWeight += mixWeight
            hasData = hasData || group.records.any { record -> record.prices.any { it in prices } }
        }

        if (totalMixWeight <= 0.0) {
            return HelperRedModel(basePrior, pseudoCounts(basePrior))
        }
        val probabilities = normalizeMap(combined.mapValues { it.value / totalMixWeight })
        return HelperRedModel(
            probabilities = probabilities,
            weightedCounts = if (hasData) combinedCounts else pseudoCounts(probabilities),
        )
    }

    fun conditionOnKnown(model: HelperRedModel, knownPrices: List<Long>): HelperRedModel {
        if (knownPrices.isEmpty()) return model
        val prices = model.probabilities.keys.toList()
        val counts = model.weightedCounts.toMutableMap()
        knownPrices.groupingBy { it }.eachCount().forEach { (price, count) ->
            if (price in counts) counts[price] = max(0.0, counts.getValue(price) - count.toDouble())
        }
        val total = counts.values.sum()
        if (total <= 0.0) return model
        val probabilities = prices.associateWith { counts.getValue(it) / total }
        return HelperRedModel(probabilities, counts)
    }

    fun restrictTo(model: HelperRedModel, candidates: Collection<Long>): HelperRedModel? {
        val allowed = candidates.toSet().intersect(model.probabilities.keys)
        if (allowed.isEmpty()) return null
        val mass = allowed.sumOf { model.probabilities[it] ?: 0.0 }
        if (mass <= 0.0) return null
        val probabilities = allowed.associateWith { model.probabilities.getValue(it) / mass }
        val counts = allowed.associateWith { model.weightedCounts[it] ?: 0.0 }
        return HelperRedModel(probabilities, counts)
    }

    /** Reference tool's "更新权重": prior × bounded posterior/prior boost, then normalize. */
    fun updatedPriceWeights(group: HelperMemoryGroup): List<Double> {
        val prices = NteHelperCatalog.redEstimationPool.map { it.price }
        val prior = group.priceWeights?.takeIf { it.size >= prices.size }
            ?.let { normalizeWeights(prices, it) }
            ?: normalizeWeights(prices, NteHelperCatalog.redPriorWeights)
        val counts = mutableMapOf<Long, Int>()
        group.records.forEach { record ->
            record.prices.filter { it in prices }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val total = counts.values.sum().toDouble()
        val alpha = 2.0
        val posteriorDenominator = total + alpha * prices.size
        val boosted = prices.map { price ->
            val posterior = ((counts[price] ?: 0) + alpha) / posteriorDenominator
            val priorP = (prior[price] ?: 0.0).coerceAtLeast(1e-9)
            val boost = (posterior / priorP).coerceIn(0.5, 3.0)
            priorP * boost
        }
        val sum = boosted.sum().takeIf { it > 0.0 } ?: 1.0
        return boosted.map { it / sum }
    }

    private fun buildGroup(
        group: HelperMemoryGroup,
        prices: List<Long>,
        globalPrior: Map<Long, Double>,
    ): HelperRedModel {
        val prior = group.priceWeights?.takeIf { it.size >= prices.size }
            ?.let { normalizeWeights(prices, it) }
            ?: globalPrior
        val counts = prices.associateWith { 0.0 }.toMutableMap()
        group.records.forEach { record ->
            record.prices.forEach { price -> if (price in counts) counts[price] = counts.getValue(price) + 1.0 }
        }
        val total = counts.values.sum()
        if (total <= 0.0) return HelperRedModel(prior, pseudoCounts(prior))
        val empirical = prices.associateWith { counts.getValue(it) / total }
        val probability = if (total < MIN_RED_SAMPLES) {
            normalizeMap(prices.associateWith { price ->
                BLEND_WEIGHT * empirical.getValue(price) + (1.0 - BLEND_WEIGHT) * prior.getValue(price)
            })
        } else empirical
        return HelperRedModel(probability, counts)
    }

    private fun normalizeWeights(prices: List<Long>, weights: List<Double>): Map<Long, Double> {
        val aligned = prices.indices.map { index -> (weights.getOrNull(index) ?: 0.0).coerceAtLeast(0.0) }
        val total = aligned.sum()
        if (total <= 0.0) return prices.associateWith { 1.0 / prices.size.coerceAtLeast(1) }
        return prices.indices.associate { prices[it] to aligned[it] / total }
    }

    private fun normalizeMap(values: Map<Long, Double>): Map<Long, Double> {
        val total = values.values.sum()
        if (total <= 0.0) return values.keys.associateWith { 1.0 / values.size.coerceAtLeast(1) }
        return values.mapValues { (_, value) -> value / total }
    }

    private fun pseudoCounts(probabilities: Map<Long, Double>): Map<Long, Double> {
        val mass = max(probabilities.size, 100).toDouble()
        return probabilities.mapValues { (_, probability) -> probability * mass }
    }
}
