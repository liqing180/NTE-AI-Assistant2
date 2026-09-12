package com.nte.auction.domain

enum class Quality {
    WHITE, GREEN, BLUE, PURPLE, GOLD, RED;

    val isHighValue: Boolean get() = this == PURPLE || this == GOLD || this == RED
}

enum class CollectibleType {
    ANTIQUE,
    JEWEL,
    TECHNOLOGY,
    FOOD,
    COMMODITY,
    SUPERPOWER
}

data class GridSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "GridSize must be positive" }
    }

    val cells: Int get() = width * height

    fun matches(other: GridSize, rotatable: Boolean): Boolean {
        if (this == other) return true
        return rotatable && width == other.height && height == other.width
    }
}

data class Collectible(
    val id: String,
    val name: String,
    val type: CollectibleType,
    val quality: Quality,
    val size: GridSize,
    val price: Long,
    val silhouetteId: String,
    val pearlBaseWeight: Double = 1.0,
    val pearlEnabled: Boolean = true,
    val canRotate: Boolean = false,
) {
    init {
        require(price >= 0) { "price must be >= 0" }
        require(pearlBaseWeight >= 0.0) { "pearlBaseWeight must be >= 0" }
    }
}

enum class EvidenceSource {
    OCR,
    VISION,
    USER,
    DERIVED,
    IMPORTED
}

sealed interface Evidence {
    val round: Int
    val confidence: Double
    val source: EvidenceSource
}

sealed interface ItemEvidence : Evidence {
    val itemId: Long
}

data class QualityEvidence(
    override val itemId: Long,
    val quality: Quality,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : ItemEvidence

data class TypeEvidence(
    override val itemId: Long,
    val type: CollectibleType,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : ItemEvidence

data class SilhouetteEvidence(
    override val itemId: Long,
    val silhouetteId: String,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : ItemEvidence

data class DimensionEvidence(
    override val itemId: Long,
    val size: GridSize,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : ItemEvidence

sealed interface GlobalEvidence : Evidence

data class QualityCountEvidence(
    val quality: Quality,
    val count: Int,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : GlobalEvidence

data class TypeCountEvidence(
    val type: CollectibleType,
    val count: Int,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : GlobalEvidence

data class QualityAveragePriceEvidence(
    val quality: Quality,
    val averagePrice: Long,
    val tolerance: Long = 0,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : GlobalEvidence

data class QualityTotalPriceEvidence(
    val quality: Quality,
    val totalPrice: Long,
    val tolerance: Long = 0,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : GlobalEvidence

data class QualityTotalCellsEvidence(
    val quality: Quality,
    val totalCells: Int,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : GlobalEvidence

data class HighQualityTotalCountEvidence(
    val count: Int,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : GlobalEvidence

data class SystemEstimateEvidence(
    val minimumValue: Long,
    override val round: Int,
    override val confidence: Double,
    override val source: EvidenceSource,
) : GlobalEvidence

enum class RegionKnowledge {
    EMPTY,
    UNKNOWN,
    UNSCANNED
}

data class WarehouseItem(
    val id: Long,
    val row: Int,
    val column: Int,
    val size: GridSize,
    val mask: Set<Pair<Int, Int>> = defaultMask(size),
    val evidenceHistory: MutableList<ItemEvidence> = mutableListOf(),
) {
    companion object {
        fun defaultMask(size: GridSize): Set<Pair<Int, Int>> = buildSet {
            for (r in 0 until size.height) {
                for (c in 0 until size.width) add(r to c)
            }
        }
    }
}

data class Warehouse(
    val items: MutableMap<Long, WarehouseItem> = linkedMapOf(),
    val regionKnowledge: MutableMap<Pair<Int, Int>, RegionKnowledge> = linkedMapOf(),
    var maxObservedRow: Int = -1,
    var totalRows: Int? = null,
)

enum class ScanMode { FULL_SCAN, FAST_REFRESH }
enum class ScanState { IDLE, WAITING_FOR_TOP, SCANNING, NEED_OVERLAP, COMPLETE, ERROR }

data class WarehouseScanSession(
    val sessionId: String,
    val mode: ScanMode,
    val coveredRows: MutableSet<Int> = mutableSetOf(),
    var state: ScanState = ScanState.IDLE,
    var maxObservedRow: Int = -1,
    var totalRows: Int? = null,
) {
    val coverage: Double
        get() {
            val total = totalRows ?: return 0.0
            if (total <= 0) return 0.0
            return coveredRows.count { it in 0 until total }.toDouble() / total.toDouble()
        }
}

data class AuctionSession(
    val id: String,
    var round: Int = 1,
    val warehouse: Warehouse = Warehouse(),
    val globalEvidence: MutableList<GlobalEvidence> = mutableListOf(),
)
