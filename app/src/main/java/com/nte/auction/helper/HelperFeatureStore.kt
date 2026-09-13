package com.nte.auction.helper

import android.content.Context
import com.nte.auction.domain.Quality
import com.nte.auction.ui.AuctionStateStore
import com.nte.auction.ui.WarehouseQualityUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android replacement for the reference project's Flask + browser state. */
data class HelperScreenFields(
    val goldAverage: Double? = null,
    val totalItems: Int? = null,
    val purpleCount: Int = 0,
    val goldTotalCells: Int? = null,
    val knownGoldCount: Int? = null,
    val knownRedCount: Int? = null,
    val doubleGold: Boolean = false,
    val redEstimateMode: HelperRedEstimateMode = HelperRedEstimateMode.NEUTRAL,
)

data class HelperItemSelection(
    val warehouseItemId: String,
    val quality: Quality,
    val price: Long,
    val name: String,
)

data class HelperFeatureUiState(
    val fields: HelperScreenFields = HelperScreenFields(),
    val itemSelections: Map<String, HelperItemSelection> = emptyMap(),
    val unknownRedCount: Int = 0,
    val analysis: HelperAnalysisResult? = null,
    val memory: HelperMemorySnapshot = HelperMemorySnapshot(),
    val customBids: List<Long> = NteHelperCatalog.defaultCustomBids,
    val bidAmount: Long? = null,
    val statsScanPending: Boolean = false,
    val statsStatus: String = "拍卖参数待识别",
    val lastOcrLines: List<String> = emptyList(),
)

object HelperFeatureStore {
    private val analyzer = NteHelperAnalyzer()
    private val _state = MutableStateFlow(HelperFeatureUiState())
    val state: StateFlow<HelperFeatureUiState> = _state.asStateFlow()

    private var persistence: HelperPersistence? = null
    private var memoryUndo: HelperMemorySnapshot? = null

    @Synchronized
    fun initialize(context: Context) {
        if (persistence != null) return
        val store = HelperPersistence(context)
        persistence = store
        _state.update { it.copy(memory = store.loadMemory(), customBids = store.loadCustomBids()) }
    }

    fun setGoldAverage(value: Double?) = updateFields { copy(goldAverage = value?.takeIf { it > 0.0 }) }
    fun setTotalItems(value: Int?) = updateFields { copy(totalItems = value?.takeIf { it > 0 }) }
    fun setPurpleCount(value: Int) = updateFields { copy(purpleCount = value.coerceAtLeast(0)) }
    fun setGoldTotalCells(value: Int?) = updateFields { copy(goldTotalCells = value?.takeIf { it > 0 }) }
    fun setKnownGoldCount(value: Int?) = updateFields { copy(knownGoldCount = value?.takeIf { it > 0 }) }
    fun setKnownRedCount(value: Int?) = updateFields { copy(knownRedCount = value?.takeIf { it > 0 }) }
    fun setDoubleGold(value: Boolean) = updateFields { copy(doubleGold = value) }
    fun setRedEstimateMode(value: HelperRedEstimateMode) = updateFields { copy(redEstimateMode = value) }
    fun setUnknownRedCount(value: Int) = _state.update { it.copy(unknownRedCount = value.coerceAtLeast(0), analysis = null) }
    fun setBidAmount(value: Long?) = _state.update { it.copy(bidAmount = value?.takeIf { amount -> amount > 0L }) }

    fun resetInputs() {
        _state.update {
            it.copy(
                fields = HelperScreenFields(),
                itemSelections = emptyMap(),
                unknownRedCount = 0,
                analysis = null,
                bidAmount = null,
                statsStatus = "已重置拍卖参数",
                lastOcrLines = emptyList(),
            )
        }
    }

    fun requestStatsScan() {
        _state.update { it.copy(statsScanPending = true, statsStatus = "等待下一帧并识别拍卖参数…") }
    }

    @Synchronized
    fun consumeStatsScanRequest(): Boolean {
        if (!_state.value.statsScanPending) return false
        _state.update { it.copy(statsScanPending = false, statsStatus = "正在识别拍卖参数…") }
        return true
    }

    fun applyOcrFields(fields: HelperOcrFields) {
        _state.update { current ->
            val previous = current.fields
            current.copy(
                fields = previous.copy(
                    goldAverage = fields.goldAverage ?: previous.goldAverage,
                    totalItems = fields.totalItems ?: previous.totalItems,
                    purpleCount = fields.purpleCount ?: previous.purpleCount,
                    goldTotalCells = fields.goldTotalCells ?: previous.goldTotalCells,
                    knownGoldCount = fields.goldCount ?: previous.knownGoldCount,
                ),
                analysis = null,
                statsStatus = buildString {
                    append("屏幕识别完成")
                    fields.totalItems?.let { append(" · 总件数 $it") }
                    fields.purpleCount?.let { append(" · 紫 $it") }
                    fields.goldAverage?.let { append(" · 金均价 ${it.toLong()}") }
                },
                lastOcrLines = fields.rawLines,
            )
        }
    }

    fun failStatsScan(message: String) {
        _state.update { it.copy(statsScanPending = false, statsStatus = message) }
    }

    fun candidatesForWarehouseItem(itemId: String): List<HelperCatalogItem> {
        val item = AuctionStateStore.state.value.warehouse.items.firstOrNull { it.id == itemId } ?: return emptyList()
        return when (item.quality) {
            WarehouseQualityUi.GOLD -> NteHelperCatalog.candidates(Quality.GOLD, item.width, item.height)
            WarehouseQualityUi.RED -> NteHelperCatalog.candidates(Quality.RED, item.width, item.height)
            else -> emptyList()
        }
    }

    fun selectWarehouseItem(itemId: String, price: Long) {
        val candidate = candidatesForWarehouseItem(itemId).firstOrNull { it.price == price } ?: return
        _state.update { current ->
            current.copy(
                itemSelections = current.itemSelections + (
                    itemId to HelperItemSelection(itemId, candidate.quality, candidate.price, candidate.name)
                ),
                analysis = null,
            )
        }
    }

    fun clearWarehouseItemSelection(itemId: String) {
        _state.update { current ->
            current.copy(itemSelections = current.itemSelections - itemId, analysis = null)
        }
    }

    fun analyzeCurrent(): Result<HelperAnalysisResult> = runCatching {
        val ui = _state.value
        val fields = ui.fields
        val average = fields.goldAverage ?: error("请先输入或识别金色均价")
        val warehouse = AuctionStateStore.state.value.warehouse
        val goldItems = warehouse.items.filter { it.quality == WarehouseQualityUi.GOLD }
        val redItems = warehouse.items.filter { it.quality == WarehouseQualityUi.RED }
        val knownGoldSelections = ui.itemSelections.values.filter { it.quality == Quality.GOLD }
        val knownRedSelections = ui.itemSelections.values.filter { it.quality == Quality.RED }

        val request = HelperAnalyzeRequest(
            goldAverage = average,
            totalItems = fields.totalItems,
            purpleCount = fields.purpleCount,
            goldTotalCells = fields.goldTotalCells ?: goldItems.takeIf { it.isNotEmpty() }?.sumOf { it.width * it.height },
            knownGoldCount = fields.knownGoldCount,
            knownRedCount = fields.knownRedCount,
            knownGoldPrices = knownGoldSelections.map { it.price },
            knownRedPrices = knownRedSelections.map { it.price },
            unknownRedCount = ui.unknownRedCount,
            goldRegions = goldItems.map { item -> HelperRegionSpec(item.width, item.height) },
            unconfirmedRedRegions = redItems
                .filter { it.id !in ui.itemSelections }
                .map { item ->
                    HelperRegionSpec(
                        width = item.width,
                        height = item.height,
                        candidatePrices = NteHelperCatalog.candidates(Quality.RED, item.width, item.height)
                            .map { it.price }.toSet(),
                    )
                },
            doubleGold = fields.doubleGold,
            redEstimateMode = fields.redEstimateMode,
        )
        analyzer.analyze(request, ui.memory)
    }.onSuccess { result ->
        _state.update { it.copy(analysis = result, statsStatus = "分析完成 · ${result.rows.size} 种件数组合") }
        val knownRed = _state.value.itemSelections.values.filter { it.quality == Quality.RED }.map { it.price }
        if (knownRed.isNotEmpty()) addMemoryRecord(knownRed, source = "auto_from_analysis", deduplicateRecent = true)
    }.onFailure { error ->
        _state.update { it.copy(statsStatus = "分析失败：${error.message ?: error::class.java.simpleName}") }
    }

    fun addCustomBid(value: Long) {
        if (value <= 0L) return
        val next = (_state.value.customBids + value).distinct()
        persistence?.saveCustomBids(next)
        _state.update { it.copy(customBids = next) }
    }

    fun removeCustomBid(value: Long) {
        val next = _state.value.customBids.filter { it != value }
        persistence?.saveCustomBids(next)
        _state.update { it.copy(customBids = next) }
    }

    fun addMemoryRecord(
        prices: List<Long>,
        groupName: String = _state.value.memory.currentGroup,
        source: String? = null,
        deduplicateRecent: Boolean = false,
    ) {
        val valid = prices.filter { it >= 0L }
        if (valid.isEmpty()) return
        mutateMemory { memory ->
            memory.copy(groups = memory.groups.map { group ->
                if (group.name != groupName) return@map group
                var nextPrices = valid
                if (deduplicateRecent) {
                    val recent = group.records.asReversed().asSequence()
                        .filter { it.source == source }
                        .take(5)
                        .flatMap { it.prices.asSequence() }
                        .toSet()
                    nextPrices = valid.filter { it !in recent }
                }
                if (nextPrices.isEmpty()) group else group.copy(
                    records = group.records + HelperMemoryRecord(
                        prices = nextPrices,
                        timestamp = nowText(),
                        source = source,
                    )
                )
            })
        }
    }

    fun createMemoryGroup(name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) return
        mutateMemory { memory ->
            if (memory.groups.any { it.name == normalized }) return@mutateMemory memory
            memory.copy(
                groups = memory.groups + HelperMemoryGroup(normalized, created = todayText()),
                activeGroups = memory.activeGroups + normalized,
                currentGroup = normalized,
            )
        }
    }

    fun renameMemoryGroup(oldName: String, newName: String) {
        val normalized = newName.trim()
        if (normalized.isEmpty()) return
        mutateMemory { memory ->
            if (memory.groups.none { it.name == oldName } || memory.groups.any { it.name == normalized }) return@mutateMemory memory
            memory.copy(
                groups = memory.groups.map { if (it.name == oldName) it.copy(name = normalized) else it },
                activeGroups = memory.activeGroups.mapTo(linkedSetOf()) { if (it == oldName) normalized else it },
                currentGroup = if (memory.currentGroup == oldName) normalized else memory.currentGroup,
            )
        }
    }

    fun deleteMemoryGroup(name: String) {
        memoryUndo = _state.value.memory
        mutateMemory(saveUndo = false) { memory ->
            val remaining = memory.groups.filter { it.name != name }.ifEmpty {
                listOf(HelperMemoryGroup(HelperMemorySnapshot.DEFAULT_GROUP, created = todayText()))
            }
            val current = memory.currentGroup.takeIf { current -> remaining.any { it.name == current } } ?: remaining.first().name
            val active = memory.activeGroups.filterTo(linkedSetOf()) { activeName -> remaining.any { it.name == activeName } }
                .ifEmpty { linkedSetOf(current) }
            memory.copy(groups = remaining, activeGroups = active, currentGroup = current)
        }
    }

    fun deleteMemoryRecord(groupName: String, index: Int) {
        memoryUndo = _state.value.memory
        mutateMemory(saveUndo = false) { memory ->
            memory.copy(groups = memory.groups.map { group ->
                if (group.name != groupName || index !in group.records.indices) group
                else group.copy(records = group.records.filterIndexed { idx, _ -> idx != index })
            })
        }
    }

    fun editMemoryRecord(groupName: String, index: Int, prices: List<Long>) {
        val valid = prices.filter { it >= 0L }
        if (valid.isEmpty()) return
        mutateMemory { memory ->
            memory.copy(groups = memory.groups.map { group ->
                if (group.name != groupName || index !in group.records.indices) group
                else group.copy(records = group.records.mapIndexed { idx, record ->
                    if (idx == index) record.copy(prices = valid, timestamp = nowText()) else record
                })
            })
        }
    }

    fun undoMemoryDelete() {
        val previous = memoryUndo ?: return
        memoryUndo = null
        persistence?.saveMemory(previous)
        _state.update { it.copy(memory = previous, analysis = null) }
    }

    fun setCurrentMemoryGroup(name: String) {
        mutateMemory { memory -> if (memory.groups.any { it.name == name }) memory.copy(currentGroup = name) else memory }
    }

    fun setMemoryGroupActive(name: String, active: Boolean) {
        mutateMemory { memory ->
            if (memory.groups.none { it.name == name }) return@mutateMemory memory
            val next = if (active) memory.activeGroups + name else memory.activeGroups - name
            memory.copy(activeGroups = next.ifEmpty { setOf(name) })
        }
    }

    fun setMemoryGroupWeight(name: String, weight: Double) {
        mutateMemory { memory ->
            memory.copy(groups = memory.groups.map { group ->
                if (group.name == name) group.copy(weight = weight.coerceIn(0.0, 10.0)) else group
            })
        }
    }

    fun updateActiveMemoryWeights() {
        mutateMemory { memory ->
            memory.copy(groups = memory.groups.map { group ->
                if (group.name in memory.activeGroups && group.records.isNotEmpty()) {
                    group.copy(priceWeights = HelperRedProbabilityModel.updatedPriceWeights(group))
                } else group
            })
        }
    }

    private fun updateFields(block: HelperScreenFields.() -> HelperScreenFields) {
        _state.update { it.copy(fields = it.fields.block(), analysis = null) }
    }

    private fun mutateMemory(
        saveUndo: Boolean = false,
        block: (HelperMemorySnapshot) -> HelperMemorySnapshot,
    ) {
        val current = _state.value.memory
        if (saveUndo) memoryUndo = current
        val next = block(current)
        persistence?.saveMemory(next)
        _state.update { it.copy(memory = next, analysis = null) }
    }

    private fun nowText(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun todayText(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
