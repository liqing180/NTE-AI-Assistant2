package com.nte.auction.ui

import androidx.lifecycle.ViewModel
import com.nte.auction.domain.ScanMode
import com.nte.auction.domain.ScanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AuctionViewModel : ViewModel() {
    private val _state = MutableStateFlow(AuctionUiState())
    val state: StateFlow<AuctionUiState> = _state.asStateFlow()

    fun onCaptureAuthorized() {
        _state.update { it.copy(captureAuthorized = true, statusText = "屏幕读取已授权") }
    }

    fun startNewAuction() {
        _state.value = AuctionUiState(
            captureAuthorized = _state.value.captureAuthorized,
            sessionActive = true,
            round = 1,
            statusText = "新对局：请滚动到仓库顶部",
        )
    }

    fun startFullScan() {
        _state.update {
            it.copy(
                scanMode = ScanMode.FULL_SCAN,
                scanState = ScanState.WAITING_FOR_TOP,
                scanCoverage = 0.0,
                statusText = "完整建仓：请从顶部向下滚动",
            )
        }
    }

    fun startFastRefresh() {
        _state.update {
            it.copy(
                scanMode = ScanMode.FAST_REFRESH,
                scanState = ScanState.SCANNING,
                scanCoverage = 0.0,
                changedItems = 0,
                statusText = "快速刷新：滑过整个仓库即可",
            )
        }
    }

    /** VisionPipeline 完成后通过此入口回写扫描进度。 */
    fun updateScanProgress(coverage: Double, observedItems: Int, changedItems: Int) {
        _state.update {
            it.copy(
                scanState = if (coverage >= 0.999) ScanState.COMPLETE else ScanState.SCANNING,
                scanCoverage = coverage.coerceIn(0.0, 1.0),
                observedItems = observedItems,
                changedItems = changedItems,
                statusText = if (coverage >= 0.999) "仓库信息已更新" else "继续向下滑动仓库",
            )
        }
    }

    fun nextRound() {
        _state.update { it.copy(round = it.round + 1, statusText = "第 ${it.round + 1} 回合") }
    }
}
