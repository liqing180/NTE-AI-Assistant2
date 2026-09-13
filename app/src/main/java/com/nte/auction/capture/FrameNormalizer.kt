package com.nte.auction.capture

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Normalizes MediaProjection frames before they enter the recognition pipeline.
 *
 * Handles two OEM/Android behaviours observed on the target device:
 * 1) landscape game pixels letterboxed inside a portrait MediaProjection buffer;
 * 2) an invalid/app-scoped projection session returning an almost completely
 *    black frame (sometimes only the navigation gesture bar is visible).
 */
object FrameNormalizer {
    data class Result(
        val bitmap: Bitmap,
        val sourceRect: Rect,
        val changed: Boolean,
        val reason: String,
    )

    private const val DARK_CHANNEL_THRESHOLD = 20
    private const val ACTIVE_ROW_RATIO = 0.055f
    private const val SAMPLE_STEP_X = 6
    private const val SAMPLE_STEP_Y = 2
    private const val MIN_LANDSCAPE_ASPECT = 1.45f
    private const val MAX_LANDSCAPE_ASPECT = 2.65f
    private const val MIN_ACTIVE_HEIGHT_RATIO = 0.12f
    private const val EDGE_PADDING_ROWS = 2

    // Blank-frame guard. The failed 2026-09-13 capture had only ~0.015% pixels
    // above a very low brightness threshold, while a valid warehouse frame had
    // abundant non-dark pixels. Keep the threshold conservative so dark game
    // scenes are not rejected accidentally.
    private const val BLACK_FRAME_SAMPLE_STEP = 12
    private const val BLACK_FRAME_MIN_NON_DARK_RATIO = 0.005f // 0.5%

    fun normalize(input: Bitmap): Result {
        val full = Rect(0, 0, input.width, input.height)
        if (input.width <= 0 || input.height <= 0) {
            return Result(input, full, false, "invalid-size")
        }

        if (isNearBlackFrame(input)) {
            return Result(input, full, false, "capture-near-black")
        }

        // A true landscape frame is already usable. Avoid extra copies.
        if (input.width >= input.height) {
            return Result(input, full, false, "already-landscape")
        }

        val activeRows = BooleanArray(input.height)
        var y = 0
        while (y < input.height) {
            activeRows[y] = isActiveRow(input, y)
            y += SAMPLE_STEP_Y
        }

        // Fill gaps created by sampled rows so the run detector works on pixels.
        y = 0
        while (y < input.height) {
            val value = activeRows[y]
            val end = min(input.height, y + SAMPLE_STEP_Y)
            for (yy in y until end) activeRows[yy] = value
            y += SAMPLE_STEP_Y
        }

        closeSmallGaps(activeRows, max(4, input.height / 300))
        val run = largestTrueRun(activeRows)
            ?: return Result(input, full, false, "no-active-band")

        val top = max(0, run.first - EDGE_PADDING_ROWS)
        val bottomExclusive = min(input.height, run.last + 1 + EDGE_PADDING_ROWS)
        val activeHeight = bottomExclusive - top
        val aspect = input.width.toFloat() / activeHeight.toFloat()
        val activeHeightRatio = activeHeight.toFloat() / input.height.toFloat()

        val looksLikeLetterboxedLandscape =
            activeHeightRatio >= MIN_ACTIVE_HEIGHT_RATIO &&
                aspect in MIN_LANDSCAPE_ASPECT..MAX_LANDSCAPE_ASPECT &&
                activeHeight < input.height * 0.78f

        if (!looksLikeLetterboxedLandscape) {
            return Result(
                input,
                full,
                false,
                "active-band-rejected top=$top bottom=$bottomExclusive aspect=$aspect ratio=$activeHeightRatio",
            )
        }

        val cropRect = Rect(0, top, input.width, bottomExclusive)
        val cropped = Bitmap.createBitmap(
            input,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height(),
        )
        return Result(
            bitmap = cropped,
            sourceRect = cropRect,
            changed = true,
            reason = "letterbox-crop ${input.width}x${input.height} -> ${cropped.width}x${cropped.height}",
        )
    }

    /**
     * Detects a projection frame that contains effectively no app/game content.
     * Sampling is intentional: this runs on the capture hot path.
     */
    fun isNearBlackFrame(bitmap: Bitmap): Boolean {
        var samples = 0
        var nonDark = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                if (max(r, max(g, b)) > DARK_CHANNEL_THRESHOLD) nonDark++
                samples++
                x += BLACK_FRAME_SAMPLE_STEP
            }
            y += BLACK_FRAME_SAMPLE_STEP
        }
        if (samples == 0) return true
        return nonDark.toFloat() / samples.toFloat() < BLACK_FRAME_MIN_NON_DARK_RATIO
    }

    private fun isActiveRow(bitmap: Bitmap, y: Int): Boolean {
        var samples = 0
        var nonDark = 0
        var x = 0
        while (x < bitmap.width) {
            val color = bitmap.getPixel(x, y)
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            if (max(r, max(g, b)) > DARK_CHANNEL_THRESHOLD) nonDark++
            samples++
            x += SAMPLE_STEP_X
        }
        return samples > 0 && nonDark.toFloat() / samples.toFloat() >= ACTIVE_ROW_RATIO
    }

    private fun closeSmallGaps(values: BooleanArray, maxGap: Int) {
        var i = 0
        while (i < values.size) {
            if (values[i]) {
                i++
                continue
            }
            val start = i
            while (i < values.size && !values[i]) i++
            val end = i
            if (start > 0 && end < values.size && end - start <= maxGap) {
                for (j in start until end) values[j] = true
            }
        }
    }

    private fun largestTrueRun(values: BooleanArray): IntRange? {
        var bestStart = -1
        var bestEnd = -1
        var currentStart = -1

        for (i in values.indices) {
            if (values[i]) {
                if (currentStart < 0) currentStart = i
            } else if (currentStart >= 0) {
                if (bestStart < 0 || i - currentStart > bestEnd - bestStart + 1) {
                    bestStart = currentStart
                    bestEnd = i - 1
                }
                currentStart = -1
            }
        }
        if (currentStart >= 0 &&
            (bestStart < 0 || values.size - currentStart > bestEnd - bestStart + 1)
        ) {
            bestStart = currentStart
            bestEnd = values.lastIndex
        }
        return if (bestStart >= 0) bestStart..bestEnd else null
    }
}
