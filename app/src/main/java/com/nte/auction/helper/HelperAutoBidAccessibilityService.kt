package com.nte.auction.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * Android equivalent of nte-auction-helper's pyautogui auto-bid path.
 * It only acts after an explicit user request and requires the user to enable this
 * accessibility service in system settings.
 */
class HelperAutoBidAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var busy = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        recognizer.close()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun startBid(amount: Long, callback: (Result<Unit>) -> Unit) {
        if (busy) {
            callback(Result.failure(IllegalStateException("自动出价正在执行")))
            return
        }
        if (amount <= 0L) {
            callback(Result.failure(IllegalArgumentException("请输入有效出价金额")))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(Result.failure(UnsupportedOperationException("自动出价截图需要 Android 11+")))
            return
        }
        busy = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()
                    if (bitmap == null) {
                        finish(callback, Result.failure(IllegalStateException("无法读取屏幕截图")))
                        return
                    }
                    locateBidAndRun(bitmap, amount, callback)
                }

                override fun onFailure(errorCode: Int) {
                    finish(callback, Result.failure(IllegalStateException("无障碍截图失败：$errorCode")))
                }
            },
        )
    }

    private fun locateBidAndRun(bitmap: Bitmap, amount: Long, callback: (Result<Unit>) -> Unit) {
        val width = bitmap.width
        val height = bitmap.height
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val center = text.textBlocks.asSequence()
                    .flatMap { it.lines.asSequence() }
                    .mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        val normalized = normalize(line.text)
                        if ("出价" !in normalized && "岀价" !in normalized) return@mapNotNull null
                        val x = box.exactCenterX()
                        val y = box.exactCenterY()
                        if (x < width * 0.58f || y < height * 0.50f) return@mapNotNull null
                        x to y
                    }
                    .lastOrNull()
                bitmap.recycle()
                if (center == null) {
                    finish(callback, Result.failure(IllegalStateException("未找到游戏页面中的“出价”按钮")))
                    return@addOnSuccessListener
                }
                tap(center.first, center.second, 550L) { firstTap ->
                    if (firstTap.isFailure) {
                        finish(callback, firstTap)
                    } else {
                        enterAmount(width, height, amount, callback)
                    }
                }
            }
            .addOnFailureListener { error ->
                bitmap.recycle()
                finish(callback, Result.failure(error))
            }
    }

    private fun enterAmount(width: Int, height: Int, amount: Long, callback: (Result<Unit>) -> Unit) {
        val clear = keypadPoint(width, height, "清空")
            ?: return finish(callback, Result.failure(IllegalStateException("无法定位清空键")))
        tap(clear.first, clear.second, 80L) { clearResult ->
            if (clearResult.isFailure) {
                finish(callback, clearResult)
                return@tap
            }
            val labels = compactAmount(amount.toString())
            tapLabels(width, height, labels, 0) { inputResult ->
                if (inputResult.isFailure) {
                    finish(callback, inputResult)
                    return@tapLabels
                }
                val confirm = ratioPoint(width, height, CONFIRM_X_RATIO, CONFIRM_Y_RATIO)
                tap(confirm.first, confirm.second, 350L) { firstConfirm ->
                    if (firstConfirm.isFailure) {
                        finish(callback, firstConfirm)
                    } else {
                        tap(confirm.first, confirm.second, 0L) { secondConfirm -> finish(callback, secondConfirm) }
                    }
                }
            }
        }
    }

    private fun tapLabels(
        width: Int,
        height: Int,
        labels: List<String>,
        index: Int,
        callback: (Result<Unit>) -> Unit,
    ) {
        if (index >= labels.size) {
            callback(Result.success(Unit))
            return
        }
        val point = keypadPoint(width, height, labels[index])
        if (point == null) {
            callback(Result.failure(IllegalStateException("无法定位数字键：${labels[index]}")))
            return
        }
        tap(point.first, point.second, 50L) { result ->
            if (result.isFailure) callback(result)
            else tapLabels(width, height, labels, index + 1, callback)
        }
    }

    private fun tap(x: Float, y: Float, delayAfter: Long, callback: (Result<Unit>) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.postDelayed({ callback(Result.success(Unit)) }, delayAfter)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback(Result.failure(IllegalStateException("系统取消了点击手势")))
                }
            },
            mainHandler,
        )
        if (!accepted) callback(Result.failure(IllegalStateException("系统拒绝了点击手势")))
    }

    private fun compactAmount(amount: String): List<String> {
        val output = mutableListOf<String>()
        var index = 0
        while (index < amount.length) {
            val digit = amount[index]
            if (digit != '0') {
                output += digit.toString()
                index++
                continue
            }
            var end = index
            while (end < amount.length && amount[end] == '0') end++
            var zeros = end - index
            while (zeros >= 4) { output += "0000"; zeros -= 4 }
            while (zeros >= 2) { output += "00"; zeros -= 2 }
            while (zeros >= 1) { output += "0"; zeros-- }
            index = end
        }
        return output
    }

    private fun keypadPoint(width: Int, height: Int, label: String): Pair<Float, Float>? {
        val cell = KEYPAD_CELLS[label] ?: return null
        val left = width * KEYPAD_LEFT_RATIO
        val top = height * KEYPAD_TOP_RATIO
        val boxWidth = width * KEYPAD_WIDTH_RATIO
        val boxHeight = height * KEYPAD_HEIGHT_RATIO
        val cellWidth = boxWidth / 4f
        val cellHeight = boxHeight / 4f
        return (left + cellWidth * (cell.first + 0.5f)) to (top + cellHeight * (cell.second + 0.5f))
    }

    private fun ratioPoint(width: Int, height: Int, x: Float, y: Float): Pair<Float, Float> =
        width * x to height * y

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), "").replace('：', ':')

    private fun finish(callback: (Result<Unit>) -> Unit, result: Result<Unit>) {
        busy = false
        callback(result)
    }

    companion object {
        @Volatile private var instance: HelperAutoBidAccessibilityService? = null

        val isAvailable: Boolean get() = instance != null

        fun bid(amount: Long, callback: (Result<Unit>) -> Unit) {
            val service = instance
            if (service == null) {
                callback(Result.failure(IllegalStateException("请先在系统无障碍设置中启用“即刻落槌自动出价”")))
                return
            }
            service.startBid(amount, callback)
        }

        private const val KEYPAD_LEFT_RATIO = 0.198f
        private const val KEYPAD_TOP_RATIO = 0.484f
        private const val KEYPAD_WIDTH_RATIO = 0.375f
        private const val KEYPAD_HEIGHT_RATIO = 0.465f
        private const val CONFIRM_X_RATIO = 0.704f
        private const val CONFIRM_Y_RATIO = 0.886f

        private val KEYPAD_CELLS = mapOf(
            "1" to (0 to 0), "2" to (1 to 0), "3" to (2 to 0), "退格" to (3 to 0),
            "4" to (0 to 1), "5" to (1 to 1), "6" to (2 to 1), "2x" to (3 to 1),
            "7" to (0 to 2), "8" to (1 to 2), "9" to (2 to 2), "上轮出价" to (3 to 2),
            "0" to (0 to 3), "00" to (1 to 3), "0000" to (2 to 3), "清空" to (3 to 3),
        )
    }
}
