package com.nte.auction

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nte.auction.capture.ProjectionCaptureService
import com.nte.auction.ui.AuctionScreen
import com.nte.auction.ui.AuctionViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AuctionViewModel by viewModels()

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ProjectionCaptureService.start(this, result.resultCode, data)
            viewModel.onCaptureAuthorized()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            AuctionScreen(
                state = state,
                onAuthorizeCapture = ::requestScreenCapture,
                onNewAuction = viewModel::startNewAuction,
                onFullScan = viewModel::startFullScan,
                onFastRefresh = viewModel::startFastRefresh,
                onNextRound = viewModel::nextRound,
            )
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }
}
