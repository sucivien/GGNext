package com.littlecodesauce.ggnext.model

/**
 * 数据类型位掩码，与原版 GG 的 flags 语义及数值范围（ParserNumbers.getRanges）严格保持一致。
 * 1=Byte 2=Word 4=Dword 8=Xor 16=Float 32=Qword 64=Double
 *
 * 原版 GG 整数类型同时支持有符号负数下界与无符号正数上界：
 * - Byte:  -128 ~ 255
 * - Word:  -32,768 ~ 65,535
 * - Dword: -2,147,483,648 ~ 4,294,967,295
 * - Xor:   -2,147,483,648 ~ 4,294,967,295
 * - Qword: -9,223,372,036,854,775,808 ~ 18,446,744,073,709,551,615
 */
object ValueType {
    const val NONE = 0
    const val BYTE = 1
    const val WORD = 2
    const val DWORD = 4
    const val XOR = 8
    const val FLOAT = 16
    const val QWORD = 32
    const val DOUBLE = 64
    const val AUTO = 127

    /** 搜索比较方式 */
    const val SIGN_EQUAL = 0x20000000
    const val SIGN_NOT_EQUAL = 0x10000000
    const val SIGN_GREATER_EQ = 0x04000000
    const val SIGN_LESS_EQ = 0x08000000
    const val SIGN_MASK = 0x3C000000

    /** 附加标志 */
    const val FLAG_MODE_HACKING = 0x02000000
    const val FLAG_RANGE = 0x08000000
    const val FLAG_ORDERED = 0x400000
    const val FLAG_FUZZY = Int.MIN_VALUE

    val order = intArrayOf(BYTE, WORD, DWORD, FLOAT, DOUBLE, QWORD, XOR)

    fun sizeOf(flags: Int): Int = when (flags and 0x7F) {
        BYTE -> 1
        WORD -> 2
        DWORD, FLOAT, XOR -> 4
        QWORD, DOUBLE -> 8
        else -> 4
    }

    fun shortName(flags: Int): String = when (flags and 0x7F) {
        BYTE -> "B"
        WORD -> "W"
        DWORD -> "D"
        XOR -> "X"
        FLOAT -> "F"
        QWORD -> "Q"
        DOUBLE -> "E"
        AUTO -> "A"
        else -> "?"
    }

    fun longName(flags: Int): String = when (flags and 0x7F) {
        BYTE -> "Byte"
        WORD -> "Word"
        DWORD -> "Dword"
        XOR -> "Xor"
        FLOAT -> "Float"
        QWORD -> "Qword"
        DOUBLE -> "Double"
        AUTO -> "Auto"
        else -> "?"
    }

    fun signName(sign: Int): String = when (sign and SIGN_MASK) {
        SIGN_NOT_EQUAL -> "≠"
        SIGN_GREATER_EQ -> "≥"
        SIGN_LESS_EQ -> "≤"
        else -> "="
    }

    /**
     * Bug 3 修复：搜索界面与修改界面共用同一套取值范围文案，
     * 所有整数类型均完整包含有符号负数下界与无符号正数上界（与原版 GG AddressItem.getLimits 一致）。
     */
    fun rangeText(flags: Int): String = when (flags and 0x7F) {
        BYTE -> "Byte(-128 - 255)"
        WORD -> "Word(-32,768 - 65,535)"
        DWORD -> "Dword(-2,147,483,648 - 4,294,967,295)"
        XOR -> "Xor(-2,147,483,648 - 4,294,967,295)"
        FLOAT -> "Float(-3.4e38 - 3.4e38)"
        QWORD -> "Qword(-9,223,372,036,854,775,808 - 18,446,744,073,709,551,615)"
        DOUBLE -> "Double(-1.8e308 - 1.8e308)"
        AUTO -> "Auto(-1.8e308 - 1.8e308)"
        else -> "Dword(-2,147,483,648 - 4,294,967,295)"
    }

    fun describe(flags: Int): String = when (flags and 0x7F) {
        BYTE -> "B: Byte (-128 - 255)"
        WORD -> "W: Word (-32,768 - 65,535)"
        DWORD -> "D: Dword (-2,147,483,648 - 4,294,967,295)"
        XOR -> "X: Xor (-2,147,483,648 - 4,294,967,295)"
        FLOAT -> "F: Float (-3.4e38 - 3.4e38)"
        QWORD -> "Q: Qword (-9,223,372,036,854,775,808 - 18,446,744,073,709,551,615)"
        DOUBLE -> "E: Double (-1.8e308 - 1.8e308)"
        AUTO -> "A: Auto (-1.8e308 - 1.8e308)"
        else -> "?"
    }

    fun rangeOf(flags: Int): Pair<Long, Long> = when (flags and 0x7F) {
        BYTE -> -128L to 255L
        WORD -> -32768L to 65535L
        DWORD -> -2147483648L to 4294967295L
        XOR -> -2147483648L to 4294967295L
        QWORD -> Long.MIN_VALUE to Long.MAX_VALUE
        else -> Long.MIN_VALUE to Long.MAX_VALUE
    }
}
