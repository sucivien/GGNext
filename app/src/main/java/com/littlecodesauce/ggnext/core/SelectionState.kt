package com.littlecodesauce.ggnext.core

import com.littlecodesauce.ggnext.model.MemoryItem

/**
 * O(1) 批量选择状态模型（模块 4）：
 *
 * 使用 `(allSelected, toggledKeys)` 表达任意规模列表（100,000+ 候选）的选择状态：
 * - `allSelected == false`: `toggledKeys` 存储已勾选的条目 Key
 * - `allSelected == true`: `toggledKeys` 存储被反向取消勾选的条目 Key
 *
 * 全选、反选、清空、单项切换与已选计数全部为 O(1) 常数时间，彻底消除列表全量拷贝与 UI 卡顿。
 */
data class SelectionState(
    val allSelected: Boolean = false,
    val toggledKeys: Set<Long> = emptySet()
) {
    fun isSelected(key: Long): Boolean =
        if (allSelected) key !in toggledKeys else key in toggledKeys

    fun toggle(key: Long): SelectionState {
        val next = if (key in toggledKeys) toggledKeys - key else toggledKeys + key
        return copy(toggledKeys = next)
    }

    fun selectAll(): SelectionState = SelectionState(allSelected = true, toggledKeys = emptySet())

    fun clear(): SelectionState = SelectionState(allSelected = false, toggledKeys = emptySet())

    fun invert(): SelectionState = SelectionState(allSelected = !allSelected, toggledKeys = toggledKeys)

    fun selectedCount(totalSize: Int): Int {
        if (totalSize <= 0) return 0
        return if (allSelected) {
            (totalSize - toggledKeys.size).coerceIn(0, totalSize)
        } else {
            toggledKeys.size.coerceIn(0, totalSize)
        }
    }

    fun hasAnySelection(totalSize: Int): Boolean = selectedCount(totalSize) > 0

    fun <T> resolveSelected(items: List<T>, keySelector: (T) -> Long): List<T> {
        if (items.isEmpty()) return emptyList()
        if (!allSelected && toggledKeys.isEmpty()) return emptyList()
        if (allSelected && toggledKeys.isEmpty()) return items
        return items.filter { isSelected(keySelector(it)) }
    }

    fun prune(validKeys: Set<Long>): SelectionState {
        if (validKeys.isEmpty()) return clear()
        if (toggledKeys.isEmpty()) return this
        val retained = toggledKeys.filterTo(LinkedHashSet()) { it in validKeys }
        return if (retained.size == toggledKeys.size) this else copy(toggledKeys = retained)
    }

    companion object {
        val EMPTY = SelectionState()

        /** 搜索结果/保存列表复合键：区分同一地址不同数据类型 */
        fun itemKey(item: MemoryItem): Long =
            (item.address shl 8) xor (item.flags and 0x7F).toLong()

        /** 内存浏览页键：按行地址唯一标识 */
        fun memoryRowKey(item: MemoryItem): Long = item.address
    }
}
