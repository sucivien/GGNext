package com.littlecodesauce.ggnext.ui.model

/**
 * 完整内存区域目录（对齐原版 GG 的 15 个区域）。
 */
data class RegionType(
    val code: String,
    val name: String,
    val slow: Boolean = false,
    val danger: Boolean = false
) {
    val display: String get() = when {
        slow && danger -> "$code: $name (慢/危险)"
        danger -> "$code: $name (危险)"
        slow -> "$code: $name (慢)"
        else -> "$code: $name"
    }
}

object RegionCatalog {
    val ALL = listOf(
        RegionType("Ch", "C++ heap"),
        RegionType("Jh", "Java heap"),
        RegionType("Ca", "C++ alloc"),
        RegionType("Cd", "C++ .data"),
        RegionType("Cb", "C++ .bss"),
        RegionType("Ps", "PPSSPP"),
        RegionType("A", "Anonymous"),
        RegionType("J", "Java"),
        RegionType("S", "Stack"),
        RegionType("As", "Ashmem"),
        RegionType("V", "Video"),
        RegionType("O", "Other", slow = true),
        RegionType("B", "Bad", danger = true),
        RegionType("Xa", "Code app", danger = true),
        RegionType("Xs", "Code system", danger = true)
    )

    fun byCode(code: String): RegionType? = ALL.firstOrNull { it.code.equals(code, ignoreCase = true) }

    val DEFAULT_SELECTED = setOf("Ch", "Jh", "Ca", "Cd", "Cb", "Ps", "A")

    fun name(code: String): String = byCode(code)?.name ?: code
}
