package com.nte.auction.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.nte.auction.domain.Quality
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Local reference-image pack used by the warehouse candidate picker.
 *
 * Compatible ZIP layouts:
 * - database/gold_items/<price>.png
 * - database/red_items/<price>.png
 * - gold/<price>.png
 * - red/<price>.png
 *
 * Images are deliberately imported by the user instead of being bundled from the
 * upstream project because that repository currently does not publish an explicit license.
 */
object HelperReferenceImageStore {
    data class ImportResult(
        val goldCount: Int,
        val redCount: Int,
        val skippedCount: Int,
    ) {
        val totalCount: Int get() = goldCount + redCount
    }

    data class PackStats(
        val goldCount: Int,
        val redCount: Int,
    ) {
        val totalCount: Int get() = goldCount + redCount
    }

    fun resolveImageFile(context: Context, quality: Quality, price: Long): File? {
        val qualityDir = File(rootDir(context), qualityKey(quality))
        return IMAGE_EXTENSIONS.asSequence()
            .map { extension -> File(qualityDir, "$price.$extension") }
            .firstOrNull { it.isFile && it.length() > 0L }
    }

    fun decode(context: Context, quality: Quality, price: Long): Bitmap? {
        val file = resolveImageFile(context, quality, price) ?: return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun stats(context: Context): PackStats = PackStats(
        goldCount = countImages(File(rootDir(context), GOLD_DIR)),
        redCount = countImages(File(rootDir(context), RED_DIR)),
    )

    fun clear(context: Context) {
        rootDir(context).deleteRecursively()
    }

    fun importZip(context: Context, uri: Uri): ImportResult {
        val root = rootDir(context).apply { mkdirs() }
        var goldCount = 0
        var redCount = 0
        var skippedCount = 0
        var acceptedEntries = 0

        val input = context.contentResolver.openInputStream(uri)
            ?: error("无法打开参考图压缩包")
        input.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (acceptedEntries >= MAX_IMAGE_COUNT) {
                        skippedCount++
                        zip.closeEntry()
                        continue
                    }
                    val target = parseTarget(root, entry.name)
                    if (target == null) {
                        skippedCount++
                        zip.closeEntry()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            copied += read
                            if (copied > MAX_IMAGE_BYTES) {
                                throw IllegalArgumentException("单张参考图超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MB：${entry.name}")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    if (BitmapFactory.decodeFile(target.absolutePath) == null) {
                        target.delete()
                        skippedCount++
                    } else {
                        acceptedEntries++
                        if (target.parentFile?.name == GOLD_DIR) goldCount++ else redCount++
                    }
                    zip.closeEntry()
                }
            }
        }
        return ImportResult(goldCount, redCount, skippedCount)
    }

    private fun parseTarget(root: File, rawName: String): File? {
        val normalized = rawName.replace('\\', '/').trimStart('/')
        if (normalized.contains("../")) return null
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val fileName = parts.last()
        val sourceDir = parts[parts.lastIndex - 1].lowercase()
        val qualityDir = when (sourceDir) {
            "gold_items", "gold" -> GOLD_DIR
            "red_items", "red" -> RED_DIR
            else -> return null
        }
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return null
        val price = fileName.substring(0, dot).toLongOrNull()?.takeIf { it > 0L } ?: return null
        val extension = fileName.substring(dot + 1).lowercase()
        if (extension !in IMAGE_EXTENSIONS) return null

        val directory = File(root, qualityDir)
        return File(directory, "$price.$extension")
    }

    private fun countImages(directory: File): Int = directory.listFiles()
        ?.count { file ->
            file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS && file.nameWithoutExtension.toLongOrNull() != null
        }
        ?: 0

    private fun rootDir(context: Context): File = File(context.filesDir, ROOT_DIR)

    private fun qualityKey(quality: Quality): String = when (quality) {
        Quality.GOLD -> GOLD_DIR
        Quality.RED -> RED_DIR
        else -> error("参考图仅支持金色和红色藏品")
    }

    private const val ROOT_DIR = "helper_reference_images"
    private const val GOLD_DIR = "gold"
    private const val RED_DIR = "red"
    private const val MAX_IMAGE_COUNT = 256
    private const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
}
