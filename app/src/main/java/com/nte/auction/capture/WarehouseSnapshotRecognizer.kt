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

        val rawItems = contours.mapNotNull { contour ->
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

            // 这批诊断图暴露出大量“纯灰占位块”会被亮度连通域误认为藏品。
            // 真正藏品的内部至少包含图标纹理，或品质底色带来的明显色彩；灰块中央则几乎完全平坦。
            if (!hasItemVisualDetail(
                    pixels = pixels,
                    screenWidth = width,
                    screenHeight = height,
                    left = contour.left.toFloat(),
                    top = contour.top.toFloat(),
                    itemWidth = contour.width.toFloat(),
                    itemHeight = contour.height.toFloat(),
                )
            ) {
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
        val items = suppressOverlappingItems(rawItems)

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

        // 阈值过高会把同一个大藏品内部的高亮区域再次切成小连通域。
        // 诊断样本中旧算法 p75 + 20 被顶到 68 后，3x3 内部额外产生了一个 2x2。
        // 改用更靠近背景的 p60，并限制到 57..66：既压住暗网格，又保留物品整体连通性。
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
        val p60 = if (samples.isEmpty()) 0 else samples[(samples.size * 3 / 5).coerceAtMost(samples.lastIndex)]
        val threshold = (p60 + 4).coerceIn(57, 66)

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

    /**
     * 过滤只有灰色渐变/描边、内部没有真实图标内容的占位块。
     * 仅检测中央区域，刻意避开矩形边框；这样白色品质的真实藏品仍可依靠图标明暗纹理通过。
     */
    private fun hasItemVisualDetail(
        pixels: IntArray,
        screenWidth: Int,
        screenHeight: Int,
        left: Float,
        top: Float,
        itemWidth: Float,
        itemHeight: Float,
    ): Boolean {
        val x0 = (left + itemWidth * 0.18f).roundToInt().coerceIn(0, screenWidth - 1)
        val x1 = (left + itemWidth * 0.82f).roundToInt().coerceIn(x0 + 1, screenWidth)
        val y0 = (top + itemHeight * 0.18f).roundToInt().coerceIn(0, screenHeight - 1)
        val y1 = (top + itemHeight * 0.82f).roundToInt().coerceIn(y0 + 1, screenHeight)

        val luminanceSamples = ArrayList<Int>()
        var colorfulSamples = 0
        var sampleCount = 0
        for (y in y0 until y1 step 2) {
            val offset = y * screenWidth
            for (x in x0 until x1 step 2) {
                val color = pixels[offset + x]
                luminanceSamples += luma(color)

                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val maxChannel = maxOf(red, green, blue)
                val minChannel = minOf(red, green, blue)
                if (maxChannel >= 45 &&
                    (maxChannel - minChannel).toFloat() / maxChannel.toFloat() >= DETAIL_SATURATION_MIN
                ) {
                    colorfulSamples++
                }
                sampleCount++
            }
        }

        if (luminanceSamples.size < 4 || sampleCount == 0) return false
        luminanceSamples.sort()
        val p10Index = (luminanceSamples.size / 10).coerceIn(0, luminanceSamples.lastIndex)
        val p90Index = (luminanceSamples.size * 9 / 10).coerceIn(0, luminanceSamples.lastIndex)
        val contrastSpan = luminanceSamples[p90Index] - luminanceSamples[p10Index]
        val colorfulFraction = colorfulSamples.toFloat() / sampleCount.toFloat()

        return contrastSpan >= DETAIL_LUMA_SPAN_MIN || colorfulFraction >= DETAIL_COLOR_FRACTION_MIN
    }

    /**
     * 仓库物品不能占用同一个网格位置。连通域阈值偶尔会在大物品内部再生成一个较小候选，
     * 这里在网格空间做一次冲突消解：被较大候选完整包含、且置信度没有明显优势的小候选直接丢弃；
     * 对剩余极少数重叠候选，再按置信度和覆盖面积综合选择。
     */
    private fun suppressOverlappingItems(items: List<DetectedItem>): List<DetectedItem> {
        if (items.size < 2) return items

        val withoutContainedFragments = items.filter { candidate ->
            items.none { other ->
                other !== candidate &&
                    containsGridCells(outer = other, inner = candidate) &&
                    other.width * other.height > candidate.width * candidate.height &&
                    other.confidence >= candidate.confidence - CONTAINED_CONFIDENCE_TOLERANCE
            }
        }

        val ranked = withoutContainedFragments.sortedWith(
            compareByDescending<DetectedItem> {
                it.confidence + (it.width * it.height).toFloat() * GRID_AREA_PRIORITY_WEIGHT
            }.thenByDescending { it.width * it.height }
        )

        val kept = mutableListOf<DetectedItem>()
        for (candidate in ranked) {
            if (kept.none { overlapsGridCells(candidate, it) }) {
                kept += candidate
            }
        }

        return kept.sortedWith(compareBy<DetectedItem> { it.row }.thenBy { it.column })
    }

    private fun containsGridCells(outer: DetectedItem, inner: DetectedItem): Boolean {
        return inner.row >= outer.row &&
            inner.column >= outer.column &&
            inner.row + inner.height <= outer.row + outer.height &&
            inner.column + inner.width <= outer.column + outer.width
    }

    private fun overlapsGridCells(a: DetectedItem, b: DetectedItem): Boolean {
        val rowStart = maxOf(a.row, b.row)
        val rowEnd = minOf(a.row + a.height, b.row + b.height)
        val columnStart = maxOf(a.column, b.column)
        val columnEnd = minOf(a.column + a.width, b.column + b.width)
        return rowStart < rowEnd && columnStart < columnEnd
    }

    /**
     * 品质颜色主要存在于藏品卡片的外圈底色。旧逻辑对中央区域做 RGB 平均，
     * 银白色主体会把蓝色底色稀释成灰色，导致蓝色护甲被判 UNKNOWN。
     * 现在只统计外圈，并按像素 HSV 投票，避免图标主体干扰。
     */
    private fun classifyQuality(
        pixels: IntArray,
        screenWidth: Int,
        screenHeight: Int,
        left: Float,
        top: Float,
        itemWidth: Float,
        itemHeight: Float,
    ): WarehouseQualityUi {
        val x0 = left.roundToInt().coerceIn(0, screenWidth - 1)
        val x1 = (left + itemWidth).roundToInt().coerceIn(x0 + 1, screenWidth)
        val y0 = top.roundToInt().coerceIn(0, screenHeight - 1)
        val y1 = (top + itemHeight).roundToInt().coerceIn(y0 + 1, screenHeight)

        val minDimension = minOf(x1 - x0, y1 - y0).toFloat()
        val ringInner = maxOf(1f, minDimension * QUALITY_RING_INNER_FRACTION)
        val ringOuter = maxOf(ringInner + 1f, minDimension * QUALITY_RING_OUTER_FRACTION)
        val hsv = FloatArray(3)

        var whiteVotes = 0
        var redVotes = 0
        var goldVotes = 0
        var greenVotes = 0
        var blueVotes = 0
        var purpleVotes = 0
        var eligibleVotes = 0

        for (y in y0 until y1 step 2) {
            val offset = y * screenWidth
            for (x in x0 until x1 step 2) {
                val edgeDistance = minOf(
                    (x - x0).toFloat(),
                    (x1 - 1 - x).toFloat(),
                    (y - y0).toFloat(),
                    (y1 - 1 - y).toFloat(),
                )
                if (edgeDistance < ringInner || edgeDistance > ringOuter) continue

                val color = pixels[offset + x]
                Color.colorToHSV(color, hsv)
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]

                val quality = when {
                    saturation < 0.18f && value >= 0.58f -> WarehouseQualityUi.WHITE
                    saturation < 0.20f || value < 0.18f -> null
                    hue < 20f || hue >= 340f -> WarehouseQualityUi.RED
                    hue in 20f..75f -> WarehouseQualityUi.GOLD
                    hue in 80f..170f -> WarehouseQualityUi.GREEN
                    hue in 180f..250f -> WarehouseQualityUi.BLUE
                    hue in 250f..335f -> WarehouseQualityUi.PURPLE
                    else -> null
                }

                when (quality) {
                    WarehouseQualityUi.WHITE -> whiteVotes++
                    WarehouseQualityUi.RED -> redVotes++
                    WarehouseQualityUi.GOLD -> goldVotes++
                    WarehouseQualityUi.GREEN -> greenVotes++
                    WarehouseQualityUi.BLUE -> blueVotes++
                    WarehouseQualityUi.PURPLE -> purpleVotes++
                    else -> continue
                }
                eligibleVotes++
            }
        }

        if (eligibleVotes == 0) return WarehouseQualityUi.UNKNOWN
        val maxVotes = listOf(
            whiteVotes,
            redVotes,
            goldVotes,
            greenVotes,
            blueVotes,
            purpleVotes,
        ).maxOrNull() ?: 0
        if (maxVotes < QUALITY_MIN_VOTES ||
            maxVotes.toFloat() / eligibleVotes.toFloat() < QUALITY_MIN_DOMINANCE
        ) {
            return WarehouseQualityUi.UNKNOWN
        }

        return when (maxVotes) {
            whiteVotes -> WarehouseQualityUi.WHITE
            redVotes -> WarehouseQualityUi.RED
            goldVotes -> WarehouseQualityUi.GOLD
            greenVotes -> WarehouseQualityUi.GREEN
            blueVotes -> WarehouseQualityUi.BLUE
            purpleVotes -> WarehouseQualityUi.PURPLE
            else -> WarehouseQualityUi.UNKNOWN
        }
    }

    private fun luma(color: Int): Int {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
    }

    private companion object {
        const val EDGE_SCROLL_EPSILON = 0.035
        const val EDGE_TOUCH_FRACTION = 0.18f
        const val CONTAINED_CONFIDENCE_TOLERANCE = 0.12f
        const val GRID_AREA_PRIORITY_WEIGHT = 0.012f
        const val DETAIL_LUMA_SPAN_MIN = 18
        const val DETAIL_SATURATION_MIN = 0.12f
        const val DETAIL_COLOR_FRACTION_MIN = 0.08f
        const val QUALITY_RING_INNER_FRACTION = 0.04f
        const val QUALITY_RING_OUTER_FRACTION = 0.22f
        const val QUALITY_MIN_VOTES = 3
        const val QUALITY_MIN_DOMINANCE = 0.45f
    }
}
