package com.nte.auction.ui

import com.nte.auction.domain.ScanMode
import com.nte.auction.domain.ScanState

data class AuctionUiState(
    val captureAuthorized: Boolean = false,
    val sessionActive: Boolean = false,
    val round: Int = 1,
    val scanMode: ScanMode? = null,
    val scanState: ScanState = ScanState.IDLE,
    val scanCoverage: Double = 0.0,
    val observedItems: Int = 0,
    val changedItems: Int = 0,
    val p25: Long? = null,
    val p50: Long? = null,
    val p75: Long? = null,
    val safeBid: Long? = null,
    val unknownRatio: Double? = null,
    val statusText: String = "等待开始",
    val warehouse: WarehouseUiModel = WarehouseUiModel(),
)

data class WarehouseUiModel(
    val columns: Int = 5,
    val rows: Int = 0,
    val items: List<WarehouseUiItem> = emptyList(),
)

data class WarehouseUiItem(
    val id: String,
    val row: Int,
    val column: Int,
    val width: Int,
    val height: Int,
    val label: String = "?",
    val quality: WarehouseQualityUi = WarehouseQualityUi.UNKNOWN,
    val confidence: Float = 0f,
    val changedThisRound: Boolean = false,
)

enum class WarehouseQualityUi {
    UNKNOWN,
    WHITE,
    GREEN,
    BLUE,
    PURPLE,
    GOLD,
    RED,
}
