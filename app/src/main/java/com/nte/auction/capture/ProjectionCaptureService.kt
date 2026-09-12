package com.nte.auction.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.view.Surface
import android.view.WindowManager
import com.nte.auction.ui.AuctionStateStore

class ProjectionCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastEmitMs = 0L
    private val warehouseRecognizer = WarehouseSnapshotRecognizer()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection == null) startProjection(resultCode, resultData)
        return START_STICKY
    }

    override fun onDestroy() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        imageReader = null
        virtualDisplay = null
        projection = null
        super.onDestroy()
    }

    private data class CaptureSize(val width: Int, val height: Int)

    /**
     * WindowMetrics 属于本应用窗口。悬浮窗从竖屏 Activity 启动后切回横屏游戏时，
     * 某些 ROM 仍会返回 1080x2354，导致 VirtualDisplay 也被错误创建成竖屏。
     * 这里直接读取物理 Display + 当前 rotation，得到游戏真正的屏幕方向。
     */
    @Suppress("DEPRECATION")
    private fun resolveCaptureSize(): CaptureSize {
        val display = getSystemService(WindowManager::class.java).defaultDisplay
        val mode = display.mode
        val physicalWidth = mode.physicalWidth.coerceAtLeast(1)
        val physicalHeight = mode.physicalHeight.coerceAtLeast(1)
        val naturalLong = maxOf(physicalWidth, physicalHeight)
        val naturalShort = minOf(physicalWidth, physicalHeight)
        return when (display.rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> CaptureSize(naturalLong, naturalShort)
            else -> CaptureSize(naturalShort, naturalLong)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            },
            Handler(mainLooper),
        )

        val captureSize = resolveCaptureSize()
        val width = captureSize.width
        val height = captureSize.height
        val density = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (now - lastEmitMs < 125L) return@setOnImageAvailableListener
                lastEmitMs = now
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                if (cropped !== padded) padded.recycle()

                if (AuctionStateStore.consumeWarehouseSnapshotRequest()) {
                    val result = runCatching { warehouseRecognizer.recognize(cropped) }.getOrNull()
                    if (result == null) {
                        AuctionStateStore.failWarehouseSnapshot(
                            "未识别到仓库：截图 ${cropped.width}×${cropped.height}；当前应为横屏，若仍失败请保留此提示截图"
                        )
                    } else {
                        AuctionStateStore.applyWarehouseSnapshot(result)
                    }
                }

                FrameHub.offer(cropped)
            } finally {
                image.close()
            }
        }, null)

        virtualDisplay = projection?.createVirtualDisplay(
            "NteAuctionCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // MediaProjection 会把内容适配到创建时的 Surface。这里不重新申请授权；
        // 下一次启动截图服务时会按最新 rotation 创建正确尺寸。
    }

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "仓库识别", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("即刻落槌估价")
            .setContentText("正在读取游戏画面，仅在本机处理")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "nte_capture"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.nte.auction.capture.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ProjectionCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ProjectionCaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
