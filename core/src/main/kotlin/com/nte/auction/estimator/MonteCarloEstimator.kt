package com.nte.auction.estimator

import com.nte.auction.domain.*
import com.nte.auction.solver.CandidateResolution
import kotlin.math.max
import kotlin.random.Random

data class EstimateDistribution(
    val acceptedSamples: Int,
    val attempts: Int,
    val p10: Long,
    val p25: Long,
    val p50: Long,
    val mean: Long,
    val p75: Long,
    val p90: Long,
    val min: Long,
    val max: Long,
    val knownValue: Long,
    val unknownExpectedValue: Long,
    val unknownRatio: Double,
    val confidence: Double,
)

class MonteCarloEstimator(
    private val targetSamples: Int = 10_000,
    private val maxAttemptsMultiplier: Int = 30,
    private val seed: Int = 20260912,
) {
    fun estimate(
        resolution: CandidateResolution,
        globalEvidence: List<GlobalEvidence>,
    ): EstimateDistribution {
        require(resolution.isValid) { "Cannot estimate invalid resolution: ${resolution.contradictions}" }
        require(resolution.byItem.isNotEmpty()) { "No warehouse items" }

        val random = Random(seed)
        val sortedEntries = resolution.byItem.entries.sortedBy { it.key }
        val accepted = ArrayList<Long>(targetSamples)
        val maxAttempts = max(targetSamples, targetSamples * maxAttemptsMultiplier)
        var attempts = 0

        while (accepted.size < targetSamples && attempts < maxAttempts) {
            attempts++
            val sample = LinkedHashMap<Long, Collectible>(sortedEntries.size)
            for ((itemId, candidates) in sortedEntries) {
                sample[itemId] = weightedPick(candidates, random)
            }
            if (!satisfiesGlobalEvidence(sample.values.toList(), globalEvidence)) continue
            accepted += sample.values.sumOf { it.price }
        }

        if (accepted.isEmpty()) {
            error("No valid Monte Carlo states after $attempts attempts")
        }

        accepted.sort()
        val mean = accepted.average().toLong()
        val knownValue = sortedEntries.sumOf { (_, candidates) ->
            if (candidates.size == 1) candidates.first().price else 0L
        }
        val unknownExpected = max(0L, mean - knownValue)
        val unknownRatio = if (mean <= 0L) 0.0 else unknownExpected.toDouble() / mean.toDouble()
        val acceptanceRate = accepted.size.toDouble() / attempts.toDouble()
        val sampleCompleteness = (accepted.size.toDouble() / targetSamples.toDouble()).coerceIn(0.0, 1.0)
        val informationConfidence = (1.0 - unknownRatio).coerceIn(0.0, 1.0)
        val confidence = (0.50 * informationConfidence + 0.30 * sampleCompleteness + 0.20 * acceptanceRate)
            .coerceIn(0.0, 1.0)

        return EstimateDistribution(
            acceptedSamples = accepted.size,
            attempts = attempts,
            p10 = percentile(accepted, 0.10),
            p25 = percentile(accepted, 0.25),
            p50 = percentile(accepted, 0.50),
            mean = mean,
            p75 = percentile(accepted, 0.75),
            p90 = percentile(accepted, 0.90),
            min = accepted.first(),
            max = accepted.last(),
            knownValue = knownValue,
            unknownExpectedValue = unknownExpected,
            unknownRatio = unknownRatio,
            confidence = confidence,
        )
    }

    private fun weightedPick(candidates: List<Collectible>, random: Random): Collectible {
        require(candidates.isNotEmpty())
        if (candidates.size == 1) return candidates.first()
        val weights = candidates.map { max(0.0, it.pearlBaseWeight) }
        val total = weights.sum()
        if (total <= 0.0) return candidates[random.nextInt(candidates.size)]
        var cursor = random.nextDouble(total)
        for (i in candidates.indices) {
            cursor -= weights[i]
            if (cursor <= 0.0) return candidates[i]
        }
        return candidates.last()
    }

    private fun satisfiesGlobalEvidence(
        items: List<Collectible>,
        evidence: List<GlobalEvidence>,
    ): Boolean {
        val hard = evidence.filter { it.confidence >= 0.75 }
        for (e in hard) {
            when (e) {
                is QualityCountEvidence -> if (items.count { it.quality == e.quality } != e.count) return false
                is TypeCountEvidence -> if (items.count { it.type == e.type } != e.count) return false
                is HighQualityTotalCountEvidence -> if (items.count { it.quality.isHighValue } != e.count) return false
                is QualityAveragePriceEvidence -> {
                    val matched = items.filter { it.quality == e.quality }
                    if (matched.isEmpty()) return false
                    val average = matched.sumOf { it.price }.toDouble() / matched.size.toDouble()
                    if (average < e.averagePrice - e.tolerance || average > e.averagePrice + e.tolerance) return false
                }
                is QualityTotalPriceEvidence -> {
                    val total = items.filter { it.quality == e.quality }.sumOf { it.price }
                    if (total < e.totalPrice - e.tolerance || total > e.totalPrice + e.tolerance) return false
                }
                is QualityTotalCellsEvidence -> {
                    val cells = items.filter { it.quality == e.quality }.sumOf { it.size.cells }
                    if (cells != e.totalCells) return false
                }
                is SystemEstimateEvidence -> {
                    if (items.sumOf { it.price } < e.minimumValue) return false
                }
            }
        }
        return true
    }

    private fun percentile(sorted: List<Long>, q: Double): Long {
        if (sorted.size == 1) return sorted.first()
        val index = ((sorted.lastIndex) * q).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
