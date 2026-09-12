package com.nte.auction.solver

import com.nte.auction.domain.*

data class CandidateSet(
    val itemId: Long,
    val candidates: MutableList<Collectible>
)

data class CandidateResolution(
    val byItem: Map<Long, List<Collectible>>,
    val contradictions: List<String> = emptyList()
) {
    val isValid: Boolean get() = contradictions.isEmpty() && byItem.values.none { it.isEmpty() }
}

class CandidateResolver(
    private val collectibles: List<Collectible>,
) {
    fun resolve(session: AuctionSession): CandidateResolution {
        val basePool = collectibles.filter { it.pearlEnabled }
        val sets = linkedMapOf<Long, CandidateSet>()
        val contradictions = mutableListOf<String>()

        for ((id, item) in session.warehouse.items) {
            val evidence = EvidenceResolver.resolve(item)
            val filtered = basePool.asSequence()
                .filter { evidence.quality == null || it.quality == evidence.quality }
                .filter { evidence.type == null || it.type == evidence.type }
                .filter { evidence.silhouetteId == null || it.silhouetteId == evidence.silhouetteId }
                .filter { evidence.size == null || evidence.size.matches(it.size, it.canRotate) }
                .toMutableList()

            if (filtered.isEmpty()) {
                contradictions += "Item $id has no candidates after item-level evidence"
            }
            sets[id] = CandidateSet(id, filtered)
        }

        val propagator = GlobalConstraintPropagator(session.globalEvidence)
        propagator.propagate(sets, contradictions)

        return CandidateResolution(
            byItem = sets.mapValues { (_, value) -> value.candidates.toList() },
            contradictions = contradictions.distinct()
        )
    }
}
