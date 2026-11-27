package com.littlecodesauce.ggnext.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 轻量日志缓冲，对应原版的日志视图 */
object AppLog {
    private const val MAX = 500
    private val buffer = ArrayDeque<String>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  [$tag]  $message"
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.removeFirst()
        _flow.value = buffer.toList()
    }

    fun i(tag: String, message: String) = add(tag, message)
    fun w(tag: String, message: String) = add(tag, "WARN: $message")
    fun e(tag: String, message: String, t: Throwable? = null) =
        add(tag, if (t != null) "ERR: $message (${t.message})" else "ERR: $message")

    @Synchronized
    fun clear() {
        buffer.clear()
        _flow.value = emptyList()
    }

    @Synchronized
    fun text(): String = buffer.joinToString("\n")

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()
}
