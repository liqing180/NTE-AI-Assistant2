package com.nte.auction.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.nte.auction.ui.AuctionStateStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * 仓库真机诊断旁路。
 * 每次真正消费“识别/更新仓库”的截图时保存原始帧与采集参数；导出时额外生成
 * +90/-90 候选图和独立滚动条探测报告，避免诊断代码改变正式识别逻辑。
 */
object WarehouseDiagnostics {
    private val lock = Any()
    private var latestPng: ByteArray? = null
    private var latestMeta: String = "尚未捕获仓库截图"
    private var latestRecognition: String = "尚未执行识别"
    private var latestException: String? = null
    private var capturedAtMs: Long = 0L

    fun recordCapture(bitmap: Bitmap, metadata: String) {
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        synchronized(lock) {
            latestPng = bytes
            latestMeta = metadata
            latestRecognition = "识别执行中"
            latestException = null
            capturedAtMs = System.currentTimeMillis()
        }
    }

    fun recordRecognition(message: String, error: Throwable? = null) {
        synchronized(lock) {
            latestRecognition = message
            latestException = error?.stackTraceToString()
        }
    }

    fun hasCapture(): Boolean = synchronized(lock) { latestPng != null }

    fun exportAndShare(context: Context): Boolean {
        val snapshot = synchronized(lock) {
            Snapshot(latestPng, latestMeta, latestRecognition, latestException, capturedAtMs)
        }
        val png = snapshot.png ?: return false
        val root = File(context.cacheDir, "warehouse-diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(root, "warehouse-diagnostic-$stamp.zip")

        val raw = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                zip.putNextEntry(ZipEntry("screenshot_raw.png"))
                zip.write(png)
                zip.closeEntry()

                val report = buildReport(context, raw, snapshot)
                zip.putNextEntry(ZipEntry("diagnostic.txt"))
                zip.write(report.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                writeRotated(zip, raw, 90f, "screenshot_rotate_90.png")
                writeRotated(zip, raw, -90f, "screenshot_rotate_minus_90.png")
            }
        } finally {
            raw.recycle()
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NTE 仓库识别诊断包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(send, "导出仓库诊断包").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    private fun writeRotated(zip: ZipOutputStream, source: Bitmap, degrees: Float, name: String) {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        try {
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                rotated.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        } finally {
            if (rotated !== source) rotated.recycle()
        }
    }

    private fun buildReport(context: Context, raw: Bitmap, snapshot: Snapshot): String = buildString {
        appendLine("NTE Warehouse Diagnostic")
        appendLine("capturedAtMs=${snapshot.capturedAtMs}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("deviceProduct=${Build.PRODUCT}")
        appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine("display=${Build.DISPLAY}")
        appendLine("package=${context.packageName}")
        appendLine("resourcesOrientation=${context.resources.configuration.orientation}")
        appendLine("resourcesDensityDpi=${context.resources.displayMetrics.densityDpi}")
        appendLine("rawBitmap=${raw.width}x${raw.height}")
        appendLine()
        appendLine("--- capture metadata ---")
        appendLine(snapshot.meta)
        appendLine()
        appendLine("--- recognition ---")
        appendLine(snapshot.recognition)
        snapshot.exception?.let {
            appendLine("--- exception ---")
            appendLine(it)
        }
        appendLine()
        appendLine("--- independent probes ---")
        appendLine(probe(raw, "raw"))
        val matrix90 = Matrix().apply { postRotate(90f) }
        val plus = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix90, true)
        try { appendLine(probe(plus, "+90")) } finally { if (plus !== raw) plus.recycle() }
        val matrixMinus = Matrix().apply { postRotate(-90f) }
        val minus = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrixMinus, true)
        try { appendLine(probe(minus, "-90")) } finally { if (minus !== raw) minus.recycle() }
        appendLine()
        appendLine("--- app warehouse state ---")
        val state = AuctionStateStore.state.value
        appendLine("scanState=${state.scanState} coverage=${state.scanCoverage}")
        appendLine("statusText=${state.statusText}")
        appendLine("warehouse=${state.warehouse.columns}x${state.warehouse.rows} items=${state.warehouse.items.size}")
        state.warehouse.items.forEach {
            appendLine("item id=${it.id} row=${it.row} col=${it.column} size=${it.width}x${it.height} quality=${it.quality} confidence=${it.confidence}")
        }
    }

    private fun probe(bitmap: Bitmap, label: String): String {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 2 || height < 2) return "$label invalidBitmap=${width}x$height"
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val trackTop = (height * 0.18f).roundToInt().coerceIn(0, height - 1)
        val trackBottom = (height * 0.75f).roundToInt().coerceIn(trackTop + 1, height)
        val startX = (width * 0.925f).roundToInt().coerceIn(0, width - 1)
        val endX = (width * 0.95f).roundToInt().coerceIn(startX + 1, width)
        var bestX = -1
        var bestStart = -1
        var bestEnd = -1
        var bestLen = 0
        for (x in startX until endX) {
            var runStart = -1
            for (y in trackTop until trackBottom) {
                val bright = luma(pixels[y * width + x]) >= 145
                if (bright && runStart < 0) runStart = y
                if ((!bright || y == trackBottom - 1) && runStart >= 0) {
                    val runEnd = if (bright && y == trackBottom - 1) y else y - 1
                    val len = runEnd - runStart + 1
                    if (len > bestLen) {
                        bestLen = len; bestX = x; bestStart = runStart; bestEnd = runEnd
                    }
                    runStart = -1
                }
            }
        }
        val gridLeft = (width * 0.665f).roundToInt()
        val gridRight = if (bestX >= 0) (bestX - width * 0.006f).roundToInt() else -1
        val gridTop = (height * 0.165f).roundToInt()
        val cell = if (gridRight > gridLeft) (gridRight - gridLeft) / 10f else -1f
        return "$label bitmap=${width}x$height landscape=${width > height} trackY=$trackTop..$trackBottom searchX=$startX..${endX - 1} bestScrollbar=x:$bestX y:$bestStart..$bestEnd len:$bestLen gridLeft=$gridLeft gridRight=$gridRight gridTop=$gridTop cell=$cell"
    }

    private fun luma(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    private data class Snapshot(
        val png: ByteArray?,
        val meta: String,
        val recognition: String,
        val exception: String?,
        val capturedAtMs: Long,
    )
}
