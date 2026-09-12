package com.nte.auction.domain

data class ResolvedItemEvidence(
    val quality: Quality? = null,
    val type: CollectibleType? = null,
    val silhouetteId: String? = null,
    val size: GridSize? = null,
)

object EvidenceResolver {
    private const val HARD_THRESHOLD = 0.75

    fun resolve(item: WarehouseItem): ResolvedItemEvidence {
        fun <T : ItemEvidence> best(clazz: Class<T>): T? = item.evidenceHistory
            .asSequence()
            .filter { clazz.isInstance(it) && it.confidence >= HARD_THRESHOLD }
            .map { clazz.cast(it) }
            .maxWithOrNull(compareBy<T> { it.round }.thenBy { it.confidence })

        return ResolvedItemEvidence(
            quality = best(QualityEvidence::class.java)?.quality,
            type = best(TypeEvidence::class.java)?.type,
            silhouetteId = best(SilhouetteEvidence::class.java)?.silhouetteId,
            size = best(DimensionEvidence::class.java)?.size ?: item.size,
        )
    }
}
