package com.nte.auction.helper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nte.auction.ui.WarehouseQualityUi
import com.nte.auction.ui.WarehouseUiModel

@Composable
fun HelperAnalysisPanel(
    helper: HelperFeatureUiState,
    warehouse: WarehouseUiModel,
    captureAuthorized: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val bidStatus by HelperBidController.status.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("异环拍卖分析", fontWeight = FontWeight.Bold)
                Text(helper.statsStatus, style = MaterialTheme.typography.labelMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumericField(
                        label = "金色均价",
                        value = helper.fields.goldAverage?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty(),
                        modifier = Modifier.weight(1f),
                        decimal = true,
                    ) { HelperFeatureStore.setGoldAverage(it.toDoubleOrNull()) }
                    NumericField(
                        label = "总件数",
                        value = helper.fields.totalItems?.toString().orEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { HelperFeatureStore.setTotalItems(it.toIntOrNull()) }
                    NumericField(
                        label = "紫色件数",
                        value = helper.fields.purpleCount.toString(),
                        modifier = Modifier.weight(1f),
                    ) { HelperFeatureStore.setPurpleCount(it.toIntOrNull() ?: 0) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumericField(
                        label = "金色占格",
                        value = helper.fields.goldTotalCells?.toString().orEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { HelperFeatureStore.setGoldTotalCells(it.toIntOrNull()) }
                    NumericField(
                        label = "已知金件数",
                        value = helper.fields.knownGoldCount?.toString().orEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { HelperFeatureStore.setKnownGoldCount(it.toIntOrNull()) }
                    NumericField(
                        label = "已知红件数",
                        value = helper.fields.knownRedCount?.toString().orEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { HelperFeatureStore.setKnownRedCount(it.toIntOrNull()) }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("金价×2", modifier = Modifier.weight(1f))
                    Switch(
                        checked = helper.fields.doubleGold,
                        onCheckedChange = HelperFeatureStore::setDoubleGold,
                    )
                    Spacer(Modifier.width(12.dp))
                    NumericField(
                        label = "未知红色数",
                        value = helper.unknownRedCount.toString(),
                        modifier = Modifier.width(130.dp),
                    ) { HelperFeatureStore.setUnknownRedCount(it.toIntOrNull() ?: 0) }
                }

                Text("红色估值模式", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EstimateModeButton("保守", HelperRedEstimateMode.CONSERVATIVE, helper.fields.redEstimateMode)
                    EstimateModeButton("中性", HelperRedEstimateMode.NEUTRAL, helper.fields.redEstimateMode)
                    EstimateModeButton("激进", HelperRedEstimateMode.AGGRESSIVE, helper.fields.redEstimateMode)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = HelperFeatureStore::requestStatsScan,
                        enabled = captureAuthorized && !helper.statsScanPending,
                        modifier = Modifier.weight(1f),
                    ) { Text("屏幕识别") }
                    Button(
                        onClick = { HelperFeatureStore.analyzeCurrent() },
                        modifier = Modifier.weight(1f),
                    ) { Text("分析") }
                    OutlinedButton(
                        onClick = HelperFeatureStore::resetInputs,
                        modifier = Modifier.weight(1f),
                    ) { Text("重置") }
                }
            }
        }

        HelperWarehouseCandidatePanel(helper, warehouse)
        HelperAnalysisResults(helper.analysis)
        HelperBidPanel(helper, bidStatus, onOpenAccessibilitySettings)
    }
}

@Composable
private fun HelperWarehouseCandidatePanel(helper: HelperFeatureUiState, warehouse: WarehouseUiModel) {
    val highValue = warehouse.items.filter {
        it.quality == WarehouseQualityUi.GOLD || it.quality == WarehouseQualityUi.RED
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("仓库候选确认", fontWeight = FontWeight.Bold)
            Text(
                "自动仓库识别负责尺寸/品质；这里按尺寸筛选原项目目录并确认具体藏品。",
                style = MaterialTheme.typography.labelSmall,
            )
            if (highValue.isEmpty()) {
                Text("暂无金/红藏品；可先在虚拟仓库中修正品质。", style = MaterialTheme.typography.bodySmall)
            }
            highValue.forEach { item ->
                val selected = helper.itemSelections[item.id]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (item.quality == WarehouseQualityUi.GOLD) "金" else "红"} ${item.width}×${item.height}",
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(selected?.let { "${it.name} · ${it.price}" } ?: "未确认", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    if (selected != null) {
                        TextButton(onClick = { HelperFeatureStore.clearWarehouseItemSelection(item.id) }) { Text("清除") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    HelperFeatureStore.candidatesForWarehouseItem(item.id).forEach { candidate ->
                        FilterChip(
                            selected = selected?.price == candidate.price,
                            onClick = { HelperFeatureStore.selectWarehouseItem(item.id, candidate.price) },
                            label = { Text("${candidate.name} ${candidate.price}") },
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HelperAnalysisResults(result: HelperAnalysisResult?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("估价结果", fontWeight = FontWeight.Bold)
            if (result == null) {
                Text("等待分析", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("红色均值 ${formatMoney(result.redMean)}")
                    Text("未知红均值 ${formatMoney(result.unknownRedMean)}")
                }
                if (result.unconfirmedRedSum > 0L) {
                    Text("未确认红色区域估值 ${formatMoney(result.unconfirmedRedSum)}", style = MaterialTheme.typography.labelMedium)
                }
                result.warning?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
                if (result.rows.isEmpty()) Text("没有符合当前约束的组合")
                result.rows.forEach { row ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("金 ${row.goldCount} · 红 ${row.redCount}${if (row.estimated) " · 估算" else ""}")
                                Text(formatMoney(row.totalValue), fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "区间 ${formatMoney(row.lowValue)} ～ ${formatMoney(row.highValue)} · 组合 ${row.comboCount}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            row.combos.forEachIndexed { index, combo ->
                                Text(
                                    "${index + 1}. ${combo.prices.joinToString(" + ")} · 格数 ${combo.sizes.joinToString("+")}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(combo.names.joinToString(" / "), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                result.unconfirmedRedRegionEstimates.forEach { region ->
                    Text(
                        "红 ${region.width}×${region.height}: ${region.candidateCount} 候选 · " +
                            (region.estimatedValue?.let(::formatMoney) ?: "无法估算"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun HelperBidPanel(
    helper: HelperFeatureUiState,
    bidStatus: String,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var customText by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("出价工具", fontWeight = FontWeight.Bold)
            NumericField(
                label = "出价金额",
                value = helper.bidAmount?.toString().orEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { HelperFeatureStore.setBidAmount(it.toLongOrNull()) }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                listOf(2.0, 1.6, 1.3, 1.1).forEach { multiplier ->
                    OutlinedButton(onClick = { HelperBidController.multiply(multiplier) }) { Text("×$multiplier") }
                }
                listOf(1L, 10L, 100L, 1000L).forEach { delta ->
                    OutlinedButton(onClick = { HelperBidController.increment(delta) }) { Text("+$delta") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = HelperBidController::useLowestEstimate, modifier = Modifier.weight(1f)) {
                    Text("最低估价填入")
                }
                Button(onClick = HelperBidController::performBid, modifier = Modifier.weight(1f)) {
                    Text("自动出价")
                }
            }
            Text(bidStatus, style = MaterialTheme.typography.labelSmall)
            if (!HelperAutoBidAccessibilityService.isAvailable) {
                TextButton(onClick = onOpenAccessibilitySettings) { Text("打开系统无障碍设置") }
            }

            Text("自定义金额", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                NumericField(
                    label = "新增金额",
                    value = customText,
                    modifier = Modifier.weight(1f),
                ) { customText = it }
                Button(onClick = {
                    customText.toLongOrNull()?.let(HelperFeatureStore::addCustomBid)
                    customText = ""
                }) { Text("保存") }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                helper.customBids.forEach { value ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AssistChip(
                            onClick = { HelperFeatureStore.setBidAmount(value) },
                            label = { Text(value.toString()) },
                        )
                        TextButton(
                            onClick = { HelperFeatureStore.removeCustomBid(value) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
fun HelperMemoryPanel(helper: HelperFeatureUiState) {
    val redModel = remember(helper.memory) { HelperRedProbabilityModel.build(helper.memory) }
    var newGroupName by remember { mutableStateOf("") }
    var manualPrice by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("红色记忆池", fontWeight = FontWeight.Bold)
                Text("混合红色均值 ${formatMoney(redModel.mean.toLong())} · ${helper.memory.groups.sumOf { it.records.size }} 条记录")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text("新组名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        HelperFeatureStore.createMemoryGroup(newGroupName)
                        newGroupName = ""
                    }) { Text("新建") }
                    OutlinedButton(onClick = HelperFeatureStore::undoMemoryDelete) { Text("撤销删除") }
                }
                Button(onClick = HelperFeatureStore::updateActiveMemoryWeights, modifier = Modifier.fillMaxWidth()) {
                    Text("按当前记忆更新红色权重")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("向当前组添加红色价格", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumericField(
                        label = "价格",
                        value = manualPrice,
                        modifier = Modifier.weight(1f),
                    ) { manualPrice = it }
                    Button(onClick = {
                        manualPrice.toLongOrNull()?.let { HelperFeatureStore.addMemoryRecord(listOf(it)) }
                        manualPrice = ""
                    }) { Text("添加") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    NteHelperCatalog.redAll.forEach { item ->
                        AssistChip(
                            onClick = { HelperFeatureStore.addMemoryRecord(listOf(item.price)) },
                            label = { Text(item.price.toString()) },
                        )
                    }
                }
            }
        }

        helper.memory.groups.forEach { group -> MemoryGroupCard(helper, group) }
    }
}

@Composable
private fun MemoryGroupCard(helper: HelperFeatureUiState, group: HelperMemoryGroup) {
    var renameText by remember(group.name) { mutableStateOf(group.name) }
    var weightText by remember(group.name, group.weight) { mutableStateOf(group.weight.toString()) }
    var showAll by remember(group.name) { mutableStateOf(false) }
    val indexedRecords = group.records.withIndex().toList()
    val records = if (showAll) indexedRecords else indexedRecords.takeLast(10)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, fontWeight = FontWeight.Bold)
                if (helper.memory.currentGroup == group.name) {
                    Text(" · 当前", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                Text("激活", style = MaterialTheme.typography.labelSmall)
                Switch(
                    checked = group.name in helper.memory.activeGroups,
                    onCheckedChange = { HelperFeatureStore.setMemoryGroupActive(group.name, it) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { HelperFeatureStore.setCurrentMemoryGroup(group.name) }) { Text("设为当前") }
                NumericField(
                    label = "组权重",
                    value = weightText,
                    modifier = Modifier.width(110.dp),
                    decimal = true,
                ) { text ->
                    weightText = text
                    text.toDoubleOrNull()?.let { HelperFeatureStore.setMemoryGroupWeight(group.name, it) }
                }
                OutlinedButton(onClick = { HelperFeatureStore.deleteMemoryGroup(group.name) }) { Text("删除组") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("重命名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { HelperFeatureStore.renameMemoryGroup(group.name, renameText) }) { Text("保存") }
            }
            Text("${group.records.size} 条记录", style = MaterialTheme.typography.labelSmall)
            records.forEach { indexed ->
                MemoryRecordRow(group.name, indexed.index, indexed.value)
            }
            if (group.records.size > 10) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "收起" else "显示全部") }
            }
        }
    }
}

@Composable
private fun MemoryRecordRow(groupName: String, index: Int, record: HelperMemoryRecord) {
    var editing by remember(groupName, index, record.prices) { mutableStateOf(false) }
    var text by remember(groupName, index, record.prices) { mutableStateOf(record.prices.joinToString(",")) }
    if (editing) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("价格，逗号分隔") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val prices = text.split(',').mapNotNull { it.trim().toLongOrNull() }
                if (prices.isNotEmpty()) HelperFeatureStore.editMemoryRecord(groupName, index, prices)
                editing = false
            }) { Text("保存") }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.prices.joinToString(", ") { formatMoney(it) }, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${record.timestamp}${record.source?.let { " · $it" }.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = { editing = true }) { Text("编辑") }
            TextButton(onClick = { HelperFeatureStore.deleteMemoryRecord(groupName, index) }) { Text("删除") }
        }
    }
}

@Composable
private fun EstimateModeButton(label: String, value: HelperRedEstimateMode, selected: HelperRedEstimateMode) {
    FilterChip(
        selected = value == selected,
        onClick = { HelperFeatureStore.setRedEstimateMode(value) },
        label = { Text(label) },
    )
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

private fun formatMoney(value: Long): String = when {
    value >= 10_000L -> "%.2f万".format(value / 10_000.0)
    else -> value.toString()
}
