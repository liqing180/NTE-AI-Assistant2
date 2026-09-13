package com.nte.auction

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nte.auction.capture.ProjectionCaptureService
import com.nte.auction.overlay.AuctionOverlayService
import com.nte.auction.ui.AuctionScreen
import com.nte.auction.ui.AuctionViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AuctionViewModel by viewModels()
    private var overlayEnabled by mutableStateOf(false)
    private var overlayPermissionRequested = false

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ProjectionCaptureService.start(this, result.resultCode, data)
            viewModel.onCaptureAuthorized()
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateOverlayPermission(startWhenGranted = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayEnabled = Settings.canDrawOverlays(this)
        setContent {
            val state by viewModel.state.collectAsState()
            AuctionScreen(
                state = state,
                overlayEnabled = overlayEnabled,
                onAuthorizeCapture = ::requestScreenCapture,
                onEnableOverlay = ::requestOrShowOverlay,
                onNewAuction = viewModel::startNewAuction,
                onUpdateWarehouse = viewModel::updateWarehouseFromScreenshot,
                onNextRound = viewModel::nextRound,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayPermission(startWhenGranted = overlayPermissionRequested)
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Android 14+ 默认的无参 createScreenCaptureIntent() 会允许选择
        // “单个应用”或“整个屏幕”。仓库识别必须持续读取游戏画面；如果误选
        // 单个应用（尤其是本助手本身），切回游戏后 MediaProjection 可能只得到
        // 黑帧/系统手势条，识别器自然无法定位仓库。
        //
        // 强制捕获 DEFAULT_DISPLAY，消除这类授权范围歧义。
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
    }

    private fun requestOrShowOverlay() {
        if (Settings.canDrawOverlays(this)) {
            overlayEnabled = true
            AuctionOverlayService.start(this)
            return
        }
        overlayPermissionRequested = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        overlayLauncher.launch(intent)
    }

    private fun updateOverlayPermission(startWhenGranted: Boolean) {
        overlayEnabled = Settings.canDrawOverlays(this)
        if (overlayEnabled && startWhenGranted) {
            AuctionOverlayService.start(this)
            overlayPermissionRequested = false
        }
    }
}
