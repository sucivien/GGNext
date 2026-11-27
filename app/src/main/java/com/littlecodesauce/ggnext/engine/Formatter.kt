package com.littlecodesauce.ggnext.engine

import com.littlecodesauce.ggnext.model.ValueType
import java.util.Locale
import kotlin.math.abs

/**
 * 数值显示格式，复刻 GG 的显示掩码位：
 * bit0 h(hex) bit1 r(rhex) bit2 S(string) bit3 J(java) bit4 A(arm) bit5 T(thumb)
 * bit6 A8(arm64) bit7 D bit8 F bit9 E bit10 W bit11 B bit12 Q bit13 X
 */
object Formatter {

    const val F_HEX = 1 shl 0
    const val F_RHEX = 1 shl 1
    const val F_STRING = 1 shl 2
    const val F_JAVA = 1 shl 3
    const val F_ARM = 1 shl 4
    const val F_THUMB = 1 shl 5
    const val F_ARM8 = 1 shl 6
    const val F_DWORD = 1 shl 7
    const val F_FLOAT = 1 shl 8
    const val F_DOUBLE = 1 shl 9
    const val F_WORD = 1 shl 10
    const val F_BYTE = 1 shl 11
    const val F_QWORD = 1 shl 12
    const val F_XOR = 1 shl 13
    const val F_X86 = 1 shl 14
    const val F_X64 = 1 shl 15

    const val DEFAULT_MASK = F_HEX or F_DWORD or F_FLOAT
    /** 仅包含辅助格式位（搜索列表与保存列表专用，不包含多数据类型 D/F/E/W/B/Q/X） */
    const val AUX_FORMAT_MASK = F_HEX or F_RHEX or F_STRING or F_JAVA or F_ARM or F_THUMB or F_ARM8 or F_X86 or F_X64

    data class FormatItem(val bit: Int, val short: String, val label: String)

    val items = listOf(
        FormatItem(F_HEX, "h", "HEX"),
        FormatItem(F_RHEX, "r", "HEX 逆序"),
        FormatItem(F_STRING, "S", "字符串"),
        FormatItem(F_JAVA, "J", "Java 字符串"),
        FormatItem(F_ARM8, "A8", "ARM64 汇编 (A8)"),
        FormatItem(F_ARM, "A", "ARM 汇编 (A)"),
        FormatItem(F_THUMB, "T", "Thumb 汇编 (T)"),
        FormatItem(F_X64, "x64", "x86_64 汇编"),
        FormatItem(F_X86, "x86", "x86 汇编"),
        FormatItem(F_DWORD, "D", "Dword"),
        FormatItem(F_FLOAT, "F", "Float"),
        FormatItem(F_DOUBLE, "E", "Double"),
        FormatItem(F_WORD, "W", "Word"),
        FormatItem(F_BYTE, "B", "Byte"),
        FormatItem(F_QWORD, "Q", "Qword"),
        FormatItem(F_XOR, "X", "Xor")
    )

    /** 原版 GG 搜索/保存页可用的辅助显示格式列表（不含多数据类型） */
    val auxItems = listOf(
        FormatItem(F_HEX, "h", "h: 十六进制格式 (little-endian)"),
        FormatItem(F_RHEX, "r", "r: 反向十六进制格式 (big-endian)"),
        FormatItem(F_STRING, "S", "S: 字符串表达式"),
        FormatItem(F_JAVA, "J", "J: UTF-16LE"),
        FormatItem(F_ARM, "A", "A: ARM (x32) 操作码"),
        FormatItem(F_THUMB, "T", "T: Thumb 操作码"),
        FormatItem(F_ARM8, "A8", "A8: ARM (x64) 操作码")
    )

    fun activeAsmArch(mask: Int): com.littlecodesauce.ggnext.model.AsmArch? = when {
        mask and F_ARM8 != 0 -> com.littlecodesauce.ggnext.model.AsmArch.ARM64
        mask and F_ARM != 0 -> com.littlecodesauce.ggnext.model.AsmArch.ARM
        mask and F_THUMB != 0 -> com.littlecodesauce.ggnext.model.AsmArch.THUMB
        mask and F_X64 != 0 -> com.littlecodesauce.ggnext.model.AsmArch.X86_64
        mask and F_X86 != 0 -> com.littlecodesauce.ggnext.model.AsmArch.X86
        else -> null
    }

    fun maskSummary(mask: Int): String =
        items.filter { mask and it.bit != 0 }.joinToString(",") { it.short }.ifEmpty { "-" }

    /** 十六进制，宽度随类型变化 */
    fun hex(value: Long, size: Int): String {
        val digits = (size * 2).coerceIn(2, 16)
        val mask = if (digits >= 16) -1L else (1L shl (digits * 4)) - 1
        return java.lang.Long.toHexString(value and mask).uppercase().padStart(digits, '0')
    }

    fun signed(value: Long, size: Int): Long = when (size) {
        1 -> value.toByte().toLong()
        2 -> value.toShort().toLong()
        4 -> value.toInt().toLong()
        else -> value
    }

    /** 带千位分隔，与原版一致：负数带 -，正数原样 */
    fun decimal(value: Long, size: Int): String {
        val v = signed(value, size)
        return String.format(Locale.US, "%,d", v)
    }

    fun float(value: Long): Float = Float.fromBits(value.toInt())

    fun double(value: Long): Double = Double.fromBits(value)

    fun floatText(value: Long): String {
        val f = float(value)
        return when {
            f.isNaN() -> "NaN"
            f.isInfinite() -> if (f > 0) "∞" else "-∞"
            f == 0f -> "0.0"
            abs(f) < 0.0001f || abs(f) >= 1e7f -> String.format(Locale.US, "%.6e", f)
            else -> String.format(Locale.US, "%.6f", f).trimEnd('0').trimEnd('.')
        }
    }

    fun doubleText(value: Long): String {
        val d = double(value)
        return when {
            d.isNaN() -> "NaN"
            d.isInfinite() -> if (d > 0) "∞" else "-∞"
            d == 0.0 -> "0.0"
            abs(d) < 0.0001 || abs(d) >= 1e12 -> String.format(Locale.US, "%.9e", d)
            else -> String.format(Locale.US, "%.9f", d).trimEnd('0').trimEnd('.')
        }
    }

    /** 渲染缓存（线程安全）：避免 Dispatchers.Default 与 UI 主线程并发访问 LinkedHashMap(accessOrder=true) 引发异常 */
    private val renderCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Long, String>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > 512
        }
    )

    /** 按当前显示掩码渲染一个地址条目 */
    fun render(item: com.littlecodesauce.ggnext.model.MemoryItem, mask: Int): String {
        val addrPart = if (mask and F_XOR != 0) item.address else 0L
        val key = ((item.value * 31 + mask) * 31 + item.flags) * 31 + addrPart
        synchronized(renderCache) {
            renderCache[key]?.let { return it }
        }
        return renderUncached(item, mask).also {
            synchronized(renderCache) {
                renderCache[key] = it
            }
        }
    }

    private fun renderUncached(item: com.littlecodesauce.ggnext.model.MemoryItem, mask: Int): String {
        val size = item.size
        val sb = StringBuilder()
        if (mask and F_HEX != 0) {
            sb.append(hex(item.value, size)).append("h; ")
        }
        if (mask and F_RHEX != 0) {
            sb.append(reverseHex(item.value, size)).append("r; ")
        }
        if (mask and F_STRING != 0) {
            val text = bytesToAscii(item.value, size)
            if (text.isNotEmpty()) sb.append('\'').append(text).append("'; ")
        }
        if (mask and F_JAVA != 0) {
            val text16 = bytesToUtf16(item.value, size)
            if (text16.isNotEmpty()) sb.append('"').append(text16).append("\"; ")
        }
        if (mask and F_DWORD != 0) {
            sb.append(decimal(item.value, 4)).append("D; ")
        }
        if (mask and F_FLOAT != 0) {
            sb.append(floatText(item.value)).append("F; ")
        }
        if (mask and F_DOUBLE != 0) {
            sb.append(doubleText(item.value)).append("E; ")
        }
        if (mask and F_WORD != 0) {
            sb.append(decimal(item.value, 2)).append("W; ")
        }
        if (mask and F_BYTE != 0) {
            sb.append(decimal(item.value, 1)).append("B; ")
        }
        if (mask and F_QWORD != 0) {
            sb.append(decimal(item.value, 8)).append("Q; ")
        }
        if (mask and F_XOR != 0) {
            val xorDecoded = (item.value xor (item.address and 0xFFFFFFFFL))
            sb.append(decimal(xorDecoded, 4)).append("X; ")
        }
        return sb.toString().trim().ifEmpty { "--" }
    }

    /** 主值（一行显示，用于紧凑列表） */
    fun primary(item: com.littlecodesauce.ggnext.model.MemoryItem, mask: Int): String {
        return when {
            mask and F_DWORD != 0 && item.flags and 0x7F == ValueType.DWORD -> decimal(item.value, 4) + "D"
            mask and F_FLOAT != 0 && item.flags and 0x7F == ValueType.FLOAT -> floatText(item.value) + "F"
            mask and F_DOUBLE != 0 && item.flags and 0x7F == ValueType.DOUBLE -> doubleText(item.value) + "E"
            mask and F_WORD != 0 && item.flags and 0x7F == ValueType.WORD -> decimal(item.value, 2) + "W"
            mask and F_BYTE != 0 && item.flags and 0x7F == ValueType.BYTE -> decimal(item.value, 1) + "B"
            mask and F_QWORD != 0 && item.flags and 0x7F == ValueType.QWORD -> decimal(item.value, 8) + "Q"
            mask and F_XOR != 0 && item.flags and 0x7F == ValueType.XOR -> decimal(item.value xor (item.address and 0xFFFFFFFFL), 4) + "X"
            mask and F_HEX != 0 -> hex(item.value, item.size) + "h"
            else -> decimal(item.value, item.size)
        }
    }

    /** 反向十六进制（小端序字节翻转，供 OffsetSheet 与 F_RHEX 调用） */
    fun reverseHex(value: Long, size: Int): String {
        return hex(value, size).chunked(2).reversed().joinToString("")
    }

    fun bytesToAscii(value: Long, size: Int): String {
        val sb = StringBuilder()
        for (i in 0 until size.coerceIn(1, 8)) {
            val b = (value ushr (i * 8)) and 0xFF
            if (b in 32..126) sb.append(b.toInt().toChar()) else sb.append('.')
        }
        return sb.toString()
    }

    /** UTF-16LE 双字节解码（供 OffsetSheet 与 F_JAVA 调用） */
    fun bytesToUtf16(value: Long, size: Int): String {
        val sb = StringBuilder()
        val pairs = (size.coerceIn(2, 8)) / 2
        for (i in 0 until pairs) {
            val ch = ((value ushr (i * 16)) and 0xFFFF).toInt()
            if (ch in 32..126 || ch in 0x4E00..0x9FFF) {
                sb.append(ch.toChar())
            } else {
                sb.append('.')
            }
        }
        return sb.toString()
    }
}
