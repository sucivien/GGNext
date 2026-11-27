package com.littlecodesauce.ggnext.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.FontFamily
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 自定义字体管理器与多字体缓存：
 * - 不内置任何字体文件，默认完全使用系统字体 (FontFamily.Default / Typeface.DEFAULT)
 * - 支持导入多个 .ttf / .otf / .ttc 字体文件到应用私有目录，保留历史导入并支持自由切换与删除
 * - 校验字体文件头与 Typeface 可加载性，防止损坏文件导致崩溃
 * - 变更后主界面与悬浮窗共享同一份路径与缓存，实现实时同步
 */
object AppFontCache {
    private const val FONT_DIR_NAME = "fonts"
    private const val MAX_FONT_BYTES = 32L * 1024L * 1024L // 32 MB 上限

    private val familyCache = ConcurrentHashMap<String, FontFamily>()
    private val typefaceCache = ConcurrentHashMap<String, Typeface>()

    data class ImportedFont(
        val path: String,
        val displayName: String,
        val sizeBytes: Long = 0L
    )

    fun fontFamilyFor(path: String): FontFamily {
        if (path.isBlank()) return FontFamily.Default
        familyCache[path]?.let { return it }
        val tf = loadAndCache(path) ?: return FontFamily.Default
        return familyCache[path] ?: FontFamily(tf)
    }

    fun typefaceFor(path: String): Typeface? {
        if (path.isBlank()) return null
        typefaceCache[path]?.let { return it }
        return loadAndCache(path)
    }

    @Synchronized
    private fun loadAndCache(path: String): Typeface? {
        typefaceCache[path]?.let { return it }
        val file = File(path)
        if (!file.isFile || !file.canRead() || file.length() < 12L) {
            return null
        }
        val tf = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
        typefaceCache[path] = tf
        familyCache[path] = FontFamily(tf)
        return tf
    }

    @Synchronized
    fun clear() {
        familyCache.clear()
        typefaceCache.clear()
    }

    /** 校验常见 TrueType / OpenType 字体魔数头（纯函数，可用于单元测试） */
    fun isValidFontHeader(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF
        val isTtf = (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00)
        val isTrue = (b0 == 't'.code && b1 == 'r'.code && b2 == 'u'.code && b3 == 'e'.code)
        val isOtto = (b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code)
        val isTtc = (b0 == 't'.code && b1 == 't'.code && b2 == 'c'.code && b3 == 'f'.code)
        return isTtf || isTrue || isOtto || isTtc
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = c.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "custom_font.ttf"
    }

    fun importFromUri(context: Context, uri: Uri): Result<ImportedFont> {
        val displayName = resolveDisplayName(context, uri)
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext != "ttf" && ext != "otf" && ext != "ttc") {
            return Result.failure(IllegalArgumentException("仅支持导入 .ttf / .otf 字体文件"))
        }

        val fontsDir = File(context.filesDir, FONT_DIR_NAME).apply { mkdirs() }
        val suffix = if (ext == "otf" || ext == "ttc") ext else "ttf"
        val destFile = File(fontsDir, "user_font_${System.currentTimeMillis()}.$suffix")

        return runCatching {
            var totalBytes = 0L
            val header = ByteArray(4)
            var headerRead = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (headerRead < 4) {
                            val toCopy = minOf(4 - headerRead, n)
                            System.arraycopy(buf, 0, header, headerRead, toCopy)
                            headerRead += toCopy
                        }
                        totalBytes += n
                        if (totalBytes > MAX_FONT_BYTES) {
                            throw IllegalArgumentException("字体文件过大（上限 32MB）")
                        }
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            } ?: throw IllegalStateException("无法读取所选字体文件")

            if (headerRead < 4 || !isValidFontHeader(header)) {
                destFile.delete()
                throw IllegalArgumentException("所选文件不是有效的 TTF/OTF 字体文件")
            }

            // 预加载验证 Typeface 合法性
            val tf = runCatching { Typeface.createFromFile(destFile) }.getOrNull()
                ?: run {
                    destFile.delete()
                    throw IllegalArgumentException("无法解析该字体文件，请更换有效的 TTF 字体")
                }

            typefaceCache[destFile.absolutePath] = tf
            familyCache[destFile.absolutePath] = FontFamily(tf)
            ImportedFont(
                path = destFile.absolutePath,
                displayName = displayName,
                sizeBytes = totalBytes
            )
        }.onFailure {
            runCatching { destFile.delete() }
        }
    }

    fun importFromFile(context: Context, sourcePath: String): Result<ImportedFont> {
        val src = File(sourcePath)
        if (!src.isFile || !src.canRead()) {
            return Result.failure(IllegalArgumentException("找不到或无法读取字体文件"))
        }
        val ext = src.extension.lowercase()
        if (ext != "ttf" && ext != "otf" && ext != "ttc") {
            return Result.failure(IllegalArgumentException("仅支持导入 .ttf / .otf 字体文件"))
        }
        if (src.length() > MAX_FONT_BYTES) {
            return Result.failure(IllegalArgumentException("字体文件过大（上限 32MB）"))
        }

        val fontsDir = File(context.filesDir, FONT_DIR_NAME).apply { mkdirs() }
        val destFile = File(fontsDir, "user_font_${System.currentTimeMillis()}.$ext")

        return runCatching {
            val header = ByteArray(4)
            var headerRead = 0
            var totalBytes = 0L
            src.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (headerRead < 4) {
                            val toCopy = minOf(4 - headerRead, n)
                            System.arraycopy(buf, 0, header, headerRead, toCopy)
                            headerRead += toCopy
                        }
                        totalBytes += n
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
            if (headerRead < 4 || !isValidFontHeader(header)) {
                destFile.delete()
                throw IllegalArgumentException("所选文件不是有效的 TTF/OTF 字体文件")
            }
            val tf = runCatching { Typeface.createFromFile(destFile) }.getOrNull()
                ?: run {
                    destFile.delete()
                    throw IllegalArgumentException("无法解析该字体文件，请更换有效的 TTF 字体")
                }

            typefaceCache[destFile.absolutePath] = tf
            familyCache[destFile.absolutePath] = FontFamily(tf)
            ImportedFont(
                path = destFile.absolutePath,
                displayName = src.name,
                sizeBytes = totalBytes
            )
        }.onFailure {
            runCatching { destFile.delete() }
        }
    }

    /** 删除单个已导入字体（仅删除应用私有字体目录中的文件） */
    fun deleteManagedFont(context: Context, path: String) {
        familyCache.remove(path)
        typefaceCache.remove(path)
        runCatching {
            val fontsDir = File(context.filesDir, FONT_DIR_NAME).canonicalPath
            val target = File(path)
            if (target.isFile && target.canonicalPath.startsWith(fontsDir)) {
                target.delete()
            }
        }
    }

    fun deleteAllManagedFonts(context: Context) {
        clear()
        runCatching {
            val fontsDir = File(context.filesDir, FONT_DIR_NAME)
            fontsDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun encodeFontList(fonts: List<ImportedFont>): String {
        val arr = JSONArray()
        fonts.forEach { f ->
            arr.put(
                JSONObject().apply {
                    put("path", f.path)
                    put("name", f.displayName)
                    put("size", f.sizeBytes)
                }
            )
        }
        return arr.toString()
    }

    fun decodeFontList(json: String?): List<ImportedFont> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val list = ArrayList<ImportedFont>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString("path").trim()
                val name = obj.optString("name").trim()
                val size = obj.optLong("size", 0L)
                if (path.isNotEmpty()) {
                    list.add(ImportedFont(path = path, displayName = name.ifEmpty { File(path).name }, sizeBytes = size))
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    /**
     * 校对已保存字体列表与磁盘实际文件：
     * - 剔除磁盘上已不存在的无效记录
     * - 补齐当前激活字体或私有目录中遗留的字体文件
     */
    fun reconcileManagedFonts(
        context: Context?,
        saved: List<ImportedFont>,
        activePath: String,
        activeName: String
    ): List<ImportedFont> {
        val map = LinkedHashMap<String, ImportedFont>()
        saved.forEach { item ->
            val f = File(item.path)
            if (f.isFile && f.canRead()) {
                map[item.path] = item.copy(
                    displayName = item.displayName.ifBlank { f.name },
                    sizeBytes = if (item.sizeBytes > 0L) item.sizeBytes else f.length()
                )
            }
        }
        if (activePath.isNotBlank()) {
            val f = File(activePath)
            if (f.isFile && f.canRead() && !map.containsKey(activePath)) {
                map[activePath] = ImportedFont(
                    path = activePath,
                    displayName = activeName.ifBlank { f.name },
                    sizeBytes = f.length()
                )
            }
        }
        if (context != null) {
            runCatching {
                val fontsDir = File(context.filesDir, FONT_DIR_NAME)
                fontsDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.canRead() && !map.containsKey(f.absolutePath)) {
                        val ext = f.extension.lowercase()
                        if (ext == "ttf" || ext == "otf" || ext == "ttc") {
                            map[f.absolutePath] = ImportedFont(
                                path = f.absolutePath,
                                displayName = f.name,
                                sizeBytes = f.length()
                            )
                        }
                    }
                }
            }
        }
        return map.values.toList()
    }

    fun formatFontSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        return when {
            bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
