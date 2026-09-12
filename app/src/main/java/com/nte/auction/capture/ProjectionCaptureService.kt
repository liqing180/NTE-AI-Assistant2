package com.nte.auction.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
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

        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val width = metrics.width().coerceAtLeast(1)
        val height = metrics.height().coerceAtLeast(1)
        val density = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                // 屏幕读取仍限频约 8 FPS；仓库识别只在用户点击按钮后消费其中一帧。
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
                    val result = runCatching { recognizeWarehouseFrame(cropped) }.getOrNull()
                    if (result == null) {
                        AuctionStateStore.failWarehouseSnapshot(
                            "未识别到仓库：截图 ${cropped.width}×${cropped.height}，请保持仓库界面可见并确保右侧滚动条出现"
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

    /**
     * 某些 Android 设备在横屏游戏中仍会把 MediaProjection 缓冲区按竖屏物理方向
     * 提供（例如 720×1570），画面依赖 90° 显示矩阵才是用户看到的横屏。
     *
     * 两个旋转方向都要尝试：错误方向偶尔也会把界面亮边误识别成滚动条，所以不能
     * 采用“第一个非 null”。优先选择总行数大于可见行数、识别到有效藏品更多的候选。
     */
    private fun recognizeWarehouseFrame(bitmap: Bitmap): WarehouseSnapshotRecognizer.Result? {
        warehouseRecognizer.recognize(bitmap)?.let { return it }
        if (bitmap.width >= bitmap.height) return null

        val candidates = mutableListOf<WarehouseSnapshotRecognizer.Result>()
        val rotations = floatArrayOf(90f, -90f)
        for (degrees in rotations) {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                false,
            )
            try {
                warehouseRecognizer.recognize(rotated)?.let(candidates::add)
            } finally {
                if (rotated !== bitmap && !rotated.isRecycled) rotated.recycle()
            }
        }

        return candidates.maxWithOrNull(
            compareBy<WarehouseSnapshotRecognizer.Result> {
                if (it.totalRows > it.visibleRows) 1 else 0
            }.thenBy { it.items.size }
                .thenBy { it.totalRows }
        )
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
