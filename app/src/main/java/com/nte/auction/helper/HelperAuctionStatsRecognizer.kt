package com.nte.auction.helper

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

data class HelperOcrFields(
    val totalItems: Int? = null,
    val purpleCount: Int? = null,
    val goldCount: Int? = null,
    val goldTotalCells: Int? = null,
    val goldAverage: Double? = null,
    val rawLines: List<String> = emptyList(),
)

/** RapidOCR desktop behavior rewritten for Android ML Kit Chinese OCR. */
class HelperAuctionStatsRecognizer {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun recognize(bitmap: Bitmap, callback: (Result<HelperOcrFields>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { block -> block.lines.map { it.text } }
                    .filter { it.isNotBlank() }
                callback(runCatching { parseLines(lines) })
            }
            .addOnFailureListener { error -> callback(Result.failure(error)) }
    }

    fun parseLines(lines: List<String>): HelperOcrFields {
        val normalized = lines.map(::normalize).filter { it.isNotBlank() }
        val joined = normalized.joinToString("")
        val sources = normalized + joined

        val totalLine = pickLine(
            sources,
            includeAll = listOf("总件数"),
            includeAny = listOf("紫色", "金色", "红色"),
        )
        val purpleLine = pickLine(
            sources,
            includeAll = listOf("紫色", "总数量"),
            excludeAny = listOf("金色"),
        )
        val goldCellLine = pickLine(
            normalized,
            includeAll = listOf("金色", "所占格数"),
            excludeAny = listOf("蓝色", "紫色", "红色", "总件数", "总数量", "平均价值"),
        )
        val averageLine = pickLine(
            sources,
            includeAll = listOf("金色", "平均价值"),
            excludeAny = listOf("紫色"),
        )
        val goldCountLine = pickLine(
            sources,
            includeAll = listOf("金色", "总数量"),
            excludeAny = listOf("蓝色", "紫色", "红色", "所占格", "平均价"),
        )

        return HelperOcrFields(
            totalItems = numberAfterKeywords(totalLine ?: joined, listOf(
                "本局内紫色金色和红色品质藏品的总件数",
                "本局内所有紫色金色和红色品质藏品的总件数",
                "总件数",
            ))?.toInt(),
            purpleCount = numberAfterKeywords(purpleLine ?: joined, listOf(
                "本局内所有紫色藏品的总数量",
                "本局内所有紫色品质藏品的总数量",
                "所有紫色藏品的总数量",
                "所有紫色品质藏品的总数量",
                "紫色藏品的总数量",
                "紫色品质藏品的总数量",
            ))?.toInt(),
            goldTotalCells = numberFromPatterns(goldCellLine.orEmpty(), listOf(
                Regex("(?:本局内所有|所有|本局内)?金色品质藏品的所占格数(?:为|是|:)?(\\d[\\d,]*)"),
                Regex("(?:本局内所有|所有|本局内)?金色藏品的所占格数(?:为|是|:)?(\\d[\\d,]*)"),
                Regex("金色品质藏品的所占格数(?:为|是|:)?(\\d[\\d,]*)"),
                Regex("金色藏品的所占格数(?:为|是|:)?(\\d[\\d,]*)"),
            ))?.toInt() ?: numberAfterKeywords(goldCellLine.orEmpty(), listOf(
                "本局内所有金色品质藏品的所占格数", "所有金色品质藏品的所占格数", "金色品质藏品的所占格数",
                "本局内所有金色藏品的所占格数", "所有金色藏品的所占格数", "金色藏品的所占格数",
            ))?.toInt(),
            goldAverage = numberAfterKeywords(averageLine ?: joined, listOf(
                "本局内所有金色品质藏品的平均价值", "所有金色品质藏品的平均价值", "金色品质藏品的平均价值",
            )),
            goldCount = numberFromPatterns(goldCountLine ?: joined, listOf(
                Regex("(?:本局内所有|所有|本局内)?金色品质藏品的总数量(?:为|是|:)?(\\d[\\d,]*)"),
                Regex("(?:本局内所有|所有|本局内)?金色藏品的总数量(?:为|是|:)?(\\d[\\d,]*)"),
                Regex("金色品质藏品的总数量(?:为|是|:)?(\\d[\\d,]*)"),
                Regex("金色藏品的总数量(?:为|是|:)?(\\d[\\d,]*)"),
            ))?.toInt() ?: numberAfterKeywords(goldCountLine ?: joined, listOf(
                "本局内所有金色藏品的总数量为", "本局内所有金色品质藏品的总数量为",
                "所有金色藏品的总数量为", "所有金色品质藏品的总数量为",
                "金色藏品的总数量为", "金色品质藏品的总数量为", "金色藏品总数量为", "金色总数量为",
            ))?.toInt(),
            rawLines = lines,
        )
    }

    private fun normalize(text: String): String = text
        .replace(Regex("\\s+"), "")
        .replace('：', ':')
        .replace('，', ',')
        .replace('．', '.')
        .replace("。", "")

    private fun parseNumber(raw: String): Double? {
        val match = Regex("\\d[\\d,]*(?:\\.\\d+)?").find(normalize(raw)) ?: return null
        return match.value.replace(",", "").toDoubleOrNull()
    }

    private fun numberAfterKeywords(text: String, keywords: List<String>): Double? {
        val source = normalize(text)
        for (keyword in keywords) {
            val index = source.indexOf(keyword)
            if (index < 0) continue
            val tail = source.substring(index + keyword.length).trimStart(':', '=', '为', '是')
            parseNumber(tail)?.let { return it }
        }
        return null
    }

    private fun numberFromPatterns(text: String, patterns: List<Regex>): Double? {
        val source = normalize(text)
        patterns.forEach { pattern ->
            val match = pattern.find(source) ?: return@forEach
            match.groupValues.drop(1).firstNotNullOfOrNull(::parseNumber)?.let { return it }
            parseNumber(match.value)?.let { return it }
        }
        return null
    }

    private fun pickLine(
        lines: List<String>,
        includeAny: List<String> = emptyList(),
        includeAll: List<String> = emptyList(),
        excludeAny: List<String> = emptyList(),
    ): String? {
        var best: String? = null
        var bestScore = -1
        lines.forEach { line ->
            val text = normalize(line)
            if (includeAll.any { it !in text }) return@forEach
            if (includeAny.isNotEmpty() && includeAny.none { it in text }) return@forEach
            if (excludeAny.any { it in text }) return@forEach
            val score = includeAll.count { it in text } * 2 + includeAny.count { it in text }
            if (score > bestScore) {
                best = text
                bestScore = score
            }
        }
        return best
    }
}
