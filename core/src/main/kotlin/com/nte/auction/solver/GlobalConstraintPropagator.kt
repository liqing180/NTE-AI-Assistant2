package com.nte.auction.solver

import com.nte.auction.domain.*

class GlobalConstraintPropagator(
    evidence: List<GlobalEvidence>,
) {
    private val evidence = evidence.filter { it.confidence >= HARD_THRESHOLD }

    fun propagate(
        sets: MutableMap<Long, CandidateSet>,
        contradictions: MutableList<String>,
    ) {
        var changed: Boolean
        var guard = 0
        do {
            changed = false
            changed = applyQualityCountConstraints(sets, contradictions) || changed
            changed = applyTypeCountConstraints(sets, contradictions) || changed
            changed = applyHighQualityCountConstraint(sets, contradictions) || changed
            changed = applyQualityCellConstraints(sets, contradictions) || changed
            changed = applyQualityPriceConstraints(sets, contradictions) || changed
            guard++
        } while (changed && guard < 32)
    }

    private fun applyQualityCountConstraints(
        sets: MutableMap<Long, CandidateSet>,
        contradictions: MutableList<String>,
    ): Boolean {
        var changed = false
        latestQualityCounts().forEach { (quality, target) ->
            val definite = sets.values.count { set ->
                set.candidates.isNotEmpty() && set.candidates.all { it.quality == quality }
            }
            val possibleSets = sets.values.filter { set -> set.candidates.any { it.quality == quality } }
            val possible = possibleSets.size

            if (target < definite || target > possible) {
                contradictions += "Quality count $quality=$target impossible: definite=$definite possible=$possible"
                return@forEach
            }

            if (definite == target) {
                sets.values.filter { set -> set.candidates.any { it.quality != quality } }.forEach { set ->
                    val before = set.candidates.size
                    if (!set.candidates.all { it.quality == quality }) {
                        set.candidates.removeAll { it.quality == quality }
                    }
                    changed = changed || before != set.candidates.size
                }
            } else if (possible == target) {
                possibleSets.forEach { set ->
                    val before = set.candidates.size
                    set.candidates.removeAll { it.quality != quality }
                    changed = changed || before != set.candidates.size
                }
            }
        }
        return changed
    }

    private fun applyTypeCountConstraints(
        sets: MutableMap<Long, CandidateSet>,
        contradictions: MutableList<String>,
    ): Boolean {
        var changed = false
        latestTypeCounts().forEach { (type, target) ->
            val definite = sets.values.count { it.candidates.isNotEmpty() && it.candidates.all { c -> c.type == type } }
            val possibleSets = sets.values.filter { it.candidates.any { c -> c.type == type } }
            val possible = possibleSets.size
            if (target < definite || target > possible) {
                contradictions += "Type count $type=$target impossible: definite=$definite possible=$possible"
                return@forEach
            }
            if (definite == target) {
                sets.values.filter { !it.candidates.all { c -> c.type == type } }.forEach { set ->
                    val before = set.candidates.size
                    set.candidates.removeAll { c -> c.type == type }
                    changed = changed || before != set.candidates.size
                }
            } else if (possible == target) {
                possibleSets.forEach { set ->
                    val before = set.candidates.size
                    set.candidates.removeAll { c -> c.type != type }
                    changed = changed || before != set.candidates.size
                }
            }
        }
        return changed
    }

    private fun applyHighQualityCountConstraint(
        sets: MutableMap<Long, CandidateSet>,
        contradictions: MutableList<String>,
    ): Boolean {
        val target = evidence.filterIsInstance<HighQualityTotalCountEvidence>()
            .maxByOrNull { it.round }?.count ?: return false
        val definite = sets.values.count { it.candidates.isNotEmpty() && it.candidates.all { c -> c.quality.isHighValue } }
        val possibleSets = sets.values.filter { it.candidates.any { c -> c.quality.isHighValue } }
        val possible = possibleSets.size
        if (target < definite || target > possible) {
            contradictions += "High quality total=$target impossible: definite=$definite possible=$possible"
            return false
        }
        var changed = false
        if (definite == target) {
            sets.values.filter { !it.candidates.all { c -> c.quality.isHighValue } }.forEach { set ->
                val before = set.candidates.size
                set.candidates.removeAll { c -> c.quality.isHighValue }
                changed = changed || before != set.candidates.size
            }
        } else if (possible == target) {
            possibleSets.forEach { set ->
                val before = set.candidates.size
                set.candidates.removeAll { c -> !c.quality.isHighValue }
                changed = changed || before != set.candidates.size
            }
        }
        return changed
    }

    private fun applyQualityCellConstraints(
        sets: MutableMap<Long, CandidateSet>,
        contradictions: MutableList<String>,
    ): Boolean {
        var changed = false
        latestQualityCells().forEach { (quality, target) ->
            changed = pruneByAdditiveTarget(
                sets = sets,
                targetLow = target.toLong(),
                targetHigh = target.toLong(),
                contribution = { c -> if (c.quality == quality) c.size.cells.toLong() else 0L },
                description = "$quality total cells=$target",
                contradictions = contradictions,
            ) || changed
        }
        return changed
    }

    private fun applyQualityPriceConstraints(
        sets: MutableMap<Long, CandidateSet>,
        contradictions: MutableList<String>,
    ): Boolean {
        var changed = false
        val explicitTotals = latestQualityTotals().toMutableMap()
        val counts = latestQualityCounts()
        latestQualityAverages().forEach { (quality, avg) ->
            val count = counts[quality] ?: return@forEach
            explicitTotals.putIfAbsent(
                quality,
                PriceTarget(avg.averagePrice * count, avg.tolerance * count)
            )
        }

        explicitTotals.forEach { (quality, target) ->
            changed = pruneByAdditiveTarget(
                sets = sets,
                targetLow = target.value - target.tolerance,
                targetHigh = target.value + target.tolerance,
                contribution = { c -> if (c.quality == quality) c.price else 0L },
                description = "$quality total price=${target.value}±${target.tolerance}",
                contradictions = contradictions,
            ) || changed
        }
        return changed
    }

    private fun pruneByAdditiveTarget(
        sets: MutableMap<Long, CandidateSet>,
        targetLow: Long,
        targetHigh: Long,
        contribution: (Collectible) -> Long,
        description: String,
        contradictions: MutableList<String>,
    ): Boolean {
        if (sets.values.any { it.candidates.isEmpty() }) return false

        val ids = sets.keys.toList()
        val mins = LongArray(ids.size)
        val maxs = LongArray(ids.size)
        ids.forEachIndexed { index, id ->
            val values = sets.getValue(id).candidates.map(contribution)
            mins[index] = values.minOrNull() ?: 0L
            maxs[index] = values.maxOrNull() ?: 0L
        }
        val globalMin = mins.sum()
        val globalMax = maxs.sum()
        if (globalMax < targetLow || globalMin > targetHigh) {
            contradictions += "$description impossible: range=$globalMin..$globalMax"
            return false
        }

        var changed = false
        ids.forEachIndexed { index, id ->
            val set = sets.getValue(id)
            val otherMin = globalMin - mins[index]
            val otherMax = globalMax - maxs[index]
            val iterator = set.candidates.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val value = contribution(candidate)
                val minWith = otherMin + value
                val maxWith = otherMax + value
                if (maxWith < targetLow || minWith > targetHigh) {
                    iterator.remove()
                    changed = true
                }
            }
            if (set.candidates.isEmpty()) contradictions += "Item $id emptied by $description"
        }
        return changed
    }

    private fun latestQualityCounts(): Map<Quality, Int> = evidence
        .filterIsInstance<QualityCountEvidence>()
        .groupBy { it.quality }
        .mapValues { (_, list) -> list.maxBy { it.round }.count }

    private fun latestTypeCounts(): Map<CollectibleType, Int> = evidence
        .filterIsInstance<TypeCountEvidence>()
        .groupBy { it.type }
        .mapValues { (_, list) -> list.maxBy { it.round }.count }

    private fun latestQualityCells(): Map<Quality, Int> = evidence
        .filterIsInstance<QualityTotalCellsEvidence>()
        .groupBy { it.quality }
        .mapValues { (_, list) -> list.maxBy { it.round }.totalCells }

    private fun latestQualityTotals(): Map<Quality, PriceTarget> = evidence
        .filterIsInstance<QualityTotalPriceEvidence>()
        .groupBy { it.quality }
        .mapValues { (_, list) ->
            val latest = list.maxBy { it.round }
            PriceTarget(latest.totalPrice, latest.tolerance)
        }

    private fun latestQualityAverages(): Map<Quality, QualityAveragePriceEvidence> = evidence
        .filterIsInstance<QualityAveragePriceEvidence>()
        .groupBy { it.quality }
        .mapValues { (_, list) -> list.maxBy { it.round } }

    private data class PriceTarget(val value: Long, val tolerance: Long)

    companion object {
        private const val HARD_THRESHOLD = 0.75
    }
}
