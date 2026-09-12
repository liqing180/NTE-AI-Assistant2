package com.nte.auction.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun AuctionScreen(
    state: AuctionUiState,
    onAuthorizeCapture: () -> Unit,
    onNewAuction: () -> Unit,
    onFullScan: () -> Unit,
    onFastRefresh: () -> Unit,
    onNextRound: () -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("即刻落槌 · 真珠场估价", style = MaterialTheme.typography.headlineSmall)
                Text(state.statusText, style = MaterialTheme.typography.bodyMedium)

                if (state.scanState.name != "IDLE") {
                    LinearProgressIndicator(
                        progress = { state.scanCoverage.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "扫描 ${(state.scanCoverage * 100).roundToInt()}% · 已见 ${state.observedItems} 件 · 本轮变化 ${state.changedItems} 件"
                    )
                }

                EstimateCard(state)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onAuthorizeCapture, enabled = !state.captureAuthorized) {
                        Text(if (state.captureAuthorized) "已授权截图" else "授权截图")
                    }
                    Button(onClick = onNewAuction) { Text("新对局") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onFullScan, enabled = state.sessionActive) { Text("完整建仓") }
                    Button(onClick = onFastRefresh, enabled = state.sessionActive) { Text("快速刷新") }
                    OutlinedButton(onClick = onNextRound, enabled = state.sessionActive) { Text("下一回合") }
                }

                Spacer(Modifier.weight(1f))
                Text(
                    "当前阶段：截图链路 + 核心估价引擎已落代码；仓库视觉检测器将在下一层接入。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
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
                Text(if (state.scanState.name == "COMPLETE") "仓库 ✓ 最新" else "仓库待更新")
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
