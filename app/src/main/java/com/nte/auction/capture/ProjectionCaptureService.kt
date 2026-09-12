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
    private var captureInfo: String = "capture not initialized"

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

    @Suppress("DEPRECATION")
    private fun buildCaptureInfo(width: Int, height: Int, density: Int): String {
        val wm = getSystemService(WindowManager::class.java)
        val display = wm.defaultDisplay
        val mode = display.mode
        val metrics = wm.currentWindowMetrics.bounds
        val config = resources.configuration
        return buildString {
            appendLine("requestedSurface=${width}x$height densityDpi=$density")
            appendLine("displayRotation=${display.rotation}")
            appendLine("displayModePhysical=${mode.physicalWidth}x${mode.physicalHeight} modeId=${mode.modeId} refresh=${mode.refreshRate}")
            appendLine("windowMetrics=${metrics.width()}x${metrics.height()} bounds=$metrics")
            appendLine("resourceDisplayMetrics=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
            appendLine("configurationOrientation=${config.orientation} screenWidthDp=${config.screenWidthDp} screenHeightDp=${config.screenHeightDp}")
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
        captureInfo = buildCaptureInfo(width, height, density)

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
                    val frameMetadata = buildString {
                        append(captureInfo)
                        appendLine("image=${image.width}x${image.height} format=${image.format} timestamp=${image.timestamp}")
                        appendLine("planePixelStride=$pixelStride rowStride=$rowStride rowPadding=$rowPadding paddedWidth=$paddedWidth")
                        appendLine("croppedBitmap=${cropped.width}x${cropped.height} config=${cropped.config}")
                    }
                    WarehouseDiagnostics.recordCapture(cropped, frameMetadata)

                    val attempt = runCatching { warehouseRecognizer.recognize(cropped) }
                    val result = attempt.getOrNull()
                    if (result == null) {
                        val error = attempt.exceptionOrNull()
                        WarehouseDiagnostics.recordRecognition(
                            message = if (error == null) {
                                "recognizer returned null"
                            } else {
                                "recognizer threw ${error::class.java.name}: ${error.message}"
                            },
                            error = error,
                        )
                        AuctionStateStore.failWarehouseSnapshot(
                            "未识别到仓库：截图 ${cropped.width}×${cropped.height}；请点“导出诊断包”发给开发者"
                        )
                    } else {
                        WarehouseDiagnostics.recordRecognition(
                            "SUCCESS columns=${result.columns} totalRows=${result.totalRows} viewportStartRow=${result.viewportStartRow} visibleRows=${result.visibleRows} scrollRatio=${result.scrollRatio} items=${result.items.joinToString { "${it.stableId}:${it.quality}:${it.confidence}" }}"
                        )
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
