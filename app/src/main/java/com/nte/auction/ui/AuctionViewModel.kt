package com.nte.auction.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class AuctionViewModel : ViewModel() {
    val state: StateFlow<AuctionUiState> = AuctionStateStore.state

    fun onCaptureAuthorized() = AuctionStateStore.onCaptureAuthorized()
    fun startNewAuction() = AuctionStateStore.startNewAuction()
    fun updateWarehouseFromScreenshot() = AuctionStateStore.requestWarehouseSnapshot()

    /** VisionPipeline 完成后通过此入口回写扫描进度。 */
    fun updateScanProgress(
        coverage: Double,
        observedItems: Int,
        changedItems: Int,
        warehouse: WarehouseUiModel? = null,
    ) = AuctionStateStore.updateScanProgress(
        coverage = coverage,
        observedItems = observedItems,
        changedItems = changedItems,
        warehouse = warehouse,
    )

    fun nextRound() = AuctionStateStore.nextRound()
}
