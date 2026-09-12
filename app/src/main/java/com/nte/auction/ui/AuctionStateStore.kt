package com.nte.auction.ui

import com.nte.auction.capture.WarehouseSnapshotRecognizer
import com.nte.auction.domain.ScanMode
import com.nte.auction.domain.ScanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Activity、悬浮窗、截图识别共用的一份对局状态。 */
object AuctionStateStore {
    private val _state = MutableStateFlow(AuctionUiState())
    val state: StateFlow<AuctionUiState> = _state.asStateFlow()

    private val coveredRows = linkedSetOf<Int>()

    @Volatile
    private var warehouseSnapshotPending = false

    fun onCaptureAuthorized() {
        _state.update { it.copy(captureAuthorized = true, statusText = "屏幕读取已授权") }
    }

    fun startNewAuction() {
        coveredRows.clear()
        warehouseSnapshotPending = false
        _state.value = AuctionUiState(
            captureAuthorized = _state.value.captureAuthorized,
            sessionActive = true,
            round = 1,
            statusText = "新对局：打开仓库后点击“识别/更新仓库”",
        )
    }

    /**
     * 初始化仓库和快速刷新统一走这里。
     * 每次点击只消费下一张完整屏幕截图，识别当前 viewport，并按滚动条位置增量合并。
     */
    fun requestWarehouseSnapshot() {
        val current = _state.value
        if (!current.sessionActive) {
            _state.update { it.copy(statusText = "请先开始新对局") }
            return
        }
        if (!current.captureAuthorized) {
            _state.update { it.copy(statusText = "请先授权截图") }
            return
        }
        warehouseSnapshotPending = true
        _state.update {
            it.copy(
                scanMode = if (it.warehouse.rows <= 0) ScanMode.FULL_SCAN else ScanMode.FAST_REFRESH,
                scanState = ScanState.SCANNING,
                statusText = "正在截取当前仓库画面…",
            )
        }
    }

    /** ProjectionCaptureService 每帧调用；一次请求只允许一帧进入识别器。 */
    @Synchronized
    fun consumeWarehouseSnapshotRequest(): Boolean {
        if (!warehouseSnapshotPending) return false
        warehouseSnapshotPending = false
        return true
    }

    fun applyWarehouseSnapshot(result: WarehouseSnapshotRecognizer.Result) {
        val previous = _state.value.warehouse
        val visibleStart = result.viewportStartRow
        val visibleEnd = (visibleStart + result.visibleRows - 1).coerceAtMost(result.totalRows - 1)

        for (row in visibleStart..visibleEnd) coveredRows += row
        coveredRows.removeAll { it !in 0 until result.totalRows }

        val previousById = previous.items.associateBy { it.id }

        // 只替换“完整落在当前 viewport 内”的旧对象；跨 viewport 边界的对象保留，
        // 防止滚动裁切时把完整对象误删。
        val retained = previous.items.filter { item ->
            val itemEnd = item.row + item.height - 1
            !(item.row >= visibleStart && itemEnd <= visibleEnd)
        }.toMutableList()

        val detected = result.items.map { item ->
            val old = previousById[item.stableId]
            val quality = when {
                item.quality != WarehouseQualityUi.UNKNOWN -> item.quality
                old != null -> old.quality
                else -> WarehouseQualityUi.UNKNOWN
            }
            WarehouseUiItem(
                id = item.stableId,
                row = item.row,
                column = item.column,
                width = item.width,
                height = item.height,
                label = old?.label ?: "?",
                quality = quality,
                confidence = maxOf(item.confidence, old?.confidence ?: 0f),
                changedThisRound = old == null || old.quality != quality,
            )
        }

        val nextItems = (retained + detected)
            .distinctBy { it.id }
            .sortedWith(compareBy<WarehouseUiItem> { it.row }.thenBy { it.column })

        val oldVisibleIds = previous.items
            .filter { it.row >= visibleStart && it.row + it.height - 1 <= visibleEnd }
            .map { it.id }
            .toSet()
        val newVisibleIds = detected.map { it.id }.toSet()
        val changed = (oldVisibleIds - newVisibleIds).size +
            (newVisibleIds - oldVisibleIds).size +
            detected.count { newItem ->
                val old = previousById[newItem.id]
                old != null && old.quality != newItem.quality
            }

        val coverage = if (result.totalRows > 0) {
            coveredRows.count { it in 0 until result.totalRows }.toDouble() / result.totalRows.toDouble()
        } else {
            0.0
        }
        val complete = coverage >= 0.999
        val percent = (result.scrollRatio * 100.0).toInt().coerceIn(0, 100)

        _state.update {
            it.copy(
                scanState = if (complete) ScanState.COMPLETE else ScanState.IDLE,
                scanCoverage = coverage.coerceIn(0.0, 1.0),
                observedItems = nextItems.size,
                changedItems = changed,
                warehouse = WarehouseUiModel(
                    columns = result.columns,
                    rows = result.totalRows,
                    items = nextItems,
                ),
                statusText = "截图已更新：第 ${visibleStart + 1}-${visibleEnd + 1} 行 · 滚动条 $percent%",
            )
        }
    }

    fun failWarehouseSnapshot(message: String) {
        _state.update {
            it.copy(
                scanState = ScanState.ERROR,
                statusText = message,
            )
        }
    }

    /** 保留旧视觉管线回写入口，避免后续模块调用断裂。 */
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
                statusText = if (coverage >= 0.999) "仓库信息已更新" else "仓库识别中",
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

    fun correctItemQuality(itemId: String, quality: WarehouseQualityUi) {
        _state.update { state ->
            state.copy(
                warehouse = state.warehouse.copy(
                    items = state.warehouse.items.map { item ->
                        if (item.id == itemId) item.copy(quality = quality, confidence = 1f) else item
                    },
                ),
                statusText = "已人工修正藏品 $itemId",
            )
        }
    }

    fun nextRound() {
        _state.update {
            val next = (it.round + 1).coerceAtMost(6)
            it.copy(
                round = next,
                scanState = ScanState.IDLE,
                changedItems = 0,
                warehouse = it.warehouse.copy(
                    items = it.warehouse.items.map { item -> item.copy(changedThisRound = false) },
                ),
                statusText = "第 $next 回合：仓库变化后点击“识别/更新仓库”",
            )
        }
    }
}
