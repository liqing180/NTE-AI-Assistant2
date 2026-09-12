package com.nte.auction.capture

import android.graphics.Bitmap
import android.graphics.Color
import com.nte.auction.ui.WarehouseQualityUi
import kotlin.math.roundToInt

/**
 * 单张全屏截图仓库识别。
 *
 * 游戏仓库固定在横屏右侧。这里不再依赖连续视频帧去猜滚动距离，而是直接读取
 * 仓库右侧滚动条：滚动条滑块位置决定当前 viewport 对应的全局起始行，滑块长度
 * 用来估算完整仓库行数。这样用户可以滚到任意位置后点击一次“识别/更新仓库”，
 * 每张截图都能独立定位并增量合并。
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
        val width = bitmap.width
        val height = bitmap.height
        if (width <= height || width < 800 || height < 400) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val trackTop = (height * 0.18f).roundToInt().coerceIn(0, height - 1)
        val trackBottom = (height * 0.75f).roundToInt().coerceIn(trackTop + 1, height)
        val scroll = detectScrollbar(pixels, width, height, trackTop, trackBottom) ?: return null

        // 视频样本中仓库为 10 列，右边界紧贴滚动条左侧。使用滚动条作为横向锚点，
        // 比单纯写死屏幕坐标更能适配不同 16:9 / 20:9 横屏分辨率。
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

        val occupied = Array(visibleRows) { BooleanArray(columns) }
        for (row in 0 until visibleRows) {
            for (column in 0 until columns) {
                occupied[row][column] = isOccupiedCell(
                    pixels = pixels,
                    screenWidth = width,
                    screenHeight = height,
                    left = gridLeft + column * cellSize,
                    top = gridTop + row * cellSize,
                    size = cellSize,
                )
            }
        }

        val components = buildComponents(
            occupied = occupied,
            pixels = pixels,
            screenWidth = width,
            screenHeight = height,
            gridLeft = gridLeft.toFloat(),
            gridTop = gridTop.toFloat(),
            cellSize = cellSize,
        )

        val items = components.mapNotNull { component ->
            // 非顶部/底部 viewport 的边缘对象可能是被滚动裁掉的一部分，跳过它们，
            // 避免把一个完整藏品错误记录成更小尺寸。下一次有完整视野时会补回来。
            if (viewportStartRow > 0 && component.minRow == 0) return@mapNotNull null
            if (viewportStartRow + visibleRows < totalRows && component.maxRow == visibleRows - 1) {
                return@mapNotNull null
            }

            val itemWidth = component.maxColumn - component.minColumn + 1
            val itemHeight = component.maxRow - component.minRow + 1
            val quality = classifyQuality(
                pixels = pixels,
                screenWidth = width,
                screenHeight = height,
                left = gridLeft + component.minColumn * cellSize,
                top = gridTop + component.minRow * cellSize,
                itemWidth = itemWidth * cellSize,
                itemHeight = itemHeight * cellSize,
            )
            DetectedItem(
                row = viewportStartRow + component.minRow,
                column = component.minColumn,
                width = itemWidth,
                height = itemHeight,
                quality = quality,
                confidence = component.confidence,
            )
        }

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

    private fun isOccupiedCell(
        pixels: IntArray,
        screenWidth: Int,
        screenHeight: Int,
        left: Float,
        top: Float,
        size: Float,
    ): Boolean {
        val x0 = (left + size * 0.22f).roundToInt().coerceIn(0, screenWidth - 1)
        val x1 = (left + size * 0.78f).roundToInt().coerceIn(x0 + 1, screenWidth)
        val y0 = (top + size * 0.22f).roundToInt().coerceIn(0, screenHeight - 1)
        val y1 = (top + size * 0.78f).roundToInt().coerceIn(y0 + 1, screenHeight)

        var sum = 0L
        var bright = 0
        var count = 0
        for (y in y0 until y1) {
            val offset = y * screenWidth
            for (x in x0 until x1) {
                val value = luma(pixels[offset + x])
                sum += value
                if (value >= 48) bright++
                count++
            }
        }
        if (count == 0) return false
        val mean = sum.toDouble() / count.toDouble()
        val brightRatio = bright.toDouble() / count.toDouble()
        return mean >= 38.0 && brightRatio >= 0.52
    }

    private data class Component(
        val minRow: Int,
        val maxRow: Int,
        val minColumn: Int,
        val maxColumn: Int,
        val confidence: Float,
    )

    private fun buildComponents(
        occupied: Array<BooleanArray>,
        pixels: IntArray,
        screenWidth: Int,
        screenHeight: Int,
        gridLeft: Float,
        gridTop: Float,
        cellSize: Float,
    ): List<Component> {
        val rows = occupied.size
        val columns = occupied.firstOrNull()?.size ?: return emptyList()
        val parent = IntArray(rows * columns) { it }

        fun index(row: Int, column: Int) = row * columns + column
        fun find(value: Int): Int {
            var v = value
            while (parent[v] != v) {
                parent[v] = parent[parent[v]]
                v = parent[v]
            }
            return v
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (!occupied[row][column]) continue
                if (column + 1 < columns && occupied[row][column + 1] &&
                    hasContinuousVerticalBridge(
                        pixels, screenWidth, screenHeight,
                        gridLeft + (column + 1) * cellSize,
                        gridTop + row * cellSize,
                        cellSize,
                    )
                ) {
                    union(index(row, column), index(row, column + 1))
                }
                if (row + 1 < rows && occupied[row + 1][column] &&
                    hasContinuousHorizontalBridge(
                        pixels, screenWidth, screenHeight,
                        gridLeft + column * cellSize,
                        gridTop + (row + 1) * cellSize,
                        cellSize,
                    )
                ) {
                    union(index(row, column), index(row + 1, column))
                }
            }
        }

        val groups = linkedMapOf<Int, MutableList<Pair<Int, Int>>>()
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (!occupied[row][column]) continue
                groups.getOrPut(find(index(row, column))) { mutableListOf() } += row to column
            }
        }

        return groups.values.map { cells ->
            val minRow = cells.minOf { it.first }
            val maxRow = cells.maxOf { it.first }
            val minColumn = cells.minOf { it.second }
            val maxColumn = cells.maxOf { it.second }
            val rectangleCells = (maxRow - minRow + 1) * (maxColumn - minColumn + 1)
            val rectangularity = cells.size.toFloat() / rectangleCells.toFloat()
            Component(
                minRow = minRow,
                maxRow = maxRow,
                minColumn = minColumn,
                maxColumn = maxColumn,
                confidence = (0.72f + 0.25f * rectangularity).coerceIn(0f, 0.97f),
            )
        }.filter { component ->
            // 藏品应当接近矩形。零散亮点（触控圆点/特效）会在这里被过滤。
            val width = component.maxColumn - component.minColumn + 1
            val height = component.maxRow - component.minRow + 1
            width in 1..6 && height in 1..6
        }
    }

    /**
     * 同一个多格藏品跨过内部网格线时，边界附近通常是近乎均匀的灰色填充；
     * 两个相邻但独立的藏品在接缝处会出现圆角、亮边和暗缝，亮度方差明显更大。
     * 仅看平均亮度会把相邻藏品错误合并，所以这里同时限制方差。
     */
    private fun hasContinuousVerticalBridge(
        pixels: IntArray,
        width: Int,
        height: Int,
        boundaryX: Float,
        cellTop: Float,
        cellSize: Float,
    ): Boolean {
        val x0 = (boundaryX - cellSize * 0.08f).roundToInt().coerceIn(0, width - 1)
        val x1 = (boundaryX + cellSize * 0.08f).roundToInt().coerceIn(x0 + 1, width)
        val y0 = (cellTop + cellSize * 0.15f).roundToInt().coerceIn(0, height - 1)
        val y1 = (cellTop + cellSize * 0.85f).roundToInt().coerceIn(y0 + 1, height)
        return isUniformBrightBridge(pixels, width, x0, y0, x1, y1)
    }

    private fun hasContinuousHorizontalBridge(
        pixels: IntArray,
        width: Int,
        height: Int,
        cellLeft: Float,
        boundaryY: Float,
        cellSize: Float,
    ): Boolean {
        val x0 = (cellLeft + cellSize * 0.15f).roundToInt().coerceIn(0, width - 1)
        val x1 = (cellLeft + cellSize * 0.85f).roundToInt().coerceIn(x0 + 1, width)
        val y0 = (boundaryY - cellSize * 0.08f).roundToInt().coerceIn(0, height - 1)
        val y1 = (boundaryY + cellSize * 0.08f).roundToInt().coerceIn(y0 + 1, height)
        return isUniformBrightBridge(pixels, width, x0, y0, x1, y1)
    }

    private fun isUniformBrightBridge(
        pixels: IntArray,
        width: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Boolean {
        var sum = 0L
        var sumSquares = 0L
        var count = 0
        for (y in y0 until y1) {
            val offset = y * width
            for (x in x0 until x1) {
                val value = luma(pixels[offset + x])
                sum += value
                sumSquares += value.toLong() * value.toLong()
                count++
            }
        }
        if (count == 0) return false
        val mean = sum.toDouble() / count.toDouble()
        val variance = (sumSquares.toDouble() / count.toDouble()) - mean * mean
        return mean >= 36.0 && variance <= 25.0
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
}
