package com.nte.auction.ui

import com.nte.auction.domain.ScanMode
import com.nte.auction.domain.ScanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Activity、悬浮窗、视觉识别管线共用的一份对局状态。
 * 后续 VisionPipeline / Estimator 只需要向这里回写，不需要知道 UI 在 Activity 还是 Overlay。
 */
object AuctionStateStore {
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
            statusText = "新对局：请初始化仓库",
        )
    }

    fun startFullScan() {
        _state.update {
            it.copy(
                scanMode = ScanMode.FULL_SCAN,
                scanState = ScanState.WAITING_FOR_TOP,
                scanCoverage = 0.0,
                observedItems = 0,
                changedItems = 0,
                statusText = "初始化仓库：请从顶部向下滚动",
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

    fun updateScanProgress(
        coverage: Double,
        observedItems: Int,
        changedItems: Int,
        warehouse: WarehouseUiModel? = null,
    ) {
        _state.update {
            it.copy(
                scanState = if (coverage >= 0.999) ScanState.COMPLETE else ScanState.SCANNING,
                scanCoverage = coverage.coerceIn(0.0, 1.0),
                observedItems = observedItems,
                changedItems = changedItems,
                warehouse = warehouse ?: it.warehouse,
                statusText = if (coverage >= 0.999) "仓库信息已更新" else "继续向下滑动仓库",
            )
        }
    }

    fun updateEstimate(
        p25: Long?,
        p50: Long?,
        p75: Long?,
        safeBid: Long?,
        unknownRatio: Double?,
    ) {
        _state.update {
            it.copy(
                p25 = p25,
                p50 = p50,
                p75 = p75,
                safeBid = safeBid,
                unknownRatio = unknownRatio,
            )
        }
    }

    fun updateWarehouse(warehouse: WarehouseUiModel) {
        _state.update { it.copy(warehouse = warehouse) }
    }

    fun nextRound() {
        _state.update {
            val next = (it.round + 1).coerceAtMost(6)
            it.copy(
                round = next,
                scanState = if (it.sessionActive) ScanState.IDLE else it.scanState,
                statusText = "第 $next 回合：使用道具后执行快速刷新",
            )
        }
    }
}
