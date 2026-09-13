package com.nte.auction.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nte.auction.helper.HelperAnalysisPanel
import com.nte.auction.helper.HelperFeatureStore
import com.nte.auction.helper.HelperMemoryPanel
import kotlin.math.roundToInt

@Composable
fun AuctionScreen(
    state: AuctionUiState,
    overlayEnabled: Boolean,
    onAuthorizeCapture: () -> Unit,
    onEnableOverlay: () -> Unit,
    onNewAuction: () -> Unit,
    onUpdateWarehouse: () -> Unit,
    onNextRound: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val helper by HelperFeatureStore.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("即刻落槌 · 真珠场估价", style = MaterialTheme.typography.headlineSmall)
                Text(state.statusText, style = MaterialTheme.typography.bodyMedium)

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("总览") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("拍卖分析") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("记忆池") })
                }

                when (selectedTab) {
                    0 -> OverviewTab(
                        state = state,
                        overlayEnabled = overlayEnabled,
                        onAuthorizeCapture = onAuthorizeCapture,
                        onEnableOverlay = onEnableOverlay,
                        onNewAuction = onNewAuction,
                        onUpdateWarehouse = onUpdateWarehouse,
                        onNextRound = onNextRound,
                    )
                    1 -> HelperAnalysisPanel(
                        helper = helper,
                        warehouse = state.warehouse,
                        captureAuthorized = state.captureAuthorized,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    )
                    2 -> HelperMemoryPanel(helper)
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(
    state: AuctionUiState,
    overlayEnabled: Boolean,
    onAuthorizeCapture: () -> Unit,
    onEnableOverlay: () -> Unit,
    onNewAuction: () -> Unit,
    onUpdateWarehouse: () -> Unit,
    onNextRound: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.scanCoverage > 0.0 || state.scanState.name == "SCANNING") {
            LinearProgressIndicator(
                progress = { state.scanCoverage.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "仓库覆盖 ${(state.scanCoverage * 100).roundToInt()}% · 已识别 ${state.observedItems} 件 · 本轮变化 ${state.changedItems} 件"
            )
        }

        EstimateCard(state)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAuthorizeCapture, enabled = !state.captureAuthorized) {
                Text(if (state.captureAuthorized) "已授权截图" else "授权截图")
            }
            Button(onClick = onEnableOverlay) {
                Text(if (overlayEnabled) "显示悬浮窗" else "开启悬浮窗")
            }
            Button(onClick = onNewAuction) { Text("新对局") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onUpdateWarehouse,
                enabled = state.sessionActive && state.captureAuthorized && state.scanState.name != "SCANNING",
            ) {
                Text("识别/更新仓库")
            }
            OutlinedButton(onClick = onNextRound, enabled = state.sessionActive) {
                Text("下一回合")
            }
        }

        Text(
            "横屏打开仓库后，滚到任意位置点击“识别/更新仓库”。每次点击只截取一张全屏画面，并根据右侧滚动条位置增量合并当前可见区域；拍卖页面参数识别请切换到“拍卖分析”。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EstimateCard(state: AuctionUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("第 ${state.round} 回合")
                Text(if (state.warehouse.rows > 0) "仓库已建立" else "仓库待识别")
            }
            HorizontalDivider()
            ValueRow("P25", state.p25)
            ValueRow("P50", state.p50)
            ValueRow("P75", state.p75)
            ValueRow("安全买入", state.safeBid)
            val unknown = state.unknownRatio
            if (unknown != null) {
                Text("未知价值占比 ${(unknown * 100).roundToInt()}%")
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: Long?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(value?.let(::formatPrice) ?: "--")
    }
}

private fun formatPrice(value: Long): String = when {
    value >= 10_000L -> "%.1f 万".format(value / 10_000.0)
    else -> value.toString()
}
