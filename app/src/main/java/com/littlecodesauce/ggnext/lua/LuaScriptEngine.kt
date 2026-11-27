package com.littlecodesauce.ggnext.lua

import android.content.Context
import android.os.Build
import android.os.Environment
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.engine.NativeMemBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * GGNext 原生 Lua 5.5.1 脚本执行引擎协调器（单实例、线程安全、非阻塞）。
 *
 * 核心职责：
 * - 在独立后台脚本线程中运行原生 Lua 5.5.1 虚拟机（绝不阻塞 Android 主线程）；
 * - 通过原子取消标志 + `lua_sethook` 指令钩子 + 弹窗/事件队列唤醒，实现任意死循环或等待态下的瞬时中止；
 * - 管理 `gg.alert`/`choice`/`multiChoice`/`prompt` 交互弹窗、声明式 `ui.*` 面板及 `Sx` 悬浮按钮状态；
 * - 脚本结束后自动还原内存区域选择与进程暂停状态（除非脚本调用 `gg.skipRestoreState()`）。
 */
object LuaScriptEngine {

    private const val TAG = "LuaScriptEngine"
    private const val MAX_LOG_ENTRIES = 2000

    private val _state = MutableStateFlow(LuaScriptState())
    val state: StateFlow<LuaScriptState> = _state.asStateFlow()

    private val requestIdGen = AtomicLong(1L)
    private val logIdGen = AtomicLong(1L)
    private val cancelled = AtomicBoolean(false)
    private val skipRestoreState = AtomicBoolean(false)
    private val uiButtonClicked = AtomicBoolean(false)
    private val logLock = Any()
    private val logRing = ArrayDeque<LuaLogEntry>(256)

    @Volatile
    private var activeLuaHandle: Long = 0L

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var networkConsentDecision: Boolean? = null

    private val dialogLock = Any()
    private var pendingDialogLatch: CountDownLatch? = null
    private var pendingDialogResult: Any? = null
    private var pendingDialogId: Long = 0L

    private val uiEventQueue = LinkedBlockingQueue<LuaUiEvent>()
    private val apiHandler = GgLuaApiHandler()

    fun isRunning(): Boolean = _state.value.running

    fun isCancelled(): Boolean = cancelled.get()

    fun clearLogs() {
        synchronized(logLock) {
            logRing.clear()
        }
        _state.update { it.copy(logs = emptyList(), lastError = "") }
    }

    fun appendLog(line: String, level: LuaLogLevel = LuaLogLevel.PRINT) {
        val resolvedLevel = when {
            level != LuaLogLevel.PRINT -> level
            line.startsWith("[Error]", ignoreCase = true) || line.startsWith("✗") -> LuaLogLevel.ERROR
            line.startsWith("[Warn]", ignoreCase = true) || line.startsWith("⚠") -> LuaLogLevel.WARN
            line.startsWith("[Info]", ignoreCase = true) -> LuaLogLevel.INFO
            else -> LuaLogLevel.PRINT
        }
        when (resolvedLevel) {
            LuaLogLevel.ERROR -> AppLog.e(TAG, line)
            LuaLogLevel.WARN -> AppLog.w(TAG, line)
            else -> AppLog.i(TAG, line)
        }
        val entry = LuaLogEntry(
            id = logIdGen.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            level = resolvedLevel,
            message = line
        )
        val snapshot = synchronized(logLock) {
            if (logRing.size >= MAX_LOG_ENTRIES) {
                logRing.removeFirst()
            }
            logRing.addLast(entry)
            logRing.toList()
        }
        _state.update { it.copy(logs = snapshot) }
    }

    fun updateSxOffset(x: Float, y: Float) {
        _state.update { it.copy(sxOffsetX = x, sxOffsetY = y) }
    }

    fun updateStopOffset(x: Float, y: Float) {
        _state.update { it.copy(stopOffsetX = x, stopOffsetY = y) }
    }

    fun exportLogsToFile(context: Context?, entries: List<LuaLogEntry> = _state.value.logs): Result<String> {
        return runCatching {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            val stamp = sdf.format(java.util.Date())
            val fileName = "lua_log_$stamp.txt"
            val primaryDir = File("/sdcard/GGNext/logs")
            val targetDir = if (primaryDir.exists() || primaryDir.mkdirs()) {
                primaryDir
            } else {
                val fallback = context?.getExternalFilesDir("logs")
                    ?: File(context?.filesDir ?: File("/data/local/tmp"), "logs")
                fallback.mkdirs()
                fallback
            }
            val outFile = File(targetDir, fileName)
            val content = buildString {
                for (e in entries) {
                    append(e.formatExportLine())
                    append('\n')
                }
            }
            outFile.writeText(content, Charsets.UTF_8)
            outFile.absolutePath
        }
    }

    fun markSkipRestoreState() {
        skipRestoreState.set(true)
    }

    fun setUiButtonVisible(visible: Boolean) {
        if (!visible) uiButtonClicked.set(false)
        _state.update { it.copy(uiButtonVisible = visible) }
    }

    fun onUiButtonClicked() {
        uiButtonClicked.set(true)
    }

    fun consumeUiButtonClicked(): Boolean = uiButtonClicked.getAndSet(false)

    /**
     * 启动执行一个明文 Lua 脚本文件（在独立后台线程运行）。
     */
    @Synchronized
    fun runScriptFile(scriptPath: String): Boolean {
        val cleanPath = scriptPath.trim()
        if (cleanPath.isEmpty()) {
            Session.toast("请先选择或输入脚本路径")
            return false
        }
        val file = File(cleanPath)
        if (!file.exists() || !file.isFile) {
            Session.toast("脚本文件不存在：${file.name}")
            return false
        }
        if (_state.value.running) {
            Session.toast("已有脚本正在运行，请先停止当前脚本")
            return false
        }
        val ctx = Session.appContextForLua()
        if (!NativeMemBridge.ensureLoaded(ctx)) {
            val err = "脚本引擎未加载：${NativeMemBridge.loadError.ifBlank { "核心组件不可用" }}"
            appendLog(err, LuaLogLevel.ERROR)
            _state.update { it.copy(lastError = err) }
            Session.toast(err)
            return false
        }

        val fileName = file.name
        launchWorker(scriptPath = file.absolutePath, displayName = fileName) { luaHandle ->
            NativeMemBridge.nativeLuaRunFile(luaHandle, file.absolutePath)
        }
        return true
    }

    /**
     * 执行内存中的明文 Lua 脚本源码（供快速脚本测试与插件入口复用）。
     */
    @Synchronized
    fun runScriptSource(source: String, chunkName: String = "inline.lua"): Boolean {
        if (source.isBlank()) return false
        if (_state.value.running) {
            Session.toast("已有脚本正在运行，请先停止当前脚本")
            return false
        }
        val ctx = Session.appContextForLua()
        if (!NativeMemBridge.ensureLoaded(ctx)) {
            Session.toast("脚本引擎未加载")
            return false
        }
        launchWorker(scriptPath = chunkName, displayName = chunkName) { luaHandle ->
            NativeMemBridge.nativeLuaRunString(luaHandle, source, chunkName)
        }
        return true
    }

    private fun launchWorker(
        scriptPath: String,
        displayName: String,
        runner: (Long) -> String
    ) {
        cancelled.set(false)
        skipRestoreState.set(false)
        uiButtonClicked.set(false)
        networkConsentDecision = null
        uiEventQueue.clear()
        apiHandler.resetForNewRun()

        // [GGNext-Patch] 记录当前脚本目录，供 gg.downloadFile / gg.getFileContent 解析相对路径
        GgLuaApiHandler.currentScriptDir = run {
            val p = scriptPath.trim()
            if (p.isEmpty() || p.startsWith("@")) null
            else File(p).parentFile?.absolutePath
        }

        val initialRegions = Session.ui.value.selectedRegions
        val initialPaused = Session.ui.value.processPaused

        _state.update {
            it.copy(
                running = true,
                scriptPath = scriptPath,
                scriptName = displayName,
                lastError = "",
                uiButtonVisible = false,
                activeDialog = null,
                activeUiPanel = null
            )
        }
        appendLog("▶ 开始运行脚本: $displayName", LuaLogLevel.INFO)

        val t = Thread({
            var luaHandle = 0L
            try {
                val ctx = Session.appContextForLua()
                val engineHandle = NativeMemBridge.currentHandle()
                val propsJson = buildRuntimePropsJson(ctx)
                val callback = JniBridgeCallback()
                luaHandle = NativeMemBridge.nativeLuaCreate(engineHandle, callback, propsJson)
                if (luaHandle == 0L) {
                    val err = "初始化脚本引擎失败"
                    appendLog(err, LuaLogLevel.ERROR)
                    _state.update { it.copy(lastError = err) }
                    Session.toast(err)
                    return@Thread
                }
                activeLuaHandle = luaHandle

                // Execute Lua script (blocks this worker thread until completion, os.exit, error, or cancel)
                val resultJson = runner(luaHandle)
                handleRunResult(displayName, resultJson)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                appendLog("脚本异常: $msg", LuaLogLevel.ERROR)
                _state.update { it.copy(lastError = msg) }
                Session.toast("脚本异常：$msg")
            } finally {
                val h = luaHandle
                activeLuaHandle = 0L
                if (h != 0L) {
                    runCatching { NativeMemBridge.nativeLuaDestroy(h) }
                }
                cancelPendingDialogInternal()
                uiEventQueue.clear()

                // Restore ranges & pause state unless gg.skipRestoreState() was called
                if (!skipRestoreState.get()) {
                    runCatching {
                        Session.setSelectedRegionsFromMcp(initialRegions)
                        val nowPaused = Session.ui.value.processPaused
                        if (nowPaused != initialPaused && Session.engineForMcp().attachedOk) {
                            Session.controlProcessFromMcp(if (initialPaused) "pause" else "resume")
                        }
                    }
                }

                _state.update {
                    it.copy(
                        running = false,
                        uiButtonVisible = false,
                        activeDialog = null,
                        activeUiPanel = null
                    )
                }
                workerThread = null
            }
        }, "GGNext-LuaWorker")
        t.isDaemon = true
        workerThread = t
        t.start()
    }

    private fun handleRunResult(displayName: String, resultJson: String) {
        val obj = runCatching { JSONObject(resultJson) }.getOrNull()
        val ok = obj?.optBoolean("ok", false) ?: false
        val wasCancelled = obj?.optBoolean("cancelled", false) ?: cancelled.get()
        val exited = obj?.optBoolean("exited", false) ?: false
        val err = obj?.optString("error", "").orEmpty()

        when {
            wasCancelled -> {
                appendLog("■ 脚本已中止: $displayName", LuaLogLevel.WARN)
                Session.toast("脚本已停止")
            }
            ok && exited -> {
                appendLog("✓ 脚本正常退出: $displayName", LuaLogLevel.INFO)
            }
            ok -> {
                appendLog("✓ 脚本执行完成: $displayName", LuaLogLevel.INFO)
            }
            else -> {
                val cleanErr = err.ifBlank { "未知脚本错误" }
                appendLog("✗ 脚本错误: $cleanErr", LuaLogLevel.ERROR)
                _state.update { it.copy(lastError = cleanErr) }
                Session.toast("脚本错误：$cleanErr")
            }
        }
    }

    /**
     * 立即安全中止正在运行的 Lua 脚本：
     * 1. 置位原子取消标志并通知 C++ `lua_sethook`；
     * 2. 中断底层可能正在进行的内存扫描；
     * 3. 唤醒任何阻塞中的 `gg.alert`/`prompt` 弹窗与 `ui.show` 事件循环。
     */
    fun stopScript() {
        cancelled.set(true)
        val h = activeLuaHandle
        if (h != 0L && NativeMemBridge.isLoaded) {
            runCatching { NativeMemBridge.nativeLuaCancel(h) }
        }
        runCatching { Session.engineForMcp().cancelScan() }
        cancelPendingDialogInternal()
        uiEventQueue.offer(LuaUiEvent(type = "closed"))
        _state.update { it.copy(activeDialog = null, activeUiPanel = null, uiButtonVisible = false) }
        workerThread?.interrupt()
    }

    // ========================================================================
    // 弹窗同步桥接（脚本工作线程挂起等待，Compose 主线程回调唤醒）
    // ========================================================================

    internal fun requestDialogSync(build: (Long) -> LuaDialogRequest): Any? {
        if (cancelled.get()) return null
        val id = requestIdGen.incrementAndGet()
        val req = build(id)
        val latch = CountDownLatch(1)
        synchronized(dialogLock) {
            pendingDialogId = id
            pendingDialogResult = null
            pendingDialogLatch = latch
        }
        // 确保悬浮窗处于展开可见状态，避免脚本先调用 gg.setVisible(false) 后弹窗不可见导致死等
        if (!Session.ui.value.panelVisible) {
            Session.setPanelVisible(true)
        }
        _state.update { it.copy(activeDialog = req) }

        while (!cancelled.get()) {
            try {
                if (latch.await(100, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                if (cancelled.get()) break
            }
        }
        val result = synchronized(dialogLock) {
            val r = if (pendingDialogId == id) pendingDialogResult else null
            if (pendingDialogId == id) {
                pendingDialogLatch = null
                pendingDialogResult = null
            }
            r
        }
        _state.update { cur -> if (cur.activeDialog?.requestId == id) cur.copy(activeDialog = null) else cur }
        return if (cancelled.get()) null else result
    }

    fun submitDialogResult(requestId: Long, result: Any?) {
        synchronized(dialogLock) {
            if (pendingDialogId == requestId) {
                pendingDialogResult = result
                pendingDialogLatch?.countDown()
            }
        }
        _state.update { cur -> if (cur.activeDialog?.requestId == requestId) cur.copy(activeDialog = null) else cur }
    }

    private fun cancelPendingDialogInternal() {
        synchronized(dialogLock) {
            pendingDialogResult = null
            pendingDialogLatch?.countDown()
            pendingDialogLatch = null
        }
    }

    internal fun requestNetworkConsentSync(host: String, url: String): Boolean {
        networkConsentDecision?.let { return it }
        val res = requestDialogSync { id ->
            LuaDialogRequest.NetworkConsent(requestId = id, host = host, url = url)
        }
        val allowed = (res as? Boolean) == true
        networkConsentDecision = allowed
        return allowed
    }

    // ========================================================================
    // 声明式 UI (`ui.*`) 状态与事件队列桥接
    // ========================================================================

    internal fun showUiPanel(panel: LuaUiPanelState): Boolean {
        if (cancelled.get()) return false
        uiEventQueue.clear()
        if (!Session.ui.value.panelVisible) {
            Session.setPanelVisible(true)
        }
        _state.update { it.copy(activeUiPanel = panel) }
        return true
    }

    internal fun updateUiPanelReplace(root: LuaUiNode): Boolean {
        val cur = _state.value.activeUiPanel ?: return false
        _state.update { it.copy(activeUiPanel = cur.copy(root = root)) }
        return true
    }

    internal fun updateUiPanelPatch(targetId: String, patch: JSONObject): Boolean {
        val cur = _state.value.activeUiPanel ?: return false
        val patchedRoot = LuaUiProtocol.patchNode(cur.root, targetId, patch)
        _state.update { it.copy(activeUiPanel = cur.copy(root = patchedRoot)) }
        return true
    }

    fun closeUiPanel(fromUser: Boolean = true) {
        val hadPanel = _state.value.activeUiPanel != null
        _state.update { it.copy(activeUiPanel = null) }
        if (fromUser && hadPanel) {
            uiEventQueue.offer(LuaUiEvent(type = "closed"))
        }
    }

    fun dispatchUiCallback(callbackId: String, payload: Any? = null) {
        if (callbackId.isBlank()) return
        uiEventQueue.offer(
            LuaUiEvent(
                type = "event",
                callbackId = callbackId,
                payloadJson = LuaUiProtocol.encodePayload(payload)
            )
        )
    }

    internal fun pollUiEventSync(timeoutMs: Long): LuaUiEvent? {
        if (cancelled.get()) return LuaUiEvent(type = "closed")
        if (_state.value.activeUiPanel == null && uiEventQueue.isEmpty()) {
            return LuaUiEvent(type = "closed")
        }
        return try {
            if (timeoutMs <= 0L) {
                uiEventQueue.poll()
            } else {
                uiEventQueue.poll(timeoutMs.coerceIn(1L, 250L), TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            if (cancelled.get()) LuaUiEvent(type = "closed") else null
        }
    }

    // ========================================================================
    // JNI 回调入口与运行环境元数据
    // ========================================================================

    private fun buildRuntimePropsJson(ctx: Context?): String {
        val filesDir = ctx?.filesDir?.absolutePath ?: "/data/local/tmp"
        val cacheDir = ctx?.cacheDir?.absolutePath ?: "/data/local/tmp"
        val extFilesDir = ctx?.getExternalFilesDir(null)?.absolutePath ?: "/sdcard/GGNext"
        val extCacheDir = ctx?.externalCacheDir?.absolutePath ?: "/sdcard/GGNext/cache"
        val extStorage = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("/sdcard")
        val pluginDir = ctx?.let { LuaPluginBridge.luaPluginDir(it).absolutePath }.orEmpty()

        return JSONObject().apply {
            put("androidSdkInt", Build.VERSION.SDK_INT.toString())
            put("packageName", ctx?.packageName ?: "com.littlecodesauce.ggnext")
            put("version", GgLuaApiHandler.COMPAT_GG_VERSION)
            put("versionInt", GgLuaApiHandler.COMPAT_GG_VERSION_INT)
            put("build", GgLuaApiHandler.COMPAT_GG_BUILD)
            put("filesDir", filesDir)
            put("cacheDir", cacheDir)
            put("extFilesDir", extFilesDir)
            put("extCacheDir", extCacheDir)
            put("extStorage", extStorage)
            put("pluginDir", pluginDir)
        }.toString()
    }

    /**
     * 传递给 `NativeMemBridge.nativeLuaCreate` 的 JNI 回调对象。
     * 方法签名严格对应 `mem_engine_jni.cpp` 中的 `GetMethodID`。
     */
    class JniBridgeCallback {
        @Suppress("unused")
        fun onHostCall(method: String, argsJson: String): String {
            if (cancelled.get()) {
                return "{\"ok\":false,\"cancelled\":true,\"error\":\"cancelled\"}"
            }
            return try {
                apiHandler.dispatch(method, argsJson)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Host call '$method' failed", t)
                JSONObject().apply {
                    put("ok", false)
                    put("error", t.message ?: t.javaClass.simpleName)
                    put("raise", false)
                }.toString()
            }
        }

        @Suppress("unused")
        fun onPrint(line: String) {
            appendLog(line)
        }
    }
}
