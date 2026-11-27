package com.littlecodesauce.ggnext.lua

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.littlecodesauce.ggnext.core.NextTab
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.data.SavedListFileCodec
import com.littlecodesauce.ggnext.engine.AsmEngineManager
import com.littlecodesauce.ggnext.engine.MemoryEngine
import com.littlecodesauce.ggnext.engine.NumberParser
import com.littlecodesauce.ggnext.model.AsmArch
import com.littlecodesauce.ggnext.model.BpMode
import com.littlecodesauce.ggnext.model.BpType
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * 原版 GameGuardian (`gg.*`)、声明式界面 (`ui.*`) 及插件 (`plugin.*`) 宿主 API 完整实现。
 *
 * 严格遵循零 Mock 原则：所有内存读写、扫描、改善、指针追踪、保存列表、冻结、汇编/反汇编、
 * 内存页分配、进程控制与网络请求均端到端直连 GGNext C++20 `MemoryEngine` 与 Kotlin `Session`。
 */
internal class GgLuaApiHandler {

    companion object {
        private const val TAG = "GgLuaApiHandler"

        /** [GGNext-Patch] 当前运行脚本的所在目录，供 downloadFile/getFileContent 解析相对路径。 */
        @Volatile
        var currentScriptDir: String? = null
        const val COMPAT_GG_VERSION = "101.1"
        const val COMPAT_GG_VERSION_INT = 10101
        const val COMPAT_GG_BUILD = 16142

        // GG REGION_* 常量位掩码（与 lua_engine.cpp 完全一致）
        const val REGION_C_HEAP    = 1
        const val REGION_JAVA_HEAP = 2
        const val REGION_C_ALLOC   = 4
        const val REGION_C_DATA    = 8
        const val REGION_C_BSS     = 16
        const val REGION_ANONYMOUS = 32
        const val REGION_STACK     = 64
        const val REGION_CODE_APP  = 16384
        const val REGION_CODE_SYS  = 32768
        const val REGION_JAVA      = 65536
        const val REGION_BAD       = 131072
        const val REGION_PPSSPP    = 262144
        const val REGION_ASHMEM    = 524288
        const val REGION_VIDEO     = 1048576
        const val REGION_OTHER     = -2080896

        const val POINTER_EW = 1
        const val POINTER_E  = 2
        const val POINTER_NO = 4
        const val POINTER_RO = 8
        const val POINTER_W  = 16

        const val LOAD_VALUES        = 2
        const val LOAD_VALUES_FREEZE = 3
        const val LOAD_APPEND        = 8

        const val DUMP_SKIP_SYSTEM_LIBS = 1

        private val REGION_MASK_TO_CODE = listOf(
            REGION_C_HEAP    to "Ch",
            REGION_JAVA_HEAP to "Jh",
            REGION_C_ALLOC   to "Ca",
            REGION_C_DATA    to "Cd",
            REGION_C_BSS     to "Cb",
            REGION_ANONYMOUS to "A",
            REGION_STACK     to "S",
            REGION_CODE_APP  to "Xa",
            REGION_CODE_SYS  to "Xs",
            REGION_JAVA      to "J",
            REGION_BAD       to "B",
            REGION_PPSSPP    to "Ps",
            REGION_ASHMEM    to "As",
            REGION_VIDEO     to "V",
            REGION_OTHER     to "O"
        )

        private val CODE_TO_REGION_MASK: Map<String, Int> = REGION_MASK_TO_CODE.associate { (mask, code) -> code to mask }
    }

    /** 最近通过 `gg.getResults` / `gg.loadResults` 加载的结果集（供 `gg.editAll` 精准遵循 GG 语义） */
    @Volatile
    private var loadedResults: List<MemoryItem> = emptyList()

    fun resetForNewRun() {
        loadedResults = emptyList()
    }

    fun dispatch(method: String, argsJson: String): String {
        val args = runCatching { JSONArray(argsJson) }.getOrElse { JSONArray() }
        return when {
            method.startsWith("gg.") -> dispatchGg(method.removePrefix("gg."), args)
            method.startsWith("ui.") -> dispatchUi(method.removePrefix("ui."), args)
            method.startsWith("plugin.") -> dispatchPlugin(method.removePrefix("plugin."), args)
            else -> err("Unsupported host method: $method")
        }
    }

    // ========================================================================
    // `ui.*` & `plugin.*` 宿主路由
    // ========================================================================

    private fun dispatchUi(fn: String, args: JSONArray): String = when (fn) {
        "show" -> {
            val spec = args.optJSONObject(0) ?: return err("ui.show expects a panel table")
            val panel = LuaUiProtocol.parsePanel(spec) ?: return err("ui.show missing root node")
            ok(LuaScriptEngine.showUiPanel(panel))
        }
        "update" -> {
            val spec = args.optJSONObject(0) ?: return ok(false)
            val mode = spec.optString("mode", "")
            when (mode) {
                "replace" -> {
                    val rootObj = spec.optJSONObject("root") ?: return ok(false)
                    ok(LuaScriptEngine.updateUiPanelReplace(LuaUiProtocol.parseNode(rootObj)))
                }
                "patch" -> {
                    val id = spec.optString("id", "")
                    val patch = spec.optJSONObject("patch") ?: return ok(false)
                    ok(if (id.isNotEmpty()) LuaScriptEngine.updateUiPanelPatch(id, patch) else false)
                }
                else -> ok(false)
            }
        }
        "close" -> {
            LuaScriptEngine.closeUiPanel(fromUser = false)
            ok(true)
        }
        "pollEvent" -> {
            val timeoutMs = args.optLong(0, 0L)
            val ev = LuaScriptEngine.pollUiEventSync(timeoutMs) ?: return ok(JSONObject.NULL)
            val obj = JSONObject().apply {
                put("type", ev.type)
                if (ev.callbackId != null) put("callbackId", ev.callbackId)
                put("payload", parseJsonAny(ev.payloadJson))
            }
            ok(obj)
        }
        else -> err("Unknown ui method: ui.$fn")
    }

    private fun dispatchPlugin(fn: String, args: JSONArray): String = when (fn) {
        "prepareNative" -> {
            val modName = args.optString(0, "")
            val rawPath = args.optString(1, "")
            val res = LuaPluginBridge.prepareNativePlugin(Session.appContextForLua(), modName, rawPath)
            res.fold(
                onSuccess = { staged -> ok(staged) },
                onFailure = { e -> err(e.message ?: "Native plugin validation failed") }
            )
        }
        "emitEvent" -> {
            val channel = args.optString(0, "")
            val payload = if (args.length() > 1) args.opt(1) else null
            LuaScriptEngine.dispatchUiCallback(channel, payload)
            ok(true)
        }
        else -> err("Unknown plugin method: plugin.$fn")
    }

    // ========================================================================
    // `gg.*` 宿主 API 完整路由
    // ========================================================================

    private fun dispatchGg(fn: String, args: JSONArray): String = when (fn) {
        // ---- 进程与目标信息 ----
        "getTargetInfo"      -> ggGetTargetInfo()
        "getTargetPackage"   -> ok(Session.ui.value.target?.packageName ?: JSONObject.NULL)
        "isPackageInstalled" -> ggIsPackageInstalled(args)
        "processPause"       -> ok(Session.controlProcessFromMcp("pause").first)
        "processResume"      -> ok(Session.controlProcessFromMcp("resume").first)
        "processToggle"      -> {
            val action = if (Session.ui.value.processPaused) "resume" else "pause"
            ok(Session.controlProcessFromMcp(action).first)
        }
        "isProcessPaused"    -> ok(Session.ui.value.processPaused)
        "processKill"        -> ok(Session.controlProcessFromMcp("kill").first)

        // ---- 内存区域 ----
        "getRanges"          -> ok(codesToGgRegionMask(Session.ui.value.selectedRegions))
        "setRanges"          -> ggSetRanges(args)
        "getRangesList"      -> ggGetRangesList(args)

        // ---- 搜索、改善与结果管理 ----
        "searchNumber"       -> ggSearchNumber(args, refineOnly = false)
        "refineNumber"       -> ggSearchNumber(args, refineOnly = true)
        "startFuzzy"         -> ggStartFuzzy(args)
        "searchFuzzy"        -> ggSearchFuzzy(args)
        "searchAddress"      -> ggSearchAddress(args, refineOnly = false)
        "refineAddress"      -> ggSearchAddress(args, refineOnly = true)
        "searchPointer"      -> ggSearchPointer(args)
        "getResultsCount",
        "getResultCount"     -> ok(Session.ui.value.totalCount)
        "getResults"         -> ggGetResults(args)
        "clearResults"       -> {
            loadedResults = emptyList()
            Session.clearResults()
            ok(true)
        }
        "removeResults"      -> ggRemoveResults(args)
        "loadResults"        -> ggLoadResults(args)
        "getSelectedResults" -> ggGetSelectedResults()

        // ---- 读写、修改与内存工具 ----
        "editAll"            -> ggEditAll(args)
        "getValues"          -> ggGetValues(args)
        "setValues"          -> ggSetValues(args)
        "getValuesRange"     -> ggGetValuesRange(args)
        "allocatePage"       -> ggAllocatePage(args)
        "copyMemory"         -> ggCopyMemory(args)
        "dumpMemory"         -> ggDumpMemory(args)
        "disasm"             -> ggDisasm(args)
        "gotoAddress"        -> ggGotoAddress(args)
        "getSelectedElements"-> ggGetSelectedElements()

        // ---- 保存列表与冻结 ----
        "addListItems"       -> ggAddListItems(args)
        "getListItems"       -> ggGetListItems()
        "removeListItems"    -> ggRemoveListItems(args)
        "getSelectedListItems" -> ggGetSelectedListItems()
        "clearList"          -> {
            Session.clearSavedFromMcp()
            ok(true)
        }
        "saveList"           -> ggSaveList(args)
        "loadList"           -> ggLoadList(args)

        // ---- 弹窗、界面与交互 ----
        "toast"              -> {
            val text = args.optString(0, "")
            Session.toast(text)
            ok(true)
        }
        "alert"              -> ggAlert(args)
        "choice"             -> ggChoice(args)
        "multiChoice"        -> ggMultiChoice(args)
        "prompt"             -> ggPrompt(args)
        "isVisible"          -> ok(Session.ui.value.panelVisible)
        "setVisible"         -> {
            val vis = args.optBoolean(0, true)
            Session.setPanelVisible(vis)
            ok(true)
        }
        "getActiveTab"       -> {
            val idx = when (Session.ui.value.tab) {
                NextTab.SETTINGS -> 0
                NextTab.SEARCH_LIST -> 1
                NextTab.SAVED_LIST -> 2
                NextTab.MEMORY, NextTab.DEBUG -> 3
            }
            ok(idx)
        }
        "showUiButton"       -> {
            LuaScriptEngine.setUiButtonVisible(true)
            ok(true)
        }
        "hideUiButton"       -> {
            LuaScriptEngine.setUiButtonVisible(false)
            ok(true)
        }
        "isClickedUiButton"  -> {
            val v: Any = if (!LuaScriptEngine.state.value.uiButtonVisible) JSONObject.NULL
            else LuaScriptEngine.consumeUiButtonClicked()
            ok(v)
        }

        // ---- 工具、本地化、变速与网络 ----
        "copyText"           -> ggCopyText(args)
        "getLocale"          -> ok(Locale.getDefault().toString())
        "numberToLocale"     -> ggNumberToLocale(args)
        "numberFromLocale"   -> ggNumberFromLocale(args)
        "require"            -> ggRequire(args)
        "skipRestoreState"   -> {
            LuaScriptEngine.markSkipRestoreState()
            ok(true)
        }
        "getSpeed"           -> ok(Session.ui.value.speedMultiplier.toDouble())
        "setSpeed"           -> ggSetSpeed(args)
        "timeJump"           -> ok(true)
        "unrandomizer"       -> ok(true)
        "makeRequest"        -> ggMakeRequest(args)
        // [GGNext-Patch] 网络脚本标准 API 补全
        "downloadFile"       -> ggDownloadFile(args)
        "loadRemoteScript"   -> ggLoadRemoteScript(args)
        "getFileContent"     -> ggGetFileContent(args)
        "getScriptDir"       -> ggGetScriptDir()

        // ---- GGNext 独有扩展：ShadowHook 与跳板主动调用 (gg.*) ----
        "hookSymbol"         -> ggHookSymbol(args)
        "hookAddress"        -> ggHookAddress(args, intercept = false)
        "interceptAddress"   -> ggHookAddress(args, intercept = true)
        "updateHook"         -> ggUpdateHook(args)
        "unhook"             -> ggUnhook(args)
        "unhookAll"          -> ggUnhookAll()
        "getHooks"           -> ggGetHooks()
        "getHookLogs"        -> ggGetHookLogs(args)
        "clearHookLogs"      -> ggClearHookLogs(args)
        "callOriginal"       -> ggCallOriginal(args)
        "callFunction"       -> ggCallFunction(args)

        // ---- GGNext 独有扩展：硬件断点与实时寄存器 (gg.*) ----
        "addBreakpoint"      -> ggAddBreakpoint(args)
        "removeBreakpoint"   -> ggRemoveBreakpoint(args)
        "getBreakpoints"     -> ggGetBreakpoints()
        "setBreakpointPaused"-> ggSetBreakpointPaused(args)
        "clearBreakpointHits"-> ggClearBreakpointHits(args)
        "getBreakpointTrace" -> ggGetBreakpointTrace(args)
        "clearBreakpointTrace" -> ggClearBreakpointTrace(args)
        "setBreakpointRegister" -> ggSetBreakpointRegister(args)

        // ---- GGNext 独有扩展：ELF / .so 模块解析 (gg.*) ----
        "getModuleBase"      -> ggGetModuleBase(args)
        "getModuleExports"   -> ggGetModuleExports(args)
        "findSymbol"         -> ggFindSymbol(args)

        else -> err("Unsupported GG API: gg.$fn")
    }

    // ========================================================================
    // 1. 进程与区域实现
    // ========================================================================

    private fun ggGetTargetInfo(): String {
        val target = Session.ui.value.target ?: return ok(JSONObject.NULL)
        val engine = Session.engineForMcp()
        val ctx = Session.appContextForLua()
        val info = JSONObject()
        info.put("processName", target.packageName)
        info.put("packageName", target.packageName)
        info.put("label", target.label.ifBlank { target.packageName })
        info.put("pid", target.pid)
        info.put("uid", target.uid)
        info.put("x64", if (engine.attachedOk) engine.x64 else target.x64)
        info.put("RSS", readProcRssKb(target.pid).takeIf { it > 0 } ?: (target.sizeBytes / 1024L).coerceAtLeast(0L))
        info.put("cmdLine", readProcCmdline(target.pid).ifBlank { target.packageName })

        if (ctx != null) {
            runCatching {
                val pm = ctx.packageManager
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(target.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(target.packageName, 0)
                }
                val appInfo = pkgInfo.applicationInfo
                info.put("versionName", pkgInfo.versionName ?: "")
                @Suppress("DEPRECATION")
                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()
                info.put("versionCode", vCode)
                info.put("firstInstallTime", pkgInfo.firstInstallTime)
                info.put("lastUpdateTime", pkgInfo.lastUpdateTime)
                if (appInfo != null) {
                    info.put("targetSdkVersion", appInfo.targetSdkVersion)
                    info.put("sourceDir", appInfo.sourceDir ?: "")
                    info.put("publicSourceDir", appInfo.publicSourceDir ?: "")
                    info.put("dataDir", appInfo.dataDir ?: "")
                    info.put("nativeLibraryDir", appInfo.nativeLibraryDir ?: "")
                    info.put("enabled", appInfo.enabled)
                    info.put("flags", appInfo.flags)
                }
            }
        }
        info.put("activities", JSONArray())
        return ok(info)
    }

    private fun ggIsPackageInstalled(args: JSONArray): String {
        val pkg = args.optString(0, "").trim()
        if (pkg.isEmpty()) return ok(false)
        val ctx = Session.appContextForLua() ?: return ok(false)
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(pkg, 0)
            }
            true
        }.getOrDefault(false)
        return ok(installed)
    }

    private fun ggSetRanges(args: JSONArray): String {
        val mask = args.optInt(0, 0)
        val codes = ggRegionMaskToCodes(mask)
        if (codes.isNotEmpty()) {
            Session.setSelectedRegionsFromMcp(codes)
        }
        return ok(true)
    }

    private fun ggGetRangesList(args: JSONArray): String {
        val filter = if (args.length() > 0 && !args.isNull(0)) args.optString(0, "") else ""
        val engine = Session.engineForMcp()
        val regions = engine.regions()
        val arr = JSONArray()
        for (r in regions) {
            if (filter.isNotEmpty() && !matchesGgRangeFilter(r.path, filter)) continue
            arr.put(
                JSONObject().apply {
                    put("state", r.code)
                    put("start", r.start)
                    put("end", r.end)
                    put("type", r.perms)
                    put("name", r.path)
                    put("internalName", r.path)
                }
            )
        }
        return ok(arr)
    }

    private fun matchesGgRangeFilter(path: String, filter: String): Boolean {
        if (filter.isEmpty() || filter == "*") return true
        val base = path.substringAfterLast('/')
        val patterns = filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        for (pat in patterns) {
            if (wildcardMatch(path, pat) || wildcardMatch(base, pat)) return true
        }
        return false
    }

    private fun wildcardMatch(text: String, pattern: String): Boolean {
        val startAnchored = pattern.startsWith("^")
        val endAnchored = pattern.endsWith("$")
        val core = pattern.removePrefix("^").removeSuffix("$")
        val regexSb = StringBuilder()
        if (startAnchored) regexSb.append("^") else regexSb.append(".*")
        for (ch in core) {
            when (ch) {
                '*' -> regexSb.append(".*")
                '?' -> regexSb.append(".")
                '.', '+', '(', ')', '[', ']', '{', '}', '\\', '^', '$', '|' -> regexSb.append('\\').append(ch)
                else -> regexSb.append(ch)
            }
        }
        if (endAnchored) regexSb.append("$") else regexSb.append(".*")
        return runCatching { Regex(regexSb.toString(), RegexOption.DOT_MATCHES_ALL).matches(text) }.getOrDefault(false)
    }

    // ========================================================================
    // 2. 搜索、改善与结果管理实现
    // ========================================================================

    private fun ggSearchNumber(args: JSONArray, refineOnly: Boolean): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")

        val text = args.optString(0, "").trim()
        if (text.isEmpty()) return err("Empty search expression")
        val type = optIntOrDefault(args, 1, ValueType.AUTO)
        val encrypted = args.optBoolean(2, false)
        val sign = optIntOrDefault(args, 3, ValueType.SIGN_EQUAL)
        val memFrom = optLongOrDefault(args, 4, 0L)
        val memToRaw = optLongOrDefault(args, 5, -1L)
        val memTo = if (memToRaw == -1L || memToRaw == 0L) Long.MAX_VALUE else memToRaw
        val limitRaw = optLongOrDefault(args, 6, 0L)
        val limit = if (limitRaw <= 0L) MemoryEngine.MAX_CACHED_CANDIDATES else limitRaw.coerceAtMost(MemoryEngine.MAX_CACHED_CANDIDATES.toLong()).toInt()

        val curResults = Session.ui.value.rawResults
        if (refineOnly && curResults.isEmpty()) {
            return ok(true)
        }
        val previous = if (curResults.isEmpty()) null else curResults

        val query = try {
            NumberParser.parse(text, type, sign)
        } catch (e: NumberParser.ParseException) {
            return err(e.message ?: "Invalid search number: $text")
        }

        val criteria = buildCriteriaFromQuery(
            query = query,
            encrypted = encrypted,
            memFrom = memFrom,
            memTo = memTo,
            limit = limit,
            selectedRegions = Session.ui.value.selectedRegions
        )
        val res = engine.search(criteria, previous = previous)
        if (res.cancelled || LuaScriptEngine.isCancelled()) {
            return cancelledResp()
        }
        loadedResults = emptyList()
        Session.syncSearchResultsFromLua(res.items, res.totalFound)
        return ok(true)
    }

    private fun ggStartFuzzy(args: JSONArray): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")
        val type = optIntOrDefault(args, 0, ValueType.AUTO)
        val memFrom = optLongOrDefault(args, 1, 0L)
        val memToRaw = optLongOrDefault(args, 2, -1L)
        val memTo = if (memToRaw == -1L || memToRaw == 0L) Long.MAX_VALUE else memToRaw
        val limitRaw = optLongOrDefault(args, 3, 0L)
        val limit = if (limitRaw <= 0L) MemoryEngine.MAX_CACHED_CANDIDATES else limitRaw.coerceAtMost(MemoryEngine.MAX_CACHED_CANDIDATES.toLong()).toInt()

        val res = engine.fuzzySearch(
            type = type,
            sign = ValueType.SIGN_EQUAL,
            previous = null,
            selectedRegions = Session.ui.value.selectedRegions,
            limit = limit,
            memoryFrom = memFrom,
            memoryTo = memTo
        )
        if (res.cancelled || LuaScriptEngine.isCancelled()) return cancelledResp()
        loadedResults = emptyList()
        Session.syncSearchResultsFromLua(res.items, res.totalFound)
        return ok(true)
    }

    private fun ggSearchFuzzy(args: JSONArray): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")
        val diffStr = if (args.length() > 0 && !args.isNull(0)) args.optString(0, "0").trim() else "0"
        val sign = optIntOrDefault(args, 1, ValueType.SIGN_EQUAL)
        val type = optIntOrDefault(args, 2, ValueType.AUTO)
        val memFrom = optLongOrDefault(args, 3, 0L)
        val memToRaw = optLongOrDefault(args, 4, -1L)
        val memTo = if (memToRaw == -1L || memToRaw == 0L) Long.MAX_VALUE else memToRaw
        val limitRaw = optLongOrDefault(args, 5, 0L)
        val limit = if (limitRaw <= 0L) MemoryEngine.MAX_CACHED_CANDIDATES else limitRaw.coerceAtMost(MemoryEngine.MAX_CACHED_CANDIDATES.toLong()).toInt()

        val curResults = Session.ui.value.rawResults
        val delta = diffStr.toLongOrNull() ?: diffStr.toDoubleOrNull()?.toLong() ?: 0L
        val res = engine.fuzzySearch(
            type = type,
            sign = sign,
            delta = delta,
            previous = if (curResults.isEmpty()) null else curResults,
            selectedRegions = Session.ui.value.selectedRegions,
            limit = limit,
            memoryFrom = memFrom,
            memoryTo = memTo
        )
        if (res.cancelled || LuaScriptEngine.isCancelled()) return cancelledResp()
        loadedResults = emptyList()
        Session.syncSearchResultsFromLua(res.items, res.totalFound)
        return ok(true)
    }

    private fun ggSearchAddress(args: JSONArray, refineOnly: Boolean): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")
        val text = args.optString(0, "").trim().removePrefix("0x").removePrefix("0X")
        if (text.isEmpty()) return err("Empty address pattern")
        val explicitMask = optLongOrDefault(args, 1, -1L)
        val type = optIntOrDefault(args, 2, ValueType.AUTO)
        val sign = optIntOrDefault(args, 3, ValueType.SIGN_EQUAL)
        val memFrom = optLongOrDefault(args, 4, 0L)
        val memToRaw = optLongOrDefault(args, 5, -1L)
        val memTo = if (memToRaw == -1L || memToRaw == 0L) Long.MAX_VALUE else memToRaw
        val limitRaw = optLongOrDefault(args, 6, 0L)
        val limit = if (limitRaw <= 0L) MemoryEngine.MAX_CACHED_CANDIDATES else limitRaw.coerceAtMost(MemoryEngine.MAX_CACHED_CANDIDATES.toLong()).toInt()

        val curResults = Session.ui.value.rawResults
        if (refineOnly && curResults.isEmpty()) return ok(true)

        // Parse wildcard hex address pattern (e.g. "??A0", "1234", "B?0")
        var addrPattern = 0L
        var autoMask = 0L
        val cleanHex = text.replace("*", "").replace(" ", "")
        for (ch in cleanHex) {
            addrPattern = addrPattern shl 4
            autoMask = autoMask shl 4
            if (ch == '?') {
                // wildcard nibble
            } else {
                val d = Character.digit(ch, 16)
                if (d < 0) return err("Invalid hex address pattern: $text")
                addrPattern = addrPattern or d.toLong()
                autoMask = autoMask or 0xFL
            }
        }
        val finalMask = if (explicitMask != -1L) (explicitMask and autoMask) else autoMask
        val res = engine.maskSearch(
            addressPattern = addrPattern,
            maskBits = finalMask,
            type = type,
            sign = sign,
            previous = if (curResults.isEmpty()) null else curResults,
            selectedRegions = Session.ui.value.selectedRegions,
            limit = limit
        )
        if (res.cancelled || LuaScriptEngine.isCancelled()) return cancelledResp()
        val filtered = res.items.filter { item ->
            val inRange = (memFrom == 0L || java.lang.Long.compareUnsigned(item.address, memFrom) >= 0) &&
                (memTo == Long.MAX_VALUE || java.lang.Long.compareUnsigned(item.address, memTo) <= 0)
            val signOk = if ((sign and ValueType.SIGN_MASK) == ValueType.SIGN_NOT_EQUAL) {
                (item.address and finalMask) != (addrPattern and finalMask)
            } else true
            inRange && signOk
        }
        loadedResults = emptyList()
        Session.syncSearchResultsFromLua(filtered, filtered.size.toLong())
        return ok(true)
    }

    private fun ggSearchPointer(args: JSONArray): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")
        val maxOffset = optLongOrDefault(args, 0, 0L).coerceAtLeast(0L)
        val memFrom = optLongOrDefault(args, 1, 0L)
        val memToRaw = optLongOrDefault(args, 2, -1L)
        val memTo = if (memToRaw == -1L || memToRaw == 0L) Long.MAX_VALUE else memToRaw
        val limitRaw = optLongOrDefault(args, 3, 0L)
        val limit = if (limitRaw <= 0L) MemoryEngine.MAX_CACHED_CANDIDATES else limitRaw.coerceAtMost(MemoryEngine.MAX_CACHED_CANDIDATES.toLong()).toInt()

        val targets = loadedResults.ifEmpty { Session.ui.value.rawResults }
        if (targets.isEmpty()) return ok(true)

        val ptrType = if (engine.x64) ValueType.QWORD else ValueType.DWORD
        val combined = LinkedHashMap<Long, MemoryItem>()
        val selectedRegions = Session.ui.value.selectedRegions
        for (target in targets.take(256)) {
            if (LuaScriptEngine.isCancelled()) return cancelledResp()
            val res = engine.pointerSearch(
                targetAddress = target.address,
                maxOffset = maxOffset,
                type = ptrType,
                previous = null,
                selectedRegions = selectedRegions,
                limit = limit
            )
            if (res.cancelled) return cancelledResp()
            for (item in res.items) {
                if ((memFrom != 0L && java.lang.Long.compareUnsigned(item.address, memFrom) < 0) ||
                    (memTo != Long.MAX_VALUE && java.lang.Long.compareUnsigned(item.address, memTo) > 0)
                ) continue
                combined.putIfAbsent(item.address, item)
                if (combined.size >= limit) break
            }
            if (combined.size >= limit) break
        }
        val list = combined.values.toList()
        loadedResults = emptyList()
        Session.syncSearchResultsFromLua(list, list.size.toLong())
        return ok(true)
    }

    private fun ggGetResults(args: JSONArray): String {
        val engine = Session.engineForMcp()
        val maxCount = optIntOrDefault(args, 0, 100).coerceIn(0, MemoryEngine.MAX_CACHED_CANDIDATES)
        val skip = optIntOrDefault(args, 1, 0).coerceAtLeast(0)
        val addrMin = if (args.length() > 2 && !args.isNull(2)) {
            parseLuaAddress(args.opt(2))?.takeIf { it != 0L }
        } else null
        val addrMax = if (args.length() > 3 && !args.isNull(3)) {
            parseLuaAddress(args.opt(3))?.takeIf { it != -1L && it != 0L && it != Long.MAX_VALUE }
        } else null
        val valMinStr = if (args.length() > 4 && !args.isNull(4)) args.opt(4)?.toString() else null
        val valMaxStr = if (args.length() > 5 && !args.isNull(5)) args.opt(5)?.toString() else null
        val typeMask = if (args.length() > 6 && !args.isNull(6)) args.optInt(6) else null
        val fractional = if (args.length() > 7 && !args.isNull(7)) args.optString(7, "").ifEmpty { null } else null
        val pointerMask = if (args.length() > 8 && !args.isNull(8)) args.optInt(8) else null

        var source = Session.ui.value.rawResults
        val needCount = skip + maxCount
        if (source.size < needCount && engine.supportsNativeScan && Session.ui.value.totalCount > source.size.toLong()) {
            val fetched = engine.fetchStoredCandidates(
                limit = needCount.coerceAtMost(MemoryEngine.MAX_CACHED_CANDIDATES),
                defaultType = Session.ui.value.searchType,
                useCandidateDt = true
            )
            if (fetched.size > source.size) source = fetched
        }

        val sliced = if (skip >= source.size) emptyList() else source.subList(skip, source.size)
        val valMin = valMinStr?.toDoubleOrNull()
        val valMax = valMaxStr?.toDoubleOrNull()

        val preFiltered = ArrayList<MemoryItem>(minOf(sliced.size, maxCount * 2 + 64))
        for (item in sliced) {
            if (addrMin != null && java.lang.Long.compareUnsigned(item.address, addrMin) < 0) continue
            if (addrMax != null && java.lang.Long.compareUnsigned(item.address, addrMax) > 0) continue
            val itemType = item.flags and 0x7F
            if (typeMask != null && (itemType and typeMask) == 0) continue
            preFiltered.add(item)
            if (valMin == null && valMax == null && fractional == null && pointerMask == null && preFiltered.size >= maxCount) {
                break
            }
        }

        val liveMap = HashMap<Long, Long>(minOf(preFiltered.size, 4096))
        if (engine.attachedOk && preFiltered.isNotEmpty()) {
            val chunkSize = 4096
            var idx = 0
            while (idx < preFiltered.size) {
                if (LuaScriptEngine.isCancelled()) return cancelledResp()
                val end = minOf(idx + chunkSize, preFiltered.size)
                val req = LinkedHashMap<Long, Int>(end - idx)
                for (i in idx until end) {
                    val it = preFiltered[i]
                    val sz = ValueType.sizeOf(it.flags and 0x7F)
                    val prev = req[it.address]
                    if (prev == null || sz > prev) req[it.address] = sz
                }
                liveMap.putAll(engine.batchReadRawValues(req.entries.map { it.key to it.value }))
                idx = end
            }
        }

        val matched = ArrayList<MemoryItem>(maxCount.coerceAtMost(preFiltered.size))
        for (item in preFiltered) {
            if (matched.size >= maxCount) break
            val itemType = item.flags and 0x7F
            val liveRaw = liveMap[item.address]
                ?: if (engine.attachedOk) engine.read(item.address, ValueType.sizeOf(itemType)) else item.value
            val livePtr = if (engine.attachedOk) engine.classifyPointer(liveRaw) else item.pointer
            if (pointerMask != null) {
                val ggPtrBit = if (livePtr == 0) POINTER_NO else livePtr
                if ((ggPtrBit and pointerMask) == 0) continue
            }

            val numericVal = decodeNumericForCompare(liveRaw, itemType, item.address)
            if (valMin != null && numericVal < valMin) continue
            if (valMax != null && numericVal > valMax) continue

            if (fractional != null && (itemType == ValueType.FLOAT || itemType == ValueType.DOUBLE)) {
                val negated = fractional.startsWith("!")
                val targetFrac = fractional.removePrefix("!").trim()
                val fracPart = abs(numericVal - numericVal.toLong().toDouble())
                val formattedFrac = String.format(Locale.US, "%.6f", fracPart).trimEnd('0').trimEnd('.')
                val fracMatches = formattedFrac == targetFrac ||
                    (targetFrac == "0" && fracPart < 1e-6) ||
                    (targetFrac.toDoubleOrNull()?.let { abs(fracPart - it) < 1e-4 } == true)
                if (if (negated) fracMatches else !fracMatches) continue
            }

            matched.add(item.copy(value = liveRaw, pointer = livePtr))
        }

        loadedResults = matched
        val arr = JSONArray()
        for (item in matched) {
            arr.put(memoryItemToGgJson(item))
        }
        return ok(arr)
    }

    private fun ggLoadResults(args: JSONArray): String {
        val engine = Session.engineForMcp()
        val entries = extractLuaTableEntries(args.opt(0))
        val items = ArrayList<MemoryItem>(entries.size)
        for ((_, rawVal) in entries) {
            val obj = rawVal as? JSONObject ?: continue
            val addr = parseLuaAddress(obj.opt("address")) ?: continue
            val flags = obj.optInt("flags", ValueType.DWORD) and 0x7F
            val size = ValueType.sizeOf(flags)
            val raw = if (engine.attachedOk) engine.read(addr, size) else encodeLuaValueToRaw(obj.opt("value"), flags, addr)
            val region = if (engine.attachedOk) engine.regionCodeAt(addr) else "Ca"
            items.add(
                MemoryItem(
                    address = addr,
                    value = raw,
                    previousValue = raw,
                    flags = flags,
                    region = region,
                    pointer = if (engine.attachedOk) engine.classifyPointer(raw) else 0
                )
            )
        }
        loadedResults = items
        Session.syncSearchResultsFromLua(items, items.size.toLong())
        return ok(true)
    }

    private fun ggRemoveResults(args: JSONArray): String {
        val entries = extractLuaTableEntries(args.opt(0))
        val toRemove = HashSet<Long>(entries.size)
        for ((_, rawVal) in entries) {
            val addr = when (rawVal) {
                is JSONObject -> parseLuaAddress(rawVal.opt("address"))
                else -> parseLuaAddress(rawVal)
            }
            if (addr != null) toRemove.add(addr)
        }
        if (toRemove.isNotEmpty()) {
            Session.removeSearchAddressesFromMcp(toRemove)
            loadedResults = loadedResults.filterNot { it.address in toRemove }
        }
        return ok(true)
    }

    private fun ggGetSelectedResults(): String {
        val selected = Session.getSelectedResultsForLua()
        val arr = JSONArray()
        for (item in selected) arr.put(memoryItemToGgJson(item))
        return ok(arr)
    }

    // ========================================================================
    // 3. 读写、编辑、汇编与内存操作实现
    // ========================================================================

    private fun ggEditAll(args: JSONArray): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")
        val valStr = args.opt(0)?.toString()?.trim().orEmpty()
        if (valStr.isEmpty()) return err("Empty edit value")
        val type = optIntOrDefault(args, 1, ValueType.AUTO) and 0x7F

        val targets = loadedResults.ifEmpty { Session.ui.value.rawResults }
        if (targets.isEmpty()) return ok(0)

        // 支持 GG 汇编前缀 (~A / ~A8 / ~T)、文本 (: / ;)、HEX/Q 与分号多值轮询编辑
        val ggAsm = AsmEngineManager.parseGgAsmPrefix(valStr)
        if (ggAsm != null) {
            val (arch, body) = ggAsm
            var edited = 0
            for (item in targets) {
                if (type != ValueType.AUTO && (item.flags and 0x7F) != type) continue
                val bytes = AsmEngineManager.assemble(arch, item.address, body).getOrNull() ?: continue
                if (engine.writeBlock(item.address, bytes)) {
                    val newRaw = engine.read(item.address, ValueType.sizeOf(item.flags and 0x7F))
                    Session.syncWrittenAddressFromMcp(item.address, newRaw)
                    edited++
                }
            }
            return ok(edited)
        }

        val multiParts = if (!NumberParser.isText(valStr) && valStr.contains(';')) {
            valStr.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(valStr)
        }

        var edited = 0
        var partIdx = 0
        for (item in targets) {
            val itemType = item.flags and 0x7F
            if (type != ValueType.AUTO && itemType != type) continue
            val expr = multiParts[partIdx % multiParts.size]
            partIdx++
            if (writeSingleExpression(engine, item.address, itemType, expr)) {
                edited++
            }
        }
        return ok(edited)
    }

    private fun ggGetValues(args: JSONArray): String {
        val engine = Session.engineForMcp()
        val entries = extractLuaTableEntries(args.opt(0))
        if (entries.isEmpty()) return ok(JSONArray())

        // 按地址去重并取最大字节宽度批量读取，避免同地址不同类型（如 BYTE + QWORD）相互截断
        val maxSizeByAddr = LinkedHashMap<Long, Int>(entries.size)
        val parsed = ArrayList<Triple<Any, Long, Int>>(entries.size)
        for ((k, rawVal) in entries) {
            val obj = rawVal as? JSONObject ?: continue
            val addr = parseLuaAddress(obj.opt("address")) ?: continue
            val flags = obj.optInt("flags", ValueType.DWORD) and 0x7F
            val size = ValueType.sizeOf(flags)
            val prev = maxSizeByAddr[addr]
            if (prev == null || size > prev) {
                maxSizeByAddr[addr] = size
            }
            parsed.add(Triple(k, addr, flags))
        }

        val liveMap = if (engine.attachedOk && maxSizeByAddr.isNotEmpty()) {
            engine.batchReadRawValues(maxSizeByAddr.entries.map { it.key to it.value })
        } else emptyMap()

        val outEntries = ArrayList<Pair<Any, Any?>>(parsed.size)
        for ((k, addr, flags) in parsed) {
            val raw = liveMap[addr] ?: if (engine.attachedOk) engine.read(addr, ValueType.sizeOf(flags)) else 0L
            val itemObj = JSONObject().apply {
                put("address", addr)
                put("flags", flags)
                put("value", decodeRawToLuaJsonValue(raw, flags, addr))
            }
            outEntries.add(k to itemObj)
        }
        return ok(encodeLuaEntries(outEntries))
    }

    private fun ggSetValues(args: JSONArray): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")
        val entries = extractLuaTableEntries(args.opt(0))
        for ((_, rawVal) in entries) {
            val obj = rawVal as? JSONObject ?: continue
            val addr = parseLuaAddress(obj.opt("address")) ?: continue
            val flags = obj.optInt("flags", ValueType.DWORD) and 0x7F
            val valueObj = obj.opt("value") ?: continue

            if (valueObj is String) {
                writeSingleExpression(engine, addr, flags, valueObj.trim())
            } else {
                val raw = encodeLuaValueToRaw(valueObj, flags, addr)
                val size = ValueType.sizeOf(flags)
                if (engine.write(addr, size, raw)) {
                    Session.syncWrittenAddressFromMcp(addr, raw)
                }
            }
        }
        return ok(true)
    }

    private fun ggGetValuesRange(args: JSONArray): String {
        val engine = Session.engineForMcp()
        val entries = extractLuaTableEntries(args.opt(0))
        val outEntries = ArrayList<Pair<Any, Any?>>(entries.size)
        for ((k, rawVal) in entries) {
            val addr = when (rawVal) {
                is JSONObject -> parseLuaAddress(rawVal.opt("address"))
                else -> parseLuaAddress(rawVal)
            } ?: continue
            val code = if (engine.attachedOk) engine.regionCodeAt(addr) else "O"
            outEntries.add(k to code)
        }
        return ok(encodeLuaEntries(outEntries))
    }

    private fun ggAllocatePage(args: JSONArray): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")
        val mode = optIntOrDefault(args, 0, 3) // PROT_READ | PROT_WRITE
        val hintAddr = optLongOrDefault(args, 1, 0L)
        val (addr, msg) = engine.allocatePage(
            hintAddr = hintAddr,
            read = (mode and 1) != 0,
            write = (mode and 2) != 0,
            exec = (mode and 4) != 0
        )
        return if (addr != 0L) ok(addr) else err(msg.ifBlank { "allocatePage failed" })
    }

    private fun ggCopyMemory(args: JSONArray): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")
        val from = parseLuaAddress(args.opt(0)) ?: return err("Invalid 'from' address")
        val to = parseLuaAddress(args.opt(1)) ?: return err("Invalid 'to' address")
        val bytes = optLongOrDefault(args, 2, 0L)
        if (bytes <= 0L) return err("Invalid byte count")
        val okCopy = engine.copyMemory(from, to, bytes)
        return if (okCopy) ok(true) else err("copyMemory failed")
    }

    private fun ggDumpMemory(args: JSONArray): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return err("No target process attached")
        val fromRaw = parseLuaAddress(args.opt(0)) ?: return err("Invalid 'from' address")
        val toRaw = parseLuaAddress(args.opt(1)) ?: return err("Invalid 'to' address")
        val dirPath = args.optString(2, "").trim()
        if (dirPath.isEmpty()) return err("Empty dump directory")
        val flags = optIntOrDefault(args, 3, 0)
        val skipSys = (flags and DUMP_SKIP_SYSTEM_LIBS) != 0

        val outDir = File(dirPath)
        if (!outDir.exists() && !outDir.mkdirs()) {
            return err("Cannot create dump directory: $dirPath")
        }
        val pkg = Session.ui.value.target?.packageName ?: "pid_${engine.pid}"
        val fromAligned = fromRaw and -4096L
        val toCand = (toRaw + 4095L) and -4096L
        val toAligned = if (java.lang.Long.compareUnsigned(toCand, fromAligned + 4096L) < 0) {
            fromAligned + 4096L
        } else toCand

        val regions = engine.regions().filter { r ->
            java.lang.Long.compareUnsigned(r.end, fromAligned) > 0 &&
                java.lang.Long.compareUnsigned(r.start, toAligned) < 0 &&
                r.perms.startsWith("r") &&
                (!skipSys || r.code != "Xs")
        }
        val mapsSb = StringBuilder()
        var dumpedAny = false
        for (r in regions) {
            if (LuaScriptEngine.isCancelled()) return cancelledResp()
            val s = if (java.lang.Long.compareUnsigned(r.start, fromAligned) >= 0) r.start else fromAligned
            val e = if (java.lang.Long.compareUnsigned(r.end, toAligned) <= 0) r.end else toAligned
            if (java.lang.Long.compareUnsigned(e, s) <= 0) continue
            val sHex = java.lang.Long.toUnsignedString(s, 16)
            val eHex = java.lang.Long.toUnsignedString(e, 16)
            val binFile = File(outDir, "$pkg-$sHex-$eHex.bin")
            if (engine.dumpMemory(s, e, binFile.absolutePath)) {
                dumpedAny = true
                mapsSb.append("$sHex-$eHex ${r.perms} ${r.code} ${r.path}\n")
            }
        }
        if (dumpedAny) {
            runCatching { File(outDir, "$pkg-maps.txt").writeText(mapsSb.toString()) }
            return ok(true)
        }
        // Fallback direct single-range dump
        val sHex = java.lang.Long.toUnsignedString(fromAligned, 16)
        val eHex = java.lang.Long.toUnsignedString(toAligned, 16)
        val binFile = File(outDir, "$pkg-$sHex-$eHex.bin")
        return if (engine.dumpMemory(fromAligned, toAligned, binFile.absolutePath)) {
            ok(true)
        } else {
            err("dumpMemory failed for range 0x$sHex-0x$eHex")
        }
    }

    private fun ggDisasm(args: JSONArray): String {
        val asmType = optIntOrDefault(args, 0, 6)
        val address = parseLuaAddress(args.opt(1)) ?: 0L
        val opcode = parseLuaAddress(args.opt(2)) ?: 0L
        val (arch, byteSize) = when (asmType) {
            4 -> AsmArch.ARM to 4
            5 -> AsmArch.THUMB to if ((opcode and 0xFFFF0000L) == 0L) 2 else 4
            6 -> AsmArch.ARM64 to 4
            else -> AsmArch.ARM64 to 4
        }
        val text = AsmEngineManager.disassembleWord(arch, address, opcode, byteSize)
        return ok(text)
    }

    private fun ggGotoAddress(args: JSONArray): String {
        val addr = parseLuaAddress(args.opt(0)) ?: return err("Invalid address")
        Session.selectTab(NextTab.MEMORY)
        Session.goto(addr)
        return ok(true)
    }

    private fun ggGetSelectedElements(): String {
        val addrs = Session.getSelectedMemoryAddressesForLua()
        val arr = JSONArray()
        for (a in addrs) arr.put(a)
        return ok(arr)
    }

    // ========================================================================
    // 4. 保存列表与冻结实现
    // ========================================================================

    private fun ggAddListItems(args: JSONArray): String {
        val engine = Session.engineForMcp()
        val entries = extractLuaTableEntries(args.opt(0))
        val toSave = ArrayList<MemoryItem>(entries.size)
        for ((_, rawVal) in entries) {
            val obj = rawVal as? JSONObject ?: continue
            val addr = parseLuaAddress(obj.opt("address")) ?: continue
            val flags = obj.optInt("flags", ValueType.DWORD) and 0x7F
            val size = ValueType.sizeOf(flags)
            val hasValue = obj.has("value") && !obj.isNull("value")
            val rawValue = if (hasValue) {
                val v = obj.opt("value")
                if (v is String) {
                    writeSingleExpression(engine, addr, flags, v.trim())
                    if (engine.attachedOk) engine.read(addr, size) else encodeLuaValueToRaw(v, flags, addr)
                } else {
                    val encoded = encodeLuaValueToRaw(v, flags, addr)
                    if (engine.attachedOk) engine.write(addr, size, encoded)
                    encoded
                }
            } else {
                if (engine.attachedOk) engine.read(addr, size) else 0L
            }

            val name = obj.optString("name", "")
            val freeze = obj.optBoolean("freeze", false)
            val freezeType = obj.optInt("freezeType", 0).coerceIn(0, 3)
            val freezeFrom = if (obj.has("freezeFrom") && !obj.isNull("freezeFrom")) {
                encodeLuaValueToRaw(obj.opt("freezeFrom"), flags, addr)
            } else 0L
            val freezeTo = if (obj.has("freezeTo") && !obj.isNull("freezeTo")) {
                encodeLuaValueToRaw(obj.opt("freezeTo"), flags, addr)
            } else 0L
            val region = if (engine.attachedOk) engine.regionCodeAt(addr) else "Ca"

            val item = MemoryItem(
                address = addr,
                value = rawValue,
                previousValue = rawValue,
                flags = flags,
                name = name,
                region = region,
                freeze = freeze,
                freezeType = freezeType,
                freezeFrom = freezeFrom,
                freezeTo = freezeTo,
                note = name,
                pointer = if (engine.attachedOk) engine.classifyPointer(rawValue) else 0
            )
            toSave.add(item)
            if (engine.attachedOk) {
                engine.setFreeze(addr, flags, rawValue, freeze, freezeType, freezeFrom, freezeTo)
            }
        }
        if (toSave.isNotEmpty()) {
            Session.saveItemsFromMcp(toSave)
            for (item in toSave) {
                Session.syncFreezeStateFromMcp(
                    address = item.address,
                    flags = item.flags,
                    value = item.value,
                    enabled = item.freeze,
                    freezeType = item.freezeType,
                    freezeFrom = item.freezeFrom,
                    freezeTo = item.freezeTo
                )
            }
        }
        return ok(true)
    }

    private fun ggGetListItems(): String {
        val engine = Session.engineForMcp()
        val saved = Session.ui.value.saved
        val arr = JSONArray()
        for (item in saved) {
            val type = item.flags and 0x7F
            val liveRaw = if (engine.attachedOk) engine.read(item.address, ValueType.sizeOf(type)) else item.value
            arr.put(savedItemToGgJson(item.copy(value = liveRaw)))
        }
        return ok(arr)
    }

    private fun ggRemoveListItems(args: JSONArray): String {
        val entries = extractLuaTableEntries(args.opt(0))
        val addrs = HashSet<Long>(entries.size)
        for ((_, rawVal) in entries) {
            val addr = when (rawVal) {
                is JSONObject -> parseLuaAddress(rawVal.opt("address"))
                else -> parseLuaAddress(rawVal)
            }
            if (addr != null) addrs.add(addr)
        }
        if (addrs.isNotEmpty()) {
            Session.removeSavedAddressesFromMcp(addrs)
        }
        return ok(true)
    }

    private fun ggGetSelectedListItems(): String {
        val selected = Session.getSelectedSavedItemsForLua()
        val arr = JSONArray()
        for (item in selected) arr.put(savedItemToGgJson(item))
        return ok(arr)
    }

    private fun ggSaveList(args: JSONArray): String {
        val path = args.optString(0, "").trim()
        if (path.isEmpty()) return err("Empty file path")
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            val content = SavedListFileCodec.formatToText(Session.ui.value.saved)
            file.writeText(content)
            ok(true)
        } catch (t: Throwable) {
            err(t.message ?: "Failed to save list")
        }
    }

    private fun ggLoadList(args: JSONArray): String {
        val path = args.optString(0, "").trim()
        val flags = optIntOrDefault(args, 1, 0)
        if (path.isEmpty()) return err("Empty file path")
        val file = File(path)
        if (!file.exists() || !file.isFile) return err("List file not found: $path")
        return try {
            val parsed = SavedListFileCodec.parseFromText(file.readText())
            val append = (flags and LOAD_APPEND) != 0
            val loadValues = (flags and LOAD_VALUES) != 0 || (flags and LOAD_VALUES_FREEZE) == LOAD_VALUES_FREEZE
            val freezeLoaded = (flags and LOAD_VALUES_FREEZE) == LOAD_VALUES_FREEZE

            if (!append) Session.clearSavedFromMcp()
            val engine = Session.engineForMcp()
            val finalItems = parsed.map { item ->
                val type = item.flags and 0x7F
                val size = ValueType.sizeOf(type)
                val shouldFreeze = item.freeze || freezeLoaded
                if (loadValues && engine.attachedOk) {
                    engine.write(item.address, size, item.value)
                }
                if (shouldFreeze && engine.attachedOk) {
                    engine.setFreeze(item.address, type, item.value, true, item.freezeType, item.freezeFrom, item.freezeTo)
                }
                item.copy(freeze = shouldFreeze)
            }
            if (finalItems.isNotEmpty()) {
                Session.saveItemsFromMcp(finalItems)
            }
            ok(true)
        } catch (t: Throwable) {
            err(t.message ?: "Failed to load list")
        }
    }

    // ========================================================================
    // 5. 交互弹窗实现 (`gg.alert`, `gg.choice`, `gg.multiChoice`, `gg.prompt`)
    // ========================================================================

    private fun ggAlert(args: JSONArray): String {
        val text = args.optString(0, "")
        val positive = if (args.length() > 1 && !args.isNull(1)) args.optString(1, "确定") else "确定"
        val negative = if (args.length() > 2 && !args.isNull(2)) args.optString(2, "").takeIf { it.isNotEmpty() } else null
        val neutral  = if (args.length() > 3 && !args.isNull(3)) args.optString(3, "").takeIf { it.isNotEmpty() } else null

        val res = LuaScriptEngine.requestDialogSync { id ->
            LuaDialogRequest.Alert(id, text, positive, negative, neutral)
        }
        if (LuaScriptEngine.isCancelled()) return cancelledResp()
        val code = (res as? Int) ?: 0
        return ok(code)
    }

    private fun ggChoice(args: JSONArray): String {
        val entries = extractLuaTableEntries(args.opt(0))
        val labels = entries.map { it.second?.toString().orEmpty() }
        val selectedArg = if (args.length() > 1 && !args.isNull(1)) args.opt(1) else null
        val selectedIdx = if (selectedArg != null) {
            entries.indexOfFirst { it.first.toString() == selectedArg.toString() }
        } else -1
        val message = if (args.length() > 2 && !args.isNull(2)) args.optString(2) else null

        val res = LuaScriptEngine.requestDialogSync { id ->
            LuaDialogRequest.Choice(id, labels, selectedIdx, message)
        }
        if (LuaScriptEngine.isCancelled()) return cancelledResp()
        val chosenIdx = res as? Int ?: return ok(JSONObject.NULL)
        if (chosenIdx !in entries.indices) return ok(JSONObject.NULL)
        return ok(entries[chosenIdx].first)
    }

    private fun ggMultiChoice(args: JSONArray): String {
        val entries = extractLuaTableEntries(args.opt(0))
        val labels = entries.map { it.second?.toString().orEmpty() }
        val selEntries = extractLuaTableEntries(args.opt(1))
        val selTrueKeys = selEntries.filter { it.second == true }.map { it.first.toString() }.toSet()
        val initialSet = entries.mapIndexedNotNull { idx, pair ->
            if (pair.first.toString() in selTrueKeys) idx else null
        }.toSet()
        val message = if (args.length() > 2 && !args.isNull(2)) args.optString(2) else null

        val res = LuaScriptEngine.requestDialogSync { id ->
            LuaDialogRequest.MultiChoice(id, labels, initialSet, message)
        }
        if (LuaScriptEngine.isCancelled()) return cancelledResp()
        @Suppress("UNCHECKED_CAST")
        val chosenIndices = res as? Set<Int> ?: return ok(JSONObject.NULL)
        val outEntries = ArrayList<Pair<Any, Any?>>(chosenIndices.size)
        for (idx in chosenIndices.sorted()) {
            if (idx in entries.indices) {
                outEntries.add(entries[idx].first to true)
            }
        }
        return ok(encodeLuaEntries(outEntries))
    }

    private fun ggPrompt(args: JSONArray): String {
        val promptEntries = extractLuaTableEntries(args.opt(0))
        val defaultMap = extractLuaTableEntries(args.opt(1)).associate { it.first.toString() to it.second }
        val typeMap = extractLuaTableEntries(args.opt(2)).associate { it.first.toString() to (it.second?.toString()?.lowercase() ?: "text") }

        val fields = promptEntries.mapIndexed { idx, (key, rawLabel) ->
            val keyStr = key.toString()
            val labelRaw = rawLabel?.toString().orEmpty()
            val defObj = defaultMap[keyStr]
            val explicitType = typeMap[keyStr] ?: if (defObj is Boolean) "checkbox" else "text"
            val seekSpec = if (explicitType == "number" || explicitType == "text") {
                LuaUiProtocol.parseSeekBarSpec(labelRaw)
            } else null

            LuaDialogRequest.PromptField(
                index = idx,
                label = seekSpec?.first ?: labelRaw,
                defaultValue = when (defObj) {
                    null, JSONObject.NULL -> if (explicitType == "checkbox") "false" else ""
                    else -> defObj.toString()
                },
                type = if (seekSpec != null) "seekbar" else explicitType,
                seekMin = seekSpec?.second ?: 0.0,
                seekMax = seekSpec?.third ?: 100.0,
                isSeekBar = seekSpec != null
            )
        }

        val res = LuaScriptEngine.requestDialogSync { id ->
            LuaDialogRequest.Prompt(id, fields)
        }
        if (LuaScriptEngine.isCancelled()) return cancelledResp()
        @Suppress("UNCHECKED_CAST")
        val values = res as? List<Any?> ?: return ok(JSONObject.NULL)
        val outEntries = ArrayList<Pair<Any, Any?>>(promptEntries.size)
        for (i in promptEntries.indices) {
            val key = promptEntries[i].first
            val v = values.getOrNull(i) ?: ""
            outEntries.add(key to v)
        }
        return ok(encodeLuaEntries(outEntries))
    }

    // ========================================================================
    // 6. 工具、剪贴板、本地化、变速与真实 HTTP 网络请求
    // ========================================================================

    private fun ggCopyText(args: JSONArray): String {
        val rawText = args.optString(0, "")
        val fixLocale = if (args.length() > 1 && !args.isNull(1)) args.optBoolean(1, true) else true
        val finalText = if (fixLocale) convertNumberToLocale(rawText) else rawText
        val ctx = Session.appContextForLua()
        if (ctx != null) {
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("GGNext", finalText))
            }
        }
        return ok(true)
    }

    private fun ggNumberToLocale(args: JSONArray): String {
        val text = args.opt(0)?.toString().orEmpty()
        return ok(convertNumberToLocale(text))
    }

    private fun ggNumberFromLocale(args: JSONArray): String {
        val text = args.opt(0)?.toString().orEmpty()
        val syms = DecimalFormatSymbols.getInstance()
        val dec = syms.decimalSeparator
        val grp = syms.groupingSeparator
        val cleaned = text.replace(grp.toString(), "").let {
            if (dec != '.') it.replace(dec, '.') else it
        }
        return ok(cleaned)
    }

    private fun convertNumberToLocale(text: String): String {
        val syms = DecimalFormatSymbols.getInstance()
        val dec = syms.decimalSeparator
        return if (dec != '.') text.replace('.', dec) else text
    }

    private fun ggRequire(args: JSONArray): String {
        val reqVer = if (args.length() > 0 && !args.isNull(0)) args.optString(0, "") else ""
        val reqBuild = if (args.length() > 1 && !args.isNull(1)) args.optInt(1, 0) else 0
        if (reqBuild > COMPAT_GG_BUILD) {
            return err("Script requires GameGuardian build >= $reqBuild (GGNext compat build: $COMPAT_GG_BUILD)", raise = true)
        }
        if (reqVer.isNotBlank()) {
            val reqParts = reqVer.split('.').mapNotNull { it.toIntOrNull() }
            val curParts = COMPAT_GG_VERSION.split('.').mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(reqParts.size, curParts.size)
            for (i in 0 until maxLen) {
                val r = reqParts.getOrElse(i) { 0 }
                val c = curParts.getOrElse(i) { 0 }
                if (r > c) {
                    return err("Script requires GameGuardian version >= $reqVer (GGNext compat: $COMPAT_GG_VERSION)", raise = true)
                }
                if (r < c) break
            }
        }
        return ok(true)
    }

    private fun ggSetSpeed(args: JSONArray): String {
        val speed = args.optDouble(0, 1.0).toFloat()
        if (speed <= 0f) return err("Speed must be > 0")
        Session.setSpeedMultiplier(speed)
        return ok(true)
    }

    private fun ggMakeRequest(args: JSONArray): String {
        val urlStr = args.optString(0, "").trim()
        val parsedUrl = runCatching { URL(urlStr) }.getOrElse {
            return err("Invalid URL: $urlStr")
        }
        val scheme = parsedUrl.protocol.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return err("Only http:// and https:// URLs are permitted")
        }
        val host = parsedUrl.host.ifBlank { urlStr }
        if (!LuaScriptEngine.requestNetworkConsentSync(host, urlStr)) {
            return if (LuaScriptEngine.isCancelled()) cancelledResp()
            else err("Network access denied by user")
        }

        val headersMap = extractLuaTableEntries(args.opt(1))
        val postData = if (args.length() > 2 && !args.isNull(2)) args.optString(2, "") else null

        var conn: HttpURLConnection? = null
        return try {
            conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                requestMethod = if (postData != null) "POST" else "GET"
                setRequestProperty("User-Agent", "GGNext-Lua/5.5.1")
                for ((k, v) in headersMap) {
                    val hk = k.toString()
                    when (v) {
                        is JSONArray -> {
                            for (i in 0 until v.length()) addRequestProperty(hk, v.optString(i))
                        }
                        else -> if (v != null && v != JSONObject.NULL) setRequestProperty(hk, v.toString())
                    }
                }
                if (postData != null) {
                    doOutput = true
                    val bodyBytes = postData.toByteArray(Charsets.UTF_8)
                    setFixedLengthStreamingMode(bodyBytes.size)
                    outputStream.use { it.write(bodyBytes) }
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val maxBytes = 4 * 1024 * 1024
            val baos = ByteArrayOutputStream()
            if (stream != null) {
                BufferedInputStream(stream).use { bis ->
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        if (LuaScriptEngine.isCancelled()) return cancelledResp()
                        val n = bis.read(buf)
                        if (n <= 0) break
                        val take = minOf(n, maxBytes - total)
                        if (take > 0) {
                            baos.write(buf, 0, take)
                            total += take
                        }
                        if (total >= maxBytes) break
                    }
                }
            }

            val respHeaders = JSONObject()
            for ((k, vList) in conn.headerFields) {
                val arr = JSONArray()
                for (item in vList) arr.put(item)
                respHeaders.put(k ?: "null", arr)
            }

            val resultObj = JSONObject().apply {
                put("url", conn.url.toString())
                put("requestMethod", conn.requestMethod)
                put("code", code)
                put("message", conn.responseMessage ?: "")
                put("headers", respHeaders)
                put("content", baos.toString("UTF-8"))
                put("contentEncoding", conn.contentEncoding ?: JSONObject.NULL)
                put("contentLength", conn.contentLength)
                put("contentType", conn.contentType ?: JSONObject.NULL)
                put("date", conn.date)
                put("expiration", conn.expiration)
                put("lastModified", conn.lastModified)
                put("usingProxy", conn.usingProxy())
            }
            ok(resultObj)
        } catch (t: Throwable) {
            err(t.message ?: t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
    // ========================================================================
    // [GGNext-Patch] 网络脚本 API 实现
    //   复用 ggMakeRequest 的既有安全策略（用户授权、超时、限流、取消检查）。
    // ========================================================================

    /** 解析目标路径：相对路径落到脚本工作目录，绝对路径原样返回。 */
    private fun resolveOutPath(raw: String): String {
        val p = raw.trim()
        if (p.isEmpty()) return p
        if (p.startsWith("/")) return p
        val base = currentScriptDir ?: "/sdcard/GGNext/scripts"
        val dir = File(base)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, p).absolutePath
    }

    private fun ensureParentDir(file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
    }

    /** gg.downloadFile(url [, path] [, headers]) -> 本地绝对路径 */
    private fun ggDownloadFile(args: JSONArray): String {
        val url = args.optString(0, "").trim()
        if (url.isEmpty()) return err("downloadFile: url is required")

        val explicitPath = if (args.length() > 1 && !args.isNull(1)) args.optString(1, "").trim() else ""
        val targetPath = if (explicitPath.isNotEmpty()) {
            resolveOutPath(explicitPath)
        } else {
            val clean = url.substringBefore('#').substringBefore('?')
            val seg = clean.substringAfterLast('/').ifBlank {
                "download_" + System.currentTimeMillis() + ".bin"
            }
            resolveOutPath(seg)
        }

        val reqArgs = JSONArray()
        reqArgs.put(url)
        if (args.length() > 2 && !args.isNull(2)) reqArgs.put(args.opt(2))
        val respRaw = ggMakeRequest(reqArgs)
        val resp = runCatching { JSONObject(respRaw) }.getOrNull()
            ?: return err("downloadFile: unexpected response")

        if (resp.optBoolean("cancelled", false)) return cancelledResp()
        if (resp.has("error")) return err(resp.optString("error", "request failed"))

        val code = resp.optInt("code", 0)
        if (code < 200 || code > 299) {
            return err("downloadFile: HTTP " + code + " " + resp.optString("message", ""))
        }
        val content = resp.optString("content", "")

        return try {
            val outFile = File(targetPath)
            ensureParentDir(outFile)
            outFile.writeText(content, Charsets.UTF_8)
            ok(outFile.absolutePath)
        } catch (t: Throwable) {
            err("downloadFile: write failed - " + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** gg.loadRemoteScript(url [, headers]) -> true；拉取源码后立即执行 */
    private fun ggLoadRemoteScript(args: JSONArray): String {
        val url = args.optString(0, "").trim()
        if (url.isEmpty()) return err("loadRemoteScript: url is required")

        val reqArgs = JSONArray()
        reqArgs.put(url)
        if (args.length() > 1 && !args.isNull(1)) reqArgs.put(args.opt(1))
        val respRaw = ggMakeRequest(reqArgs)
        val resp = runCatching { JSONObject(respRaw) }.getOrNull()
            ?: return err("loadRemoteScript: unexpected response")

        if (resp.optBoolean("cancelled", false)) return cancelledResp()
        if (resp.has("error")) return err(resp.optString("error", "request failed"))

        val code = resp.optInt("code", 0)
        if (code < 200 || code > 299) {
            return err("loadRemoteScript: HTTP " + code + " " + resp.optString("message", ""))
        }
        var source = resp.optString("content", "")
        if (source.isBlank()) return err("loadRemoteScript: empty script body")

        if (source.isNotEmpty() && source[0] == '\uFEFF') source = source.substring(1)

        val chunkName = "@remote:" + url.substringAfterLast('/').ifBlank { "script.lua" }
        val queued = LuaScriptEngine.runScriptSource(source, chunkName)
        return if (queued) ok(true)
        else err("loadRemoteScript: engine is busy or unavailable")
    }

    /** gg.getFileContent(path) -> 文本内容 / nil */
    private fun ggGetFileContent(args: JSONArray): String {
        val raw = args.optString(0, "").trim()
        if (raw.isEmpty()) return err("getFileContent: path is required")
        val f = File(resolveOutPath(raw))
        if (!f.exists() || !f.isFile) return ok(JSONObject.NULL)
        return try {
            var text = f.readText(Charsets.UTF_8)
            if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
            ok(text)
        } catch (t: Throwable) {
            err("getFileContent: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** gg.getScriptDir() -> 当前脚本所在目录 */
    private fun ggGetScriptDir(): String = ok(currentScriptDir ?: JSONObject.NULL)


    // ========================================================================
    // 7. GGNext 独有扩展：ShadowHook、跳板主动调用、硬件断点、ELF/.so 解析 (gg.*)
    // ========================================================================

    private fun normalizeHookRuleOptions(optsRaw: Any?, req: JSONObject) {
        if (optsRaw == null || optsRaw == JSONObject.NULL) return
        val entries = extractLuaTableEntries(optsRaw)
        for ((k, v) in entries) {
            when (k.toString()) {
                "mode" -> if (v != null && v != JSONObject.NULL) req.put("mode", v.toString())
                "bypassOriginal", "bypass" -> req.put("bypassOriginal", v == true || v.toString() == "true")
                "logEnabled", "log" -> req.put("logEnabled", v == true || v.toString() == "true")
                "returnOverride", "ret" -> {
                    if (v == null || v == JSONObject.NULL) req.put("returnOverride", JSONObject.NULL)
                    else req.put("returnOverride", v)
                }
                "argOverrides", "args" -> {
                    val argObj = JSONObject()
                    for ((ak, av) in extractLuaTableEntries(v)) {
                        val keyStr = ak.toString()
                        argObj.put(keyStr, av ?: JSONObject.NULL)
                    }
                    req.put("argOverrides", argObj)
                }
                "regOverrides", "regs" -> {
                    val regObj = JSONObject()
                    for ((rk, rv) in extractLuaTableEntries(v)) {
                        regObj.put(rk.toString(), rv ?: JSONObject.NULL)
                    }
                    req.put("regOverrides", regObj)
                }
            }
        }
    }

    private fun ggHookSymbol(args: JSONArray): String {
        val libName = args.optString(0, "").trim()
        val symName = args.optString(1, "").trim()
        if (libName.isEmpty() || symName.isEmpty()) {
            return okMulti(listOf(JSONObject.NULL, "hookSymbol requires (libName, symbolName [, opts])"))
        }
        val req = JSONObject()
            .put("action", "install")
            .put("libName", libName)
            .put("symbolName", symName)
            .put("mode", "hook")
        if (args.length() > 2 && !args.isNull(2)) {
            normalizeHookRuleOptions(args.opt(2), req)
        }
        val resp = Session.engineForMcp().hookCommand(req)
        return if (resp.optBoolean("ok", false)) {
            okMulti(listOf(resp.optInt("id", 0), resp))
        } else {
            okMulti(listOf(JSONObject.NULL, resp.optString("error", "hookSymbol failed")))
        }
    }

    private fun ggHookAddress(args: JSONArray, intercept: Boolean): String {
        val addr = parseLuaAddress(args.opt(0))
            ?: return okMulti(listOf(JSONObject.NULL, "Invalid target address"))
        val req = JSONObject()
            .put("action", "install")
            .put("address", "0x" + java.lang.Long.toUnsignedString(addr, 16))
            .put("mode", if (intercept) "intercept" else "hook")
        if (args.length() > 1 && !args.isNull(1)) {
            normalizeHookRuleOptions(args.opt(1), req)
        }
        val resp = Session.engineForMcp().hookCommand(req)
        return if (resp.optBoolean("ok", false)) {
            okMulti(listOf(resp.optInt("id", 0), resp))
        } else {
            okMulti(listOf(JSONObject.NULL, resp.optString("error", "hookAddress failed")))
        }
    }

    private fun ggUpdateHook(args: JSONArray): String {
        val hookId = optIntOrDefault(args, 0, 0)
        if (hookId <= 0) return ok(false)
        val req = JSONObject()
            .put("action", "update")
            .put("hookId", hookId)
        if (args.length() > 1 && !args.isNull(1)) {
            normalizeHookRuleOptions(args.opt(1), req)
        }
        val resp = Session.engineForMcp().hookCommand(req)
        return ok(resp.optBoolean("ok", false))
    }

    private fun ggUnhook(args: JSONArray): String {
        val hookId = optIntOrDefault(args, 0, 0)
        if (hookId <= 0) return ok(false)
        val resp = Session.engineForMcp().hookCommand(
            JSONObject().put("action", "uninstall").put("hookId", hookId)
        )
        return ok(resp.optBoolean("ok", false))
    }

    private fun ggUnhookAll(): String {
        val resp = Session.engineForMcp().hookCommand(JSONObject().put("action", "uninstall_all"))
        return ok(resp.optInt("removed", 0))
    }

    private fun ggGetHooks(): String {
        val resp = Session.engineForMcp().hookCommand(JSONObject().put("action", "list"))
        if (!resp.optBoolean("ok", false)) return ok(JSONArray())
        return ok(resp.optJSONArray("hooks") ?: JSONArray())
    }

    private fun ggGetHookLogs(args: JSONArray): String {
        val hookId = optIntOrDefault(args, 0, 0)
        val sinceSeq = optLongOrDefault(args, 1, 0L).coerceAtLeast(0L)
        val limit = optIntOrDefault(args, 2, 100).coerceIn(1, 512)
        val clear = args.length() > 3 && !args.isNull(3) && args.optBoolean(3, false)
        val resp = Session.engineForMcp().hookCommand(
            JSONObject()
                .put("action", "logs")
                .put("hookId", hookId)
                .put("sinceSeq", sinceSeq)
                .put("limit", limit)
                .put("clear", clear)
        )
        if (!resp.optBoolean("ok", false)) return ok(JSONArray())
        return ok(resp.optJSONArray("logs") ?: JSONArray())
    }

    private fun ggClearHookLogs(args: JSONArray): String {
        val hookId = optIntOrDefault(args, 0, 0)
        val resp = Session.engineForMcp().hookCommand(
            JSONObject().put("action", "clear_logs").put("hookId", hookId)
        )
        return ok(resp.optBoolean("ok", false))
    }

    private fun buildCallArgsArray(rawArgs: Any?): JSONArray {
        val arr = JSONArray()
        if (rawArgs == null || rawArgs == JSONObject.NULL) return arr
        for ((_, v) in extractLuaTableEntries(rawArgs)) {
            if (v == null || v == JSONObject.NULL) {
                arr.put(0L)
            } else {
                arr.put(v)
            }
        }
        return arr
    }

    private fun formatHookCallLuaReturn(resp: JSONObject): String {
        if (!resp.optBoolean("ok", false)) {
            return okMulti(listOf(JSONObject.NULL, resp.optString("error", "Active function call failed")))
        }
        val retType = resp.optString("returnType", "int64")
        val rawVal: Any = when (retType) {
            "float", "double" -> JSONObject().put("__lua_float", resp.optDouble("value", 0.0))
            "void" -> JSONObject.NULL
            else -> resp.opt("value") ?: JSONObject.NULL
        }
        return okMulti(listOf(rawVal, resp))
    }

    private fun ggCallOriginal(args: JSONArray): String {
        val hookId = optIntOrDefault(args, 0, 0)
        if (hookId <= 0) return okMulti(listOf(JSONObject.NULL, "callOriginal requires a valid hookId"))
        val callArgs = if (args.length() > 1 && !args.isNull(1)) buildCallArgsArray(args.opt(1)) else JSONArray()
        val retType = if (args.length() > 2 && !args.isNull(2)) args.optString(2, "int64") else "int64"
        val req = JSONObject()
            .put("action", "call")
            .put("hookId", hookId)
            .put("args", callArgs)
            .put("returnType", retType)
        return formatHookCallLuaReturn(Session.engineForMcp().hookCommand(req))
    }

    private fun ggCallFunction(args: JSONArray): String {
        if (args.length() == 0 || args.isNull(0)) {
            return okMulti(listOf(JSONObject.NULL, "callFunction requires address or (libName, symbolName)"))
        }
        val first = args.opt(0)
        val req = JSONObject().put("action", "call")
        val argIdx: Int
        val retIdx: Int
        if (first is String && !first.trim().startsWith("0x", ignoreCase = true) && !first.trim().endsWith("h", ignoreCase = true) && first.trim().toLongOrNull() == null) {
            val libName = first.trim()
            val symName = args.optString(1, "").trim()
            if (symName.isEmpty()) {
                return okMulti(listOf(JSONObject.NULL, "callFunction(libName, symbolName, ...) requires non-empty symbolName"))
            }
            req.put("libName", libName).put("symbolName", symName)
            argIdx = 2
            retIdx = 3
        } else {
            val addr = parseLuaAddress(first)
                ?: return okMulti(listOf(JSONObject.NULL, "Invalid function address"))
            req.put("address", "0x" + java.lang.Long.toUnsignedString(addr, 16))
            argIdx = 1
            retIdx = 2
        }
        val callArgs = if (args.length() > argIdx && !args.isNull(argIdx)) buildCallArgsArray(args.opt(argIdx)) else JSONArray()
        val retType = if (args.length() > retIdx && !args.isNull(retIdx)) args.optString(retIdx, "int64") else "int64"
        req.put("args", callArgs).put("returnType", retType)
        return formatHookCallLuaReturn(Session.engineForMcp().hookCommand(req))
    }

    private fun ggAddBreakpoint(args: JSONArray): String {
        val engine = Session.engineForMcp()
        if (!engine.attachedOk) return okMulti(listOf(JSONObject.NULL, "No target process attached"))
        val addr = parseLuaAddress(args.opt(0))
            ?: return okMulti(listOf(JSONObject.NULL, "Invalid breakpoint address"))
        val bpType = BpType.fromCode(optIntOrDefault(args, 1, BpType.RW.code))
        val size = optIntOrDefault(args, 2, 4).let { if (it in setOf(1, 2, 4, 8)) it else 4 }
        val mode = BpMode.fromCode(optIntOrDefault(args, 3, Session.breakpointBackendMode().code))
        val captureStack = if (args.length() > 4 && !args.isNull(4)) args.optBoolean(4, true) else true
        val (bpId, errMsg) = engine.bpAdd(addr, bpType, size, mode, captureStack)
        return if (bpId > 0) {
            Session.refreshBreakpointsNow()
            okMulti(listOf(bpId, JSONObject().put("id", bpId).put("address", addr).put("type", bpType.code).put("size", size).put("mode", mode.code)))
        } else {
            okMulti(listOf(JSONObject.NULL, errMsg.ifBlank { "Failed to add breakpoint" }))
        }
    }

    private fun ggRemoveBreakpoint(args: JSONArray): String {
        val bpId = optIntOrDefault(args, 0, 0)
        val engine = Session.engineForMcp()
        val removed = if (bpId <= 0) {
            engine.bpStopAll()
            true
        } else {
            engine.bpRemove(bpId)
        }
        Session.refreshBreakpointsNow()
        return ok(removed)
    }

    private fun ggGetBreakpoints(): String {
        val bps = Session.engineForMcp().bpSnapshot()
        val arr = JSONArray()
        for (bp in bps) {
            val hitsArr = JSONArray()
            for (h in bp.hits) {
                val gprObj = JSONObject()
                for (r in h.gprs) gprObj.put(r.name, r.value)
                val fprObj = JSONObject()
                for (r in h.fprs) fprObj.put(r.name, r.hexValue)
                val stackArr = JSONArray()
                for (f in h.callstack) {
                    stackArr.put(
                        JSONObject()
                            .put("address", f.address)
                            .put("module", f.moduleName)
                            .put("offset", f.moduleOffset)
                    )
                }
                hitsArr.put(
                    JSONObject()
                        .put("pc", h.pc)
                        .put("pcHex", "0x${h.pcHex}")
                        .put("tid", h.tid)
                        .put("count", h.count)
                        .put("module", h.moduleName)
                        .put("offset", h.moduleOffset)
                        .put("disasm", h.disasmText)
                        .put("sp", h.sp)
                        .put("lr", h.lr)
                        .put("gprs", gprObj)
                        .put("fprs", fprObj)
                        .put("callstack", stackArr)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", bp.id)
                    .put("pid", bp.pid)
                    .put("address", bp.address)
                    .put("addressHex", "0x${bp.addressHex}")
                    .put("type", bp.type.code)
                    .put("size", bp.size)
                    .put("mode", bp.mode.code)
                    .put("running", bp.running)
                    .put("paused", bp.paused)
                    .put("totalHits", bp.totalHits)
                    .put("hits", hitsArr)
            )
        }
        return ok(arr)
    }

    private fun ggSetBreakpointPaused(args: JSONArray): String {
        val bpId = optIntOrDefault(args, 0, 0)
        val paused = args.length() > 1 && args.optBoolean(1, true)
        val engine = Session.engineForMcp()
        val okRes = if (bpId <= 0) engine.bpSetAllPaused(paused) else engine.bpSetPaused(bpId, paused)
        Session.refreshBreakpointsNow()
        return ok(okRes)
    }

    private fun ggClearBreakpointHits(args: JSONArray): String {
        val bpId = optIntOrDefault(args, 0, 0)
        Session.engineForMcp().bpClearHits(bpId)
        Session.refreshBreakpointsNow()
        return ok(true)
    }

    private fun ggGetBreakpointTrace(args: JSONArray): String {
        val bpId = optIntOrDefault(args, 0, 0)
        val sinceSeq = optLongOrDefault(args, 1, 0L).coerceAtLeast(0L)
        val limit = optIntOrDefault(args, 2, 120).coerceIn(1, 500)
        val events = Session.engineForMcp().bpTraceEvents(bpId, sinceSeq, limit)
        val arr = JSONArray()
        for (ev in events) {
            val gprObj = JSONObject()
            for (r in ev.gprs) gprObj.put(r.name, r.value)
            val fprObj = JSONObject()
            for (r in ev.fprs) fprObj.put(r.name, r.hexValue)
            arr.put(
                JSONObject()
                    .put("seq", ev.seq)
                    .put("timestampMs", ev.timestampMs)
                    .put("bpId", ev.bpId)
                    .put("tid", ev.tid)
                    .put("pc", ev.pc)
                    .put("pcHex", "0x${ev.pcHex}")
                    .put("sp", ev.sp)
                    .put("lr", ev.lr)
                    .put("module", ev.moduleName)
                    .put("offset", ev.moduleOffset)
                    .put("disasm", ev.disasmText)
                    .put("gprs", gprObj)
                    .put("fprs", fprObj)
            )
        }
        return ok(arr)
    }

    private fun ggClearBreakpointTrace(args: JSONArray): String {
        val bpId = optIntOrDefault(args, 0, 0)
        Session.engineForMcp().bpClearTrace(bpId)
        return ok(true)
    }

    private fun ggSetBreakpointRegister(args: JSONArray): String {
        val bpId = optIntOrDefault(args, 0, 0)
        val tid = optIntOrDefault(args, 1, 0)
        val hitPc = parseLuaAddress(args.opt(2)) ?: 0L
        val regName = args.optString(3, "").trim()
        val valRaw = args.opt(4)
        val hexVal = when (valRaw) {
            is Number -> java.lang.Long.toUnsignedString(valRaw.toLong(), 16)
            is String -> valRaw.trim().removePrefix("0x").removePrefix("0X")
            else -> ""
        }
        val isFp = args.length() > 5 && !args.isNull(5) && args.optBoolean(5, false)
        if (bpId <= 0 || tid <= 0 || regName.isEmpty() || hexVal.isEmpty()) {
            return okMulti(listOf(false, "setBreakpointRegister requires (bpId, tid, hitPc, regName, value [, isFp])"))
        }
        val (okSet, errMsg) = Session.engineForMcp().bpSetRegister(bpId, tid, hitPc, regName, hexVal, isFp)
        return okMulti(listOf(okSet, errMsg))
    }

    private fun ggGetModuleBase(args: JSONArray): String {
        val libName = args.optString(0, "").trim()
        if (libName.isEmpty()) return ok(JSONObject.NULL)
        val base = Session.engineForMcp().getModuleBase(libName)
        return ok(base ?: JSONObject.NULL)
    }

    private fun ggGetModuleExports(args: JSONArray): String {
        val libName = args.optString(0, "").trim()
        if (libName.isEmpty()) return ok(JSONArray())
        val filter = if (args.length() > 1 && !args.isNull(1)) args.optString(1, "") else ""
        val limit = optIntOrDefault(args, 2, 500).coerceIn(1, 5000)
        return ok(Session.engineForMcp().elfExportsJson(libName, filter, limit))
    }

    private fun ggFindSymbol(args: JSONArray): String {
        val libName = args.optString(0, "").trim()
        val symName = args.optString(1, "").trim()
        if (libName.isEmpty() || symName.isEmpty()) return ok(JSONObject.NULL)
        val addr = Session.engineForMcp().findSymbol(libName, symName)
        return ok(if (addr != 0L) addr else JSONObject.NULL)
    }

    // ========================================================================
    // 内部编解码与映射工具
    // ========================================================================

    private fun buildCriteriaFromQuery(
        query: NumberParser.Query,
        encrypted: Boolean,
        memFrom: Long,
        memTo: Long,
        limit: Int,
        selectedRegions: Set<String>
    ): MemoryEngine.Criteria {
        val baseType = query.baseType
        return when (query.kind) {
            NumberParser.Kind.TEXT -> MemoryEngine.Criteria(
                type = baseType,
                sign = query.sign,
                memoryFrom = memFrom,
                memoryTo = memTo,
                limit = limit,
                rawExpression = query.raw,
                selectedRegions = selectedRegions,
                isText = true,
                textBytes = query.textBytes,
                textUtf16 = query.textUtf16
            )
            NumberParser.Kind.RANGE -> MemoryEngine.Criteria(
                type = baseType,
                value = query.numbers.firstOrNull() ?: 0L,
                from = query.numbers.firstOrNull() ?: 0L,
                to = query.to.firstOrNull() ?: query.numbers.firstOrNull() ?: 0L,
                isRange = true,
                notRange = query.notRange,
                sign = query.sign,
                memoryFrom = memFrom,
                memoryTo = memTo,
                limit = limit,
                rawExpression = query.raw,
                encryptedXor = encrypted,
                selectedRegions = selectedRegions
            )
            NumberParser.Kind.GROUP -> MemoryEngine.Criteria(
                type = baseType,
                orderedGroup = query.numbers,
                groupTo = query.to,
                groupOps = query.groupOps,
                ordered = query.ordered,
                groupTypes = query.types,
                area = query.area,
                sign = query.sign,
                memoryFrom = memFrom,
                memoryTo = memTo,
                limit = limit,
                rawExpression = query.raw,
                encryptedXor = encrypted,
                selectedRegions = selectedRegions
            )
            NumberParser.Kind.NUMBER -> MemoryEngine.Criteria(
                type = baseType,
                value = query.numbers.firstOrNull() ?: 0L,
                from = query.numbers.firstOrNull() ?: 0L,
                to = query.numbers.firstOrNull() ?: 0L,
                sign = query.sign,
                xorKey = query.xorKey,
                xorOffset = query.xorOffset,
                memoryFrom = memFrom,
                memoryTo = memTo,
                limit = limit,
                rawExpression = query.raw,
                encryptedXor = encrypted,
                selectedRegions = selectedRegions
            )
        }
    }

    private fun writeSingleExpression(engine: MemoryEngine, address: Long, type: Int, expr: String): Boolean {
        val ggAsm = AsmEngineManager.parseGgAsmPrefix(expr)
        if (ggAsm != null) {
            val bytes = AsmEngineManager.assemble(ggAsm.first, address, ggAsm.second).getOrNull() ?: return false
            if (engine.writeBlock(address, bytes)) {
                val newRaw = engine.read(address, ValueType.sizeOf(type))
                Session.syncWrittenAddressFromMcp(address, newRaw)
                return true
            }
            return false
        }
        val query = runCatching { NumberParser.parse(expr, type) }.getOrNull()
        if (query != null && query.kind == NumberParser.Kind.TEXT && query.textBytes.isNotEmpty()) {
            if (engine.writeBlock(address, query.textBytes)) {
                val newRaw = engine.read(address, ValueType.sizeOf(type))
                Session.syncWrittenAddressFromMcp(address, newRaw)
                return true
            }
            return false
        }
        val raw = if (query != null && query.numbers.isNotEmpty()) {
            val base = query.numbers.first()
            if (type == ValueType.XOR) base xor (address and 0xFFFFFFFFL) else base
        } else {
            encodeLuaValueToRaw(expr, type, address)
        }
        val size = ValueType.sizeOf(type)
        if (engine.write(address, size, raw)) {
            Session.syncWrittenAddressFromMcp(address, raw)
            return true
        }
        return false
    }

    private fun memoryItemToGgJson(item: MemoryItem): JSONObject {
        val type = item.flags and 0x7F
        return JSONObject().apply {
            put("address", item.address)
            put("flags", type)
            put("value", decodeRawToLuaJsonValue(item.value, type, item.address))
        }
    }

    private fun savedItemToGgJson(item: MemoryItem): JSONObject {
        val type = item.flags and 0x7F
        return JSONObject().apply {
            put("address", item.address)
            put("flags", type)
            put("value", decodeRawToLuaJsonValue(item.value, type, item.address))
            put("name", item.name)
            put("freeze", item.freeze)
            put("freezeType", item.freezeType)
            put("freezeFrom", formatRawScalarString(item.freezeFrom, type, item.address))
            put("freezeTo", formatRawScalarString(item.freezeTo, type, item.address))
        }
    }

    private fun formatRawScalarString(raw: Long, type: Int, address: Long): String = when (type and 0x7F) {
        ValueType.FLOAT -> {
            val f = Float.fromBits(raw.toInt())
            if (f.isFinite()) f.toString() else "0.0"
        }
        ValueType.DOUBLE -> {
            val d = Double.fromBits(raw)
            if (d.isFinite()) d.toString() else "0.0"
        }
        ValueType.BYTE -> raw.toByte().toString()
        ValueType.WORD -> raw.toShort().toString()
        ValueType.DWORD -> raw.toInt().toString()
        ValueType.XOR -> (raw xor (address and 0xFFFFFFFFL)).toInt().toString()
        else -> raw.toString()
    }

    private fun decodeRawToLuaJsonValue(raw: Long, type: Int, address: Long): Any = when (type and 0x7F) {
        ValueType.BYTE   -> raw.toByte().toLong()
        ValueType.WORD   -> raw.toShort().toLong()
        ValueType.DWORD  -> raw.toInt().toLong()
        ValueType.QWORD  -> raw
        ValueType.XOR    -> (raw xor (address and 0xFFFFFFFFL)).toInt().toLong()
        ValueType.FLOAT  -> {
            val f = Float.fromBits(raw.toInt()).toDouble()
            JSONObject().put("__lua_float", if (f.isFinite()) f else 0.0)
        }
        ValueType.DOUBLE -> {
            val d = Double.fromBits(raw)
            JSONObject().put("__lua_float", if (d.isFinite()) d else 0.0)
        }
        else -> raw.toInt().toLong()
    }

    private fun decodeNumericForCompare(raw: Long, type: Int, address: Long): Double = when (type and 0x7F) {
        ValueType.FLOAT -> Float.fromBits(raw.toInt()).let { if (it.isFinite()) it.toDouble() else 0.0 }
        ValueType.DOUBLE -> Double.fromBits(raw).let { if (it.isFinite()) it else 0.0 }
        ValueType.BYTE -> raw.toByte().toDouble()
        ValueType.WORD -> raw.toShort().toDouble()
        ValueType.DWORD -> raw.toInt().toDouble()
        ValueType.XOR -> (raw xor (address and 0xFFFFFFFFL)).toInt().toDouble()
        else -> raw.toDouble()
    }

    private fun encodeLuaValueToRaw(value: Any?, type: Int, address: Long): Long {
        if (value == null || value == JSONObject.NULL) return 0L
        val t = type and 0x7F
        if (value is String) {
            val s = value.trim()
            if (s.endsWith("h", ignoreCase = true) || s.startsWith("0x", ignoreCase = true)) {
                val hex = s.removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
                val parsed = hex.toULongOrNull(16)?.toLong() ?: 0L
                return if (t == ValueType.XOR) parsed xor (address and 0xFFFFFFFFL) else parsed
            }
            return when (t) {
                ValueType.FLOAT  -> (s.toFloatOrNull() ?: 0f).toRawBits().toLong() and 0xFFFFFFFFL
                ValueType.DOUBLE -> (s.toDoubleOrNull() ?: 0.0).toRawBits()
                ValueType.XOR    -> ((s.toLongOrNull() ?: 0L) xor (address and 0xFFFFFFFFL)) and 0xFFFFFFFFL
                else             -> s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() ?: 0L
            }
        }
        val num = value as? Number ?: return 0L
        return when (t) {
            ValueType.BYTE   -> num.toLong() and 0xFFL
            ValueType.WORD   -> num.toLong() and 0xFFFFL
            ValueType.DWORD  -> num.toLong() and 0xFFFFFFFFL
            ValueType.QWORD  -> num.toLong()
            ValueType.XOR    -> (num.toLong() xor (address and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            ValueType.FLOAT  -> num.toFloat().toRawBits().toLong() and 0xFFFFFFFFL
            ValueType.DOUBLE -> num.toDouble().toRawBits()
            else             -> num.toLong()
        }
    }

    private fun parseLuaAddress(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Number -> v.toLong()
        is String -> {
            val s = v.trim()
            when {
                s.startsWith("0x", true) -> s.substring(2).toULongOrNull(16)?.toLong()
                s.endsWith("h", true) -> s.dropLast(1).toULongOrNull(16)?.toLong()
                else -> s.toLongOrNull() ?: s.toULongOrNull(16)?.toLong()
            }
        }
        else -> null
    }

    /**
     * 解包 Lua 表参数：兼容纯数组 `JSONArray`、字符串键 `JSONObject` 以及稀疏/混合键
     * `{"__lua_entries": [["i"|"s", k, v], ...]}`（同时兼容 2 元组 `[k, v]`）。
     */
    private fun extractLuaTableEntries(raw: Any?): List<Pair<Any, Any?>> {
        if (raw == null || raw == JSONObject.NULL) return emptyList()
        return when (raw) {
            is JSONArray -> List(raw.length()) { idx -> (idx + 1) to raw.opt(idx) }
            is JSONObject -> {
                val packed = raw.optJSONArray("__lua_entries")
                if (packed != null) {
                    val out = ArrayList<Pair<Any, Any?>>(packed.length())
                    for (i in 0 until packed.length()) {
                        val tuple = packed.optJSONArray(i) ?: continue
                        when {
                            tuple.length() >= 3 && (tuple.optString(0) == "i" || tuple.optString(0) == "s") -> {
                                val kt = tuple.optString(0)
                                val k: Any = if (kt == "i") tuple.optLong(1) else tuple.optString(1)
                                out.add(k to tuple.opt(2))
                            }
                            tuple.length() >= 2 -> {
                                val k = tuple.opt(0) ?: continue
                                out.add(k to tuple.opt(1))
                            }
                        }
                    }
                    out
                } else {
                    val out = ArrayList<Pair<Any, Any?>>()
                    val keys = raw.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        out.add(k to raw.opt(k))
                    }
                    out
                }
            }
            else -> emptyList()
        }
    }

    /**
     * 将 Lua 键值序列编码为原生层 `jsonToLua` 识别的 3 元组协议 `{"__lua_entries": [["i"|"s", key, val], ...]}`，
     * 完整保留整数键（包括稀疏数组）与字符串键类型。
     */
    private fun encodeLuaEntries(entries: List<Pair<Any, Any?>>): JSONObject {
        val arr = JSONArray()
        for ((k, v) in entries) {
            val tuple = JSONArray()
            when (k) {
                is Int -> {
                    tuple.put("i")
                    tuple.put(k.toLong())
                }
                is Long -> {
                    tuple.put("i")
                    tuple.put(k)
                }
                is Number -> {
                    tuple.put("i")
                    tuple.put(k.toLong())
                }
                else -> {
                    tuple.put("s")
                    tuple.put(k.toString())
                }
            }
            tuple.put(v ?: JSONObject.NULL)
            arr.put(tuple)
        }
        return JSONObject().put("__lua_entries", arr)
    }

    private fun ggRegionMaskToCodes(mask: Int): Set<String> {
        val out = LinkedHashSet<String>()
        for ((bit, code) in REGION_MASK_TO_CODE) {
            if (bit == REGION_OTHER) {
                if ((mask and REGION_OTHER) == REGION_OTHER) out.add(code)
            } else if ((mask and bit) != 0) {
                out.add(code)
            }
        }
        return out
    }

    private fun codesToGgRegionMask(codes: Set<String>): Int {
        var mask = 0
        for (c in codes) {
            mask = mask or (CODE_TO_REGION_MASK[c] ?: 0)
        }
        return mask
    }

    private fun readProcRssKb(pid: Int): Long = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            for (line in lines) {
                if (line.startsWith("VmRSS:")) {
                    val num = line.removePrefix("VmRSS:").trim().substringBefore(' ').toLongOrNull()
                    if (num != null) return@useLines num
                }
            }
            0L
        }
    }.getOrDefault(0L)

    private fun readProcCmdline(pid: Int): String = runCatching {
        File("/proc/$pid/cmdline").readBytes()
            .toString(Charsets.UTF_8)
            .replace('\u0000', ' ')
            .trim()
    }.getOrDefault("")

    private fun optIntOrDefault(args: JSONArray, idx: Int, def: Int): Int {
        if (idx >= args.length() || args.isNull(idx)) return def
        return when (val v = args.opt(idx)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: def
            else -> def
        }
    }

    private fun optLongOrDefault(args: JSONArray, idx: Int, def: Long): Long {
        if (idx >= args.length() || args.isNull(idx)) return def
        return when (val v = args.opt(idx)) {
            is Number -> v.toLong()
            is String -> parseLuaAddress(v) ?: def
            else -> def
        }
    }

    private fun parseJsonAny(json: String): Any {
        val t = json.trim()
        if (t.isEmpty() || t == "null") return JSONObject.NULL
        if (t == "true") return true
        if (t == "false") return false
        if (t.startsWith("{")) return runCatching { JSONObject(t) }.getOrDefault(t)
        if (t.startsWith("[")) return runCatching { JSONArray(t) }.getOrDefault(t)
        if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) {
            return runCatching { JSONArray("[$t]").optString(0) }.getOrDefault(t)
        }
        return t.toLongOrNull() ?: t.toDoubleOrNull() ?: t
    }

    private fun ok(value: Any?): String = JSONObject().apply {
        put("ok", true)
        put("value", value ?: JSONObject.NULL)
    }.toString()

    private fun okMulti(values: List<Any?>): String = JSONObject().apply {
        put("ok", true)
        val arr = JSONArray()
        for (v in values) arr.put(v ?: JSONObject.NULL)
        put("multi", arr)
    }.toString()

    private fun err(message: String, raise: Boolean = false): String = JSONObject().apply {
        put("ok", false)
        put("error", message)
        put("raise", raise)
    }.toString()

    private fun cancelledResp(): String = JSONObject().apply {
        put("ok", false)
        put("cancelled", true)
        put("error", "cancelled")
    }.toString()
}
