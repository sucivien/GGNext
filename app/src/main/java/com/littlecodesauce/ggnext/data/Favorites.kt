package com.littlecodesauce.ggnext.data

/**
 * 输入历史管理（模块 6）：
 * - 严格遵循 `history_limit`：当 `limit == 0` 时不记录新输入历史；
 * - 超出上限时仅裁剪末尾未置顶项，置顶项永不被自动淘汰；
 * - 支持与 `HistoryPersistence` 双向 JSON 同步。
 */
class SearchHistory(initialLimit: Int = 500) {

    @Volatile
    private var limit: Int = initialLimit.coerceAtLeast(0)

    private val entries = ArrayDeque<String>()
    private val pins = HashSet<String>()

    @Synchronized
    fun setLimit(newLimit: Int) {
        limit = newLimit.coerceAtLeast(0)
        trimToLimitLocked()
    }

    @Synchronized
    fun getLimit(): Int = limit

    @Synchronized
    fun add(value: String): Boolean {
        val clean = value.trim()
        if (clean.isEmpty()) return false
        if (limit <= 0) return false
        entries.remove(clean)
        entries.addFirst(clean)
        trimToLimitLocked()
        return true
    }

    @Synchronized
    fun list(): List<String> = entries.toList()

    @Synchronized
    fun exportEntries(): List<SearchHistoryEntry> =
        entries.map { SearchHistoryEntry(text = it, pinned = it in pins) }

    @Synchronized
    fun restore(saved: List<SearchHistoryEntry>) {
        entries.clear()
        pins.clear()
        for (item in saved) {
            if (item.text.isBlank()) continue
            if (item.text !in entries) {
                entries.addLast(item.text)
            }
            if (item.pinned) {
                pins.add(item.text)
            }
        }
        trimToLimitLocked()
    }

    @Synchronized
    fun pin(value: String, pinned: Boolean = true) {
        if (pinned) pins.add(value) else pins.remove(value)
    }

    @Synchronized
    fun togglePin(value: String): Boolean {
        return if (pins.contains(value)) {
            pins.remove(value)
            false
        } else {
            pins.add(value)
            true
        }
    }

    @Synchronized
    fun isPinned(value: String): Boolean = pins.contains(value)

    @Synchronized
    fun clear() {
        entries.clear()
        pins.clear()
    }

    @Synchronized
    fun remove(value: String) {
        entries.remove(value)
        pins.remove(value)
    }

    private fun trimToLimitLocked() {
        if (limit <= 0) {
            entries.removeAll { it !in pins }
            return
        }
        while (entries.size > limit) {
            var removed = false
            for (i in entries.indices.reversed()) {
                val candidate = entries[i]
                if (candidate !in pins) {
                    entries.removeAt(i)
                    removed = true
                    break
                }
            }
            if (!removed) break
        }
    }
}
