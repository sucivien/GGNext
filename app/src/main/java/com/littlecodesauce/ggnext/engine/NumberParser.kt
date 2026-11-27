package com.littlecodesauce.ggnext.engine

import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.model.ValueType

/**
 * 查询解析器。支持 GG 的输入语法：
 *  - 普通数字：123 / -100 / -4.5 / 0x1F / 1e9
 *  - 十六进制：1234h、h41424344、0x10F
 *  - 文本：'abc'（UTF-8）、"abc"（UTF-16LE）、:abc、;abc
 *  - 范围：10~20、-50~50、100~~200（反向）
 *  - 联合：1;2;3 / 1;2;3:100（无序） / 1;2;3::64（有序）
 *  - 异或：100X7 / 1000X5A5Ah
 *  - 类型后缀：10D、10F、10W、10B、10Q、10E、10X
 */
object NumberParser {

    const val DEFAULT_GROUP_AREA = 512

    class ParseException(message: String) : Exception(AppStrings.tr(message))

    enum class Kind { NUMBER, TEXT, RANGE, GROUP }

    data class Query(
        val kind: Kind,
        val raw: String,
        val numbers: List<Long> = emptyList(),
        /** 每个数字的类型 flags */
        val types: List<Int> = emptyList(),
        /** 范围上界（RANGE 或 GROUP 各字段上界） */
        val to: List<Long> = emptyList(),
        /** 联合搜索每个字段的操作符：0=EXACT, 1=RANGE_INC(~), 2=RANGE_INV(~~), 3=WILDCARD(0~~0 / *) */
        val groupOps: List<Int> = emptyList(),
        val sign: Int = ValueType.SIGN_EQUAL,
        val xorKey: Long = 0L,
        /** GG 异或偏移搜索（如 25X4, 25X8, 25X-4，表示 [addr] ^ [addr + xorOffset] == target） */
        val xorOffset: Int = 0,
        val area: Int = DEFAULT_GROUP_AREA,
        val ordered: Boolean = false,
        val notRange: Boolean = false,
        val textBytes: ByteArray = ByteArray(0),
        val textUtf16: Boolean = false
    ) {
        val baseType: Int get() = types.firstOrNull() ?: ValueType.AUTO
    }

    private val TYPE_SUFFIX = mapOf(
        'b' to ValueType.BYTE, 'w' to ValueType.WORD, 'd' to ValueType.DWORD,
        'x' to ValueType.XOR, 'f' to ValueType.FLOAT, 'q' to ValueType.QWORD,
        'e' to ValueType.DOUBLE, 'a' to ValueType.AUTO
    )

    fun isText(raw: String): Boolean {
        val t = raw.trim()
        if (t.length <= 1) return false
        return t[0] == '\'' || t[0] == '"' || t[0] == ':' || t[0] == ';' ||
            ((t.startsWith("h ", true) || t.startsWith("Q ", false)) && t.length > 2)
    }

    /** 解析 GG 的 HEX (h XX YY) 或混合字节流 (Q XX 'utf8' "utf16le") 为原始字节数组 */
    private fun parseHexMixedBytes(input: String): ByteArray {
        val isMixedQ = input.startsWith("Q ", false)
        val body = input.substring(2).trim()
        val out = ArrayList<Byte>(body.length)
        var i = 0
        val n = body.length
        while (i < n) {
            val c = body[i]
            when {
                c.isWhitespace() -> i++
                isMixedQ && (c == '\'' || c == '"') -> {
                    val end = body.indexOf(c, i + 1)
                    if (end == -1) throw ParseException("引号未闭合")
                    val seg = body.substring(i + 1, end)
                    val enc = if (c == '"') seg.toByteArray(Charsets.UTF_16LE) else seg.toByteArray(Charsets.UTF_8)
                    for (b in enc) out.add(b)
                    i = end + 1
                }
                else -> {
                    var j = i
                    while (j < n && !body[j].isWhitespace() && (!isMixedQ || (body[j] != '\'' && body[j] != '"'))) j++
                    val tok = body.substring(i, j)
                    val b = tok.toIntOrNull(16)
                        ?: throw ParseException("无效的十六进制字节: $tok")
                    if (b !in 0..0xFF) throw ParseException("字节越界: $tok")
                    out.add(b.toByte())
                    i = j
                }
            }
        }
        if (out.isEmpty()) throw ParseException("字节流不能为空")
        return ByteArray(out.size) { out[it] }
    }

    /** 判断输入是否为联合搜索表达式（不含文本前缀） */
    fun isGroupExpression(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty() || isText(t) || AsmEngineManager.parseGgAsmPrefix(t) != null) return false
        val norm = t.replace('；', ';').replace('：', ':')
        return norm.contains(';') || norm.contains("::") || norm.count { it == ':' } >= 2
    }

    /** 提取联合搜索的 (ordered, area)，未指定距离时默认 512 */
    fun extractGroupMeta(raw: String): Pair<Boolean, Int> {
        val norm = raw.trim().replace('；', ';').replace('：', ':')
        if (norm.isEmpty() || isText(norm)) return false to DEFAULT_GROUP_AREA
        if (norm.contains("::")) {
            val idx = norm.lastIndexOf("::")
            val areaStr = norm.substring(idx + 2).trim()
            val area = runCatching { if (areaStr.isEmpty()) DEFAULT_GROUP_AREA else parseAreaSpan(areaStr) }
                .getOrDefault(DEFAULT_GROUP_AREA)
            return true to area
        }
        if (norm.contains(';')) {
            val idx = norm.lastIndexOf(':')
            if (idx >= 0) {
                val areaStr = norm.substring(idx + 1).trim()
                val area = runCatching { if (areaStr.isEmpty()) DEFAULT_GROUP_AREA else parseAreaSpan(areaStr) }
                    .getOrDefault(DEFAULT_GROUP_AREA)
                return false to area
            }
            return false to DEFAULT_GROUP_AREA
        }
        val parts = norm.split(':')
        if (parts.size >= 3) {
            val areaStr = parts.last().trim()
            val area = runCatching { if (areaStr.isEmpty()) DEFAULT_GROUP_AREA else parseAreaSpan(areaStr) }
                .getOrDefault(DEFAULT_GROUP_AREA)
            return false to area
        }
        return false to DEFAULT_GROUP_AREA
    }

    /**
     * 规范化联合搜索表达式，显式带上冒号距离（如 1;2:512 或 1;2::512）。
     */
    fun normalizeGroupExpression(raw: String, ordered: Boolean? = null, area: Int? = null): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || isText(trimmed)) return trimmed
        val norm = trimmed.replace('；', ';').replace('：', ':').replace('～', '~')
        val (curOrdered, curArea) = extractGroupMeta(norm)
        val targetOrdered = ordered ?: curOrdered
        val targetArea = (area ?: curArea).coerceIn(2, 65536)

        val bodyPart = when {
            norm.contains("::") -> norm.substring(0, norm.lastIndexOf("::")).trimEnd(':', ';', ' ')
            norm.contains(';') -> {
                val idx = norm.lastIndexOf(':')
                if (idx >= 0) norm.substring(0, idx).trimEnd(':', ';', ' ')
                else norm.trimEnd(':', ';', ' ')
            }
            norm.count { it == ':' } >= 2 -> {
                val parts = norm.split(':').map { it.trim() }
                parts.dropLast(1).filter { it.isNotEmpty() }.joinToString(";")
            }
            norm.contains(':') -> {
                norm.split(':').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(";")
            }
            else -> norm
        }
        if (bodyPart.isEmpty()) return trimmed
        val sep = if (targetOrdered) "::" else ":"
        return "$bodyPart$sep$targetArea"
    }

    /** 解析一条完整输入（自动识别联合/范围/文本/异或/反向十六进制） */
    fun parse(raw: String, defaultType: Int, sign: Int = ValueType.SIGN_EQUAL): Query {
        var input = raw.trim()
        if (input.isEmpty()) throw ParseException("请输入要搜索的值")

        // 原版 GG 汇编操作码搜索（支持 ~A8 / ~A / ~T / ~X86 / ~X64 前缀，借助 Keystone 编译为机器码字节流搜索）
        val ggAsm = AsmEngineManager.parseGgAsmPrefix(input)
        if (ggAsm != null) {
            val (asmArch, asmBody) = ggAsm
            if (asmBody.isBlank()) {
                throw ParseException("请输入汇编指令，例如 ${asmArch.ggPrefix} MOV X0, #1")
            }
            val asmRes = AsmEngineManager.assemble(asmArch, 0L, asmBody)
            val bytes = asmRes.getOrElse { err ->
                throw ParseException(err.message ?: "汇编操作码解析失败")
            }
            if (bytes.isEmpty()) throw ParseException("未生成有效机器码")
            return Query(
                kind = Kind.TEXT,
                raw = input,
                numbers = listOf(bytes.size.toLong()),
                types = listOf(if (asmArch.fixedStep == 4) ValueType.DWORD else ValueType.BYTE),
                sign = sign,
                area = bytes.size,
                ordered = true,
                textBytes = bytes,
                textUtf16 = false
            )
        }

        if (isText(input)) {
            if (input.startsWith("h ", true) || input.startsWith("Q ", false)) {
                val bytes = parseHexMixedBytes(input)
                return Query(
                    kind = Kind.TEXT,
                    raw = input,
                    numbers = listOf(bytes.size.toLong()),
                    types = listOf(ValueType.BYTE),
                    sign = sign,
                    area = bytes.size,
                    ordered = true,
                    textBytes = bytes,
                    textUtf16 = false
                )
            }
            val quote = input[0]
            val utf16 = quote == '"' || quote == ';'
            var body = input.substring(1)
            if ((quote == '\'' || quote == '"') && body.endsWith(quote) && body.isNotEmpty()) {
                body = body.dropLast(1)
            }
            val bytes = if (utf16) body.toByteArray(Charsets.UTF_16LE) else body.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) throw ParseException("文本不能为空")
            return Query(
                kind = Kind.TEXT,
                raw = input,
                numbers = listOf(bytes.size.toLong()),
                types = listOf(if (utf16) ValueType.WORD else ValueType.BYTE),
                sign = sign,
                area = bytes.size,
                ordered = true,
                textBytes = bytes,
                textUtf16 = utf16
            )
        }

        // 统一将中文全角分号/冒号/波浪号归一化为半角符号，兼容中文输入法（如 100：200：100、100：200：：100）
        input = input
            .replace('；', ';')
            .replace('：', ':')
            .replace('～', '~')

        var effSign = sign
        when {
            input.startsWith("!=") || input.startsWith("≠") -> {
                effSign = ValueType.SIGN_NOT_EQUAL
                input = input.removePrefix("!=").removePrefix("≠").trim()
            }
            input.startsWith(">=") || input.startsWith("≥") -> {
                effSign = ValueType.SIGN_GREATER_EQ
                input = input.removePrefix(">=").removePrefix("≥").trim()
            }
            input.startsWith("<=") || input.startsWith("≤") -> {
                effSign = ValueType.SIGN_LESS_EQ
                input = input.removePrefix("<=").removePrefix("≤").trim()
            }
            input.startsWith(">") -> {
                effSign = ValueType.SIGN_GREATER_EQ
                input = input.substring(1).trim()
            }
            input.startsWith("<") -> {
                effSign = ValueType.SIGN_LESS_EQ
                input = input.substring(1).trim()
            }
            input.startsWith("=") -> {
                effSign = ValueType.SIGN_EQUAL
                input = input.substring(1).trim()
            }
        }

        if (input.contains(';') || input.contains(':')) return parseGroup(input, defaultType, effSign)

        // 先提取外层 X 异或表达式（支持 25X4, 25X-4, 1000X5A5Ah, 以及 40~50X4）
        var body = input
        var xorKey = 0L
        var xorOffset = 0
        val upper = body.uppercase()
        if (!upper.startsWith("0X") && !upper.endsWith("X")) {
            val xIdx = upper.lastIndexOf('X')
            if (xIdx > 0 && body[xIdx - 1] != '0') {
                val keyText = body.substring(xIdx + 1).trim()
                if (keyText.isNotEmpty()) {
                    val isExplicitHex = keyText.startsWith("0x", true) ||
                        keyText.startsWith("h", true) ||
                        keyText.endsWith("h", true) ||
                        keyText.endsWith("r", true)
                    val decOffset = if (!isExplicitHex) keyText.toIntOrNull() else null
                    if (decOffset != null && decOffset != 0 && kotlin.math.abs(decOffset) <= 4096 && (kotlin.math.abs(decOffset) % 4 == 0)) {
                        xorOffset = decOffset
                    } else {
                        xorKey = parseNumber(keyText, ValueType.DWORD).first
                    }
                    body = body.substring(0, xIdx).trim()
                }
            }
        }

        if (body.contains('~')) {
            val rq = parseRange(body, defaultType, effSign)
            return rq.copy(raw = input, xorKey = xorKey, xorOffset = xorOffset)
        }

        val parsed = parseNumber(body, defaultType)
        return Query(
            kind = Kind.NUMBER,
            raw = input,
            numbers = listOf(parsed.first),
            types = listOf(parsed.second),
            sign = effSign,
            xorKey = xorKey,
            xorOffset = xorOffset
        )
    }

    /** 单个数字解析，返回 (valueBits, typeFlags) */
    fun parseNumber(text: String, defaultType: Int): Pair<Long, Int> {
        var s = text.trim().replace("_", "").replace(",", "")
        if (s.isEmpty()) throw ParseException("数值为空")

        // 先识别末尾是否有显式类型后缀（如 12345678rD 或 1234hW）
        var explicitSuffixType: Int? = null
        if (s.length > 2) {
            val lastChar = s.last().lowercaseChar()
            val prevChar = s[s.length - 2].lowercaseChar()
            if (TYPE_SUFFIX.containsKey(lastChar) && (prevChar == 'h' || prevChar == 'r')) {
                explicitSuffixType = TYPE_SUFFIX.getValue(lastChar)
                s = s.dropLast(1)
            }
        }

        val resolvedHexType = explicitSuffixType ?: if (defaultType == ValueType.AUTO) ValueType.DWORD else defaultType

        // 1. 识别反向十六进制 (Big-Endian / Reverse HEX: 12345678r 或 r12345678)
        if (s.length > 1 && (s.last() == 'r' || s.last() == 'R')) {
            val hex = s.dropLast(1).removePrefix("0x").removePrefix("0X")
            if (hex.isNotEmpty() && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                val padded = if (hex.length % 2 != 0) "0$hex" else hex
                val reversedHex = padded.chunked(2).reversed().joinToString("")
                val v = reversedHex.toULongOrNull(16)?.toLong() ?: throw ParseException("无效的反向十六进制: $s")
                return v to (resolvedHexType and 0x7F)
            }
        }

        // 2. 优先识别显式十六进制前缀/后缀，防止 0x10F / 123Ah 末尾的 A/B/D/E/F 被误判为类型后缀
        if (s.startsWith("0x", true)) {
            val hex = s.substring(2)
            val v = hex.toULongOrNull(16)?.toLong() ?: hex.toLongOrNull(16) ?: throw ParseException("无效的十六进制: $s")
            return v to (resolvedHexType and 0x7F)
        }
        if (s.length > 1 && (s[0] == 'h' || s[0] == 'H') && s.substring(1).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            val hex = s.substring(1)
            val v = hex.toULongOrNull(16)?.toLong() ?: hex.toLongOrNull(16) ?: throw ParseException("无效的十六进制: $hex")
            return v to (resolvedHexType and 0x7F)
        }
        if (s.length > 1 && (s.last() == 'h' || s.last() == 'H')) {
            val hex = s.dropLast(1)
            val v = hex.toULongOrNull(16)?.toLong() ?: hex.toLongOrNull(16) ?: throw ParseException("无效的十六进制: $hex")
            return v to (resolvedHexType and 0x7F)
        }

        // 3. 识别类型后缀 (B/W/D/X/F/Q/E/A)，仅当去掉后缀后是纯十进制整数或浮点数时才剥离后缀
        var type = explicitSuffixType ?: defaultType
        val last = s.last().lowercaseChar()
        if (s.length > 1 && TYPE_SUFFIX.containsKey(last) && last !in "0123456789") {
            val suffixType = TYPE_SUFFIX.getValue(last)
            val candidate = s.dropLast(1).trim()
            if (isDecimalOrFloat(candidate)) {
                type = if (defaultType == ValueType.AUTO) suffixType else (defaultType and suffixType).let { if (it == 0) suffixType else it }
                s = candidate
            }
        }

        val isAuto = (type and 0x7F) == ValueType.AUTO
        val targetType = if (isAuto) {
            if (s.contains('.')) ValueType.FLOAT else ValueType.AUTO
        } else type

        return when (targetType and 0x7F) {
            ValueType.FLOAT -> {
                val f = s.toFloatOrNull() ?: throw ParseException("无效的浮点数: $s")
                (f.toRawBits().toLong() and 0xFFFFFFFFL) to ValueType.FLOAT
            }
            ValueType.DOUBLE -> {
                val d = s.toDoubleOrNull() ?: throw ParseException("无效的双精度数: $s")
                d.toRawBits() to ValueType.DOUBLE
            }
            ValueType.QWORD, ValueType.AUTO -> {
                val v = s.toLongOrNull() ?: s.toULongOrNull()?.toLong()
                    ?: s.toULongOrNull(16)?.toLong()
                    ?: throw ParseException("无效的数值: $s")
                v to (targetType and 0x7F)
            }
            else -> {
                val v = s.toLongOrNull() ?: s.toULongOrNull()?.toLong()
                    ?: s.toULongOrNull(16)?.toLong()
                    ?: throw ParseException("无效的数值: $s")
                validate(v, targetType)
                v to (targetType and 0x7F)
            }
        }
    }

    private fun isDecimalOrFloat(s: String): Boolean {
        if (s.isEmpty()) return false
        return s.toLongOrNull() != null || s.toULongOrNull() != null || s.toDoubleOrNull() != null
    }

    private fun validate(v: Long, type: Int) {
        when (type and 0x7F) {
            ValueType.BYTE -> if (v < -128L || v > 255L) {
                throw ParseException("Byte 取值范围为 -128 ~ 255，当前: $v")
            }
            ValueType.WORD -> if (v < -32768L || v > 65535L) {
                throw ParseException("Word 取值范围为 -32,768 ~ 65,535，当前: $v")
            }
            ValueType.DWORD, ValueType.XOR -> if (v < -2147483648L || v > 4294967295L) {
                throw ParseException("Dword 取值范围为 -2,147,483,648 ~ 4,294,967,295，当前: $v")
            }
        }
    }

    private fun parseRange(input: String, defaultType: Int, sign: Int): Query {
        val inverted = input.contains("~~")
        val parts = input.split(if (inverted) "~~" else "~")
        if (parts.size != 2) throw ParseException("范围只能有两个边界值")
        val a = parseNumber(parts[0], defaultType)
        val b = parseNumber(parts[1], if (a.second == ValueType.AUTO) defaultType else a.second)
        val type = if ((a.second and b.second) != 0) (a.second and b.second) else a.second
        return Query(
            kind = Kind.RANGE,
            raw = input,
            numbers = listOf(a.first),
            to = listOf(b.first),
            types = listOf(type),
            sign = sign,
            notRange = inverted
        )
    }

    private fun parseAreaSpan(areaStr: String): Int {
        val clean = areaStr.trim().replace("_", "").replace(",", "")
        if (clean.isEmpty()) return 512
        val parsed = when {
            clean.startsWith("0x", true) -> clean.substring(2).toIntOrNull(16)
            clean.endsWith("h", true) -> clean.dropLast(1).toIntOrNull(16)
            clean.startsWith("h", true) && clean.length > 1 -> clean.substring(1).toIntOrNull(16)
            else -> clean.toIntOrNull()
        } ?: throw ParseException("区域大小无效: $areaStr")
        if (parsed < 2 || parsed > 65536) throw ParseException("区域大小必须在 2 - 65,536 之间")
        return parsed
    }

    private fun parseGroup(input: String, defaultType: Int, sign: Int): Query {
        val ordered = input.contains("::")
        var area = 512
        val items: List<String>

        if (ordered) {
            // 有序联合搜索：例如 "100;200::100"、"100:200::100"、"100:200:300::100"、"100;200::"
            val idx = input.lastIndexOf("::")
            val listPart = input.substring(0, idx)
            val areaStr = input.substring(idx + 2).trim()
            if (areaStr.isNotEmpty()) {
                area = parseAreaSpan(areaStr)
            }
            items = listPart.split(';', ':').map { it.trim() }.filter { it.isNotEmpty() }
        } else if (input.contains(';')) {
            // 分号分隔无序联合搜索：例如 "100;200"、"100;200:100"
            var listPart = input
            if (input.contains(':')) {
                val idx = input.lastIndexOf(':')
                listPart = input.substring(0, idx)
                val areaStr = input.substring(idx + 1).trim()
                if (areaStr.isNotEmpty()) {
                    area = parseAreaSpan(areaStr)
                }
            }
            items = listPart.split(';', ':').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            // 纯冒号分隔无序联合搜索：例如 "100:200:100"（数据为 100 和 200，偏移距离 100）或 "100:200:300:100"
            val rawParts = input.split(':').map { it.trim() }
            if (rawParts.size >= 3) {
                val lastPart = rawParts.last()
                if (lastPart.isNotEmpty()) {
                    area = parseAreaSpan(lastPart)
                }
                items = rawParts.dropLast(1).filter { it.isNotEmpty() }
            } else {
                items = rawParts.filter { it.isNotEmpty() }
            }
        }

        if (items.size < 2) throw ParseException("联合搜索至少需要两个数值")
        if (items.size > 64) throw ParseException("联合搜索最多 64 个数值")

        val values = ArrayList<Long>(items.size)
        val maxValues = ArrayList<Long>(items.size)
        val types = ArrayList<Int>(items.size)
        val ops = ArrayList<Int>(items.size)

        val groupDefaultType = if ((defaultType and 0x7F) == ValueType.AUTO) ValueType.DWORD else defaultType
        items.forEach { item ->
            val clean = item.trim()
            when {
                clean == "*" || clean == "?" || clean.startsWith("0~~0", true) -> {
                    // 通配任意值 (如 0~~0, 0~~0D, *, ?)
                    val suffixChar = clean.last().lowercaseChar()
                    val wcType = TYPE_SUFFIX[suffixChar] ?: groupDefaultType
                    values.add(0L)
                    maxValues.add(0L)
                    types.add(wcType and 0x7F)
                    ops.add(3) // 3 = WILDCARD
                }
                clean.contains('~') -> {
                    val r = parseRange(clean, groupDefaultType, ValueType.SIGN_EQUAL)
                    val vMin = r.numbers.first()
                    val vMax = r.to.firstOrNull() ?: vMin
                    val t = r.types.first()
                    if (r.notRange && vMin == 0L && vMax == 0L) {
                        values.add(0L)
                        maxValues.add(0L)
                        types.add(t)
                        ops.add(3) // 0~~0 -> WILDCARD
                    } else {
                        values.add(vMin)
                        maxValues.add(vMax)
                        types.add(t)
                        ops.add(if (r.notRange) 2 else 1) // 2 = RANGE_INV (~~), 1 = RANGE_INC (~)
                    }
                }
                else -> {
                    val p = parseNumber(clean, groupDefaultType)
                    values.add(p.first)
                    maxValues.add(p.first)
                    types.add(p.second)
                    ops.add(0) // 0 = EXACT
                }
            }
        }
        return Query(
            kind = Kind.GROUP,
            raw = input,
            numbers = values,
            to = maxValues,
            types = types,
            groupOps = ops,
            sign = sign,
            area = area,
            ordered = ordered
        )
    }

    /** 输入是否是合法十六进制串（用于 HEX 按钮转换） */
    fun isHex(text: String): Boolean = text.isNotEmpty() && text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    fun textToHex(text: String, utf16: Boolean = false): String {
        val bytes = if (utf16) text.toByteArray(Charsets.UTF_16LE) else text.toByteArray(Charsets.UTF_8)
        return bytes.joinToString(" ") { String.format("%02X", it) }
    }
}
