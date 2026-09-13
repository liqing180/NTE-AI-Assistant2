package com.nte.auction.helper

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HelperOverlayPanel(captureAuthorized: Boolean) {
    val helper by HelperFeatureStore.state.collectAsState()
    val bidStatus by HelperBidController.status.collectAsState()
    val context = LocalContext.current

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("拍卖助手", fontWeight = FontWeight.Bold)
                Text(
                    helper.analysis?.lowestEstimate?.let { "最低 %.1f万".format(it / 10_000.0) } ?: "待分析",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "金均价 ${helper.fields.goldAverage?.toLong() ?: "--"} · 总 ${helper.fields.totalItems ?: "--"} · 紫 ${helper.fields.purpleCount}",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = HelperFeatureStore::requestStatsScan,
                    enabled = captureAuthorized && !helper.statsScanPending,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text("识别参数") }
                Button(
                    onClick = { HelperFeatureStore.analyzeCurrent() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text("分析") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = HelperBidController::useLowestEstimate,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text(helper.bidAmount?.let { "出价 $it" } ?: "填最低价") }
                Button(
                    onClick = HelperBidController::performBid,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text("自动出价") }
            }
            Text(bidStatus, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            if (!HelperAutoBidAccessibilityService.isAvailable) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("启用无障碍自动出价") }
            }
        }
    }
}
