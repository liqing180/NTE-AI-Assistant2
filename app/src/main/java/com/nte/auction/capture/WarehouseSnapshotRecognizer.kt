package com.nte.auction.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import com.nte.auction.ui.WarehouseQualityUi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 单张全屏截图仓库识别。
 *
 * 识别流程：
 * 1. 自动兼容 MediaProjection 可能返回的竖屏底层缓冲；
 * 2. 从右侧亮色滑块定位滚动条，并计算当前 viewport 的全局行位置；
 * 3. 在仓库网格区域直接检测连续亮色矩形轮廓，不再依赖“单元格中心是否够亮”；
 * 4. 将轮廓宽高映射回 10 列网格，从而稳定恢复 1x2 / 2x2 / 3x2 等尺寸。
 */
class WarehouseSnapshotRecognizer {
    data class DetectedItem(
        val row: Int,
        val column: Int,
        val width: Int,
        val height: Int,
        val quality: WarehouseQualityUi,
        val confidence: Float,
    ) {
        val stableId: String get() = "r${row}c${column}_${width}x${height}"
    }

    data class Result(
        val columns: Int,
        val totalRows: Int,
        val viewportStartRow: Int,
        val visibleRows: Int,
        val scrollRatio: Double,
        val items: List<DetectedItem>,
    )

    fun recognize(bitmap: Bitmap): Result? {
        if (bitmap.width >= bitmap.height) {
            return recognizeLandscape(bitmap)
        }

        // 某些设备/录屏会给 MediaProjection 返回 720x1570 之类的底层竖屏缓冲，
        // 但真实游戏画面带 90 度显示旋转。两个方向都尝试，选结构更像仓库的结果。
        val candidates = listOf(90f, -90f).mapNotNull { degrees ->
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true,
            )
            try {
                recognizeLandscape(rotated)
            } finally {
                if (rotated !== bitmap) rotated.recycle()
            }
        }
        return candidates.maxByOrNull(::candidateScore)
    }

    private fun candidateScore(result: Result): Double {
        val rowPlausibility = when (result.totalRows) {
            in 15..45 -> 3.0
            in 10..60 -> 1.0
            else -> -3.0
        }
        return rowPlausibility + result.items.sumOf { it.confidence.toDouble() }
    }

    private fun recognizeLandscape(bitmap: Bitmap): Result? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= height || width < 800 || height < 400) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val trackTop = (height * 0.18f).roundToInt().coerceIn(0, height - 1)
        val trackBottom = (height * 0.75f).roundToInt().coerceIn(trackTop + 1, height)
        val scroll = detectScrollbar(pixels, width, height, trackTop, trackBottom) ?: return null

        val columns = 10
        val gridRight = (scroll.x - width * 0.006f).roundToInt()
        val gridLeft = (width * 0.665f).roundToInt()
        val gridTop = (height * 0.165f).roundToInt()
        if (gridRight <= gridLeft + 100) return null

        val cellSize = (gridRight - gridLeft).toFloat() / columns.toFloat()
        if (cellSize < 20f) return null

        val visibleRows = (((height * 0.75f) - gridTop) / cellSize)
            .roundToInt()
            .coerceIn(8, 12)

        val trackHeight = (trackBottom - trackTop).coerceAtLeast(1)
        val thumbHeight = scroll.length.coerceAtLeast(1)
        val totalRows = (visibleRows * trackHeight.toDouble() / thumbHeight.toDouble())
            .roundToInt()
            .coerceAtLeast(visibleRows)
            .coerceAtMost(80)

        val travel = (trackHeight - thumbHeight).coerceAtLeast(1)
        val scrollRatio = ((scroll.startY - trackTop).toDouble() / travel.toDouble())
            .coerceIn(0.0, 1.0)
        val maxStartRow = (totalRows - visibleRows).coerceAtLeast(0)
        val viewportStartRow = (scrollRatio * maxStartRow).roundToInt()
            .coerceIn(0, maxStartRow)

        val gridBottom = (gridTop + visibleRows * cellSize)
            .roundToInt()
            .coerceAtMost(height)

        val contours = detectItemContours(
            pixels = pixels,
            screenWidth = width,
            screenHeight = height,
            gridLeft = gridLeft,
            gridTop = gridTop,
            gridRight = gridRight,
            gridBottom = gridBottom,
            cellSize = cellSize,
        )

        val items = contours.mapNotNull { contour ->
            val itemWidth = (contour.width / cellSize)
                .roundToInt()
                .coerceIn(1, 6)
            val itemHeight = (contour.height / cellSize)
                .roundToInt()
                .coerceIn(1, 6)

            // 用矩形中心反推网格起点，比直接用左上角更能容忍圆角、阴影和发光外扩。
            val centerX = contour.left + contour.width / 2f
            val centerY = contour.top + contour.height / 2f
            val column = (((centerX - gridLeft) / cellSize) - itemWidth / 2f)
                .roundToInt()
            val localRow = (((centerY - gridTop) / cellSize) - itemHeight / 2f)
                .roundToInt()

            if (column !in 0 until columns || column + itemWidth > columns) return@mapNotNull null
            if (localRow !in 0 until visibleRows || localRow + itemHeight > visibleRows) return@mapNotNull null

            // 只有位于滚动中段时才丢弃触边轮廓。诊断包证明顶部 viewport 的最后一行
            // 本身就是完整可见行，旧逻辑会把第 9/10 行合法藏品全部误判为“被裁切”。
            // 顶部/底部极限位置允许触边项进入，后续状态合并仍有 edge guard 防止误删除。
            val inMiddleViewport = scrollRatio > EDGE_SCROLL_EPSILON && scrollRatio < 1.0 - EDGE_SCROLL_EPSILON
            if (inMiddleViewport && contour.top <= gridTop + cellSize * EDGE_TOUCH_FRACTION) {
                return@mapNotNull null
            }
            if (inMiddleViewport && contour.bottom >= gridBottom - cellSize * EDGE_TOUCH_FRACTION) {
                return@mapNotNull null
            }

            val widthError = abs(contour.width / cellSize - itemWidth)
            val heightError = abs(contour.height / cellSize - itemHeight)
            val sizeFit = (1f - ((widthError + heightError) / 1.2f)).coerceIn(0f, 1f)
            val confidence = (0.78f + sizeFit * 0.19f).coerceIn(0f, 0.97f)

            DetectedItem(
                row = viewportStartRow + localRow,
                column = column,
                width = itemWidth,
                height = itemHeight,
                quality = classifyQuality(
                    pixels = pixels,
                    screenWidth = width,
                    screenHeight = height,
                    left = contour.left.toFloat(),
                    top = contour.top.toFloat(),
                    itemWidth = contour.width.toFloat(),
                    itemHeight = contour.height.toFloat(),
                ),
                confidence = confidence,
            )
        }.distinctBy { it.stableId }

        return Result(
            columns = columns,
            totalRows = totalRows,
            viewportStartRow = viewportStartRow,
            visibleRows = visibleRows,
            scrollRatio = scrollRatio,
            items = items,
        )
    }

    private data class Scrollbar(
        val x: Int,
        val startY: Int,
        val endY: Int,
    ) {
        val length: Int get() = endY - startY + 1
    }

    private fun detectScrollbar(
        pixels: IntArray,
        width: Int,
        height: Int,
        trackTop: Int,
        trackBottom: Int,
    ): Scrollbar? {
        val startX = (width * 0.925f).roundToInt().coerceIn(0, width - 1)
        val endX = (width * 0.95f).roundToInt().coerceIn(startX + 1, width)
        var best: Scrollbar? = null

        for (x in startX until endX) {
            var runStart = -1
            var y = trackTop
            while (y < trackBottom) {
                val bright = luma(pixels[y * width + x]) >= 145
                if (bright && runStart < 0) runStart = y
                val closes = (!bright || y == trackBottom - 1) && runStart >= 0
                if (closes) {
                    val runEnd = if (bright && y == trackBottom - 1) y else y - 1
                    val candidate = Scrollbar(x, runStart, runEnd)
                    if (candidate.length >= height * 0.08f &&
                        (best == null || candidate.length > best!!.length)
                    ) {
                        best = candidate
                    }
                    runStart = -1
                }
                y++
            }
        }
        return best
    }

    private data class ItemContour(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val area: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    /**
     * 直接寻找仓库里的灰/彩色亮矩形。
     *
     * 游戏仓库背景和网格线很暗，而藏品实体是一整块明显更亮的矩形。以连通域而不是
     * 单格中心作为识别单位，可以完整保留跨格藏品尺寸，也不会把相邻独立藏品强行合并。
     */
    private fun detectItemContours(
        pixels: IntArray,
        screenWidth: Int,
        screenHeight: Int,
        gridLeft: Int,
        gridTop: Int,
        gridRight: Int,
        gridBottom: Int,
        cellSize: Float,
    ): List<ItemContour> {
        if (gridLeft !in 0 until screenWidth || gridTop !in 0 until screenHeight) return emptyList()
        if (gridRight <= gridLeft || gridBottom <= gridTop) return emptyList()

        val regionWidth = gridRight - gridLeft
        val regionHeight = gridBottom - gridTop
        val pixelCount = regionWidth * regionHeight
        if (pixelCount <= 0) return emptyList()

        // 取仓库背景亮度的 75 分位做自适应基线；至少 48，避免暗网格/背景被连进来。
        val samples = ArrayList<Int>((regionWidth / 4 + 1) * (regionHeight / 4 + 1))
        var sy = gridTop
        while (sy < gridBottom) {
            var sx = gridLeft
            while (sx < gridRight) {
                samples += luma(pixels[sy * screenWidth + sx])
                sx += 4
            }
            sy += 4
        }
        samples.sort()
        val p75 = if (samples.isEmpty()) 0 else samples[(samples.size * 3 / 4).coerceAtMost(samples.lastIndex)]
        val threshold = (p75 + 20).coerceIn(48, 68)

        val foreground = BooleanArray(pixelCount)
        for (ry in 0 until regionHeight) {
            val sourceOffset = (gridTop + ry) * screenWidth + gridLeft
            val targetOffset = ry * regionWidth
            for (rx in 0 until regionWidth) {
                foreground[targetOffset + rx] = luma(pixels[sourceOffset + rx]) >= threshold
            }
        }

        val visited = BooleanArray(pixelCount)
        val queue = IntArray(pixelCount)
        val contours = mutableListOf<ItemContour>()
        val minArea = (cellSize * cellSize * 0.18f).roundToInt().coerceAtLeast(40)
        val minSpan = cellSize * 0.45f
        val maxSpan = cellSize * 6.45f

        for (seed in 0 until pixelCount) {
            if (!foreground[seed] || visited[seed]) continue

            var head = 0
            var tail = 0
            queue[tail++] = seed
            visited[seed] = true

            var minX = regionWidth
            var maxX = -1
            var minY = regionHeight
            var maxY = -1
            var area = 0

            while (head < tail) {
                val index = queue[head++]
                val x = index % regionWidth
                val y = index / regionWidth
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until regionWidth || ny !in 0 until regionHeight) continue
                        val next = ny * regionWidth + nx
                        if (foreground[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            val spanWidth = maxX - minX + 1
            val spanHeight = maxY - minY + 1
            if (area < minArea) continue
            if (spanWidth < minSpan || spanHeight < minSpan) continue
            if (spanWidth > maxSpan || spanHeight > maxSpan) continue

            val widthCells = spanWidth / cellSize
            val heightCells = spanHeight / cellSize
            val roundedWidth = widthCells.roundToInt().coerceIn(1, 6)
            val roundedHeight = heightCells.roundToInt().coerceIn(1, 6)
            if (abs(widthCells - roundedWidth) > 0.38f || abs(heightCells - roundedHeight) > 0.38f) {
                continue
            }

            contours += ItemContour(
                left = gridLeft + minX,
                top = gridTop + minY,
                right = gridLeft + maxX,
                bottom = gridTop + maxY,
                area = area,
            )
        }

        return contours.sortedWith(compareBy<ItemContour> { it.top }.thenBy { it.left })
    }

    private fun classifyQuality(
        pixels: IntArray,
        screenWidth: Int,
        screenHeight: Int,
        left: Float,
        top: Float,
        itemWidth: Float,
        itemHeight: Float,
    ): WarehouseQualityUi {
        val x0 = (left + itemWidth * 0.20f).roundToInt().coerceIn(0, screenWidth - 1)
        val x1 = (left + itemWidth * 0.80f).roundToInt().coerceIn(x0 + 1, screenWidth)
        val y0 = (top + itemHeight * 0.20f).roundToInt().coerceIn(0, screenHeight - 1)
        val y1 = (top + itemHeight * 0.80f).roundToInt().coerceIn(y0 + 1, screenHeight)

        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in y0 until y1 step 2) {
            val offset = y * screenWidth
            for (x in x0 until x1 step 2) {
                val color = pixels[offset + x]
                r += Color.red(color)
                g += Color.green(color)
                b += Color.blue(color)
                count++
            }
        }
        if (count == 0) return WarehouseQualityUi.UNKNOWN

        val avg = Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        val hsv = FloatArray(3)
        Color.colorToHSV(avg, hsv)
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        if (saturation < 0.22f) {
            return if (value >= 0.62f) WarehouseQualityUi.WHITE else WarehouseQualityUi.UNKNOWN
        }
        return when {
            hue < 18f || hue >= 340f -> WarehouseQualityUi.RED
            hue in 35f..65f -> WarehouseQualityUi.GOLD
            hue in 80f..165f -> WarehouseQualityUi.GREEN
            hue in 185f..250f -> WarehouseQualityUi.BLUE
            hue in 255f..330f -> WarehouseQualityUi.PURPLE
            else -> WarehouseQualityUi.UNKNOWN
        }
    }

    private fun luma(color: Int): Int {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
    }

    private companion object {
        const val EDGE_SCROLL_EPSILON = 0.035
        const val EDGE_TOUCH_FRACTION = 0.18f
    }
}
