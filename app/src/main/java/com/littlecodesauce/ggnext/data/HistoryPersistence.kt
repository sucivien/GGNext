package com.littlecodesauce.ggnext.data

import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import java.io.File

/**
 * 纯 Kotlin 零依赖 RFC-8259 JSON 编解码与历史/保存列表持久化工具（模块 2 & 模块 6）：
 * - 不依赖 Android `org.json`，可在本地 JVM 单元测试与 Android 运行时一致运行；
 * - 完整转义双引号、反斜杠、换行、制表符、分号、波浪号与任意 Unicode 字符。
 */
data class SearchHistoryEntry(
    val text: String,
    val pinned: Boolean = false
)

data class ScriptHistoryEntry(
    val path: String,
    val pinned: Boolean = false,
    val note: String = ""
)

object HistoryPersistence {

    val DEFAULT_SCRIPT_HISTORY: List<ScriptHistoryEntry> = listOf(
        ScriptHistoryEntry("/sdcard/Download/script.lua"),
        ScriptHistoryEntry("/sdcard/GGNext/scripts/main.lua"),
        ScriptHistoryEntry("/sdcard/GGNext/scripts/pointer_scan.lua")
    )

    // ---------------- Search History ----------------

    fun encodeSearchHistory(entries: List<SearchHistoryEntry>): String {
        val arr = entries.map { e ->
            linkedMapOf<String, Any?>(
                "text" to e.text,
                "pinned" to e.pinned
            )
        }
        return SimpleJson.stringify(arr)
    }

    fun decodeSearchHistory(json: String?): List<SearchHistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val parsed = SimpleJson.parse(json) as? List<*> ?: return emptyList()
        val out = ArrayList<SearchHistoryEntry>(parsed.size)
        for (el in parsed) {
            when (el) {
                is Map<*, *> -> {
                    val text = el["text"] as? String ?: continue
                    if (text.isBlank()) continue
                    val pinned = el["pinned"] as? Boolean ?: false
                    out.add(SearchHistoryEntry(text = text, pinned = pinned))
                }
                is String -> if (el.isNotBlank()) out.add(SearchHistoryEntry(text = el))
            }
        }
        return out
    }

    // ---------------- Goto History ----------------

    fun encodeGotoHistory(entries: List<String>): String =
        SimpleJson.stringify(entries.map { it.trim() }.filter { it.isNotEmpty() })

    fun decodeGotoHistory(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val parsed = SimpleJson.parse(json) as? List<*> ?: return emptyList()
        val out = ArrayList<String>(parsed.size)
        for (el in parsed) {
            when (el) {
                is String -> if (el.isNotBlank()) out.add(el)
                is Map<*, *> -> (el["addressHex"] as? String)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out
    }

    // ---------------- Script History ----------------

    fun encodeScriptHistory(entries: List<ScriptHistoryEntry>): String {
        val arr = entries.map { e ->
            linkedMapOf<String, Any?>(
                "path" to e.path,
                "pinned" to e.pinned,
                "note" to e.note
            )
        }
        return SimpleJson.stringify(arr)
    }

    fun decodeScriptHistory(json: String?): List<ScriptHistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val parsed = SimpleJson.parse(json) as? List<*> ?: return emptyList()
        val out = ArrayList<ScriptHistoryEntry>(parsed.size)
        for (el in parsed) {
            when (el) {
                is Map<*, *> -> {
                    val path = el["path"] as? String ?: continue
                    if (path.isBlank()) continue
                    val pinned = el["pinned"] as? Boolean ?: false
                    val note = el["note"] as? String ?: ""
                    out.add(ScriptHistoryEntry(path = path, pinned = pinned, note = note))
                }
                is String -> if (el.isNotBlank()) out.add(ScriptHistoryEntry(path = el))
            }
        }
        return out
    }

    /**
     * 按 history_limit 截断脚本历史：
     * - limit <= 0 时不新增记录（保留现有已置顶项）；
     * - limit > 0 时仅裁剪未置顶条目，置顶项永不被淘汰。
     */
    fun trimScriptHistory(entries: List<ScriptHistoryEntry>, limit: Int): List<ScriptHistoryEntry> {
        if (limit <= 0) return entries.filter { it.pinned }
        if (entries.size <= limit) return entries
        val out = entries.toMutableList()
        var idx = out.lastIndex
        while (out.size > limit && idx >= 0) {
            if (!out[idx].pinned) {
                out.removeAt(idx)
            }
            idx--
        }
        return out
    }

    // ---------------- Saved List Persistence ----------------

    fun encodeSavedList(items: List<MemoryItem>): String {
        val arr = items.map { item ->
            linkedMapOf<String, Any?>(
                "address" to item.address,
                "value" to item.value,
                "previousValue" to item.previousValue,
                "flags" to item.flags,
                "name" to item.name,
                "region" to item.region,
                "freeze" to item.freeze,
                "freezeType" to item.freezeType,
                "freezeFrom" to item.freezeFrom,
                "freezeTo" to item.freezeTo,
                "note" to item.note,
                "xorKey" to item.xorKey,
                "xorOffset" to item.xorOffset,
                "encryptedAuto" to item.encryptedAuto
            )
        }
        return SimpleJson.stringify(arr)
    }

    fun decodeSavedList(json: String?): List<MemoryItem> {
        if (json.isNullOrBlank()) return emptyList()
        val parsed = SimpleJson.parse(json) as? List<*> ?: return emptyList()
        val out = ArrayList<MemoryItem>(parsed.size)
        for (el in parsed) {
            val map = el as? Map<*, *> ?: continue
            val addr = (map["address"] as? Number)?.toLong() ?: continue
            val value = (map["value"] as? Number)?.toLong() ?: 0L
            val prev = (map["previousValue"] as? Number)?.toLong() ?: value
            val flags = (map["flags"] as? Number)?.toInt() ?: ValueType.DWORD
            val name = map["name"] as? String ?: ""
            val region = (map["region"] as? String)?.ifEmpty { "Ca" } ?: "Ca"
            val freeze = map["freeze"] as? Boolean ?: false
            val freezeType = (map["freezeType"] as? Number)?.toInt() ?: 0
            val freezeFrom = (map["freezeFrom"] as? Number)?.toLong() ?: 0L
            val freezeTo = (map["freezeTo"] as? Number)?.toLong() ?: 0L
            val note = map["note"] as? String ?: ""
            val xorKey = (map["xorKey"] as? Number)?.toLong() ?: 0L
            val xorOffset = (map["xorOffset"] as? Number)?.toInt() ?: 0
            val encryptedAuto = map["encryptedAuto"] as? Boolean ?: false
            out.add(
                MemoryItem(
                    address = addr,
                    value = value,
                    previousValue = prev,
                    flags = flags,
                    name = name,
                    region = region,
                    freeze = freeze,
                    freezeType = freezeType,
                    freezeFrom = freezeFrom,
                    freezeTo = freezeTo,
                    note = note,
                    xorKey = xorKey,
                    xorOffset = xorOffset,
                    encryptedAuto = encryptedAuto
                )
            )
        }
        return out
    }
}

/**
 * 保存列表文本文件 (`.txt`) 编解码器：
 * 严格区分只读解析 (`parseFromText`) 与只写序列化 (`formatToText`)，
 * 兼容旧版 8 列格式并扩展支持 `freezeFrom|freezeTo|xorKey|xorOffset|encryptedAuto`。
 */
object SavedListFileCodec {

    fun formatToText(items: List<MemoryItem>): String {
        return items.joinToString("\n") { item ->
            val safeName = item.name.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')
            val safeNote = item.note.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')
            listOf(
                item.cachedAddressHex,
                item.value.toString(),
                item.flags.toString(),
                item.region,
                if (item.freeze) "1" else "0",
                item.freezeType.toString(),
                safeName,
                safeNote,
                item.freezeFrom.toString(),
                item.freezeTo.toString(),
                item.xorKey.toString(),
                item.xorOffset.toString(),
                if (item.encryptedAuto) "1" else "0"
            ).joinToString("|")
        }
    }

    fun parseFromText(content: String): List<MemoryItem> {
        if (content.isBlank()) return emptyList()
        return content.lineSequence()
            .mapNotNull { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                val parts = line.split('|')
                if (parts.size < 3) return@mapNotNull null
                val addr = parts[0].trim().removePrefix("0x").removePrefix("0X").toULongOrNull(16)?.toLong()
                    ?: return@mapNotNull null
                val value = parts[1].trim().toLongOrNull() ?: 0L
                val flags = parts[2].trim().toIntOrNull() ?: ValueType.DWORD
                val region = parts.getOrNull(3)?.trim()?.ifEmpty { "Ca" } ?: "Ca"
                val freeze = parts.getOrNull(4)?.trim() == "1"
                val freezeType = parts.getOrNull(5)?.trim()?.toIntOrNull() ?: 0
                val name = parts.getOrNull(6).orEmpty()
                val note = parts.getOrNull(7).orEmpty()
                val freezeFrom = parts.getOrNull(8)?.trim()?.toLongOrNull() ?: 0L
                val freezeTo = parts.getOrNull(9)?.trim()?.toLongOrNull() ?: 0L
                val xorKey = parts.getOrNull(10)?.trim()?.toLongOrNull() ?: 0L
                val xorOffset = parts.getOrNull(11)?.trim()?.toIntOrNull() ?: 0
                val encryptedAuto = parts.getOrNull(12)?.trim() == "1"
                MemoryItem(
                    address = addr,
                    value = value,
                    previousValue = value,
                    flags = flags,
                    name = name,
                    region = region,
                    freeze = freeze,
                    freezeType = freezeType,
                    freezeFrom = freezeFrom,
                    freezeTo = freezeTo,
                    note = note,
                    xorKey = xorKey,
                    xorOffset = xorOffset,
                    encryptedAuto = encryptedAuto
                )
            }
            .distinctBy { (it.address shl 8) xor (it.flags and 0x7F).toLong() }
            .toList()
    }
}

/**
 * 轻量 RFC-8259 JSON 编解码器（支持 Object, Array, String, Long/Double, Boolean, null）。
 */
internal object SimpleJson {

    fun stringify(value: Any?): String {
        val sb = StringBuilder(128)
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(value.toString())
            is String -> writeString(sb, value)
            is List<*> -> {
                sb.append('[')
                for (i in value.indices) {
                    if (i > 0) sb.append(',')
                    writeValue(sb, value[i])
                }
                sb.append(']')
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k?.toString() ?: "")
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u")
                    val hex = ch.code.toString(16).padStart(4, '0')
                    sb.append(hex)
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        return try {
            val parser = Parser(text)
            val result = parser.parseValue()
            parser.skipWhitespace()
            if (parser.isEof()) result else null
        } catch (_: Throwable) {
            null
        }
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun isEof(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= s.length) throw IllegalArgumentException("Unexpected EOF")
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char ${s[pos]}")
            }
        }

        private fun parseLiteral(expected: String, result: Any?): Any? {
            if (!s.startsWith(expected, pos)) throw IllegalArgumentException("Expected $expected")
            pos += expected.length
            return result
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (pos < s.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') throw IllegalArgumentException("Expected ':'")
                pos++
                val v = parseValue()
                map[key] = v
                skipWhitespace()
                if (pos >= s.length) throw IllegalArgumentException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
            throw IllegalArgumentException("Unterminated object")
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (pos < s.length) {
                list.add(parseValue())
                skipWhitespace()
                if (pos >= s.length) throw IllegalArgumentException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
            throw IllegalArgumentException("Unterminated array")
        }

        private fun parseString(): String {
            if (pos >= s.length || s[pos] != '"') throw IllegalArgumentException("Expected '\"'")
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val ch = s[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalArgumentException("Bad unicode escape")
                                val code = s.substring(pos, pos + 4).toInt(16)
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val token = s.substring(start, pos)
            return if (isFloat) token.toDouble() else token.toLong()
        }
    }
}

/**
 * 独立于 DataStore 的本地原子文件存储（用于保存列表与各类历史记录的大体积 JSON）。
 * 彻底避免高频设置写入时 DataStore Protobuf 反复序列化大字符串触发 `OutOfMemoryError`。
 */
object LocalBlobStore {

    private val locks = HashMap<String, Any>()

    private fun lockFor(name: String): Any = synchronized(locks) {
        locks.getOrPut(name) { Any() }
    }

    fun readText(baseDir: File?, name: String): String? {
        if (baseDir == null) return null
        return synchronized(lockFor(name)) {
            runCatching {
                val file = File(baseDir, "$name.json")
                if (file.exists() && file.isFile) file.readText(Charsets.UTF_8) else null
            }.getOrNull()
        }
    }

    fun writeTextAtomic(baseDir: File?, name: String, content: String): Boolean {
        if (baseDir == null) return false
        return synchronized(lockFor(name)) {
            runCatching {
                if (!baseDir.exists()) {
                    baseDir.mkdirs()
                }
                val target = File(baseDir, "$name.json")
                val tmp = File(baseDir, "$name.json.tmp")
                tmp.writeText(content, Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    target.writeText(content, Charsets.UTF_8)
                    tmp.delete()
                }
                true
            }.getOrDefault(false)
        }
    }
}

