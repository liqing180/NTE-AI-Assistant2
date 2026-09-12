package com.nte.auction.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun AuctionOverlayContent(
    state: AuctionUiState,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onClose: () -> Unit,
    onDrag: (dx: Int, dy: Int) -> Unit,
    onWarehouseSnapshot: () -> Unit,
) {
    MaterialTheme {
        if (collapsed) {
            Surface(
                shape = CircleShape,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .size(64.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                        }
                    },
                onClick = onToggleCollapsed,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("估", fontWeight = FontWeight.Bold)
                        Text(formatOverlayPrice(state.p50), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            return@MaterialTheme
        }

        Surface(
            modifier = Modifier.width(372.dp),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayHeader(
                    round = state.round,
                    status = state.statusText,
                    onDrag = onDrag,
                    onCollapse = onToggleCollapsed,
                    onClose = onClose,
                )

                EstimateSummary(state)

                if (state.scanCoverage > 0.0 || state.scanState.name == "SCANNING") {
                    LinearProgressIndicator(
                        progress = { state.scanCoverage.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "仓库 ${(state.scanCoverage * 100).roundToInt()}% · ${state.observedItems} 件 · 本轮变化 ${state.changedItems}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                WarehousePanel(state.warehouse)

                OverlayActions(state, onWarehouseSnapshot)
            }
        }
    }
}

@Composable
private fun OverlayHeader(
    round: Int,
    status: String,
    onDrag: (Int, Int) -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("即刻落槌 · 第 $round 回合", fontWeight = FontWeight.Bold)
            Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        TextButton(onClick = onCollapse, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("收起")
        }
        TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("×")
        }
    }
}

@Composable
private fun EstimateSummary(state: AuctionUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("仓库估价", fontWeight = FontWeight.Bold)
                Text(if (state.warehouse.rows > 0) "已建立" else "待更新")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OverlayValue("P25", state.p25)
                OverlayValue("P50", state.p50, strong = true)
                OverlayValue("P75", state.p75)
                OverlayValue("安全价", state.safeBid, strong = true)
            }
            state.unknownRatio?.let {
                Text("未知价值占比 ${(it * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RowScope.OverlayValue(label: String, value: Long?, strong: Boolean = false) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            formatOverlayPrice(value),
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WarehousePanel(model: WarehouseUiModel) {
    var selectedId by remember(model.items) { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val selected = model.items.firstOrNull { it.id == selectedId }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("虚拟仓库", fontWeight = FontWeight.Bold)
                Text(
                    if (model.rows > 0) "${model.columns}×${model.rows} · ${model.items.size} 件" else "等待识别",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (model.rows <= 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("横屏打开仓库后点击“识别/更新仓库”")
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 230.dp)
                        .aspectRatio((model.columns.toFloat() / model.rows.toFloat()).coerceIn(0.75f, 2.2f))
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(model, canvasSize) {
                            detectTapGestures { tap ->
                                if (canvasSize.width <= 0 || canvasSize.height <= 0) return@detectTapGestures
                                val col = floor(tap.x / canvasSize.width * model.columns).toInt()
                                    .coerceIn(0, model.columns - 1)
                                val row = floor(tap.y / canvasSize.height * model.rows).toInt()
                                    .coerceIn(0, model.rows - 1)
                                selectedId = model.items.lastOrNull {
                                    col >= it.column && col < it.column + it.width &&
                                        row >= it.row && row < it.row + it.height
                                }?.id
                            }
                        },
                ) {
                    val cellW = size.width / model.columns
                    val cellH = size.height / model.rows
                    val gridColor = Color.White.copy(alpha = 0.18f)
                    for (c in 0..model.columns) {
                        drawLine(gridColor, Offset(c * cellW, 0f), Offset(c * cellW, size.height), 1f)
                    }
                    for (r in 0..model.rows) {
                        drawLine(gridColor, Offset(0f, r * cellH), Offset(size.width, r * cellH), 1f)
                    }
                    model.items.forEach { item ->
                        val left = item.column * cellW + 2f
                        val top = item.row * cellH + 2f
                        val w = item.width * cellW - 4f
                        val h = item.height * cellH - 4f
                        val color = qualityColor(item.quality)
                        drawRect(color.copy(alpha = 0.72f), Offset(left, top), Size(w, h))
                        drawRect(
                            if (item.id == selectedId) Color.White else color,
                            Offset(left, top),
                            Size(w, h),
                            style = Stroke(width = if (item.id == selectedId) 4f else 2f),
                        )
                        if (item.changedThisRound) {
                            drawCircle(Color.White, radius = 5f, center = Offset(left + w - 7f, top + 7f))
                        }
                    }
                }
            }

            selected?.let { item ->
                HorizontalDivider()
                Text(
                    "选中 ${item.label.ifBlank { item.id }} · ${item.width}×${item.height} · ${item.quality.name} · ${(item.confidence * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QualityFixButton("?", item.id, WarehouseQualityUi.UNKNOWN)
                    QualityFixButton("紫", item.id, WarehouseQualityUi.PURPLE)
                    QualityFixButton("金", item.id, WarehouseQualityUi.GOLD)
                    QualityFixButton("红", item.id, WarehouseQualityUi.RED)
                }
            }
        }
    }
}

@Composable
private fun QualityFixButton(text: String, itemId: String, quality: WarehouseQualityUi) {
    OutlinedButton(
        onClick = { AuctionStateStore.correctItemQuality(itemId, quality) },
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp),
    ) {
        Text(text)
    }
}

@Composable
private fun OverlayActions(state: AuctionUiState, onWarehouseSnapshot: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = onWarehouseSnapshot,
            enabled = state.sessionActive && state.captureAuthorized && state.scanState.name != "SCANNING",
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) { Text("识别/更新仓库") }

        Text(
            "滚到任意位置后点击；按右侧滚动条位置增量合并。",
            style = MaterialTheme.typography.labelSmall,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = AuctionStateStore::nextRound,
                enabled = state.sessionActive,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text("下一回合") }
            OutlinedButton(
                onClick = AuctionStateStore::startNewAuction,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text("新对局") }
        }
    }
}

private fun qualityColor(quality: WarehouseQualityUi): Color = when (quality) {
    WarehouseQualityUi.UNKNOWN -> Color(0xFF626A73)
    WarehouseQualityUi.WHITE -> Color(0xFFE1E5EA)
    WarehouseQualityUi.GREEN -> Color(0xFF58B368)
    WarehouseQualityUi.BLUE -> Color(0xFF4D8DFF)
    WarehouseQualityUi.PURPLE -> Color(0xFF9867D9)
    WarehouseQualityUi.GOLD -> Color(0xFFE1B84B)
    WarehouseQualityUi.RED -> Color(0xFFD95757)
}

private fun formatOverlayPrice(value: Long?): String = when {
    value == null -> "--"
    value >= 10_000L -> "%.1f万".format(value / 10_000.0)
    else -> value.toString()
}
