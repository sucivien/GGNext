package com.littlecodesauce.ggnext.core

import android.app.Application
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.data.HistoryPersistence
import com.littlecodesauce.ggnext.data.LocalBlobStore
import com.littlecodesauce.ggnext.data.ProcessScanner
import com.littlecodesauce.ggnext.data.SavedListFileCodec
import com.littlecodesauce.ggnext.data.ScriptHistoryEntry
import com.littlecodesauce.ggnext.data.SearchHistory
import com.littlecodesauce.ggnext.data.SettingKeys
import com.littlecodesauce.ggnext.data.SettingsRepository
import com.littlecodesauce.ggnext.data.SettingsSpecs
import com.littlecodesauce.ggnext.engine.AsmEngineManager
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.engine.MemoryEngine
import com.littlecodesauce.ggnext.engine.NumberParser
import com.littlecodesauce.ggnext.engine.Shell
import com.littlecodesauce.ggnext.model.AsmArch
import com.littlecodesauce.ggnext.model.AsmPackStatus
import com.littlecodesauce.ggnext.model.BpMode
import com.littlecodesauce.ggnext.model.BpSession
import com.littlecodesauce.ggnext.model.BpTraceEvent
import com.littlecodesauce.ggnext.model.BpType
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.MemoryRegion
import com.littlecodesauce.ggnext.model.TargetProcess
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.model.DEFAULT_PINNED_TOOLS
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_DEBUG
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_MEMORY
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_SAVED
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_SEARCH
import com.littlecodesauce.ggnext.ui.model.RegionCatalog
import com.littlecodesauce.ggnext.ui.model.ToolId
import com.littlecodesauce.ggnext.ui.theme.DarkMode
import com.littlecodesauce.ggnext.ui.theme.ThemePreset
import com.littlecodesauce.ggnext.ui.theme.UiMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** 五个导航页 */
enum class NextTab(val label: String) {
    SETTINGS("设置"),
    SEARCH_LIST("搜索列表"),
    SAVED_LIST("保存列表"),
    MEMORY("内存"),
    DEBUG("调试")
}

/** 主页底部四个导航页（主页 / 扩展 / 文档 / 设置） */
enum class HomeTab(val label: String) {
    HOME("主页"),
    EXTENSIONS("扩展"),
    DOCS("文档"),
    SETTINGS("设置")
}

/** 内存编辑器多级指针跳转路径节点 */
data class PointerHop(
    val address: Long,
    val sourceAddress: Long? = null
)

/** 工作模式枚举：Root 模式 与 免 Root 模式（共享 UID / 虚拟空间沙盒） */
enum class WorkMode(val code: String, val title: String, val desc: String) {
    ROOT("root", "Root 模式", "通过 Root 权限进行全系统跨进程读写（支持所有已运行应用与游戏）"),
    NO_ROOT("no_root", "免 Root 模式", "针对虚拟空间/双开沙箱（所有应用共享相同 UID），无需 Root 跨进程读写")
}

enum class Sheet {
    NONE,
    PROCESS,
    SEARCH,
    FUZZY,
    MASK_SEARCH,
    POINTER_SEARCH,
    FILTER,
    REGION,
    VALUE_FORMAT,
    EDITOR,
    OFFSET,
    CALC_OFFSET,
    REMOVE,
    TOOLBAR_ORDER,
    TOOLBAR_PICK,
    GOTO,
    MODULE_LIST,
    SCRIPT,
    FILE_BROWSER,
    SPEED,
    HISTORY,
    FUNCTION,
    LOG,
    OVERLAY,
    HELP,
    ABOUT,
    CHANGELOG,
    LANGUAGE,
    APPEARANCE,
    CONTEXT,
    TYPE_PICK,
    ADDRESS_EDIT,
    FILE_MANAGER,
    COPY_MEMORY,
    COPY_GROUP,
    DUMP,
    ALLOCATE,
    BREAKPOINT,
    WRITE_ASM
}

/** 列表文件操作模式：严格区分只读载入与只写保存/导出 */
enum class FileSheetMode {
    LOAD,
    SAVE,
    EXPORT
}

/** 文件浏览器打开参数与回调目标 */
enum class FileBrowserTarget {
    SCRIPT,
    FILE_MANAGER,
    DUMP,
    FONT,
    ASM_PACK
}

data class FileBrowserConfig(
    val currentDir: String = "/sdcard/Download",
    val selectDirectory: Boolean = false,
    val returnSheet: Sheet = Sheet.SCRIPT,
    val target: FileBrowserTarget = FileBrowserTarget.SCRIPT
)

/** 独立高频搜索进度状态（脱离 Ui，避免每 45ms 触发全界面重组） */
data class SearchProgressUi(
    val active: Boolean = false,
    val text: String = "",
    val progress: Float = 0f,
    val foundSoFar: Long = 0L,
    val etaSeconds: Int = -1,
    val scannedMb: Float = 0f,
    val totalMb: Float = 0f
)

/** 搜索面板输入 */
data class SearchInput(
    val text: String = "",
    val type: Int = ValueType.DWORD,
    val sign: Int = ValueType.SIGN_EQUAL,
    val mask: String = "FFFFFFFFFFFFFFFF",
    val offset: String = "0",
    val offsetHex: Boolean = true,
    val rangeMode: Int = 0, // 0=全部内存, 1=指定地址区间, 2=附近
    val rangeFrom: String = "00000000",
    val rangeTo: String = "FFFFFFFFFFFFFFFF",
    val nearbyAddress: String = "",
    val nearbyBefore: Boolean = true,
    val nearbyAfter: Boolean = true,
    val nearbyDistance: String = "500",
    val pointerType: Int = ValueType.QWORD,
    val encrypted: Boolean = false,
    val expanded: Boolean = false,
    val keyboardVisible: Boolean = true
)

/** 模糊搜索状态 */
data class FuzzyState(
    val active: Boolean = false,
    val type: Int = ValueType.DWORD,
    val rangeMode: Int = 0,
    val rangeFrom: String = "00000000",
    val rangeTo: String = "FFFFFFFFFFFFFFFF",
    val nearbyAddress: String = "",
    val nearbyBefore: Boolean = true,
    val nearbyAfter: Boolean = true,
    val nearbyDistance: String = "500",
    val extended: Boolean = false,
    val deltaText: String = "",
    val onlyNth: String = "",
    val keyboardVisible: Boolean = true
) {
    val rangeLabel: String
        get() = when (rangeMode) {
            1 -> "指定地址区间"
            2 -> "附近:"
            else -> "全部内存"
        }
}

/** 结果过滤/筛选状态 */
data class FilterState(
    val maxShow: Int = 2000,
    val addrGreatEnabled: Boolean = false,
    val addrGreat: String = "",
    val addrLessEnabled: Boolean = false,
    val addrLess: String = "",
    val valGreatEnabled: Boolean = false,
    val valGreat: String = "",
    val valLessEnabled: Boolean = false,
    val valLess: String = "",
    val typeEnabled: Boolean = false,
    val types: Set<Int> = emptySet(),
    val fractionalEnabled: Boolean = false,
    val fractionalNotEqual: Boolean = false,
    val fractional: String = "",
    val pointerEnabled: Boolean = false,
    val pointers: Set<Int> = emptySet(), // 4="-", 8="RO", 16="W", 2="X", 1="WX"
    val formatHex: Boolean = false,
    val formatRHex: Boolean = false,
    val formatString: Boolean = false,
    val formatUtf16: Boolean = false,
    val formatArm: Boolean = false,
    val formatThumb: Boolean = false,
    val formatArm64: Boolean = false
) {
    val isAnyFilterActive: Boolean
        get() = addrGreatEnabled || addrLessEnabled || valGreatEnabled || valLessEnabled ||
            typeEnabled || fractionalEnabled || pointerEnabled
}

/** 内存区域汇总行 */
data class RegionRow(
    val code: String,
    val name: String,
    val size: Long,
    val count: Int,
    val selected: Boolean = false
)

data class Ui(
    val tab: NextTab = NextTab.SEARCH_LIST,
    val rawResults: List<MemoryItem> = emptyList(),
    val results: List<MemoryItem> = emptyList(),
    val saved: List<MemoryItem> = emptyList(),
    val memoryRows: List<MemoryItem> = emptyList(),
    val memoryBase: Long = 0L,
    val memoryHighlightAddress: Long? = null,
    val memoryScrollTrigger: Int = 0,
    val memoryTop: Long = 0L,
    val memoryOffsetShift: Int = 0,
    val memoryHistoryBack: List<Long> = emptyList(),
    val memoryHistoryForward: List<Long> = emptyList(),

    // O(1) 独立分标签选择状态（模块 4）
    val searchSelection: SelectionState = SelectionState.EMPTY,
    val savedSelection: SelectionState = SelectionState.EMPTY,
    val memorySelection: SelectionState = SelectionState.EMPTY,

    val processes: List<TargetProcess> = emptyList(),
    val processFilter: Int = 0, // 0=全部, 1=用户, 2=系统
    val processSortBySize: Boolean = true,
    val processLoading: Boolean = true,
    val target: TargetProcess? = null,
    val processPaused: Boolean = false,

    val regions: List<RegionRow> = emptyList(),
    val selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,

    val busy: Boolean = false,
    val busyText: String = "",
    val isBatchBusy: Boolean = false,

    /** 分层弹窗导航栈（支持子弹窗逐级返回） */
    val sheetStack: List<Sheet> = emptyList(),
    val fileSheetMode: FileSheetMode = FileSheetMode.SAVE,

    val search: SearchInput = SearchInput(),
    val fuzzy: FuzzyState = FuzzyState(),
    val filter: FilterState = FilterState(),

    val scriptPath: String = "/sdcard/Download/script.lua",
    val scriptHistory: List<ScriptHistoryEntry> = HistoryPersistence.DEFAULT_SCRIPT_HISTORY,
    val fileManagerPath: String = "/sdcard/GGNext/saved_list.txt",
    val dumpDirPath: String = "/sdcard/GGNext/dump",
    val gotoDraftText: String? = null,
    val copyFromDraft: String? = null,
    val copyToDraft: String? = null,
    val copyBytesDraft: String? = null,
    val dumpFromDraft: String? = null,
    val dumpToDraft: String? = null,
    val fileBrowser: FileBrowserConfig = FileBrowserConfig(),

    val valueMask: Int = 0,
    val valueMaskSaved: Int = 0,
    val valueMaskMemory: Int = SettingsSpecs.DEFAULT_FORMAT_MASK,
    val searchType: Int = ValueType.DWORD,

    val settings: Map<String, String> = emptyMap(),
    val settingsLoaded: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    val metrics: UiMetrics = UiMetrics(),
    val preset: ThemePreset = ThemePreset.ROSE,
    val darkMode: DarkMode = DarkMode.DARK,
    val dynamicColor: Boolean = false,
    val customFontPath: String = "",
    val customFontName: String = "",
    val managedFonts: List<com.littlecodesauce.ggnext.ui.theme.AppFontCache.ImportedFont> = emptyList(),

    val tools: Map<NextTab, List<ToolId>> = emptyMap(),
    val pinnedTools: Map<NextTab, List<ToolId>> = emptyMap(),
    val totalCount: Long = 0,
    val hintVisible: Boolean = true,
    val workMode: WorkMode = WorkMode.ROOT,
    val hasRootPermission: Boolean = false,
    val attachedOk: Boolean = false,
    val editing: MemoryItem? = null,
    val editSingleOnly: Boolean = false,
    val lastBatchEditValue: String = "",
    val lastBatchEditStep: String = "",
    val contextItem: MemoryItem? = null,
    val history: List<String> = emptyList(),
    val gotoHistory: List<String> = emptyList(),
    val floatingBallVisible: Boolean = false,
    val panelVisible: Boolean = false,
    val speedBarExpanded: Boolean = false,
    val speedMultiplier: Float = 1.0f,
    val speedWrapEnabled: Boolean = false,
    val homeSettingsOpen: Boolean = false,
    val homeTab: HomeTab = HomeTab.HOME,
    val breakpoints: List<BpSession> = emptyList(),
    val selectedBpIds: Set<Int> = emptySet(),
    val debugExpandedBps: Map<Int, Boolean> = emptyMap(),
    val debugExpandedHits: Map<String, Boolean> = emptyMap(),
    val bpTraceEnabled: Boolean = false,
    val bpTracePaused: Boolean = false,
    val bpTraceFilterBpId: Int = 0,
    val bpTraceRegFilter: String = "",
    val bpTraceShowFpr: Boolean = false,
    val bpTraceLogs: List<BpTraceEvent> = emptyList(),
    val asmPack: AsmPackStatus = AsmPackStatus(),
    val memoryDisasm: Map<Long, String> = emptyMap(),
    val memoryPointerChain: List<PointerHop> = emptyList(),
    val memoryPointerIndex: Int = -1
) {
    /** 当前栈顶弹窗（派生属性，保持调用方兼容） */
    val sheet: Sheet get() = sheetStack.lastOrNull() ?: Sheet.NONE

    val searchSelectedCount: Int get() = searchSelection.selectedCount(results.size)
    val savedSelectedCount: Int get() = savedSelection.selectedCount(saved.size)
    val memorySelectedCount: Int get() = memorySelection.selectedCount(memoryRows.size)

    /** O(1) 当前标签已选数量 */
    val selectedCount: Int
        get() = when (tab) {
            NextTab.SAVED_LIST -> savedSelectedCount
            NextTab.MEMORY -> memorySelectedCount
            NextTab.SEARCH_LIST -> searchSelectedCount
            NextTab.DEBUG -> selectedBpIds.size
            NextTab.SETTINGS -> 0
        }

    fun isSearchItemSelected(item: MemoryItem): Boolean =
        searchSelection.isSelected(SelectionState.itemKey(item))

    fun isSavedItemSelected(item: MemoryItem): Boolean =
        savedSelection.isSelected(SelectionState.itemKey(item))

    fun isMemoryRowSelected(item: MemoryItem): Boolean =
        memorySelection.isSelected(SelectionState.memoryRowKey(item))

    fun maskForTab(t: NextTab): Int = when (t) {
        NextTab.SAVED_LIST -> valueMaskSaved
        NextTab.MEMORY -> valueMaskMemory
        else -> valueMask
    }

    val currentMask: Int get() = maskForTab(tab)
    val currentPinnedTools: List<ToolId> get() = pinnedTools[tab] ?: DEFAULT_PINNED_TOOLS
    val flowingTools: List<ToolId>
        get() {
            val base = tools[tab] ?: when (tab) {
                NextTab.SEARCH_LIST -> DEFAULT_TOOLS_SEARCH
                NextTab.SAVED_LIST -> DEFAULT_TOOLS_SAVED
                NextTab.MEMORY -> DEFAULT_TOOLS_MEMORY
                NextTab.DEBUG -> DEFAULT_TOOLS_DEBUG
                NextTab.SETTINGS -> emptyList()
            }
            val pinned = currentPinnedTools.toSet()
            return base.filter { it !in pinned }
        }
    val searchToolList: List<ToolId> get() = flowingTools

    val countButtonText: String
        get() {
            val total = maxOf(totalCount, rawResults.size.toLong(), results.size.toLong())
            return "(${results.size}/$total)"
        }

    val speedText: String
        get() = if (speedMultiplier >= 10f || speedMultiplier == speedMultiplier.toInt().toFloat()) {
            String.format(Locale.US, "%.1fx", speedMultiplier)
        } else {
            String.format(Locale.US, "%.2fx", speedMultiplier).trimEnd('0').let {
                if (it.endsWith(".")) "${it}0x" else "${it}x"
            }
        }
}

/**
 * 全局会话：主界面与悬浮窗共享同一份数据与引擎。
 */
object Session {

    const val MEMORY_PAGE_BYTES = 4096
    const val MEMORY_PAGE_ROWS = MEMORY_PAGE_BYTES / 4

    private val SPEED_STEPS = floatArrayOf(
        0.05f, 0.1f, 0.2f, 0.25f, 0.33f, 0.5f, 0.75f,
        1.0f,
        1.25f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f, 8.0f, 10.0f, 15.0f, 20.0f, 30.0f, 50.0f, 100.0f
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var repo: SettingsRepository? = null
    private var appContext: Application? = null
    private var blobStoreDir: File? = null
    private val engine = MemoryEngine()
    private val historyStore = SearchHistory()

    @Volatile
    private var hasFuzzySession: Boolean = false
    @Volatile
    private var liveRefreshRunning: Boolean = false
    @Volatile
    private var frozenItemsSnapshot: List<MemoryItem> = emptyList()
    @Volatile
    private var activeSearchJob: Job? = null

    private val pendingSettingsBuffer = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val settingsFlushMutex = Mutex()
    @Volatile
    private var persistSettingsJob: Job? = null
    @Volatile
    private var persistSearchHistJob: Job? = null
    @Volatile
    private var persistGotoHistJob: Job? = null
    @Volatile
    private var persistScriptHistJob: Job? = null
    @Volatile
    private var persistSavedListJob: Job? = null
    @Volatile
    private var bpPollingJob: Job? = null

    private val attachMutex = Mutex()
    private val listScrollPositions = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    fun getListScrollPosition(key: String): Pair<Int, Int> =
        listScrollPositions[key] ?: (0 to 0)

    fun saveListScrollPosition(key: String, index: Int, offset: Int) {
        listScrollPositions[key] = index.coerceAtLeast(0) to offset.coerceAtLeast(0)
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val _searchProgressState = MutableStateFlow(SearchProgressUi())
    val searchProgressState: StateFlow<SearchProgressUi> = _searchProgressState.asStateFlow()

    private val _toastState = MutableStateFlow<String?>(null)
    val toastState: StateFlow<String?> = _toastState.asStateFlow()

    private fun buildMetrics(map: Map<String, String>): UiMetrics = UiMetrics(
        fontScale = (map["font_scale"]?.toFloatOrNull() ?: 0.88f).coerceIn(0.7f, 1.3f),
        dialogCorner = (map["dialog_corner"]?.toIntOrNull() ?: 12).coerceIn(0, 28),
        sheetBlur = map["sheet_blur"]?.toIntOrNull() ?: 110,
        iconBlur = map["icon_blur"]?.toIntOrNull() ?: 139,
        skeletonLoading = map["skeleton"] != "0",
        bgOpacity = (map["bg_opacity"]?.toFloatOrNull() ?: 0.96f).coerceIn(0.25f, 1.0f),
        panelScale = (map["panel_scale"]?.toFloatOrNull() ?: 1.0f).coerceIn(0.40f, 1.0f),
        glassEnabled = map["glass_enabled"] == "1",
        glassBlur = (map["glass_blur"]?.toIntOrNull() ?: 16).coerceIn(0, 36),
        showProcessIconOnBall = (map["show_process_icon"] ?: "1") != "0"
    )

    private fun currentHistoryLimit(settings: Map<String, String> = _ui.value.settings): Int =
        (settings["history_limit"]?.toIntOrNull() ?: 500).coerceIn(0, 100000)

    private fun parseRegionsCsv(csv: String?): Set<String>? {
        if (csv.isNullOrBlank()) return null
        val validCodes = RegionCatalog.ALL.mapTo(HashSet()) { it.code }
        val parsed = csv.split(',')
            .map { it.trim() }
            .filter { it in validCodes }
            .toSet()
        return parsed.ifEmpty { null }
    }

    private fun packageRegionKey(packageName: String?): String? {
        val clean = packageName?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return "pkg_regions_$clean"
    }

    private fun resolveRegionsForPackage(settings: Map<String, String>, packageName: String?): Set<String> {
        val pkgKey = packageRegionKey(packageName)
        if (pkgKey != null) {
            parseRegionsCsv(settings[pkgKey])?.let { return it }
        }
        parseRegionsCsv(settings["default_selected_regions"])?.let { return it }
        return RegionCatalog.DEFAULT_SELECTED
    }

    private fun enqueueSettingWrite(id: String, value: String, immediate: Boolean = false) {
        pendingSettingsBuffer[id] = value
        scheduleSettingsFlush(delayMs = if (immediate) 0L else 140L)
    }

    private fun enqueueSettingsWrite(pairs: Map<String, String>, immediate: Boolean = false) {
        if (pairs.isEmpty()) return
        pendingSettingsBuffer.putAll(pairs)
        scheduleSettingsFlush(delayMs = if (immediate) 0L else 140L)
    }

    private fun scheduleSettingsFlush(delayMs: Long) {
        persistSettingsJob?.cancel()
        persistSettingsJob = scope.launch(Dispatchers.IO) {
            if (delayMs > 0L) delay(delayMs)
            settingsFlushMutex.withLock {
                withContext(NonCancellable) {
                    while (pendingSettingsBuffer.isNotEmpty()) {
                        val batch = LinkedHashMap<String, String>()
                        val iter = pendingSettingsBuffer.entries.iterator()
                        while (iter.hasNext()) {
                            val entry = iter.next()
                            batch[entry.key] = entry.value
                            iter.remove()
                        }
                        if (batch.isNotEmpty()) {
                            runCatching { repo?.setStrings(batch) }
                        }
                    }
                }
            }
        }
    }

    private fun persistSelectedRegions(
        regions: Set<String>,
        packageName: String?,
        immediate: Boolean = false
    ) {
        val csv = regions.joinToString(",")
        val pkgKey = packageRegionKey(packageName)
        val pairs = LinkedHashMap<String, String>(2)
        pairs["default_selected_regions"] = csv
        if (pkgKey != null) {
            pairs[pkgKey] = csv
        }
        _ui.update { st -> st.copy(settings = st.settings + pairs) }
        enqueueSettingsWrite(pairs, immediate = immediate)
    }

    private fun parseToolOrderCsv(csv: String?): List<ToolId>? {
        if (csv.isNullOrBlank()) return null
        val byName = ToolId.entries.associateBy { it.name }
        val list = csv.split(',').mapNotNull { byName[it.trim()] }.distinct()
        return list.ifEmpty { null }
    }

    private fun persistToolsForTab(tab: NextTab, list: List<ToolId>) {
        val key = "toolbar_order_${tab.name}"
        val csv = list.joinToString(",") { it.name }
        _ui.update { st -> st.copy(settings = st.settings + (key to csv)) }
        enqueueSettingWrite(key, csv)
    }

    private fun persistPinnedToolsForTab(tab: NextTab, list: List<ToolId>) {
        val key = "pinned_tools_${tab.name}"
        val csv = list.joinToString(",") { it.name }
        _ui.update { st -> st.copy(settings = st.settings + (key to csv)) }
        enqueueSettingWrite(key, csv)
    }

    private fun persistSearchHistoryAsync() {
        val snapshot = historyStore.exportEntries()
        persistSearchHistJob?.cancel()
        persistSearchHistJob = scope.launch(Dispatchers.IO) {
            delay(150L)
            val json = HistoryPersistence.encodeSearchHistory(snapshot)
            LocalBlobStore.writeTextAtomic(blobStoreDir, SettingKeys.KEY_HISTORY_SEARCH_JSON, json)
        }
    }

    private fun persistGotoHistoryAsync(entries: List<String>) {
        persistGotoHistJob?.cancel()
        persistGotoHistJob = scope.launch(Dispatchers.IO) {
            delay(150L)
            val json = HistoryPersistence.encodeGotoHistory(entries)
            LocalBlobStore.writeTextAtomic(blobStoreDir, SettingKeys.KEY_HISTORY_GOTO_JSON, json)
        }
    }

    private fun persistScriptHistoryAsync(entries: List<ScriptHistoryEntry>) {
        persistScriptHistJob?.cancel()
        persistScriptHistJob = scope.launch(Dispatchers.IO) {
            delay(150L)
            val json = HistoryPersistence.encodeScriptHistory(entries)
            LocalBlobStore.writeTextAtomic(blobStoreDir, SettingKeys.KEY_HISTORY_SCRIPT_JSON, json)
        }
    }

    private fun persistSavedListAsync(items: List<MemoryItem>) {
        persistSavedListJob?.cancel()
        persistSavedListJob = scope.launch(Dispatchers.IO) {
            delay(150L)
            val json = HistoryPersistence.encodeSavedList(items)
            LocalBlobStore.writeTextAtomic(blobStoreDir, SettingKeys.KEY_SAVED_LIST_JSON, json)
        }
    }

    private fun syncFrozenSnapshot(savedList: List<MemoryItem>) {
        frozenItemsSnapshot = if (savedList.isEmpty()) emptyList() else savedList.filter { it.freeze }
    }

    /** 统一处理目标进程退出/死亡回调 */
    private fun handleTargetProcessDead() {
        val hadAttach = _ui.value.attachedOk
        if (!hadAttach) return
        scope.launch {
            withContext(Dispatchers.IO) { engine.clearNativeFreezes() }
            _ui.update { st ->
                if (!st.attachedOk) st
                else st.copy(attachedOk = false, processPaused = false, busy = false)
            }
            _searchProgressState.value = SearchProgressUi()
            toast("目标进程已退出或重启，请重新附加")
        }
    }

    fun init(app: Application) {
        appContext = app
        blobStoreDir = File(app.filesDir, "ggnext_store")
        engine.setOnTargetProcessDead { handleTargetProcessDead() }
        if (repo != null) return
        val r = SettingsRepository(app)
        repo = r

        // 模块 1：绝不在主线程执行 Shell.hasRoot()，异步移至 Dispatchers.IO
        scope.launch(Dispatchers.IO) {
            val rootOk = Shell.hasRoot()
            _ui.update { st ->
                val savedMode = st.settings["work_mode"]
                val resolvedMode = when (savedMode) {
                    "no_root" -> WorkMode.NO_ROOT
                    "root" -> WorkMode.ROOT
                    else -> if (rootOk) WorkMode.ROOT else WorkMode.NO_ROOT
                }
                st.copy(hasRootPermission = rootOk, workMode = resolvedMode)
            }
            val asmStatus = AsmEngineManager.autoLoad(app)
            _ui.update { it.copy(asmPack = asmStatus) }
        }

        // 启动时单次磁盘水合（避免 DataStore 写入回声反复覆盖内存中的实时修改/区域勾选），
        // 同时将旧版保存在 DataStore 中的大体积 JSON 自动迁移至独立文件存储，防止 Protobuf 序列化 OOM。
        scope.launch(Dispatchers.IO) {
            val map = runCatching { r.settingsSnapshot().first() }.getOrDefault(emptyMap())
            val vMaskRaw = runCatching { r.valueFormatMask().first() }.getOrNull()
            val sMaskRaw = runCatching { r.savedFormatMask().first() }.getOrDefault(0)
            val mMaskRaw = runCatching { r.memoryFormatMask().first() }.getOrNull()
            val vMask = (vMaskRaw ?: 0) and Formatter.AUX_FORMAT_MASK
            val sMask = sMaskRaw and Formatter.AUX_FORMAT_MASK
            val mMask = mMaskRaw ?: (vMaskRaw?.takeIf { it != 0 } ?: SettingsSpecs.DEFAULT_FORMAT_MASK)

            val limit = (map["history_limit"]?.toIntOrNull() ?: 500).coerceIn(0, 100000)
            historyStore.setLimit(limit)
            map["language"]?.let { lang -> AppStrings.setLang(lang) }

            val blobKeys = listOf(
                SettingKeys.KEY_HISTORY_SEARCH_JSON,
                SettingKeys.KEY_HISTORY_GOTO_JSON,
                SettingKeys.KEY_HISTORY_SCRIPT_JSON,
                SettingKeys.KEY_SAVED_LIST_JSON
            )
            val legacyKeysToPurge = ArrayList<String>(4)
            fun loadBlobWithMigration(key: String): String? {
                val fromFile = LocalBlobStore.readText(blobStoreDir, key)
                val fromLegacy = map[key]
                if (fromLegacy != null) {
                    legacyKeysToPurge.add(key)
                    if (fromFile == null && fromLegacy.isNotBlank()) {
                        LocalBlobStore.writeTextAtomic(blobStoreDir, key, fromLegacy)
                    }
                }
                return fromFile ?: fromLegacy
            }

            val searchJson = loadBlobWithMigration(SettingKeys.KEY_HISTORY_SEARCH_JSON)
            val gotoJson = loadBlobWithMigration(SettingKeys.KEY_HISTORY_GOTO_JSON)
            val scriptJson = loadBlobWithMigration(SettingKeys.KEY_HISTORY_SCRIPT_JSON)
            val savedJson = loadBlobWithMigration(SettingKeys.KEY_SAVED_LIST_JSON)

            if (legacyKeysToPurge.isNotEmpty()) {
                runCatching { r.removeKeys(legacyKeysToPurge) }
            }

            val decodedSearch = HistoryPersistence.decodeSearchHistory(searchJson)
            val restoredSearchHist: List<String>? = if (decodedSearch.isNotEmpty()) {
                historyStore.restore(decodedSearch)
                historyStore.list()
            } else null

            val decodedGoto = HistoryPersistence.decodeGotoHistory(gotoJson)
            val restoredGotoHist: List<String>? = if (decodedGoto.isNotEmpty()) {
                if (limit > 0) decodedGoto.take(limit.coerceAtMost(200)) else emptyList()
            } else null

            val restoredScriptHist: List<ScriptHistoryEntry>? = if (scriptJson != null) {
                HistoryPersistence.decodeScriptHistory(scriptJson)
            } else null

            val decodedSaved = HistoryPersistence.decodeSavedList(savedJson)
            val restoredSavedItems: List<MemoryItem>? = if (decodedSaved.isNotEmpty()) {
                syncFrozenSnapshot(decodedSaved)
                decodedSaved
            } else null

            val cleanMap = LinkedHashMap<String, String>(map.size)
            for ((k, v) in map) {
                if (k !in blobKeys) {
                    cleanMap[k] = v
                }
            }

            _ui.update { st ->
                // 内存中已发生的实时修改（st.settings）优先于磁盘旧快照，防止启动瞬间点击被回滚
                val merged = cleanMap + st.settings
                val savedMode = merged["work_mode"]
                val resolvedMode = when (savedMode) {
                    "no_root" -> WorkMode.NO_ROOT
                    "root" -> WorkMode.ROOT
                    else -> if (st.hasRootPermission) WorkMode.ROOT else WorkMode.NO_ROOT
                }
                val restoredMaxShow = (merged["filter_max_show"]?.toIntOrNull() ?: st.filter.maxShow).coerceIn(1, 100000)
                val restoredSearchType = merged["search_type"]?.toIntOrNull() ?: st.search.type
                val restoredPointerType = merged["pointer_type"]?.toIntOrNull() ?: st.search.pointerType
                val restoredOffsetHex = merged["pointer_offset_hex"]?.let { it != "0" } ?: st.search.offsetHex
                val restoredFuzzyType = merged["fuzzy_type"]?.toIntOrNull() ?: st.fuzzy.type
                val restoredProcFilter = (merged["process_filter"]?.toIntOrNull() ?: st.processFilter).coerceIn(0, 2)
                val restoredProcSort = merged["process_sort_by_size"]?.let { it != "0" } ?: st.processSortBySize
                val restoredSpeed = (merged["speed_value"]?.toFloatOrNull() ?: st.speedMultiplier).coerceIn(0.01f, 100f)
                val restoredScriptPath = merged["script_path"]?.takeIf { it.isNotBlank() } ?: st.scriptPath
                val restoredFilePath = merged["file_manager_path"]?.takeIf { it.isNotBlank() } ?: st.fileManagerPath
                val restoredDumpDir = merged["dump_dir_path"]?.takeIf { it.isNotBlank() } ?: st.dumpDirPath
                val restoredRegions = resolveRegionsForPackage(merged, st.target?.packageName)

                val restoredTools = st.tools.toMutableMap()
                val restoredPinned = st.pinnedTools.toMutableMap()
                listOf(NextTab.SEARCH_LIST, NextTab.SAVED_LIST, NextTab.MEMORY, NextTab.DEBUG).forEach { t ->
                    parseToolOrderCsv(merged["toolbar_order_${t.name}"])?.let { restoredTools[t] = it }
                    parseToolOrderCsv(merged["pinned_tools_${t.name}"])?.let { restoredPinned[t] = it }
                }

                val savedFontPath = merged["custom_font_path"].orEmpty()
                val validFontPath = if (savedFontPath.isNotBlank() && File(savedFontPath).isFile) savedFontPath else ""
                val validFontName = if (validFontPath.isNotBlank()) {
                    merged["custom_font_name"].orEmpty().ifBlank { File(validFontPath).name }
                } else ""
                val savedFontList = com.littlecodesauce.ggnext.ui.theme.AppFontCache.decodeFontList(merged["managed_fonts_json"])
                val reconciledFonts = com.littlecodesauce.ggnext.ui.theme.AppFontCache.reconcileManagedFonts(
                    context = appContext,
                    saved = savedFontList,
                    activePath = validFontPath,
                    activeName = validFontName
                )

                st.copy(
                    settings = merged,
                    settingsLoaded = true,
                    disclaimerAccepted = merged["disclaimer_v1_accepted"] == "1",
                    metrics = buildMetrics(merged),
                    preset = ThemePreset.entries.firstOrNull { p -> p.name == merged["theme_preset"] } ?: st.preset,
                    darkMode = DarkMode.entries.firstOrNull { d -> d.name == merged["dark_mode"] } ?: st.darkMode,
                    dynamicColor = merged["dynamic_color"] == "1",
                    customFontPath = validFontPath,
                    customFontName = validFontName,
                    managedFonts = reconciledFonts,
                    workMode = resolvedMode,
                    selectedRegions = restoredRegions,
                    filter = st.filter.copy(
                        maxShow = restoredMaxShow,
                        formatHex = (vMask and Formatter.F_HEX) != 0,
                        formatRHex = (vMask and Formatter.F_RHEX) != 0,
                        formatString = (vMask and Formatter.F_STRING) != 0,
                        formatUtf16 = (vMask and Formatter.F_JAVA) != 0,
                        formatArm = (vMask and Formatter.F_ARM) != 0,
                        formatThumb = (vMask and Formatter.F_THUMB) != 0,
                        formatArm64 = (vMask and Formatter.F_ARM8) != 0
                    ),
                    valueMask = vMask,
                    valueMaskSaved = sMask,
                    valueMaskMemory = mMask,
                    searchType = restoredSearchType,
                    search = st.search.copy(
                        type = restoredSearchType,
                        pointerType = restoredPointerType,
                        offsetHex = restoredOffsetHex
                    ),
                    fuzzy = st.fuzzy.copy(type = restoredFuzzyType),
                    processFilter = restoredProcFilter,
                    processSortBySize = restoredProcSort,
                    speedMultiplier = restoredSpeed,
                    scriptPath = restoredScriptPath,
                    fileManagerPath = restoredFilePath,
                    dumpDirPath = restoredDumpDir,
                    tools = restoredTools,
                    pinnedTools = restoredPinned,
                    history = restoredSearchHist ?: st.history,
                    gotoHistory = restoredGotoHist ?: st.gotoHistory,
                    scriptHistory = restoredScriptHist ?: st.scriptHistory,
                    saved = restoredSavedItems ?: st.saved
                )
            }
        }

        // JVM 回退锁值与冻结间隔同步循环
        scope.launch(Dispatchers.IO) {
            var lastSyncedInterval = -1L
            while (true) {
                val rawFreeze = _ui.value.settings["freeze_interval"]?.toLongOrNull() ?: 33L
                val intervalMs = (if (rawFreeze > 1000L) rawFreeze / 1000L else rawFreeze).coerceIn(5L, 5000L)
                if (intervalMs != lastSyncedInterval) {
                    lastSyncedInterval = intervalMs
                    engine.setFreezeIntervalMs(intervalMs.toInt())
                }
                delay(intervalMs)
                val frozen = frozenItemsSnapshot
                if (frozen.isNotEmpty() && !_ui.value.processPaused && _ui.value.attachedOk) {
                    engine.tickFreeze(frozen)
                }
            }
        }

        // 实时刷新 + 周期性目标进程存活检测
        scope.launch(Dispatchers.IO) {
            var livenessTick = 0
            while (true) {
                delay(900L)
                val st = _ui.value
                if (st.attachedOk && !st.busy) {
                    livenessTick++
                    if (livenessTick >= 4) {
                        livenessTick = 0
                        if (!engine.isTargetProcessAlive()) {
                            handleTargetProcessDead()
                            continue
                        }
                    }
                    if (st.sheet == Sheet.NONE) {
                        if (st.results.isNotEmpty() || st.saved.isNotEmpty() || (st.tab == NextTab.MEMORY && st.memoryRows.isNotEmpty())) {
                            refreshLiveValues(manual = false)
                        }
                    }
                } else {
                    livenessTick = 0
                }
            }
        }
        loadProcesses(app)
    }

    // ============ 异步数据读取封装（UI 绝不直接访问 Session.engine） ============

    suspend fun readMemoryPreview(address: Long, size: Int): ByteArray =
        withContext(Dispatchers.IO) {
            engine.readBlock(address, size.coerceIn(1, 4096))
        }

    suspend fun readQwordAt(address: Long): Long =
        withContext(Dispatchers.IO) {
            engine.read(address, 8)
        }

    // ============ 模块选择器协同状态（通过 Ui 草稿字段持久化，彻底避免弹窗切换卸载丢失地址） ============
    @Volatile var modulePickerRegionFilter: String? = null
        private set
    @Volatile var modulePickerInitialUserOnly: Boolean = false
        private set
    @Volatile private var modulePickerTarget: String? = null

    fun saveGotoDraft(text: String?) {
        _ui.update { it.copy(gotoDraftText = text) }
    }

    fun saveCopyDraft(from: String?, to: String?, bytes: String?) {
        _ui.update { it.copy(copyFromDraft = from, copyToDraft = to, copyBytesDraft = bytes) }
    }

    fun saveDumpDraft(from: String?, to: String?) {
        _ui.update { it.copy(dumpFromDraft = from, dumpToDraft = to) }
    }

    fun openModulePicker(
        target: String,
        regionFilter: String? = null,
        initialUserOnly: Boolean = false
    ) {
        modulePickerTarget = target
        modulePickerRegionFilter = regionFilter
        modulePickerInitialUserOnly = initialUserOnly
        openSheet(Sheet.MODULE_LIST)
    }

    fun hasModulePickerCallback(): Boolean = modulePickerTarget != null

    fun dispatchModulePicked(startAddr: Long, endAddr: Long): Boolean {
        val target = modulePickerTarget ?: return false
        modulePickerTarget = null
        modulePickerRegionFilter = null
        modulePickerInitialUserOnly = false
        val startHex = java.lang.Long.toUnsignedString(startAddr, 16).uppercase()
        val endHex = java.lang.Long.toUnsignedString(endAddr, 16).uppercase()
        _ui.update { st ->
            when (target) {
                "goto" -> st.copy(gotoDraftText = startHex)
                "copy_from" -> st.copy(copyFromDraft = startHex)
                "copy_to" -> st.copy(copyToDraft = startHex)
                "dump_from" -> st.copy(dumpFromDraft = startHex, dumpToDraft = endHex)
                "dump_to" -> st.copy(dumpToDraft = endHex)
                else -> st
            }
        }
        popSheet()
        return true
    }

    fun clearModulePickerState() {
        modulePickerTarget = null
        modulePickerRegionFilter = null
        modulePickerInitialUserOnly = false
    }

    suspend fun loadProcessRegions(): List<MemoryRegion> =
        withContext(Dispatchers.IO) {
            engine.regions()
        }

    fun firstRegionBase(code: String): Long? = engine.firstRegionBase(code)

    /**
     * 解析“转到地址”输入表达式：
     * - 纯十六进制地址（如 73B370F000 / 0x73B370F000 / 73B370F000h）
     * - 十六进制地址 ± 十六进制偏移（如 73B370F000+1234 / 73B370F000-0x10）
     * - .so 模块名（如 libtutorial.so）及模块名 ± 十六进制偏移（如 libtutorial.so+0x1234）
     */
    suspend fun resolveGotoExpression(raw: String): Result<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val text = raw.trim()
        if (text.isEmpty()) return@withContext Result.failure(IllegalArgumentException("请输入地址或模块名"))

        fun parseHexNum(tok: String): Long? {
            val clean = tok.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
            if (clean.isEmpty()) return null
            return clean.toULongOrNull(16)?.toLong()
        }

        // 1. 尝试直接作为纯十六进制地址解析
        parseHexNum(text)?.let { addr ->
            return@withContext Result.success(addr to java.lang.Long.toHexString(addr).uppercase())
        }

        // 2. 拆分可选的 +/- 偏移（如 libil2cpp.so+0x1234 或 73B370F000+100）
        val opIdx = run {
            val plus = text.lastIndexOf('+')
            val minus = text.lastIndexOf('-')
            when {
                plus > 0 && minus > 0 -> maxOf(plus, minus)
                plus > 0 -> plus
                minus > 0 -> minus
                else -> -1
            }
        }
        val basePart = if (opIdx > 0) text.substring(0, opIdx).trim() else text
        val offsetSigned: Long = if (opIdx > 0) {
            val sign = if (text[opIdx] == '-') -1L else 1L
            val offRaw = text.substring(opIdx + 1).trim()
            val offMag = parseHexNum(offRaw)
                ?: return@withContext Result.failure(IllegalArgumentException("偏移格式无效：$offRaw"))
            sign * offMag
        } else 0L

        // 2a. basePart 是十六进制地址
        parseHexNum(basePart)?.let { baseAddr ->
            val resolved = baseAddr + offsetSigned
            return@withContext Result.success(resolved to java.lang.Long.toHexString(resolved).uppercase())
        }

        // 2b. basePart 作为 .so 模块名解析
        val regions = engine.regions()
        if (regions.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("未获取到进程内存映射，请先附加目标进程"))
        }
        val queryLower = basePart.lowercase()
        fun extractFileName(path: String): String {
            val afterBang = if (path.contains("!/")) path.substringAfterLast("!/") else path
            return afterBang.substringAfterLast('/').trim()
        }

        val exactMatches = regions.filter { r ->
            r.path.isNotBlank() && extractFileName(r.path).equals(basePart, ignoreCase = true)
        }
        val matches = if (exactMatches.isNotEmpty()) {
            exactMatches
        } else {
            regions.filter { r ->
                r.path.isNotBlank() && (
                    extractFileName(r.path).lowercase().contains(queryLower) ||
                        r.path.lowercase().contains(queryLower)
                    )
            }
        }
        if (matches.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("未在进程内存中找到模块：$basePart"))
        }
        val baseAddr = matches.minOf { it.start }
        val resolved = baseAddr + offsetSigned
        val displayName = extractFileName(matches.first().path).ifBlank { basePart }
        val label = if (offsetSigned == 0L) displayName else "$displayName${if (offsetSigned >= 0) "+" else "-"}${java.lang.Long.toHexString(kotlin.math.abs(offsetSigned)).uppercase()}"
        Result.success(resolved to label)
    }

    // ============ 工作模式与 Root 检测（全部异步 IO） ============

    fun setWorkMode(mode: WorkMode) {
        _ui.update { it.copy(workMode = mode, settings = it.settings + ("work_mode" to mode.code)) }
        enqueueSettingWrite("work_mode", mode.code, immediate = true)
        scope.launch(Dispatchers.IO) {
            val hasRoot = if (mode == WorkMode.ROOT) {
                Shell.resetRootCache()
                Shell.hasRoot()
            } else {
                _ui.value.hasRootPermission
            }
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(workMode = mode, hasRootPermission = hasRoot) }
                appContext?.let { loadProcesses(it) }
                val target = _ui.value.target
                if (target != null) {
                    attach(target, silent = true, forceReattach = true)
                }
                toast(
                    when (mode) {
                        WorkMode.ROOT -> if (hasRoot) "已切换至 Root 模式" else "已切换至 Root 模式，未检测到 Root 授权"
                        WorkMode.NO_ROOT -> "已切换至免 Root 模式"
                    }
                )
            }
        }
    }

    fun refreshRootStatus(promptIfMissing: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            Shell.resetRootCache()
            val hasRoot = Shell.hasRoot()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(hasRootPermission = hasRoot) }
                appContext?.let { loadProcesses(it) }
                val target = _ui.value.target
                if (target != null) {
                    attach(target, silent = true, forceReattach = true)
                }
                if (hasRoot) {
                    toast("Root 权限已获取")
                } else if (promptIfMissing) {
                    toast("未检测到 Root 权限，请在管理器中授权或切换到免 Root 模式")
                }
            }
        }
    }

    // ============ 分层弹窗导航栈 ============

    private val childStackSheets = setOf(
        Sheet.HISTORY,
        Sheet.FILE_BROWSER,
        Sheet.MODULE_LIST,
        Sheet.TYPE_PICK,
        Sheet.TOOLBAR_PICK,
        Sheet.ADDRESS_EDIT
    )

    fun pushSheet(sheet: Sheet, item: MemoryItem? = null, singleEdit: Boolean = false) {
        if (sheet == Sheet.NONE) {
            closeAllSheets()
            return
        }
        if (sheet == Sheet.PROCESS) {
            appContext?.let { loadProcesses(it) }
        } else if (sheet == Sheet.REGION) {
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { aggregate(engine.regions()) }
                _ui.update { it.copy(regions = fresh) }
            }
        }
        _ui.update { st ->
            val nextStack = if (st.sheetStack.lastOrNull() == sheet) st.sheetStack else st.sheetStack + sheet
            st.copy(
                sheetStack = nextStack,
                editing = item ?: st.editing,
                editSingleOnly = singleEdit && item != null,
                contextItem = item ?: st.contextItem
            )
        }
    }

    fun openSheet(sheet: Sheet, item: MemoryItem? = null, singleEdit: Boolean = false) {
        if (sheet == Sheet.NONE) {
            closeAllSheets()
            return
        }
        if (sheet in childStackSheets && _ui.value.sheetStack.isNotEmpty()) {
            pushSheet(sheet, item, singleEdit)
            return
        }
        if (sheet == Sheet.PROCESS) {
            appContext?.let { loadProcesses(it) }
        } else if (sheet == Sheet.REGION) {
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { aggregate(engine.regions()) }
                _ui.update { it.copy(regions = fresh) }
            }
        }
        _ui.update {
            it.copy(
                sheetStack = listOf(sheet),
                editing = item ?: it.editing,
                editSingleOnly = singleEdit && item != null,
                contextItem = item ?: it.contextItem
            )
        }
    }

    fun openFileSheet(mode: FileSheetMode) {
        _ui.update { st ->
            st.copy(
                fileSheetMode = mode,
                sheetStack = listOf(Sheet.FILE_MANAGER)
            )
        }
    }

    fun popSheet(): Boolean {
        val stack = _ui.value.sheetStack
        if (stack.isEmpty()) return false
        _ui.update { st ->
            val popped = st.sheetStack.dropLast(1)
            st.copy(
                sheetStack = popped,
                editSingleOnly = if (popped.lastOrNull() == Sheet.EDITOR) st.editSingleOnly else false
            )
        }
        return true
    }

    fun closeAllSheets() {
        _ui.update { it.copy(sheetStack = emptyList(), editSingleOnly = false) }
    }

    fun closeSheet() {
        popSheet()
    }

    fun openHistorySheet(fromSheet: Sheet = _ui.value.sheet) {
        if (fromSheet != Sheet.NONE && fromSheet != Sheet.HISTORY && _ui.value.sheetStack.isEmpty()) {
            _ui.update { it.copy(sheetStack = listOf(fromSheet, Sheet.HISTORY)) }
        } else {
            pushSheet(Sheet.HISTORY)
        }
    }

    fun recordGotoHistory(hex: String) {
        val clean = hex.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H").uppercase()
        if (clean.isEmpty()) return
        val limit = currentHistoryLimit()
        if (limit <= 0) return
        val cap = limit.coerceAtMost(200)
        var updatedList: List<String> = emptyList()
        _ui.update { st ->
            val list = (listOf(clean) + st.gotoHistory.filter { h -> h != clean }).take(cap)
            updatedList = list
            st.copy(gotoHistory = list)
        }
        persistGotoHistoryAsync(updatedList)
    }

    fun toast(msg: String) {
        _toastState.value = AppStrings.tr(msg)
    }

    fun consumeToast() {
        _toastState.value = null
    }

    fun selectTab(tab: NextTab) {
        _ui.update {
            if (it.tab == tab) it else it.copy(tab = tab)
        }
        if (tab == NextTab.MEMORY && _ui.value.memoryRows.isEmpty()) {
            goto(_ui.value.memoryBase, recordHistory = false, highlightTarget = false)
        }
    }

    // ============ 文件浏览器与脚本路径管理（持久化 + 诚实提示） ============

    fun openFileBrowser(
        initialPath: String,
        selectDirectory: Boolean,
        returnSheet: Sheet,
        target: FileBrowserTarget
    ) {
        val dir = initialPath.trim().let { p ->
            if (p.isEmpty()) "/sdcard/Download"
            else if (selectDirectory) p
            else p.substringBeforeLast('/', "/sdcard/Download").ifEmpty { "/sdcard" }
        }
        _ui.update { st ->
            val baseStack = if (st.sheetStack.lastOrNull() == returnSheet) {
                st.sheetStack
            } else {
                listOf(returnSheet)
            }
            st.copy(
                sheetStack = baseStack + Sheet.FILE_BROWSER,
                fileBrowser = FileBrowserConfig(
                    currentDir = dir,
                    selectDirectory = selectDirectory,
                    returnSheet = returnSheet,
                    target = target
                )
            )
        }
    }

    fun onFileBrowserPicked(selectedPath: String) {
        val target = _ui.value.fileBrowser.target
        when (target) {
            FileBrowserTarget.SCRIPT -> {
                setSetting("script_path", selectedPath)
                addOrTouchScriptHistory(selectedPath)
            }
            FileBrowserTarget.FILE_MANAGER -> setSetting("file_manager_path", selectedPath)
            FileBrowserTarget.DUMP -> setSetting("dump_dir_path", selectedPath)
            FileBrowserTarget.FONT -> {
                importCustomFontFromFile(selectedPath)
            }
            FileBrowserTarget.ASM_PACK -> {
                importAsmPackFromFile(selectedPath)
            }
        }
        _ui.update { st ->
            val nextStack = if (st.sheetStack.size > 1 && st.sheetStack.lastOrNull() == Sheet.FILE_BROWSER) {
                st.sheetStack.dropLast(1)
            } else {
                listOf(st.fileBrowser.returnSheet)
            }
            when (st.fileBrowser.target) {
                FileBrowserTarget.SCRIPT -> st.copy(scriptPath = selectedPath, sheetStack = nextStack)
                FileBrowserTarget.FILE_MANAGER -> st.copy(fileManagerPath = selectedPath, sheetStack = nextStack)
                FileBrowserTarget.DUMP -> st.copy(dumpDirPath = selectedPath, sheetStack = nextStack)
                FileBrowserTarget.FONT, FileBrowserTarget.ASM_PACK -> st.copy(sheetStack = nextStack)
            }
        }
    }

    fun setScriptPath(path: String) {
        setSetting("script_path", path)
        _ui.update { it.copy(scriptPath = path) }
    }

    fun setFileManagerPath(path: String) {
        setSetting("file_manager_path", path)
        _ui.update { it.copy(fileManagerPath = path) }
    }

    fun setDumpDirPath(path: String) {
        setSetting("dump_dir_path", path)
        _ui.update { it.copy(dumpDirPath = path) }
    }

    private fun addOrTouchScriptHistory(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        val limit = currentHistoryLimit()
        var nextList: List<ScriptHistoryEntry> = emptyList()
        _ui.update { st ->
            val existing = st.scriptHistory.firstOrNull { it.path == clean }
            val entry = existing ?: ScriptHistoryEntry(path = clean)
            val combined = if (limit <= 0 && existing == null) {
                st.scriptHistory
            } else {
                HistoryPersistence.trimScriptHistory(
                    listOf(entry) + st.scriptHistory.filterNot { it.path == clean },
                    limit.coerceAtMost(200)
                )
            }
            nextList = combined
            st.copy(scriptHistory = combined)
        }
        persistScriptHistoryAsync(nextList)
    }

    fun removeScriptHistory(path: String) {
        var nextList: List<ScriptHistoryEntry> = emptyList()
        _ui.update { st ->
            val next = st.scriptHistory.filterNot { it.path == path }
            nextList = next
            st.copy(scriptHistory = next)
        }
        persistScriptHistoryAsync(nextList)
    }

    fun toggleScriptPin(path: String) {
        var nextList: List<ScriptHistoryEntry> = emptyList()
        _ui.update { st ->
            val updated = st.scriptHistory.map { entry ->
                if (entry.path == path) entry.copy(pinned = !entry.pinned) else entry
            }.sortedByDescending { it.pinned }
            nextList = updated
            st.copy(scriptHistory = updated)
        }
        persistScriptHistoryAsync(nextList)
    }

    fun pinScriptHistory(path: String) = toggleScriptPin(path)

    fun updateScriptEntry(oldPath: String, newPath: String, note: String? = null) {
        val cleanNew = newPath.trim()
        if (cleanNew.isEmpty()) return
        var nextList: List<ScriptHistoryEntry> = emptyList()
        _ui.update { st ->
            val next = st.scriptHistory.map { entry ->
                if (entry.path == oldPath) {
                    entry.copy(path = cleanNew, note = note ?: entry.note)
                } else entry
            }
            nextList = next
            st.copy(
                scriptHistory = next,
                scriptPath = if (st.scriptPath == oldPath) cleanNew else st.scriptPath
            )
        }
        persistScriptHistoryAsync(nextList)
    }

    fun updateScriptHistory(oldPath: String, newPath: String) =
        updateScriptEntry(oldPath, newPath, null)

    /** 启动执行明文 Lua 5.5.1 脚本 */
    fun runScript(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) {
            toast("请先选择或输入脚本路径")
            return
        }
        setSetting("script_path", clean)
        addOrTouchScriptHistory(clean)
        _ui.update { it.copy(scriptPath = clean) }
        val started = com.littlecodesauce.ggnext.lua.LuaScriptEngine.runScriptFile(clean)
        if (started) {
            closeSheet()
        }
    }

    /** 立即中止正在运行的 Lua 脚本 */
    fun stopScript() {
        com.littlecodesauce.ggnext.lua.LuaScriptEngine.stopScript()
    }

    fun setting(id: String): String =
        repo?.let { SettingsSpecs.byId(id)?.let { s -> SettingsRepository.valueOf(_ui.value.settings, s) } }
            ?: _ui.value.settings[id].orEmpty()

    fun setSetting(id: String, value: String) {
        if (id == "language") {
            AppStrings.setLang(value)
        } else if (id == "history_limit") {
            val newLimit = (value.toIntOrNull() ?: 500).coerceIn(0, 100000)
            historyStore.setLimit(newLimit)
            persistSearchHistoryAsync()
        }
        enqueueSettingWrite(id, value)
        _ui.update { st ->
            val nextSettings = st.settings + (id to value)
            val nextPreset = if (id == "theme_preset") {
                ThemePreset.entries.firstOrNull { p -> p.name == value } ?: st.preset
            } else st.preset
            val nextDark = if (id == "dark_mode") {
                DarkMode.entries.firstOrNull { d -> d.name == value } ?: st.darkMode
            } else st.darkMode
            val nextDyn = if (id == "dynamic_color") (value == "1") else st.dynamicColor
            st.copy(
                settings = nextSettings,
                preset = nextPreset,
                darkMode = nextDark,
                dynamicColor = nextDyn,
                metrics = buildMetrics(nextSettings),
                history = if (id == "history_limit") historyStore.list() else st.history
            )
        }
    }

    fun resetAppSettings() {
        setPreset(ThemePreset.ROSE)
        setDarkMode(DarkMode.DARK)
        setDynamicColor(false)
        resetCustomFont(silent = true)
        setSetting("font_scale", "0.88")
        setSetting("dialog_corner", "12")
        setSetting("bg_opacity", "0.96")
        setSetting("panel_scale", "1.00")
        setSetting("glass_enabled", "0")
        setSetting("glass_blur", "16")
        setSetting("show_process_icon", "1")
        setSetting("language", "0")
        setSetting("icons_size", "48")
        toast("已恢复默认设置")
    }

    fun setPreset(p: ThemePreset) = setSetting("theme_preset", p.name)
    fun setDarkMode(m: DarkMode) = setSetting("dark_mode", m.name)
    fun setDynamicColor(v: Boolean) = setSetting("dynamic_color", if (v) "1" else "0")

    fun importCustomFontFromUri(uri: android.net.Uri) {
        val ctx = appContext ?: return
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                com.littlecodesauce.ggnext.ui.theme.AppFontCache.importFromUri(ctx, uri)
            }
            res.onSuccess { imported ->
                applyImportedFont(imported)
            }.onFailure { err ->
                toast(err.message ?: "导入字体失败")
            }
        }
    }

    fun importCustomFontFromFile(path: String) {
        val ctx = appContext ?: return
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                com.littlecodesauce.ggnext.ui.theme.AppFontCache.importFromFile(ctx, path)
            }
            res.onSuccess { imported ->
                applyImportedFont(imported)
            }.onFailure { err ->
                toast(err.message ?: "导入字体失败")
            }
        }
    }

    private fun applyImportedFont(imported: com.littlecodesauce.ggnext.ui.theme.AppFontCache.ImportedFont) {
        var nextFonts: List<com.littlecodesauce.ggnext.ui.theme.AppFontCache.ImportedFont> = emptyList()
        _ui.update { st ->
            val updated = (listOf(imported) + st.managedFonts.filterNot { it.path == imported.path })
            nextFonts = updated
            val json = com.littlecodesauce.ggnext.ui.theme.AppFontCache.encodeFontList(updated)
            st.copy(
                settings = st.settings +
                    ("custom_font_path" to imported.path) +
                    ("custom_font_name" to imported.displayName) +
                    ("managed_fonts_json" to json),
                customFontPath = imported.path,
                customFontName = imported.displayName,
                managedFonts = updated
            )
        }
        val json = com.littlecodesauce.ggnext.ui.theme.AppFontCache.encodeFontList(nextFonts)
        enqueueSettingWrite("custom_font_path", imported.path, immediate = true)
        enqueueSettingWrite("custom_font_name", imported.displayName, immediate = true)
        enqueueSettingWrite("managed_fonts_json", json, immediate = true)
        toast("已导入并应用字体：${imported.displayName}")
    }

    /** 切换字体：path 为空代表切换为“系统默认” */
    fun selectFont(path: String, displayName: String = "") {
        if (path.isBlank()) {
            enqueueSettingWrite("custom_font_path", "", immediate = true)
            enqueueSettingWrite("custom_font_name", "", immediate = true)
            _ui.update { st ->
                st.copy(
                    settings = st.settings + ("custom_font_path" to "") + ("custom_font_name" to ""),
                    customFontPath = "",
                    customFontName = ""
                )
            }
            toast("已切换为系统默认字体")
            return
        }
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            toast("字体文件不存在，已自动移除该记录")
            deleteManagedFont(path, silent = true)
            return
        }
        val resolvedName = displayName.ifBlank { file.name }
        enqueueSettingWrite("custom_font_path", path, immediate = true)
        enqueueSettingWrite("custom_font_name", resolvedName, immediate = true)
        _ui.update { st ->
            st.copy(
                settings = st.settings + ("custom_font_path" to path) + ("custom_font_name" to resolvedName),
                customFontPath = path,
                customFontName = resolvedName
            )
        }
        toast("已切换字体：$resolvedName")
    }

    /** 删除单个已导入的字体；若正在使用则自动回退到“系统默认” */
    fun deleteManagedFont(path: String, silent: Boolean = false) {
        val ctx = appContext
        if (ctx != null) {
            scope.launch(Dispatchers.IO) {
                com.littlecodesauce.ggnext.ui.theme.AppFontCache.deleteManagedFont(ctx, path)
            }
        }
        var nextFonts: List<com.littlecodesauce.ggnext.ui.theme.AppFontCache.ImportedFont> = emptyList()
        var wasActive = false
        _ui.update { st ->
            val updated = st.managedFonts.filterNot { it.path == path }
            nextFonts = updated
            wasActive = st.customFontPath == path
            val json = com.littlecodesauce.ggnext.ui.theme.AppFontCache.encodeFontList(updated)
            st.copy(
                settings = st.settings +
                    ("managed_fonts_json" to json) +
                    if (wasActive) mapOf("custom_font_path" to "", "custom_font_name" to "") else emptyMap(),
                customFontPath = if (wasActive) "" else st.customFontPath,
                customFontName = if (wasActive) "" else st.customFontName,
                managedFonts = updated
            )
        }
        enqueueSettingWrite("managed_fonts_json", com.littlecodesauce.ggnext.ui.theme.AppFontCache.encodeFontList(nextFonts), immediate = true)
        if (wasActive) {
            enqueueSettingWrite("custom_font_path", "", immediate = true)
            enqueueSettingWrite("custom_font_name", "", immediate = true)
        }
        if (!silent) {
            toast(if (wasActive) "已删除字体并恢复系统默认" else "已删除字体")
        }
    }

    fun resetCustomFont(silent: Boolean = false) {
        enqueueSettingWrite("custom_font_path", "", immediate = true)
        enqueueSettingWrite("custom_font_name", "", immediate = true)
        _ui.update { st ->
            st.copy(
                settings = st.settings + ("custom_font_path" to "") + ("custom_font_name" to ""),
                customFontPath = "",
                customFontName = ""
            )
        }
        if (!silent) {
            toast("已恢复系统默认字体")
        }
    }

    fun acceptDisclaimer() {
        _ui.update { it.copy(disclaimerAccepted = true) }
        setSetting("disclaimer_v1_accepted", "1")
    }

    fun setHomeSettingsOpen(open: Boolean) {
        _ui.update {
            it.copy(
                homeSettingsOpen = open,
                homeTab = if (open) HomeTab.SETTINGS else HomeTab.HOME
            )
        }
    }

    fun selectHomeTab(tab: HomeTab) {
        _ui.update {
            it.copy(
                homeTab = tab,
                homeSettingsOpen = tab == HomeTab.SETTINGS
            )
        }
    }

    fun setFloatingBallVisible(visible: Boolean) = _ui.update {
        it.copy(
            floatingBallVisible = visible,
            panelVisible = if (!visible) false else it.panelVisible,
            speedBarExpanded = if (!visible) false else it.speedBarExpanded
        )
    }

    private fun applyAutoPauseIfEnabled(panelOpening: Boolean) {
        val st = _ui.value
        if (st.settings["autopause"] != "1") return
        val pid = st.target?.pid ?: return
        if (pid <= 0) return
        val sig = if (panelOpening) "-STOP" else "-CONT"
        _ui.update { it.copy(processPaused = panelOpening) }
        scope.launch(Dispatchers.IO) {
            engine.setFreezePaused(panelOpening)
            Shell.runAs(
                "kill $sig $pid",
                asRoot = (st.workMode == WorkMode.ROOT && Shell.hasRoot())
            )
        }
    }

    fun setPanelVisible(visible: Boolean) {
        val prev = _ui.value.panelVisible
        _ui.update {
            it.copy(
                panelVisible = visible,
                floatingBallVisible = if (visible) true else it.floatingBallVisible,
                speedBarExpanded = if (visible) false else it.speedBarExpanded
            )
        }
        if (prev != visible) {
            applyAutoPauseIfEnabled(visible)
        }
    }

    fun togglePanelVisible() {
        val next = !_ui.value.panelVisible
        _ui.update {
            it.copy(
                panelVisible = next,
                floatingBallVisible = true,
                speedBarExpanded = false,
                sheetStack = if (!next) emptyList() else it.sheetStack
            )
        }
        applyAutoPauseIfEnabled(next)
    }

    fun minimize() {
        val wasVisible = _ui.value.panelVisible
        _ui.update {
            it.copy(
                sheetStack = emptyList(),
                panelVisible = false,
                floatingBallVisible = true
            )
        }
        if (wasVisible) {
            applyAutoPauseIfEnabled(false)
        }
    }

    // ============ 悬浮球变速控制（ShadowHook + xDL Agent 真实变速） ============

    fun toggleSpeedBar() {
        _ui.update { st -> st.copy(speedBarExpanded = !st.speedBarExpanded) }
    }

    fun setSpeedBarExpanded(expanded: Boolean) = _ui.update {
        it.copy(speedBarExpanded = expanded)
    }

    fun setSpeedWrapEnabled(enabled: Boolean) {
        _ui.update { it.copy(speedWrapEnabled = enabled) }
    }

    fun stepSpeed(faster: Boolean) {
        val cur = _ui.value.speedMultiplier
        val idx = SPEED_STEPS.indexOfFirst { abs(it - cur) < 0.01f }.let {
            if (it >= 0) it else SPEED_STEPS.indexOfFirst { s -> s >= cur }.coerceAtLeast(0)
        }
        val nextIdx = (if (faster) idx + 1 else idx - 1).coerceIn(0, SPEED_STEPS.lastIndex)
        val nextSpeed = SPEED_STEPS[nextIdx]
        _ui.update { st -> st.copy(speedBarExpanded = true) }
        setSpeedMultiplier(nextSpeed)
    }

    fun setSpeedMultiplier(v: Float) {
        val clamped = v.coerceIn(0.01f, 100f)
        setSetting("speed_value", clamped.toString())
        _ui.update { it.copy(speedMultiplier = clamped) }
        val targetPkg = _ui.value.target?.packageName
        val wrapEnabled = _ui.value.speedWrapEnabled
        scope.launch {
            val (_, msg) = withContext(Dispatchers.IO) {
                engine.applySpeedMultiplier(clamped, targetPkg, enableWrapProperty = wrapEnabled)
            }
            toast(msg)
        }
    }

    fun resetSpeed() {
        setSpeedMultiplier(1.0f)
    }

    // ============ 进程 ============

    fun loadProcesses(app: Application) {
        scope.launch {
            _ui.update { it.copy(processLoading = true) }
            val list = withContext(Dispatchers.IO) { ProcessScanner.scan(app) }
            val defaultTarget = _ui.value.target ?: list.firstOrNull { !it.system } ?: list.firstOrNull()
            _ui.update { it.copy(processes = list, processLoading = false) }
            if (_ui.value.target == null && defaultTarget != null) {
                attach(defaultTarget, silent = true)
            }
        }
    }

    fun setProcessFilter(index: Int) {
        val clamped = index.coerceIn(0, 2)
        setSetting("process_filter", clamped.toString())
        _ui.update { st -> st.copy(processFilter = clamped) }
    }

    fun toggleProcessSortBySize() {
        val next = !_ui.value.processSortBySize
        setSetting("process_sort_by_size", if (next) "1" else "0")
        _ui.update { st -> st.copy(processSortBySize = next) }
    }

    fun toggleProcessPause() {
        val st = _ui.value
        val next = !st.processPaused
        val pid = st.target?.pid ?: 0
        if (pid > 0) {
            val sig = if (next) "-STOP" else "-CONT"
            scope.launch(Dispatchers.IO) {
                engine.setFreezePaused(next)
                Shell.runAs(
                    "kill $sig $pid",
                    asRoot = (st.workMode == WorkMode.ROOT && Shell.hasRoot())
                )
            }
        }
        _ui.update { it.copy(processPaused = next) }
        toast(if (next) "已暂停目标进程 ${st.target?.label.orEmpty()}" else "已恢复目标进程 ${st.target?.label.orEmpty()}")
    }

    fun attach(proc: TargetProcess, silent: Boolean = false, forceReattach: Boolean = false) {
        val currentTarget = _ui.value.target
        if (!forceReattach && currentTarget?.pid == proc.pid && _ui.value.attachedOk) {
            if (!silent) toast("当前已附加到 ${proc.label}")
            closeAllSheets()
            return
        }
        hasFuzzySession = false
        saveListScrollPosition("search_list", 0, 0)
        scope.launch {
            attachMutex.withLock {
                val mode = _ui.value.workMode
                var resolvedProc = proc
                var ok = withContext(Dispatchers.IO) {
                    engine.attach(
                        targetPid = resolvedProc.pid,
                        targetX64 = resolvedProc.x64,
                        isRootMode = (mode == WorkMode.ROOT),
                        context = appContext
                    )
                }
                if (!ok && resolvedProc.packageName.isNotBlank()) {
                    val app = appContext
                    if (app != null) {
                        val freshList = withContext(Dispatchers.IO) { ProcessScanner.scan(app) }
                        _ui.update { it.copy(processes = freshList) }
                        val replacement = freshList.firstOrNull {
                            it.packageName == resolvedProc.packageName && it.label == resolvedProc.label
                        } ?: freshList.firstOrNull {
                            it.packageName == resolvedProc.packageName
                        }
                        if (replacement != null && replacement.pid != resolvedProc.pid) {
                            resolvedProc = replacement
                            ok = withContext(Dispatchers.IO) {
                                engine.attach(
                                    targetPid = resolvedProc.pid,
                                    targetX64 = resolvedProc.x64,
                                    isRootMode = (mode == WorkMode.ROOT),
                                    context = appContext
                                )
                            }
                        }
                    }
                }
                val rawRegions = withContext(Dispatchers.IO) { engine.regions() }
                val regions = withContext(Dispatchers.IO) { aggregate(rawRegions) }
                val defaultBase = rawRegions.firstOrNull { it.code == "Xa" || it.code == "Cd" || it.code == "Jh" || it.code == "Ca" }?.start
                    ?: rawRegions.firstOrNull { it.readable }?.start
                    ?: 0L

                // 若持久化恢复的保存列表中存在已冻结项，重新附加后自动下发到底层 ValueLocker
                if (ok) {
                    val savedFrozen = _ui.value.saved.filter { it.freeze }
                    if (savedFrozen.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            savedFrozen.forEach { item ->
                                engine.setFreeze(
                                    address = item.address,
                                    flags = item.flags,
                                    value = item.value,
                                    enabled = true,
                                    freezeType = item.freezeType,
                                    freezeFrom = item.freezeFrom,
                                    freezeTo = item.freezeTo
                                )
                            }
                        }
                    }
                }

                if (currentTarget != null && currentTarget.pid != resolvedProc.pid) {
                    withContext(Dispatchers.IO) { engine.bpStopAll() }
                    bpPollingJob?.cancel()
                    bpPollingJob = null
                }
                _ui.update {
                    val pkgRegions = resolveRegionsForPackage(it.settings, resolvedProc.packageName)
                    it.copy(
                        target = resolvedProc,
                        processPaused = false,
                        attachedOk = ok,
                        regions = regions,
                        selectedRegions = pkgRegions,
                        memoryBase = if (defaultBase != 0L) defaultBase else it.memoryBase,
                        sheetStack = if (silent) it.sheetStack else emptyList(),
                        hintVisible = true,
                        rawResults = emptyList(),
                        results = emptyList(),
                        totalCount = 0,
                        searchSelection = SelectionState.EMPTY,
                        memorySelection = SelectionState.EMPTY,
                        memoryRows = emptyList(),
                        memoryDisasm = emptyMap(),
                        memoryPointerChain = if (currentTarget?.pid != resolvedProc.pid) emptyList() else it.memoryPointerChain,
                        memoryPointerIndex = if (currentTarget?.pid != resolvedProc.pid) -1 else it.memoryPointerIndex,
                        breakpoints = if (currentTarget?.pid != resolvedProc.pid) emptyList() else it.breakpoints,
                        selectedBpIds = if (currentTarget?.pid != resolvedProc.pid) emptySet() else it.selectedBpIds,
                        debugExpandedBps = if (currentTarget?.pid != resolvedProc.pid) emptyMap() else it.debugExpandedBps,
                        debugExpandedHits = if (currentTarget?.pid != resolvedProc.pid) emptyMap() else it.debugExpandedHits,
                        fuzzy = it.fuzzy.copy(active = false)
                    )
                }
                AppLog.add("attach", "${resolvedProc.label} pid=${resolvedProc.pid} ok=$ok regions=${rawRegions.size}")
                if (!silent) {
                    if (ok) {
                        toast("已附加到 ${resolvedProc.label}")
                    } else {
                        val detail = engine.lastAttachError.ifBlank { "无跨进程内存读写权限，请检查 Root 授权" }
                        toast("附加 ${resolvedProc.label} 失败：$detail")
                    }
                }
            }
        }
    }

    fun refreshLiveValues(manual: Boolean = false) {
        val st = _ui.value
        if (!st.attachedOk || st.target == null) {
            if (manual) toast("请先附加目标进程")
            return
        }
        if (st.busy) return
        if (liveRefreshRunning && !manual) return
        liveRefreshRunning = true

        scope.launch(Dispatchers.IO) {
            try {
                val cur = _ui.value
                val liveResultMap: Map<Long, Long> = if (cur.results.isNotEmpty() && (manual || cur.tab == NextTab.SEARCH_LIST)) {
                    val scrollIdx = getListScrollPosition("search_list").first.coerceIn(0, (cur.results.size - 1).coerceAtLeast(0))
                    val startIdx = (scrollIdx - 15).coerceAtLeast(0)
                    val endIdx = (scrollIdx + 65).coerceAtMost(cur.results.size)
                    val slice = cur.results.subList(startIdx, endIdx)
                    engine.batchReadRawValues(slice.map { it.address to it.size })
                } else emptyMap()

                val liveSavedMap: Map<Long, Long> = if (cur.saved.isNotEmpty() && (manual || cur.tab == NextTab.SAVED_LIST)) {
                    val scrollIdx = getListScrollPosition("saved_list").first.coerceIn(0, (cur.saved.size - 1).coerceAtLeast(0))
                    val startIdx = (scrollIdx - 15).coerceAtLeast(0)
                    val endIdx = (scrollIdx + 65).coerceAtMost(cur.saved.size)
                    val slice = cur.saved.subList(startIdx, endIdx)
                    val dynamicOrUnfrozen = slice.filter { !it.freeze || it.freezeType != 0 }.map { it.address to it.size }
                    if (dynamicOrUnfrozen.isNotEmpty()) engine.batchReadRawValues(dynamicOrUnfrozen) else emptyMap()
                } else emptyMap()

                val pageBase = cur.memoryBase
                val memCount = cur.memoryRows.size
                val memBlock: ByteArray? = if (memCount > 0 && (manual || cur.tab == NextTab.MEMORY)) {
                    engine.readBlock(pageBase, memCount * 4)
                } else null

                if (liveResultMap.isEmpty() && liveSavedMap.isEmpty() && memBlock == null) {
                    if (manual) withContext(Dispatchers.Main) { toast("已刷新实时内存数据") }
                    return@launch
                }

                _ui.update { s ->
                    var nextResults = s.results
                    var nextRaw = s.rawResults
                    if (liveResultMap.isNotEmpty() && s.results.isNotEmpty()) {
                        var changedAny = false
                        val updatedRes = ArrayList<MemoryItem>(s.results.size)
                        for (item in s.results) {
                            val freshVal = liveResultMap[item.address]
                            if (freshVal != null && freshVal != item.value) {
                                changedAny = true
                                updatedRes.add(item.copy(value = freshVal, pointer = engine.classifyPointer(freshVal)))
                            } else {
                                updatedRes.add(item)
                            }
                        }
                        if (changedAny) {
                            nextResults = updatedRes
                            if (s.rawResults === s.results) {
                                nextRaw = updatedRes
                            } else if (s.rawResults.size <= 5000) {
                                val updatedRaw = ArrayList<MemoryItem>(s.rawResults.size)
                                for (r in s.rawResults) {
                                    val freshVal = liveResultMap[r.address]
                                    if (freshVal != null && freshVal != r.value) {
                                        updatedRaw.add(r.copy(value = freshVal, pointer = engine.classifyPointer(freshVal)))
                                    } else {
                                        updatedRaw.add(r)
                                    }
                                }
                                nextRaw = updatedRaw
                            }
                        }
                    }

                    var nextSaved = s.saved
                    if (liveSavedMap.isNotEmpty() && s.saved.isNotEmpty()) {
                        var savedChanged = false
                        val updatedSaved = ArrayList<MemoryItem>(s.saved.size)
                        for (item in s.saved) {
                            if (item.freeze && item.freezeType == 0) {
                                updatedSaved.add(item)
                            } else {
                                val freshVal = liveSavedMap[item.address]
                                if (freshVal != null && freshVal != item.value) {
                                    savedChanged = true
                                    updatedSaved.add(item.copy(value = freshVal, pointer = engine.classifyPointer(freshVal)))
                                } else {
                                    updatedSaved.add(item)
                                }
                            }
                        }
                        if (savedChanged) nextSaved = updatedSaved
                    }

                    var nextMemRows = s.memoryRows
                    if (memBlock != null && s.memoryRows.size == memCount && s.memoryBase == pageBase) {
                        var memChanged = false
                        val updatedMem = ArrayList<MemoryItem>(memCount)
                        for (r in 0 until memCount) {
                            val oldItem = s.memoryRows[r]
                            val off = r * 4
                            val v = if (off + 4 <= memBlock.size) {
                                (memBlock[off].toLong() and 0xFF) or
                                    ((memBlock[off + 1].toLong() and 0xFF) shl 8) or
                                    ((memBlock[off + 2].toLong() and 0xFF) shl 16) or
                                    ((memBlock[off + 3].toLong() and 0xFF) shl 24)
                            } else oldItem.value
                            if (v != oldItem.value) {
                                memChanged = true
                                updatedMem.add(oldItem.copy(value = v, previousValue = oldItem.value))
                            } else {
                                updatedMem.add(oldItem)
                            }
                        }
                        if (memChanged) nextMemRows = updatedMem
                    }

                    if (nextResults === s.results && nextRaw === s.rawResults && nextSaved === s.saved && nextMemRows === s.memoryRows) {
                        s
                    } else {
                        s.copy(
                            results = nextResults,
                            rawResults = nextRaw,
                            saved = nextSaved,
                            memoryRows = nextMemRows
                        )
                    }
                }
                if (manual) withContext(Dispatchers.Main) { toast("已刷新实时内存数据") }
            } finally {
                liveRefreshRunning = false
            }
        }
    }

    private fun makeProgressCallback(label: String, startTimeMs: Long): (Long, Long, Long) -> Unit = { doneBytes, totalBytes, foundSoFar ->
        val frac = if (totalBytes > 0L) (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
        val elapsedSec = ((System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)) / 1000.0
        val eta = if (frac > 0.03f && frac < 0.995f) {
            ((elapsedSec / frac) * (1.0 - frac)).toInt().coerceAtLeast(0)
        } else if (frac >= 0.995f) {
            0
        } else {
            -1
        }
        val scannedMb = doneBytes.toFloat() / (1024f * 1024f)
        val totalMb = totalBytes.toFloat() / (1024f * 1024f)
        _searchProgressState.value = SearchProgressUi(
            active = true,
            text = label,
            progress = frac,
            foundSoFar = foundSoFar,
            etaSeconds = eta,
            scannedMb = scannedMb,
            totalMb = totalMb
        )
    }

    /** 端到端扫描取消（模块 2）：立即下发 CANCEL 到 C++ 守护进程/JNI 及 JVM 回退 */
    fun cancelSearch() {
        engine.cancelScan()
        activeSearchJob?.cancel()
        activeSearchJob = null
        _searchProgressState.value = SearchProgressUi()
        _ui.update { it.copy(busy = false) }
        toast("已取消搜索")
    }

    private val regionNames = mapOf(
        "Ch" to "C++ heap", "Jh" to "Java heap", "Ca" to "C++ alloc", "Cd" to "C++ .data",
        "Cb" to "C++ .bss", "A" to "Anonymous", "S" to "Stack", "Xa" to "Code app",
        "Xs" to "Code system", "J" to "Java", "B" to "Bad", "Ps" to "PPSSPP",
        "As" to "Ashmem", "V" to "Video", "O" to "Other"
    )

    private fun aggregate(raw: List<MemoryRegion>): List<RegionRow> {
        val base = raw.groupBy { it.code }
        return RegionCatalog.ALL.map { rt ->
            val items = base[rt.code].orEmpty()
            RegionRow(
                code = rt.code,
                name = regionNames[rt.code] ?: rt.name,
                size = items.sumOf { it.size },
                count = items.size
            )
        }
    }

    fun toggleRegion(code: String) {
        var nextSet: Set<String> = emptySet()
        var pkgName: String? = null
        _ui.update { cur ->
            val updated = if (cur.selectedRegions.contains(code)) cur.selectedRegions - code else cur.selectedRegions + code
            nextSet = updated
            pkgName = cur.target?.packageName
            cur.copy(selectedRegions = updated)
        }
        persistSelectedRegions(nextSet, pkgName, immediate = false)
    }

    fun resetRegions() {
        val defaultSet = RegionCatalog.DEFAULT_SELECTED
        var pkgName: String? = null
        _ui.update { cur ->
            pkgName = cur.target?.packageName
            cur.copy(selectedRegions = defaultSet)
        }
        persistSelectedRegions(defaultSet, pkgName, immediate = true)
    }

    fun saveRegions() {
        val cur = _ui.value
        persistSelectedRegions(cur.selectedRegions, cur.target?.packageName, immediate = true)
        closeAllSheets()
        toast("已保存内存范围设置")
    }

    /**
     * 计算本次搜索从 C++ 层拉取到 Kotlin 列表的最大条目数：
     * C++ `MmapCandidateStore` 已完整保存全部数百万候选以供改善 (Refine)，
     * UI 层仅需按当前显示上限 `filter.maxShow`（开启过滤条件时适度放宽缓冲）拉取即可，
     * 避免每次搜索结束后通过管道传输 10 万条数据造成严重卡顿与堆内存膨胀。
     */
    private fun computeNativeFetchLimit(filter: FilterState): Int {
        if (!engine.supportsNativeScan) return MemoryEngine.MAX_CACHED_CANDIDATES
        val maxShow = filter.maxShow.coerceIn(1, MemoryEngine.MAX_CACHED_CANDIDATES)
        return if (filter.isAnyFilterActive) {
            maxOf(maxShow * 4, 4000).coerceAtMost(MemoryEngine.MAX_CACHED_CANDIDATES)
        } else {
            maxShow
        }
    }

    // ============ 精确 / 范围 / 联合 / 蒙版 / 指针 / 模糊搜索 ============

    fun updateSearch(f: (SearchInput) -> SearchInput) {
        val prev = _ui.value.search
        val next = f(prev)
        _ui.update { it.copy(search = next) }
        if (next.type != prev.type) setSetting("search_type", next.type.toString())
        if (next.pointerType != prev.pointerType) setSetting("pointer_type", next.pointerType.toString())
        if (next.offsetHex != prev.offsetHex) setSetting("pointer_offset_hex", if (next.offsetHex) "1" else "0")
    }

    fun updateFuzzy(f: (FuzzyState) -> FuzzyState) {
        val prev = _ui.value.fuzzy
        val next = f(prev)
        _ui.update { it.copy(fuzzy = next) }
        if (next.type != prev.type) setSetting("fuzzy_type", next.type.toString())
    }

    fun updateFilter(f: (FilterState) -> FilterState) = _ui.update { it.copy(filter = f(it.filter)) }

    /** 解析精确搜索可选地址掩码（默认全 F 代表禁用掩码过滤） */
    private fun parseOptionalAddressMask(rawMask: String): Pair<Long, Long> {
        val clean = rawMask.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H").uppercase()
        if (clean.isEmpty() || clean.all { it == 'F' } || clean == "0") return 0L to 0L
        var maskBits = 0L
        var patternBits = 0L
        for (ch in clean) {
            maskBits = maskBits shl 4
            patternBits = patternBits shl 4
            if (ch != '?' && ch != '*') {
                val digit = Character.digit(ch, 16)
                if (digit in 0..15) {
                    maskBits = maskBits or 0xFL
                    patternBits = patternBits or digit.toLong()
                }
            }
        }
        return maskBits to patternBits
    }

    fun runSearch(newSearch: Boolean) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        val input = st.search.text.trim()
        if (input.isEmpty()) {
            toast("请输入搜索条件")
            return
        }
        if (newSearch) {
            hasFuzzySession = false
            engine.clearRemoved()
            saveListScrollPosition("search_list", 0, 0)
        }
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            val parsed = try {
                NumberParser.parse(input, st.search.type, st.search.sign)
            } catch (e: NumberParser.ParseException) {
                toast(e.message ?: "输入有误")
                return@launch
            }
            val displayInput = if (parsed.kind == NumberParser.Kind.GROUP) {
                NumberParser.normalizeGroupExpression(input, parsed.ordered, parsed.area)
            } else input
            val startTime = System.currentTimeMillis()
            val label = if (newSearch) "新搜索: $displayInput" else "改善: $displayInput"
            _searchProgressState.value = SearchProgressUi(active = true, text = label)
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = label,
                    sheetStack = emptyList(),
                    tab = NextTab.SEARCH_LIST,
                    search = if (displayInput != it.search.text) it.search.copy(text = displayInput) else it.search
                )
            }
            val type = parsed.baseType and 0x7F
            val isRange = parsed.kind == NumberParser.Kind.RANGE
            val memFrom = when (st.search.rangeMode) {
                1 -> st.search.rangeFrom.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                2 -> {
                    val base = st.search.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.search.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.search.nearbyBefore) (base - dist).coerceAtLeast(0L) else base
                }
                else -> 0L
            }
            val memTo = when (st.search.rangeMode) {
                1 -> st.search.rangeTo.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: Long.MAX_VALUE
                2 -> {
                    val base = st.search.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.search.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.search.nearbyAfter) base + dist else base
                }
                else -> Long.MAX_VALUE
            }
            val (addrMask, addrPattern) = parseOptionalAddressMask(st.search.mask)
            val criteria = MemoryEngine.Criteria(
                type = type,
                value = parsed.numbers.firstOrNull() ?: 0L,
                from = parsed.numbers.firstOrNull() ?: 0L,
                to = parsed.to.firstOrNull() ?: 0L,
                isRange = isRange,
                notRange = parsed.notRange,
                sign = parsed.sign,
                xorKey = parsed.xorKey,
                xorOffset = parsed.xorOffset,
                orderedGroup = parsed.numbers,
                groupTo = parsed.to,
                groupOps = parsed.groupOps,
                ordered = parsed.ordered,
                groupTypes = parsed.types,
                area = parsed.area.coerceIn(2, 65536),
                memoryFrom = memFrom,
                memoryTo = memTo,
                limit = computeNativeFetchLimit(st.filter),
                rawExpression = displayInput,
                encryptedXor = st.search.encrypted,
                selectedRegions = st.selectedRegions,
                mask = addrMask,
                maskPattern = addrPattern,
                isText = (parsed.kind == NumberParser.Kind.TEXT),
                textBytes = parsed.textBytes,
                textUtf16 = parsed.textUtf16
            )
            val isRefine = !newSearch && (hasFuzzySession || st.rawResults.isNotEmpty() || st.totalCount > 0L)
            val previous = if (isRefine) st.rawResults else null
            val progressCb = makeProgressCallback(label, startTime)
            val activeFilter = _ui.value.filter
            val (result, filtered) = withContext(Dispatchers.IO) {
                val res = engine.search(criteria, previous, progressCb)
                val flt = if (res.cancelled) emptyList() else filterList(res.items, activeFilter)
                res to flt
            }
            _searchProgressState.value = SearchProgressUi()
            if (result.cancelled) {
                _ui.update { it.copy(busy = false) }
                return@launch
            }
            hasFuzzySession = false
            if (historyStore.add(displayInput)) {
                persistSearchHistoryAsync()
            }
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    hintVisible = filtered.isEmpty(),
                    searchSelection = SelectionState.EMPTY,
                    searchType = type,
                    history = historyStore.list(),
                    fuzzy = it.fuzzy.copy(active = false)
                )
            }
            toast("找到 ${result.totalFound} 条结果")
        }
    }

    /** 执行模糊搜索（支持 onlyNth 步进下推到 C++ 层） */
    fun runFuzzySearch(sign: Int, forceNew: Boolean = false) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            val isInit = forceNew || (!hasFuzzySession && st.rawResults.isEmpty())
            if (isInit) {
                saveListScrollPosition("search_list", 0, 0)
            }
            val actionName = when (sign and ValueType.SIGN_MASK) {
                ValueType.SIGN_NOT_EQUAL -> "有变化"
                ValueType.SIGN_GREATER_EQ -> "增加了"
                ValueType.SIGN_LESS_EQ -> "减少了"
                else -> "无变化"
            }
            val startTime = System.currentTimeMillis()
            val label = if (isInit) "模糊初始采样中..." else "模糊筛选 ($actionName)..."
            _searchProgressState.value = SearchProgressUi(active = true, text = label)
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = label,
                    sheetStack = emptyList(),
                    tab = NextTab.SEARCH_LIST
                )
            }
            val delta = st.fuzzy.deltaText.trim().toLongOrNull() ?: 0L
            val onlyNthVal = st.fuzzy.onlyNth.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            val memFrom = when (st.fuzzy.rangeMode) {
                1 -> st.fuzzy.rangeFrom.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                2 -> {
                    val base = st.fuzzy.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.fuzzy.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.fuzzy.nearbyBefore) (base - dist).coerceAtLeast(0L) else base
                }
                else -> 0L
            }
            val memTo = when (st.fuzzy.rangeMode) {
                1 -> st.fuzzy.rangeTo.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: Long.MAX_VALUE
                2 -> {
                    val base = st.fuzzy.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.fuzzy.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.fuzzy.nearbyAfter) base + dist else base
                }
                else -> Long.MAX_VALUE
            }
            val prev = if (isInit) null else st.rawResults
            val progressCb = makeProgressCallback(label, startTime)
            val activeFilter = _ui.value.filter
            val fetchLimit = computeNativeFetchLimit(activeFilter)
            val (result, filtered) = withContext(Dispatchers.IO) {
                val res = engine.fuzzySearch(
                    type = st.fuzzy.type,
                    sign = sign,
                    delta = delta,
                    onlyNth = onlyNthVal,
                    previous = prev,
                    selectedRegions = st.selectedRegions,
                    limit = fetchLimit,
                    memoryFrom = memFrom,
                    memoryTo = memTo,
                    onProgress = progressCb
                )
                val flt = if (res.cancelled) emptyList() else filterList(res.items, activeFilter)
                res to flt
            }
            _searchProgressState.value = SearchProgressUi()
            if (result.cancelled) {
                _ui.update { it.copy(busy = false) }
                return@launch
            }
            hasFuzzySession = result.totalFound > 0L || result.items.isNotEmpty()
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    hintVisible = filtered.isEmpty(),
                    searchSelection = SelectionState.EMPTY,
                    fuzzy = it.fuzzy.copy(active = hasFuzzySession)
                )
            }
            toast(
                if (isInit) "已完成初始模糊采样，共 ${result.totalFound} 项"
                else "模糊筛选 $actionName：剩余 ${result.totalFound} 项"
            )
        }
    }

    /** 蒙版搜索 */
    fun runMaskSearch(newSearch: Boolean) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        if (newSearch) {
            hasFuzzySession = false
            saveListScrollPosition("search_list", 0, 0)
        }
        val rawAddr = st.search.text.trim().removePrefix("0x").removeSuffix("h").replace("?", "0")
        val rawMask = st.search.mask.trim().removePrefix("0x").removeSuffix("h")
        val addrVal = rawAddr.toULongOrNull(16)?.toLong() ?: rawAddr.toLongOrNull(16) ?: st.memoryBase
        val maskVal = rawMask.toULongOrNull(16)?.toLong() ?: -1L
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            val startTime = System.currentTimeMillis()
            val label = "地址蒙版搜索中..."
            _searchProgressState.value = SearchProgressUi(active = true, text = label)
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = label,
                    sheetStack = emptyList(),
                    tab = NextTab.SEARCH_LIST
                )
            }
            val prev = if (!newSearch && st.rawResults.isNotEmpty()) st.rawResults else null
            val progressCb = makeProgressCallback(label, startTime)
            val activeFilter = _ui.value.filter
            val fetchLimit = computeNativeFetchLimit(activeFilter)
            val (result, filtered) = withContext(Dispatchers.IO) {
                val res = engine.maskSearch(
                    addressPattern = addrVal,
                    maskBits = maskVal,
                    type = st.search.type,
                    sign = st.search.sign,
                    previous = prev,
                    selectedRegions = st.selectedRegions,
                    limit = fetchLimit,
                    onProgress = progressCb
                )
                val flt = if (res.cancelled) emptyList() else filterList(res.items, activeFilter)
                res to flt
            }
            _searchProgressState.value = SearchProgressUi()
            if (result.cancelled) {
                _ui.update { it.copy(busy = false) }
                return@launch
            }
            if (st.search.text.isNotBlank() && historyStore.add(st.search.text.trim())) {
                persistSearchHistoryAsync()
            }
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    hintVisible = filtered.isEmpty(),
                    searchSelection = SelectionState.EMPTY,
                    history = historyStore.list()
                )
            }
            toast("蒙版搜索找到 ${result.totalFound} 项")
        }
    }

    /** 指针搜索 */
    fun runPointerSearch(newSearch: Boolean) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        if (newSearch) {
            hasFuzzySession = false
            engine.clearRemoved()
            saveListScrollPosition("search_list", 0, 0)
        }
        val rawAddr = st.search.text.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
        val targetAddr = rawAddr.toULongOrNull(16)?.toLong()
            ?: rawAddr.toLongOrNull(16)
            ?: st.contextItem?.address
            ?: st.editing?.address
            ?: st.memoryHighlightAddress
            ?: st.memoryBase
        val rawOff = st.search.offset.trim()
        val isExplicitHex = st.search.offsetHex || rawOff.startsWith("0x", true) || rawOff.endsWith("h", true)
        val cleanOff = rawOff.removePrefix("-").removePrefix("+").removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
        val maxOffset = (if (isExplicitHex) {
            cleanOff.toULongOrNull(16)?.toLong() ?: 0x200L
        } else {
            cleanOff.toLongOrNull() ?: cleanOff.toULongOrNull(16)?.toLong() ?: 512L
        }).coerceAtLeast(0L)
        val ptrType = if (st.search.pointerType == ValueType.DWORD || st.search.pointerType == ValueType.QWORD) {
            st.search.pointerType
        } else {
            if (st.target.x64) ValueType.QWORD else ValueType.DWORD
        }
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            val startTime = System.currentTimeMillis()
            val label = "指针链搜索中..."
            _searchProgressState.value = SearchProgressUi(active = true, text = label)
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = label,
                    sheetStack = emptyList(),
                    tab = NextTab.SEARCH_LIST
                )
            }
            val prev = if (!newSearch && st.rawResults.isNotEmpty()) st.rawResults else null
            val progressCb = makeProgressCallback(label, startTime)
            val activeFilter = _ui.value.filter
            val fetchLimit = computeNativeFetchLimit(activeFilter)
            val (result, filtered) = withContext(Dispatchers.IO) {
                val res = engine.pointerSearch(
                    targetAddress = targetAddr,
                    maxOffset = maxOffset,
                    type = ptrType,
                    previous = prev,
                    selectedRegions = st.selectedRegions,
                    limit = fetchLimit,
                    onProgress = progressCb
                )
                val flt = if (res.cancelled) emptyList() else filterList(res.items, activeFilter)
                res to flt
            }
            _searchProgressState.value = SearchProgressUi()
            if (result.cancelled) {
                _ui.update { it.copy(busy = false) }
                return@launch
            }
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    hintVisible = filtered.isEmpty(),
                    searchSelection = SelectionState.EMPTY,
                    searchType = ptrType
                )
            }
            toast("指针搜索找到 ${result.totalFound} 条结果")
        }
    }

    // ============ 结果过滤/筛选 ============

    fun applyFilter(newFilter: FilterState) {
        val st = _ui.value
        val maxShow = newFilter.maxShow.coerceIn(1, 100000)
        setSetting("filter_max_show", maxShow.toString())
        val normalized = newFilter.copy(maxShow = maxShow)

        var mask = st.valueMask and Formatter.AUX_FORMAT_MASK
        fun setBit(bit: Int, on: Boolean) {
            mask = if (on) (mask or bit) else (mask and bit.inv())
        }
        setBit(Formatter.F_HEX, normalized.formatHex)
        setBit(Formatter.F_RHEX, normalized.formatRHex)
        setBit(Formatter.F_STRING, normalized.formatString)
        setBit(Formatter.F_JAVA, normalized.formatUtf16)
        setBit(Formatter.F_ARM, normalized.formatArm)
        setBit(Formatter.F_THUMB, normalized.formatThumb)
        setBit(Formatter.F_ARM8, normalized.formatArm64)
        mask = mask and Formatter.AUX_FORMAT_MASK
        repo?.let { r -> scope.launch(Dispatchers.IO) { r.setValueFormatMask(mask) } }

        val desiredCap = computeNativeFetchLimit(normalized)
        val needExpandFromNative = engine.supportsNativeScan &&
            st.totalCount > st.rawResults.size.toLong() &&
            desiredCap > st.rawResults.size

        if (!needExpandFromNative) {
            val filtered = filterList(st.rawResults, normalized)
            val validKeys = filtered.mapTo(HashSet(filtered.size * 2)) { SelectionState.itemKey(it) }
            _ui.update {
                it.copy(
                    filter = normalized,
                    results = filtered,
                    searchSelection = it.searchSelection.prune(validKeys),
                    valueMask = mask,
                    sheetStack = emptyList()
                )
            }
            toast("已应用筛选")
            return
        }

        _ui.update {
            it.copy(
                filter = normalized,
                valueMask = mask,
                sheetStack = emptyList(),
                isBatchBusy = true
            )
        }
        scope.launch {
            val (expandedRaw, filtered) = withContext(Dispatchers.IO) {
                val fetched = engine.fetchStoredCandidates(
                    limit = desiredCap,
                    defaultType = st.searchType,
                    useCandidateDt = true
                )
                val base = if (fetched.isNotEmpty()) fetched else st.rawResults
                base to filterList(base, normalized)
            }
            val validKeys = filtered.mapTo(HashSet(filtered.size * 2)) { SelectionState.itemKey(it) }
            _ui.update {
                it.copy(
                    rawResults = expandedRaw,
                    results = filtered,
                    searchSelection = it.searchSelection.prune(validKeys),
                    isBatchBusy = false
                )
            }
            toast("已应用筛选")
        }
    }

    fun resetFilter() {
        val def = FilterState()
        setSetting("filter_max_show", def.maxShow.toString())
        repo?.let { r -> scope.launch(Dispatchers.IO) { r.setValueFormatMask(0) } }
        val filtered = filterList(_ui.value.rawResults, def)
        val validKeys = filtered.mapTo(HashSet(filtered.size * 2)) { SelectionState.itemKey(it) }
        _ui.update {
            it.copy(
                filter = def,
                results = filtered,
                searchSelection = it.searchSelection.prune(validKeys),
                valueMask = 0
            )
        }
        toast("已重置筛选条件")
    }

    private fun filterList(source: List<MemoryItem>, f: FilterState): List<MemoryItem> {
        if (source.isEmpty()) return emptyList()
        val addrMin = if (f.addrGreatEnabled) f.addrGreat.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() else null
        val addrMax = if (f.addrLessEnabled) f.addrLess.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() else null
        val valMin = if (f.valGreatEnabled) f.valGreat.trim().toLongOrNull() else null
        val valMax = if (f.valLessEnabled) f.valLess.trim().toLongOrNull() else null
        val fracTarget = if (f.fractionalEnabled) f.fractional.trim().removePrefix(".") else null

        val seenKeys = HashSet<Long>(minOf(source.size, f.maxShow) * 2)
        val out = ArrayList<MemoryItem>(minOf(source.size, f.maxShow))
        for (item in source) {
            if (!seenKeys.add(SelectionState.itemKey(item))) continue
            if (addrMin != null && java.lang.Long.compareUnsigned(item.address, addrMin) < 0) continue
            if (addrMax != null && java.lang.Long.compareUnsigned(item.address, addrMax) > 0) continue
            val signedVal = Formatter.signed(item.value, item.size)
            if (valMin != null && signedVal < valMin) continue
            if (valMax != null && signedVal > valMax) continue
            if (f.typeEnabled && f.types.isNotEmpty() && (item.flags and 0x7F) !in f.types) continue
            if (f.pointerEnabled && f.pointers.isNotEmpty()) {
                val pCode = if (item.pointer == 0) 4 else item.pointer
                if (pCode !in f.pointers) continue
            }
            if (fracTarget != null && fracTarget.isNotEmpty()) {
                val floatStr = when (item.flags and 0x7F) {
                    ValueType.FLOAT -> Formatter.floatText(item.value)
                    ValueType.DOUBLE -> Formatter.doubleText(item.value)
                    else -> "0.0"
                }
                val afterDot = floatStr.substringAfter('.', "0")
                val matchesFrac = afterDot.startsWith(fracTarget)
                if (f.fractionalNotEqual && matchesFrac) continue
                if (!f.fractionalNotEqual && !matchesFrac) continue
            }
            out.add(item)
            if (out.size >= f.maxShow.coerceAtLeast(1)) break
        }
        return out
    }

    fun clearResults() {
        hasFuzzySession = false
        saveListScrollPosition("search_list", 0, 0)
        scope.launch(Dispatchers.IO) { engine.clearSession() }
        _ui.update {
            it.copy(
                rawResults = emptyList(),
                results = emptyList(),
                totalCount = 0,
                searchSelection = SelectionState.EMPTY,
                hintVisible = true,
                fuzzy = it.fuzzy.copy(active = false)
            )
        }
    }

    // ============ O(1) 选择操作（模块 4：零列表拷贝，常数时间） ============

    private fun sameItemKey(a: MemoryItem, b: MemoryItem): Boolean =
        a.address == b.address && (a.flags and 0x7F) == (b.flags and 0x7F)

    fun toggle(item: MemoryItem) {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> s.copy(savedSelection = s.savedSelection.toggle(SelectionState.itemKey(item)))
                NextTab.MEMORY -> s.copy(memorySelection = s.memorySelection.toggle(SelectionState.memoryRowKey(item)))
                NextTab.SEARCH_LIST -> s.copy(searchSelection = s.searchSelection.toggle(SelectionState.itemKey(item)))
                NextTab.SETTINGS, NextTab.DEBUG -> s
            }
        }
    }

    fun selectAll() {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> if (s.saved.isEmpty()) s else s.copy(savedSelection = s.savedSelection.selectAll())
                NextTab.MEMORY -> if (s.memoryRows.isEmpty()) s else s.copy(memorySelection = s.memorySelection.selectAll())
                NextTab.SEARCH_LIST -> if (s.results.isEmpty()) s else s.copy(searchSelection = s.searchSelection.selectAll())
                NextTab.DEBUG -> s.copy(selectedBpIds = s.breakpoints.map { it.id }.toSet())
                NextTab.SETTINGS -> s
            }
        }
    }

    fun invertSelection() {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> if (s.saved.isEmpty()) s else s.copy(savedSelection = s.savedSelection.invert())
                NextTab.MEMORY -> if (s.memoryRows.isEmpty()) s else s.copy(memorySelection = s.memorySelection.invert())
                NextTab.SEARCH_LIST -> if (s.results.isEmpty()) s else s.copy(searchSelection = s.searchSelection.invert())
                NextTab.DEBUG -> {
                    val all = s.breakpoints.map { it.id }.toSet()
                    s.copy(selectedBpIds = all - s.selectedBpIds)
                }
                NextTab.SETTINGS -> s
            }
        }
    }

    fun clearSelection() {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> s.copy(savedSelection = SelectionState.EMPTY)
                NextTab.MEMORY -> s.copy(memorySelection = SelectionState.EMPTY)
                NextTab.SEARCH_LIST -> s.copy(searchSelection = SelectionState.EMPTY)
                NextTab.DEBUG -> s.copy(selectedBpIds = emptySet())
                NextTab.SETTINGS -> s
            }
        }
    }

    fun checkedItems(): List<MemoryItem> {
        val st = _ui.value
        return when (st.tab) {
            NextTab.SAVED_LIST -> st.savedSelection.resolveSelected(st.saved, SelectionState::itemKey)
            NextTab.MEMORY -> st.memorySelection.resolveSelected(st.memoryRows, SelectionState::memoryRowKey)
            NextTab.SEARCH_LIST -> st.searchSelection.resolveSelected(st.results, SelectionState::itemKey)
            NextTab.SETTINGS, NextTab.DEBUG -> emptyList()
        }
    }

    fun itemsToEdit(): List<MemoryItem> {
        val st = _ui.value
        if (st.editSingleOnly && st.editing != null) {
            return listOf(st.editing)
        }
        val checked = checkedItems()
        if (checked.isNotEmpty()) return checked
        st.editing?.let { return listOf(it) }
        return when (st.tab) {
            NextTab.SAVED_LIST -> st.saved
            NextTab.MEMORY -> st.memoryRows
            NextTab.SEARCH_LIST -> st.results
            NextTab.SETTINGS, NextTab.DEBUG -> emptyList()
        }
    }

    // ============ 保存 / 删除 / 冻结 / 恢复（全部异步 IO） ============

    fun saveChecked() {
        val selected = checkedItems()
        if (selected.isEmpty()) {
            toast("无项目被选中")
            return
        }
        scope.launch(Dispatchers.Default) {
            val cur = _ui.value
            val map = LinkedHashMap<Long, MemoryItem>((cur.saved.size + selected.size) * 4 / 3 + 16)
            for (existing in cur.saved) {
                map[SelectionState.itemKey(existing)] = existing
            }
            for (item in selected) {
                val k = SelectionState.itemKey(item)
                val prev = map[k]
                map[k] = if (prev != null) {
                    item.copy(checked = false, freeze = prev.freeze, freezeType = prev.freezeType, freezeFrom = prev.freezeFrom, freezeTo = prev.freezeTo, note = item.note.ifEmpty { prev.note })
                } else {
                    item.copy(checked = false)
                }
            }
            val mergedList = ArrayList<MemoryItem>(map.values)
            syncFrozenSnapshot(mergedList)
            persistSavedListAsync(mergedList)
            _ui.update { s -> s.copy(saved = mergedList) }
            toast("已保存 ${selected.size} 项到保存列表")
        }
    }

    fun deleteSaved(item: MemoryItem) {
        scope.launch(Dispatchers.IO) {
            engine.setFreeze(item.address, item.flags, item.value, false)
        }
        var nextSavedSnapshot: List<MemoryItem> = emptyList()
        _ui.update { st ->
            val nextSaved = st.saved.filterNot { sameItemKey(it, item) }
            nextSavedSnapshot = nextSaved
            syncFrozenSnapshot(nextSaved)
            val validKeys = nextSaved.mapTo(HashSet()) { SelectionState.itemKey(it) }
            st.copy(saved = nextSaved, savedSelection = st.savedSelection.prune(validKeys))
        }
        persistSavedListAsync(nextSavedSnapshot)
    }

    fun deleteResultItem(item: MemoryItem) {
        val stNow = _ui.value
        scope.launch {
            val nextRawLocal = withContext(Dispatchers.Default) {
                stNow.rawResults.filterNot { sameItemKey(it, item) }
            }
            val nextTotal = (stNow.totalCount - 1L).coerceAtLeast(nextRawLocal.size.toLong())
            val refillCap = computeNativeFetchLimit(stNow.filter)
            val needRefill = engine.supportsNativeScan && nextTotal > nextRawLocal.size.toLong() && nextRawLocal.size < refillCap
            val finalRaw = withContext(Dispatchers.IO) {
                engine.markRemoved(listOf(item.address))
                if (needRefill) {
                    val fetched = engine.fetchStoredCandidates(
                        limit = refillCap,
                        defaultType = stNow.searchType,
                        useCandidateDt = true
                    )
                    if (fetched.isNotEmpty()) fetched else nextRawLocal
                } else {
                    nextRawLocal
                }
            }
            val (nextRes, validKeys) = withContext(Dispatchers.Default) {
                val res = filterList(finalRaw, stNow.filter)
                val keys = res.mapTo(HashSet(res.size * 2 + 16)) { SelectionState.itemKey(it) }
                res to keys
            }
            _ui.update { st ->
                st.copy(
                    rawResults = finalRaw,
                    results = nextRes,
                    totalCount = nextTotal.coerceAtLeast(finalRaw.size.toLong()),
                    searchSelection = st.searchSelection.prune(validKeys),
                    hintVisible = nextRes.isEmpty()
                )
            }
        }
    }

    fun toggleFreeze(item: MemoryItem) {
        val nextFreeze = !item.freeze
        if (nextFreeze) {
            mcpFrozenMap[item.address] = item.flags to item.freezeType
        } else {
            mcpFrozenMap.remove(item.address)
        }
        var nextSavedSnapshot: List<MemoryItem> = emptyList()
        _ui.update { st ->
            val nextSaved = st.saved.map { if (sameItemKey(it, item)) it.copy(freeze = nextFreeze) else it }
            nextSavedSnapshot = nextSaved
            syncFrozenSnapshot(nextSaved)
            st.copy(saved = nextSaved)
        }
        persistSavedListAsync(nextSavedSnapshot)
        scope.launch(Dispatchers.IO) {
            engine.setFreeze(
                address = item.address,
                flags = item.flags,
                value = item.value,
                enabled = nextFreeze,
                freezeType = item.freezeType,
                freezeFrom = item.freezeFrom,
                freezeTo = item.freezeTo
            )
        }
    }

    fun revertChecked() {
        val targets = checkedItems()
        if (targets.isEmpty()) {
            toast("无项目被选中")
            return
        }
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            withContext(Dispatchers.IO) {
                val entries = ArrayList<Triple<Long, Int, Long>>(targets.size)
                for (it in targets) entries.add(Triple(it.address, it.size, it.previousValue))
                engine.writeBatch(entries)
            }
            val stSnap = _ui.value
            val (nextRaw, nextRes, nextSaved, nextMem, savedChanged) = withContext(Dispatchers.Default) {
                val addrSet = targets.mapTo(HashSet(targets.size * 2 + 16)) { it.address }
                var sChanged = false
                val nSaved = stSnap.saved.map {
                    if (it.address in addrSet) {
                        sChanged = true
                        it.copy(value = it.previousValue, manuallyEdited = false)
                    } else it
                }
                val nRaw = stSnap.rawResults.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it }
                val nRes = stSnap.results.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it }
                val nMem = stSnap.memoryRows.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it }
                arrayOf(nRaw, nRes, nSaved, nMem, sChanged)
            }
            @Suppress("UNCHECKED_CAST")
            val finalRaw = nextRaw as List<MemoryItem>
            @Suppress("UNCHECKED_CAST")
            val finalRes = nextRes as List<MemoryItem>
            @Suppress("UNCHECKED_CAST")
            val finalSaved = nextSaved as List<MemoryItem>
            @Suppress("UNCHECKED_CAST")
            val finalMem = nextMem as List<MemoryItem>
            val isSavedChanged = savedChanged as Boolean
            if (isSavedChanged) syncFrozenSnapshot(finalSaved)
            _ui.update { st ->
                st.copy(
                    isBatchBusy = false,
                    rawResults = finalRaw,
                    results = finalRes,
                    saved = if (isSavedChanged) finalSaved else st.saved,
                    memoryRows = finalMem
                )
            }
            if (isSavedChanged) persistSavedListAsync(finalSaved)
            toast("已恢复 ${targets.size} 项为先前数值")
        }
    }

    fun loadCheckedAsResults() {
        val items = checkedItems()
        if (items.isEmpty()) {
            toast("请先勾选要载入为搜索结果的项目")
            return
        }
        val clean = items.map { it.copy(checked = false) }
        saveListScrollPosition("search_list", 0, 0)
        _ui.update {
            it.copy(
                rawResults = clean,
                results = clean,
                totalCount = clean.size.toLong(),
                searchSelection = SelectionState.EMPTY,
                hintVisible = false,
                tab = NextTab.SEARCH_LIST
            )
        }
        toast("已将 ${clean.size} 项载入为搜索结果")
    }

    fun loadChangedAsResults() {
        val changed = _ui.value.rawResults.filter { it.value != it.previousValue }
        if (changed.isEmpty()) {
            toast("未发现数值发生变化的项目")
            return
        }
        saveListScrollPosition("search_list", 0, 0)
        _ui.update {
            it.copy(
                rawResults = changed,
                results = filterList(changed, it.filter),
                totalCount = changed.size.toLong(),
                searchSelection = SelectionState.EMPTY
            )
        }
        toast("已保留 ${changed.size} 个有变化的结果")
    }

    fun removeChecked(all: Boolean = false, revertFirst: Boolean = false) {
        val stNow = _ui.value
        val targets = if (all) {
            when (stNow.tab) {
                NextTab.SAVED_LIST -> stNow.saved
                NextTab.MEMORY -> stNow.memoryRows
                else -> stNow.results
            }
        } else {
            checkedItems()
        }
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true, sheetStack = emptyList()) }
            if (revertFirst && targets.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val entries = ArrayList<Triple<Long, Int, Long>>(targets.size)
                    for (it in targets) entries.add(Triple(it.address, it.size, it.previousValue))
                    engine.writeBatch(entries)
                }
            }
            if (all) {
                when (stNow.tab) {
                    NextTab.SAVED_LIST -> {
                        val toUnlock = _ui.value.saved.filter { it.freeze }.map { it.address }
                        toUnlock.forEach { mcpFrozenMap.remove(it) }
                        withContext(Dispatchers.IO) {
                            if (toUnlock.isNotEmpty()) engine.unlockBatch(toUnlock)
                        }
                        syncFrozenSnapshot(emptyList())
                        persistSavedListAsync(emptyList())
                        _ui.update { it.copy(isBatchBusy = false, saved = emptyList(), savedSelection = SelectionState.EMPTY, sheetStack = emptyList()) }
                    }
                    NextTab.MEMORY -> _ui.update { it.copy(isBatchBusy = false, memoryRows = emptyList(), memorySelection = SelectionState.EMPTY, sheetStack = emptyList()) }
                    else -> {
                        _ui.update { it.copy(isBatchBusy = false) }
                        clearResults()
                        closeAllSheets()
                    }
                }
                return@launch
            }

            when (stNow.tab) {
                NextTab.SAVED_LIST -> {
                    val (nextSaved, toUnlockAddrs) = withContext(Dispatchers.Default) {
                        val removeKeys = targets.mapTo(HashSet(targets.size * 2 + 16)) { SelectionState.itemKey(it) }
                        val unlock = targets.filter { it.freeze }.map { it.address }
                        val remaining = stNow.saved.filterNot { SelectionState.itemKey(it) in removeKeys }
                        remaining to unlock
                    }
                    toUnlockAddrs.forEach { mcpFrozenMap.remove(it) }
                    withContext(Dispatchers.IO) {
                        if (toUnlockAddrs.isNotEmpty()) engine.unlockBatch(toUnlockAddrs)
                    }
                    syncFrozenSnapshot(nextSaved)
                    _ui.update { s ->
                        s.copy(isBatchBusy = false, saved = nextSaved, savedSelection = SelectionState.EMPTY, sheetStack = emptyList())
                    }
                    persistSavedListAsync(nextSaved)
                }
                NextTab.MEMORY -> {
                    val nextRows = withContext(Dispatchers.Default) {
                        val removeAddrs = targets.mapTo(HashSet(targets.size * 2 + 16)) { it.address }
                        stNow.memoryRows.filterNot { it.address in removeAddrs }
                    }
                    _ui.update { s ->
                        s.copy(
                            isBatchBusy = false,
                            memoryRows = nextRows,
                            memorySelection = SelectionState.EMPTY,
                            sheetStack = emptyList()
                        )
                    }
                }
                else -> {
                    val (removedAddrs, nextRawLocal) = withContext(Dispatchers.Default) {
                        val addrs = targets.mapTo(HashSet(targets.size * 2 + 16)) { it.address }
                        val keys = targets.mapTo(HashSet(targets.size * 2 + 16)) { SelectionState.itemKey(it) }
                        val remaining = stNow.rawResults.filterNot { SelectionState.itemKey(it) in keys || it.address in addrs }
                        addrs to remaining
                    }
                    val nextTotal = (stNow.totalCount - removedAddrs.size.toLong()).coerceAtLeast(nextRawLocal.size.toLong())
                    val isFullLoadedPrefixRemoval = !stNow.filter.isAnyFilterActive &&
                        nextRawLocal.isEmpty() &&
                        stNow.rawResults.isNotEmpty() &&
                        targets.size >= stNow.rawResults.size
                    val refillCap = computeNativeFetchLimit(stNow.filter)
                    val needRefill = engine.supportsNativeScan && nextTotal > nextRawLocal.size.toLong() && nextRawLocal.size < refillCap
                    val finalRaw = withContext(Dispatchers.IO) {
                        if (removedAddrs.isNotEmpty()) {
                            if (isFullLoadedPrefixRemoval) {
                                engine.removePrefixCandidates(stNow.rawResults.size, removedAddrs)
                            } else {
                                engine.markRemoved(removedAddrs)
                            }
                        }
                        if (needRefill) {
                            val fetched = engine.fetchStoredCandidates(
                                limit = refillCap,
                                defaultType = stNow.searchType,
                                useCandidateDt = true
                            )
                            if (fetched.isNotEmpty()) fetched else nextRawLocal
                        } else {
                            nextRawLocal
                        }
                    }
                    val nextRes = withContext(Dispatchers.Default) {
                        filterList(finalRaw, stNow.filter)
                    }
                    _ui.update { s ->
                        s.copy(
                            isBatchBusy = false,
                            rawResults = finalRaw,
                            results = nextRes,
                            totalCount = nextTotal.coerceAtLeast(finalRaw.size.toLong()),
                            searchSelection = SelectionState.EMPTY,
                            hintVisible = nextRes.isEmpty(),
                            sheetStack = emptyList()
                        )
                    }
                }
            }
        }
    }

    /**
     * 模块 1 & 模块 2：全异步批量编辑：
     * - 所有 `engine.write` / `engine.setFreeze` 均在 `Dispatchers.IO` 执行，零主线程阻塞；
     * - 完整支持 4 种冻结模式 (`0=正常`, `1=递增`, `2=递减`, `3=范围钳制` + `freezeFrom`/`freezeTo`)；
     * - 若条目由加密/XOR 搜索命中 (`xorKey`/`xorOffset`/`encryptedAuto`)，自动按原编码方式重编码写回！
     */
    fun batchEdit(
        targets: List<MemoryItem>,
        rawInput: String,
        targetType: Int,
        rawStep: String = "",
        addNotReplace: Boolean = false,
        freeze: Boolean = false,
        freezeType: Int = 0,
        freezeFromRaw: String = "",
        freezeToRaw: String = "",
        customNote: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (targets.isEmpty()) {
            toast("没有可修改的项目")
            onComplete?.invoke(false)
            return false
        }
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            toast("请输入修改数值")
            onComplete?.invoke(false)
            return false
        }
        val ggAsm = AsmEngineManager.parseGgAsmPrefix(trimmed)
        if (ggAsm != null) {
            val (asmArch, asmBody) = ggAsm
            if (asmBody.isBlank()) {
                toast("请输入汇编指令，例如 ${asmArch.ggPrefix} NOP")
                onComplete?.invoke(false)
                return false
            }
            scope.launch {
                _ui.update { it.copy(isBatchBusy = true) }
                var writeError: String? = null
                val updatedByAddr = withContext(Dispatchers.IO) {
                    val writes = ArrayList<Pair<Long, ByteArray>>(targets.size)
                    val assembledItems = ArrayList<MemoryItem>(targets.size)
                    for (item in targets) {
                        val res = AsmEngineManager.assemble(asmArch, item.address, asmBody)
                        if (res.isFailure) {
                            writeError = res.exceptionOrNull()?.message ?: "汇编失败"
                            break
                        }
                        val bytes = res.getOrThrow()
                        if (bytes.isEmpty()) continue
                        writes.add(item.address to bytes)
                        assembledItems.add(item)
                    }
                    val map = HashMap<Long, MemoryItem>(assembledItems.size * 2 + 16)
                    if (writeError == null && writes.isNotEmpty()) {
                        engine.writeBlockBatch(writes)
                        val readBack = engine.batchReadRawValues(assembledItems.map { it.address to it.size })
                        for (item in assembledItems) {
                            val newWord = readBack[item.address] ?: engine.read(item.address, item.size)
                            map[item.address] = item.copy(
                                value = newWord,
                                previousValue = item.value,
                                manuallyEdited = true,
                                note = if (!customNote.isNullOrBlank()) customNote else item.note
                            )
                        }
                    }
                    map
                }
                if (writeError != null) {
                    _ui.update { it.copy(isBatchBusy = false) }
                    toast("汇编写入失败：$writeError")
                    onComplete?.invoke(false)
                    return@launch
                }
                if (historyStore.add(trimmed)) persistSearchHistoryAsync()
                val stSnap = _ui.value
                val (nRaw, nRes, nSaved, nMem) = withContext(Dispatchers.Default) {
                    arrayOf(
                        stSnap.rawResults.map { updatedByAddr[it.address] ?: it },
                        stSnap.results.map { updatedByAddr[it.address] ?: it },
                        stSnap.saved.map { updatedByAddr[it.address] ?: it },
                        stSnap.memoryRows.map { updatedByAddr[it.address] ?: it }
                    )
                }
                @Suppress("UNCHECKED_CAST")
                _ui.update { st ->
                    st.copy(
                        isBatchBusy = false,
                        rawResults = nRaw as List<MemoryItem>,
                        results = nRes as List<MemoryItem>,
                        saved = nSaved as List<MemoryItem>,
                        memoryRows = nMem as List<MemoryItem>,
                        lastBatchEditValue = trimmed,
                        history = historyStore.list()
                    )
                }
                refreshMemoryDisasmAsync()
                toast("已写入 ${asmArch.shortCode} 汇编至 ${targets.size} 处地址")
                onComplete?.invoke(true)
            }
            return true
        }

        val cleanType = if ((targetType and 0x7F) == 0 || targetType == ValueType.AUTO) {
            targets.first().flags and 0x7F
        } else {
            targetType and 0x7F
        }
        val stepDouble = rawStep.trim().toDoubleOrNull() ?: 0.0
        val stepLong = rawStep.trim().toLongOrNull() ?: 0L

        val isUtf8Text = trimmed.startsWith(":") || trimmed.startsWith("'")
        val isUtf16Text = trimmed.startsWith(";") || trimmed.startsWith("\"")
        val isHexStream = (trimmed.startsWith("h ", true) || trimmed.startsWith("Q ", true)) && trimmed.length > 2

        val parsedParts: List<Long>
        val parsedFreezeFrom: Long
        val parsedFreezeTo: Long
        try {
            parsedParts = if (!isUtf8Text && !isUtf16Text && !isHexStream) {
                val parts = if (trimmed.contains(';')) {
                    trimmed.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(trimmed)
                }
                if (parts.isEmpty()) throw NumberParser.ParseException("修改值无效")
                parts.map { NumberParser.parseNumber(it, cleanType).first }
            } else emptyList()

            parsedFreezeFrom = if (freeze && freezeType == 3 && freezeFromRaw.isNotBlank()) {
                NumberParser.parseNumber(freezeFromRaw.trim(), cleanType).first
            } else 0L
            parsedFreezeTo = if (freeze && freezeType == 3 && freezeToRaw.isNotBlank()) {
                NumberParser.parseNumber(freezeToRaw.trim(), cleanType).first
            } else 0L
        } catch (e: NumberParser.ParseException) {
            toast(e.message ?: "输入格式错误")
            onComplete?.invoke(false)
            return false
        }

        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val updatedByAddr = withContext(Dispatchers.IO) {
                val map = HashMap<Long, MemoryItem>(targets.size * 4 / 3 + 16)
                val itemSize = ValueType.sizeOf(cleanType)
                val writeEntries = ArrayList<Triple<Long, Int, Long>>(targets.size)
                val freezeEntries = ArrayList<MemoryItem>()

                // 若存在加密/XOR条目，预先批量读取所有密钥地址，避免循环内逐条 IPC
                val xorKeyMap: Map<Long, Long> = if (!isUtf8Text && !isUtf16Text && !isHexStream) {
                    val keyAddrs = ArrayList<Pair<Long, Int>>()
                    for (item in targets) {
                        when {
                            item.xorOffset != 0 -> keyAddrs.add((item.address + item.xorOffset) to itemSize)
                            item.encryptedAuto -> keyAddrs.add((item.address + itemSize) to itemSize)
                        }
                    }
                    if (keyAddrs.isNotEmpty()) engine.batchReadRawValues(keyAddrs) else emptyMap()
                } else emptyMap()

                fun reencodeIfEncrypted(item: MemoryItem, plainBits: Long): Long {
                    return when {
                        item.xorOffset != 0 -> {
                            val key = xorKeyMap[item.address + item.xorOffset] ?: engine.read(item.address + item.xorOffset, itemSize)
                            plainBits xor key
                        }
                        item.xorKey != 0L -> plainBits xor item.xorKey
                        item.encryptedAuto -> {
                            val key = xorKeyMap[item.address + itemSize] ?: engine.read(item.address + itemSize, itemSize)
                            plainBits xor key
                        }
                        else -> plainBits
                    }
                }

                val hasNote = !customNote.isNullOrBlank()
                if (isUtf8Text || isUtf16Text) {
                    val body = trimmed.substring(1).trimEnd('\'', '"')
                    val bytes = if (isUtf16Text) body.toByteArray(Charsets.UTF_16LE) else body.toByteArray(Charsets.UTF_8)
                    targets.forEachIndexed { idx, item ->
                        val bValue = if (isUtf16Text && cleanType == ValueType.WORD) {
                            val b0 = bytes.getOrNull(idx * 2)?.toLong()?.and(0xFF) ?: 0L
                            val b1 = bytes.getOrNull(idx * 2 + 1)?.toLong()?.and(0xFF) ?: 0L
                            b0 or (b1 shl 8)
                        } else {
                            bytes.getOrNull(idx)?.toLong()?.and(0xFF) ?: 0L
                        }
                        writeEntries.add(Triple(item.address, itemSize, bValue))
                        val edited = item.copy(
                            value = bValue,
                            flags = cleanType,
                            freeze = if (freeze) true else item.freeze,
                            freezeType = if (freeze) freezeType else item.freezeType,
                            freezeFrom = if (freeze && freezeType == 3) parsedFreezeFrom else item.freezeFrom,
                            freezeTo = if (freeze && freezeType == 3) parsedFreezeTo else item.freezeTo,
                            previousValue = item.value,
                            manuallyEdited = true,
                            note = if (hasNote) customNote!! else item.note
                        )
                        if (edited.freeze) freezeEntries.add(edited)
                        map[item.address] = edited
                    }
                } else if (isHexStream) {
                    val hexTokens = trimmed.substring(2).trim()
                        .split(Regex("\\s+"))
                        .mapNotNull { tok -> tok.toIntOrNull(16)?.toByte() }
                    targets.forEachIndexed { idx, item ->
                        val v = hexTokens.getOrNull(idx)?.toLong()?.and(0xFF) ?: item.value
                        writeEntries.add(Triple(item.address, itemSize, v))
                        val edited = item.copy(
                            value = v,
                            flags = cleanType,
                            freeze = if (freeze) true else item.freeze,
                            freezeType = if (freeze) freezeType else item.freezeType,
                            freezeFrom = if (freeze && freezeType == 3) parsedFreezeFrom else item.freezeFrom,
                            freezeTo = if (freeze && freezeType == 3) parsedFreezeTo else item.freezeTo,
                            previousValue = item.value,
                            manuallyEdited = true,
                            note = if (hasNote) customNote!! else item.note
                        )
                        if (edited.freeze) freezeEntries.add(edited)
                        map[item.address] = edited
                    }
                } else {
                    targets.forEachIndexed { idx, item ->
                        val parsedBase = parsedParts[idx % parsedParts.size]
                        val plainCur = when {
                            item.xorOffset != 0 -> item.value xor (xorKeyMap[item.address + item.xorOffset] ?: engine.read(item.address + item.xorOffset, itemSize))
                            item.xorKey != 0L -> item.value xor item.xorKey
                            item.encryptedAuto -> item.value xor (xorKeyMap[item.address + itemSize] ?: engine.read(item.address + itemSize, itemSize))
                            else -> item.value
                        }
                        val plainNextBits = when (cleanType) {
                            ValueType.FLOAT -> {
                                val baseF = Float.fromBits(parsedBase.toInt())
                                val curF = Float.fromBits(plainCur.toInt())
                                val nextF = (if (addNotReplace) curF + baseF else baseF) + (idx * stepDouble).toFloat()
                                nextF.toRawBits().toLong() and 0xFFFFFFFFL
                            }
                            ValueType.DOUBLE -> {
                                val baseD = Double.fromBits(parsedBase)
                                val curD = Double.fromBits(plainCur)
                                val nextD = (if (addNotReplace) curD + baseD else baseD) + (idx * stepDouble)
                                nextD.toRawBits()
                            }
                            else -> {
                                val curSigned = Formatter.signed(plainCur, itemSize)
                                val baseSigned = Formatter.signed(parsedBase, itemSize)
                                (if (addNotReplace) curSigned + baseSigned else baseSigned) + (idx * stepLong)
                            }
                        }
                        val encodedBits = reencodeIfEncrypted(item, plainNextBits)
                        writeEntries.add(Triple(item.address, itemSize, encodedBits))
                        val edited = item.copy(
                            value = encodedBits,
                            flags = cleanType,
                            freeze = if (freeze) true else item.freeze,
                            freezeType = if (freeze) freezeType else item.freezeType,
                            freezeFrom = if (freeze && freezeType == 3) parsedFreezeFrom else item.freezeFrom,
                            freezeTo = if (freeze && freezeType == 3) parsedFreezeTo else item.freezeTo,
                            previousValue = item.value,
                            manuallyEdited = true,
                            note = if (hasNote) customNote!! else item.note
                        )
                        if (edited.freeze) freezeEntries.add(edited)
                        map[item.address] = edited
                    }
                }
                if (writeEntries.isNotEmpty()) {
                    engine.writeBatch(writeEntries)
                }
                if (freezeEntries.isNotEmpty()) {
                    engine.lockBatch(freezeEntries)
                }
                map
            }

            if (historyStore.add(trimmed)) {
                persistSearchHistoryAsync()
            }

            val stSnap = _ui.value
            val addToSaved = freeze || !customNote.isNullOrBlank()
            val (nextRaw, nextRes, nextSaved, nextMem, savedChanged) = withContext(Dispatchers.Default) {
                var sChanged = false
                val nSaved: List<MemoryItem> = if (addToSaved || stSnap.saved.isNotEmpty()) {
                    val savedByAddr = LinkedHashMap<Long, MemoryItem>((stSnap.saved.size + if (addToSaved) updatedByAddr.size else 0) * 4 / 3 + 16)
                    for (sItem in stSnap.saved) {
                        savedByAddr[sItem.address] = sItem
                    }
                    updatedByAddr.values.forEach { edited ->
                        val existing = savedByAddr[edited.address]
                        if (existing != null) {
                            savedByAddr[edited.address] = edited
                            sChanged = true
                        } else if (addToSaved) {
                            savedByAddr[edited.address] = edited
                            sChanged = true
                        }
                    }
                    if (sChanged) ArrayList<MemoryItem>(savedByAddr.values) else stSnap.saved
                } else {
                    stSnap.saved
                }
                val nRaw = stSnap.rawResults.map { updatedByAddr[it.address] ?: it }
                val nRes = stSnap.results.map { updatedByAddr[it.address] ?: it }
                val nMem = stSnap.memoryRows.map { updatedByAddr[it.address] ?: it }
                arrayOf(nRaw, nRes, nSaved, nMem, sChanged)
            }
            @Suppress("UNCHECKED_CAST")
            val finalRaw = nextRaw as List<MemoryItem>
            @Suppress("UNCHECKED_CAST")
            val finalRes = nextRes as List<MemoryItem>
            @Suppress("UNCHECKED_CAST")
            val finalSaved = nextSaved as List<MemoryItem>
            @Suppress("UNCHECKED_CAST")
            val finalMem = nextMem as List<MemoryItem>
            val isSavedChanged = savedChanged as Boolean

            if (isSavedChanged) syncFrozenSnapshot(finalSaved)
            _ui.update { st ->
                st.copy(
                    isBatchBusy = false,
                    rawResults = finalRaw,
                    results = finalRes,
                    saved = if (isSavedChanged) finalSaved else st.saved,
                    memoryRows = finalMem,
                    lastBatchEditValue = trimmed,
                    lastBatchEditStep = rawStep.trim(),
                    history = historyStore.list()
                )
            }
            if (isSavedChanged) persistSavedListAsync(finalSaved)
            toast(if (targets.size == 1) "已修改地址 ${targets.first().cachedAddressHex}" else "已批量修改 ${targets.size} 项")
            onComplete?.invoke(true)
        }
        return true
    }

    fun editValue(item: MemoryItem, raw: String): Boolean =
        batchEdit(listOf(item), raw, item.flags)

    fun changeType(item: MemoryItem, type: Int) {
        batchChangeType(listOf(item), type)
    }

    /**
     * 模块 2：多选地址批量更改数据类型（全异步 Dispatchers.IO）：
     * 1. 按新类型位宽通过 `engine.batchReadRawValues` 批量重读实时值；
     * 2. 重新计算指针分类；
     * 3. 对已冻结条目按新类型与位宽重新注册底层 `ValueLocker` 锁值；
     * 4. 原子更新搜索列表、保存列表、内存页并持久化保存列表。
     */
    fun batchChangeType(targets: List<MemoryItem>, newType: Int) {
        val cleanType = newType and 0x7F
        if (targets.isEmpty() || cleanType == 0 || cleanType == ValueType.AUTO) {
            toast("没有可修改的项目")
            return
        }
        val newSize = ValueType.sizeOf(cleanType).coerceIn(1, 8)
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val updatedByOldKey = withContext(Dispatchers.IO) {
                val readPairs = targets.map { it.address to newSize }.distinctBy { it.first }
                val liveVals = engine.batchReadRawValues(readPairs)
                val freshVals = LongArray(targets.size) { i ->
                    val item = targets[i]
                    liveVals[item.address] ?: engine.read(item.address, newSize)
                }
                val ptrKinds = engine.classifyPointers(freshVals)
                val out = HashMap<Long, MemoryItem>(targets.size * 4 / 3 + 8)
                val toLock = ArrayList<MemoryItem>()
                for (i in targets.indices) {
                    val item = targets[i]
                    val freshVal = freshVals[i]
                    val updated = item.copy(
                        value = freshVal,
                        previousValue = freshVal,
                        flags = cleanType,
                        pointer = ptrKinds[i]
                    )
                    if (updated.freeze) toLock.add(updated)
                    out[SelectionState.itemKey(item)] = updated
                }
                if (toLock.isNotEmpty()) engine.lockBatch(toLock)
                out
            }

            val stSnap = _ui.value
            val (nextRaw, nextRes, nextSaved, nextMem, savedChanged) = withContext(Dispatchers.Default) {
                val updatedByAddr = HashMap<Long, MemoryItem>(updatedByOldKey.size * 2 + 16)
                updatedByOldKey.values.forEach { updatedByAddr[it.address] = it }
                fun remap(list: List<MemoryItem>): Pair<List<MemoryItem>, Boolean> {
                    var changed = false
                    val mapped = list.map { cur ->
                        val hit = updatedByOldKey[SelectionState.itemKey(cur)]
                        if (hit != null) {
                            changed = true
                            hit
                        } else cur
                    }
                    return mapped to changed
                }
                val (nSaved, sChanged) = remap(stSnap.saved)
                val (nRaw, _) = remap(stSnap.rawResults)
                val (nRes, _) = remap(stSnap.results)
                val nMem = stSnap.memoryRows.map { row ->
                    val upd = updatedByAddr[row.address]
                    if (upd != null) row.copy(flags = cleanType, value = upd.value, pointer = upd.pointer) else row
                }
                arrayOf(nRaw, nRes, nSaved, nMem, sChanged)
            }
            @Suppress("UNCHECKED_CAST")
            val finalRaw = nextRaw as List<MemoryItem>
            @Suppress("UNCHECKED_CAST")
            val finalRes = nextRes as List<MemoryItem>
            @Suppress("UNCHECKED_CAST")
            val finalSaved = nextSaved as List<MemoryItem>
            @Suppress("UNCHECKED_CAST")
            val finalMem = nextMem as List<MemoryItem>
            val isSavedChanged = savedChanged as Boolean

            if (isSavedChanged) syncFrozenSnapshot(finalSaved)
            _ui.update { s ->
                s.copy(
                    isBatchBusy = false,
                    rawResults = finalRaw,
                    results = finalRes,
                    saved = if (isSavedChanged) finalSaved else s.saved,
                    memoryRows = finalMem,
                    searchSelection = SelectionState.EMPTY,
                    savedSelection = SelectionState.EMPTY,
                    memorySelection = SelectionState.EMPTY,
                    sheetStack = emptyList()
                )
            }
            if (isSavedChanged) persistSavedListAsync(finalSaved)
            toast("已批量更改 ${targets.size} 项的类型为 ${ValueType.longName(cleanType)}")
        }
    }

    /** 异步更改单个条目的地址与类型 */
    fun changeAddress(item: MemoryItem, newAddress: Long, newType: Int = item.flags) {
        val cleanType = if (newType == 0) item.flags else newType
        val size = ValueType.sizeOf(cleanType)
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val updated = withContext(Dispatchers.IO) {
                if (item.freeze && item.address != newAddress) {
                    engine.setFreeze(item.address, item.flags, item.value, false)
                }
                val liveVal = engine.read(newAddress, size)
                val regCode = engine.regionCodeAt(newAddress)
                val next = item.copy(
                    address = newAddress,
                    value = liveVal,
                    previousValue = liveVal,
                    flags = cleanType,
                    region = regCode,
                    pointer = engine.classifyPointer(liveVal)
                )
                if (next.freeze) {
                    engine.setFreeze(newAddress, cleanType, liveVal, true, next.freezeType, next.freezeFrom, next.freezeTo)
                }
                next
            }
            var nextSavedSnapshot: List<MemoryItem> = emptyList()
            _ui.update { s ->
                val nextSaved = s.saved.map { if (sameItemKey(it, item)) updated else it }.distinctBy { SelectionState.itemKey(it) }
                nextSavedSnapshot = nextSaved
                syncFrozenSnapshot(nextSaved)
                s.copy(
                    isBatchBusy = false,
                    rawResults = s.rawResults.map { if (sameItemKey(it, item)) updated else it }.distinctBy { SelectionState.itemKey(it) },
                    results = s.results.map { if (sameItemKey(it, item)) updated else it }.distinctBy { SelectionState.itemKey(it) },
                    saved = nextSaved,
                    memoryRows = s.memoryRows.map { if (it.address == item.address) updated else it }.distinctBy { it.address },
                    sheetStack = emptyList()
                )
            }
            persistSavedListAsync(nextSavedSnapshot)
            toast("已更新地址为 ${updated.cachedAddressHex}")
        }
    }

    // ============ 模块 2：分离只读载入与只写保存列表文件（全异步 IO） ============

    /** 只读载入保存列表文件（绝不覆盖目标文件！） */
    fun loadListFromFile(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) {
            toast("请输入有效的文件路径")
            return
        }
        setSetting("file_manager_path", clean)
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(clean)
                    if (!file.exists()) {
                        return@runCatching Result.failure<Pair<String, List<MemoryItem>>>(IllegalStateException("文件不存在"))
                    }
                    val content = file.readText()
                    val loaded = SavedListFileCodec.parseFromText(content)
                    if (loaded.isEmpty()) {
                        return@runCatching Result.failure<Pair<String, List<MemoryItem>>>(IllegalStateException("文件中没有可载入的有效条目"))
                    }
                    loaded.forEach { item ->
                        if (item.freeze) {
                            engine.setFreeze(item.address, item.flags, item.value, true, item.freezeType, item.freezeFrom, item.freezeTo)
                        }
                    }
                    Result.success(file.name to loaded)
                }.getOrElse { Result.failure(it) }
            }
            _ui.update { it.copy(isBatchBusy = false) }
            result.onSuccess { (fileName, loaded) ->
                syncFrozenSnapshot(loaded)
                persistSavedListAsync(loaded)
                _ui.update {
                    it.copy(
                        saved = loaded,
                        savedSelection = SelectionState.EMPTY,
                        fileManagerPath = clean,
                        sheetStack = emptyList(),
                        tab = NextTab.SAVED_LIST
                    )
                }
                toast("已从 $fileName 载入 ${loaded.size} 项")
            }.onFailure { err ->
                val msg = err.message ?: "未知错误"
                if (msg == "文件不存在" || msg == "文件中没有可载入的有效条目") {
                    toast(msg)
                } else {
                    toast("文件读写失败: $msg")
                }
            }
        }
    }

    /** 只写保存列表到文件（`onlyChecked = true` 用于导出选中项） */
    fun saveListToFile(path: String, onlyChecked: Boolean = false) {
        val clean = path.trim()
        if (clean.isEmpty()) {
            toast("请输入有效的文件路径")
            return
        }
        val itemsToWrite = if (onlyChecked) {
            checkedItems().ifEmpty { _ui.value.saved }
        } else {
            _ui.value.saved
        }
        if (itemsToWrite.isEmpty()) {
            toast("保存列表为空，无可保存项目")
            return
        }
        setSetting("file_manager_path", clean)
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(clean)
                    file.parentFile?.mkdirs()
                    file.writeText(SavedListFileCodec.formatToText(itemsToWrite))
                    file.name
                }
            }
            _ui.update { it.copy(isBatchBusy = false) }
            result.onSuccess { fileName ->
                _ui.update { it.copy(fileManagerPath = clean, sheetStack = emptyList()) }
                toast("已保存 ${itemsToWrite.size} 项到 $fileName")
            }.onFailure { err ->
                toast("文件读写失败: ${err.message}")
            }
        }
    }

    fun setValueMask(mask: Int, targetTab: NextTab) {
        when (targetTab) {
            NextTab.MEMORY -> {
                repo?.let { r -> scope.launch(Dispatchers.IO) { r.setMemoryFormatMask(mask) } }
                _ui.update { it.copy(valueMaskMemory = mask) }
                refreshMemoryDisasmAsync()
            }
            NextTab.SAVED_LIST -> {
                val clean = mask and Formatter.AUX_FORMAT_MASK
                repo?.let { r -> scope.launch(Dispatchers.IO) { r.setSavedFormatMask(clean) } }
                _ui.update { it.copy(valueMaskSaved = clean) }
            }
            else -> {
                val clean = mask and Formatter.AUX_FORMAT_MASK
                repo?.let { r -> scope.launch(Dispatchers.IO) { r.setValueFormatMask(clean) } }
                _ui.update {
                    it.copy(
                        valueMask = clean,
                        filter = it.filter.copy(
                            formatHex = (clean and Formatter.F_HEX) != 0,
                            formatRHex = (clean and Formatter.F_RHEX) != 0,
                            formatString = (clean and Formatter.F_STRING) != 0,
                            formatUtf16 = (clean and Formatter.F_JAVA) != 0,
                            formatArm = (clean and Formatter.F_ARM) != 0,
                            formatThumb = (clean and Formatter.F_THUMB) != 0,
                            formatArm64 = (clean and Formatter.F_ARM8) != 0
                        )
                    )
                }
            }
        }
    }

    // ============ 内存浏览（4KB 单页 + 跳转高亮 + 持久化历史 + 反汇编） ============

    fun goto(
        address: Long,
        recordHistory: Boolean = true,
        highlightTarget: Boolean = true,
        sourceAddress: Long? = null
    ) {
        scope.launch {
            val stBefore = _ui.value
            val prevAnchor = (sourceAddress ?: stBefore.memoryHighlightAddress ?: stBefore.memoryBase) and -4L
            _ui.update { it.copy(busy = true, busyText = "读取内存页") }
            val targetAligned = address and -4L
            val pageBase = if (highlightTarget && targetAligned >= 12L) {
                (targetAligned - 12L) and -4L
            } else {
                targetAligned
            }
            val (rows, disasmMap) = withContext(Dispatchers.IO) { readMemoryPageWithDisasm(pageBase) }
            val targetHex = java.lang.Long.toHexString(address).uppercase()
            var updatedGotoHist: List<String>? = null
            val limit = currentHistoryLimit()
            _ui.update {
                val nextBack = if (recordHistory && prevAnchor != targetAligned && prevAnchor != 0L) {
                    (it.memoryHistoryBack + prevAnchor).takeLast(32)
                } else it.memoryHistoryBack
                val nextGotoHist = if (highlightTarget && targetHex.isNotEmpty() && limit > 0) {
                    (listOf(targetHex) + it.gotoHistory.filter { h -> h != targetHex }).take(limit.coerceAtMost(200)).also { list ->
                        updatedGotoHist = list
                    }
                } else it.gotoHistory

                // 非破坏性多级指针跳转路径记录：
                // - 仅当通过指针跳转（sourceAddress != null）时追加或定位，绝不删除后续层级；
                // - 允许用户在 A -> B -> C -> D 中切回 B 后再对照查看 C/D。
                var nextChain = it.memoryPointerChain
                var nextChainIdx = it.memoryPointerIndex
                if (sourceAddress != null) {
                    val srcAligned = sourceAddress and -4L
                    if (srcAligned != targetAligned) {
                        if (nextChain.isEmpty()) {
                            nextChain = listOf(
                                PointerHop(address = srcAligned, sourceAddress = null),
                                PointerHop(address = targetAligned, sourceAddress = srcAligned)
                            )
                            nextChainIdx = 1
                        } else {
                            val nextPos = nextChainIdx + 1
                            val existingNext = nextChain.getOrNull(nextPos)
                            if (existingNext != null && existingNext.address == targetAligned) {
                                nextChainIdx = nextPos
                            } else {
                                val appended = (nextChain + PointerHop(address = targetAligned, sourceAddress = srcAligned))
                                nextChain = if (appended.size > 24) appended.takeLast(24) else appended
                                nextChainIdx = nextChain.lastIndex
                            }
                        }
                    }
                }

                it.copy(
                    memoryRows = rows,
                    memoryDisasm = disasmMap,
                    memoryBase = pageBase,
                    gotoHistory = nextGotoHist,
                    memoryHighlightAddress = if (highlightTarget) targetAligned else it.memoryHighlightAddress,
                    memoryScrollTrigger = it.memoryScrollTrigger + 1,
                    memoryHistoryBack = nextBack,
                    memoryHistoryForward = if (recordHistory) emptyList() else it.memoryHistoryForward,
                    memoryPointerChain = nextChain,
                    memoryPointerIndex = nextChainIdx,
                    memorySelection = SelectionState.EMPTY,
                    busy = false,
                    tab = NextTab.MEMORY,
                    sheetStack = emptyList()
                )
            }
            updatedGotoHist?.let { persistGotoHistoryAsync(it) }
        }
    }

    /**
     * 点击指针路径栏任意层级：非破坏性切换，不删除后续 C/D 层级。
     */
    fun jumpToPointerChainIndex(index: Int) {
        val st = _ui.value
        val hop = st.memoryPointerChain.getOrNull(index) ?: return
        _ui.update { it.copy(memoryPointerIndex = index) }
        goto(hop.address, recordHistory = false, highlightTarget = true, sourceAddress = null)
    }

    /**
     * 手动删除指针路径栏中的指定层级。
     */
    fun removePointerChainIndex(index: Int) {
        val st = _ui.value
        if (index !in st.memoryPointerChain.indices) return
        val wasActive = (index == st.memoryPointerIndex)
        val remaining = st.memoryPointerChain.toMutableList().apply { removeAt(index) }
        if (remaining.size <= 1) {
            val fallbackAddr = remaining.firstOrNull()?.address
            _ui.update { it.copy(memoryPointerChain = emptyList(), memoryPointerIndex = -1) }
            if (wasActive && fallbackAddr != null) {
                goto(fallbackAddr, recordHistory = false, highlightTarget = true, sourceAddress = null)
            }
            return
        }
        val nextIndex = when {
            index < st.memoryPointerIndex -> (st.memoryPointerIndex - 1).coerceIn(0, remaining.lastIndex)
            index == st.memoryPointerIndex -> (index - 1).coerceIn(0, remaining.lastIndex)
            else -> st.memoryPointerIndex.coerceIn(0, remaining.lastIndex)
        }
        _ui.update { it.copy(memoryPointerChain = remaining, memoryPointerIndex = nextIndex) }
        if (wasActive) {
            val targetHop = remaining.getOrNull(nextIndex)
            if (targetHop != null) {
                goto(targetHop.address, recordHistory = false, highlightTarget = true, sourceAddress = null)
            }
        }
    }

    /** 清空多级指针跳转路径栏 */
    fun clearPointerChain() {
        _ui.update { it.copy(memoryPointerChain = emptyList(), memoryPointerIndex = -1) }
    }

    private fun readMemoryPageWithDisasm(pageBase: Long): Pair<List<MemoryItem>, Map<Long, String>> {
        val count = MEMORY_PAGE_ROWS
        val block = engine.readBlock(pageBase, MEMORY_PAGE_BYTES)
        val rows = buildMemoryPageRows(pageBase, block, count)
        val asmArch = Formatter.activeAsmArch(_ui.value.valueMaskMemory)
        val disasm = if (asmArch != null && block.isNotEmpty()) {
            AsmEngineManager.disassembleBlockMap(asmArch, pageBase, block, count)
        } else {
            emptyMap()
        }
        return rows to disasm
    }

    private fun buildMemoryPageRows(pageBase: Long, block: ByteArray, count: Int): List<MemoryItem> {
        val candidatePtrs = LongArray(count) { r ->
            val off = (r and -2) * 4
            if (engine.x64 && off + 8 <= block.size) {
                (block[off].toLong() and 0xFF) or
                    ((block[off + 1].toLong() and 0xFF) shl 8) or
                    ((block[off + 2].toLong() and 0xFF) shl 16) or
                    ((block[off + 3].toLong() and 0xFF) shl 24) or
                    ((block[off + 4].toLong() and 0xFF) shl 32) or
                    ((block[off + 5].toLong() and 0xFF) shl 40) or
                    ((block[off + 6].toLong() and 0xFF) shl 48) or
                    ((block[off + 7].toLong() and 0xFF) shl 56)
            } else if (off + 4 <= block.size) {
                (block[off].toLong() and 0xFF) or
                    ((block[off + 1].toLong() and 0xFF) shl 8) or
                    ((block[off + 2].toLong() and 0xFF) shl 16) or
                    ((block[off + 3].toLong() and 0xFF) shl 24)
            } else 0L
        }
        val classifiedPtrs = engine.classifyPointers(candidatePtrs)
        val out = ArrayList<MemoryItem>(count)
        for (r in 0 until count) {
            val off = r * 4
            val rowAddr = pageBase + r * 4L
            val v = if (off + 4 <= block.size) {
                (block[off].toLong() and 0xFF) or
                    ((block[off + 1].toLong() and 0xFF) shl 8) or
                    ((block[off + 2].toLong() and 0xFF) shl 16) or
                    ((block[off + 3].toLong() and 0xFF) shl 24)
            } else 0L
            out.add(
                MemoryItem(
                    address = rowAddr,
                    value = v,
                    flags = ValueType.DWORD,
                    region = engine.regionCodeAt(rowAddr),
                    pointer = classifiedPtrs[r]
                )
            )
        }
        return out
    }

    fun focusHighlightedMemoryAddress() {
        val st = _ui.value
        val target = st.memoryHighlightAddress ?: st.memoryBase
        goto(target, recordHistory = false, highlightTarget = true)
    }

    fun gotoBoundary(address: Long, isUpward: Boolean) {
        scope.launch {
            val stBefore = _ui.value
            val prevAnchor = (stBefore.memoryHighlightAddress ?: stBefore.memoryBase) and -4L
            _ui.update { it.copy(busy = true, busyText = "读取内存页") }
            val targetAligned = address and -4L
            val pageBase = if (isUpward) {
                val span = (MEMORY_PAGE_BYTES - 4).toLong()
                if (java.lang.Long.compareUnsigned(targetAligned, span) >= 0) {
                    (targetAligned - span) and -4L
                } else 0L
            } else {
                targetAligned
            }
            val (rows, disasmMap) = withContext(Dispatchers.IO) { readMemoryPageWithDisasm(pageBase) }
            _ui.update {
                val nextBack = if (prevAnchor != targetAligned && prevAnchor != 0L) {
                    (it.memoryHistoryBack + prevAnchor).takeLast(32)
                } else it.memoryHistoryBack
                it.copy(
                    memoryRows = rows,
                    memoryDisasm = disasmMap,
                    memoryBase = pageBase,
                    memoryHighlightAddress = targetAligned,
                    memoryScrollTrigger = it.memoryScrollTrigger + 1,
                    memoryHistoryBack = nextBack,
                    memoryHistoryForward = emptyList(),
                    memorySelection = SelectionState.EMPTY,
                    busy = false,
                    tab = NextTab.MEMORY
                )
            }
        }
    }

    fun memoryScrollBy(rows: Int) {
        val base = (_ui.value.memoryBase + rows * 4L).coerceAtLeast(0L)
        goto(base, recordHistory = false, highlightTarget = false)
    }

    fun memoryBack() {
        val st = _ui.value
        val curAnchor = (st.memoryHighlightAddress ?: st.memoryBase) and -4L

        // 若处于多级指针路径链中，点击工具栏「后退」按钮时移除当前层并回退至上一层
        if (st.memoryPointerChain.isNotEmpty() && st.memoryPointerIndex in st.memoryPointerChain.indices) {
            val curIdx = st.memoryPointerIndex
            val curHop = st.memoryPointerChain[curIdx]
            val remaining = st.memoryPointerChain.toMutableList().apply { removeAt(curIdx) }
            val nextIdx = (curIdx - 1).coerceIn(0, (remaining.size - 1).coerceAtLeast(0))
            val targetAddr = remaining.getOrNull(nextIdx)?.address ?: st.memoryHistoryBack.lastOrNull()
            val finalChain = if (remaining.size <= 1) emptyList() else remaining
            val finalIdx = if (remaining.size <= 1) -1 else nextIdx
            _ui.update {
                val poppedBack = if (targetAddr != null && it.memoryHistoryBack.lastOrNull() == targetAddr) {
                    it.memoryHistoryBack.dropLast(1)
                } else if (curHop.sourceAddress != null && it.memoryHistoryBack.lastOrNull() == curHop.sourceAddress) {
                    it.memoryHistoryBack.dropLast(1)
                } else {
                    it.memoryHistoryBack
                }
                it.copy(
                    memoryPointerChain = finalChain,
                    memoryPointerIndex = finalIdx,
                    memoryHistoryBack = poppedBack,
                    memoryHistoryForward = if (curAnchor != 0L) (it.memoryHistoryForward + curAnchor).takeLast(32) else it.memoryHistoryForward
                )
            }
            if (targetAddr != null) {
                goto(targetAddr, recordHistory = false, highlightTarget = true, sourceAddress = null)
            } else {
                memoryScrollBy(-MEMORY_PAGE_ROWS)
            }
            return
        }

        val prev = st.memoryHistoryBack.lastOrNull()
        if (prev == null) {
            memoryScrollBy(-MEMORY_PAGE_ROWS)
            return
        }
        _ui.update {
            it.copy(
                memoryHistoryBack = it.memoryHistoryBack.dropLast(1),
                memoryHistoryForward = if (curAnchor != 0L) (it.memoryHistoryForward + curAnchor).takeLast(32) else it.memoryHistoryForward
            )
        }
        goto(prev, recordHistory = false, highlightTarget = true)
    }

    fun memoryForward() {
        val st = _ui.value
        val next = st.memoryHistoryForward.lastOrNull()
        if (next == null) {
            memoryScrollBy(MEMORY_PAGE_ROWS)
            return
        }
        val curAnchor = (st.memoryHighlightAddress ?: st.memoryBase) and -4L
        _ui.update {
            it.copy(
                memoryHistoryForward = it.memoryHistoryForward.dropLast(1),
                memoryHistoryBack = if (curAnchor != 0L) (it.memoryHistoryBack + curAnchor).takeLast(32) else it.memoryHistoryBack
            )
        }
        goto(next, recordHistory = false, highlightTarget = true)
    }

    // ============ 内存导出 & 诚实的内存页分配 ============

    fun dumpMemory(startAddr: Long, endAddr: Long, dirPath: String) {
        if (endAddr <= startAddr) {
            toast("结束地址必须大于起始地址")
            return
        }
        val cleanDir = dirPath.trim().trimEnd('/').ifEmpty { "/sdcard/GGNext/dump" }
        setSetting("dump_dir_path", cleanDir)
        val fileName = "${java.lang.Long.toHexString(startAddr).uppercase()}-${java.lang.Long.toHexString(endAddr).uppercase()}.bin"
        val fullPath = "$cleanDir/$fileName"
        scope.launch {
            _ui.update { it.copy(busy = true, busyText = "导出内存中") }
            val ok = withContext(Dispatchers.IO) {
                engine.dumpMemory(startAddr, endAddr, fullPath)
            }
            _ui.update { it.copy(busy = false, dumpDirPath = cleanDir, sheetStack = emptyList()) }
            if (ok) {
                toast("已导出内存区间到 $fullPath")
            } else {
                toast("导出内存失败")
            }
        }
    }

    /** 目标进程真实匿名内存页分配 */
    fun allocateMemoryPage(address: Long, read: Boolean, write: Boolean, exec: Boolean) {
        scope.launch {
            _ui.update { it.copy(busy = true, busyText = "正在分配内存页") }
            val (allocAddr, msg, refreshedRegions) = withContext(Dispatchers.IO) {
                val (a, m) = engine.allocatePage(address, read, write, exec)
                val regs = if (a != 0L) aggregate(engine.regions()) else null
                Triple(a, m, regs)
            }
            _ui.update { st ->
                st.copy(
                    busy = false,
                    regions = refreshedRegions ?: st.regions
                )
            }
            toast(msg)
            if (allocAddr != 0L) {
                closeAllSheets()
                goto(allocAddr, recordHistory = true, highlightTarget = true)
            }
        }
    }

    // ============ 历史（完整持久化） ============

    fun clearHistory() {
        historyStore.clear()
        persistSearchHistoryAsync()
        persistGotoHistoryAsync(emptyList())
        _ui.update { it.copy(history = emptyList(), gotoHistory = emptyList()) }
        toast("已清空历史")
    }

    fun removeHistory(v: String) {
        historyStore.remove(v)
        persistSearchHistoryAsync()
        _ui.update { it.copy(history = historyStore.list()) }
    }

    fun toggleHistoryPin(v: String) {
        historyStore.togglePin(v)
        persistSearchHistoryAsync()
        _ui.update { it.copy(history = historyStore.list()) }
    }

    fun isHistoryPinned(v: String): Boolean = historyStore.isPinned(v)

    fun pickHistory(v: String) = _ui.update { st ->
        val parentSheet = st.sheetStack.dropLast(1).lastOrNull() ?: Sheet.SEARCH
        val nextStack = if (st.sheetStack.size > 1) st.sheetStack.dropLast(1) else listOf(parentSheet)
        when (parentSheet) {
            Sheet.EDITOR -> st.copy(lastBatchEditValue = v, sheetStack = nextStack)
            else -> st.copy(search = st.search.copy(text = v), sheetStack = nextStack)
        }
    }

    // ============ 悬浮窗 ============

    fun toggleOverlay(app: Application, enabled: Boolean) {
        if (enabled) {
            setFloatingBallVisible(true)
            com.littlecodesauce.ggnext.service.OverlayManager.start(app)
            toast("已启动悬浮球，短按打开面板")
        } else {
            setFloatingBallVisible(false)
            setPanelVisible(false)
            com.littlecodesauce.ggnext.service.OverlayManager.stop(app)
            toast("已关闭悬浮窗")
        }
    }

    // ============ 工具栏 ============

    fun toolList(tab: NextTab): List<ToolId> = _ui.value.tools[tab] ?: when (tab) {
        NextTab.SEARCH_LIST -> DEFAULT_TOOLS_SEARCH
        NextTab.SAVED_LIST -> DEFAULT_TOOLS_SAVED
        NextTab.MEMORY -> DEFAULT_TOOLS_MEMORY
        NextTab.DEBUG -> DEFAULT_TOOLS_DEBUG
        NextTab.SETTINGS -> emptyList()
    }

    fun moveTool(tab: NextTab, from: Int, to: Int) {
        val list = toolList(tab).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        _ui.update { it.copy(tools = it.tools + (tab to list)) }
        persistToolsForTab(tab, list)
    }

    fun toggleTool(tab: NextTab, id: ToolId) {
        val list = toolList(tab).toMutableList()
        if (list.contains(id)) {
            if (list.size > 1) list.remove(id)
        } else {
            list.add(id)
        }
        _ui.update { it.copy(tools = it.tools + (tab to list)) }
        persistToolsForTab(tab, list)
    }

    fun resetTools(tab: NextTab) {
        val def = when (tab) {
            NextTab.SEARCH_LIST -> DEFAULT_TOOLS_SEARCH
            NextTab.SAVED_LIST -> DEFAULT_TOOLS_SAVED
            NextTab.MEMORY -> DEFAULT_TOOLS_MEMORY
            NextTab.DEBUG -> DEFAULT_TOOLS_DEBUG
            NextTab.SETTINGS -> emptyList()
        }
        _ui.update {
            it.copy(
                tools = it.tools + (tab to def),
                pinnedTools = it.pinnedTools + (tab to DEFAULT_PINNED_TOOLS)
            )
        }
        persistToolsForTab(tab, def)
        persistPinnedToolsForTab(tab, DEFAULT_PINNED_TOOLS)
    }

    fun pinnedToolList(tab: NextTab): List<ToolId> = _ui.value.pinnedTools[tab] ?: DEFAULT_PINNED_TOOLS

    fun pinTool(tab: NextTab, id: ToolId) {
        val list = pinnedToolList(tab).toMutableList()
        if (!list.contains(id)) {
            list.add(id)
            _ui.update { it.copy(pinnedTools = it.pinnedTools + (tab to list)) }
            persistPinnedToolsForTab(tab, list)
            toast("已将「${AppStrings.tr(id.label)}」固定到右侧工具栏")
        }
    }

    fun unpinTool(tab: NextTab, id: ToolId) {
        val list = pinnedToolList(tab).toMutableList()
        if (list.size <= 1) {
            toast("固定工具栏至少需保留一项")
            return
        }
        if (list.remove(id)) {
            _ui.update { it.copy(pinnedTools = it.pinnedTools + (tab to list)) }
            persistPinnedToolsForTab(tab, list)
            toast("已将「${AppStrings.tr(id.label)}」移回流动工具栏")
        }
    }

    fun movePinnedTool(tab: NextTab, from: Int, to: Int) {
        val list = pinnedToolList(tab).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        _ui.update { it.copy(pinnedTools = it.pinnedTools + (tab to list)) }
        persistPinnedToolsForTab(tab, list)
    }

    /** 模块 1：终止目标进程全异步 IO，绝不阻塞主线程 */
    fun terminateTargetProcess() {
        val target = _ui.value.target ?: run {
            toast("未选择目标进程")
            return
        }
        val pid = target.pid
        scope.launch {
            withContext(Dispatchers.IO) {
                engine.detach()
                if (Shell.hasRoot()) {
                    Shell.runAs("kill -9 $pid", asRoot = true)
                } else {
                    try {
                        android.os.Process.killProcess(pid)
                    } catch (_: Throwable) {
                        Shell.runAs("kill -9 $pid", asRoot = false)
                    }
                }
            }
            _ui.update {
                it.copy(
                    target = null,
                    attachedOk = false,
                    processPaused = false,
                    rawResults = emptyList(),
                    results = emptyList(),
                    memoryRows = emptyList(),
                    memoryPointerChain = emptyList(),
                    memoryPointerIndex = -1,
                    debugExpandedBps = emptyMap(),
                    debugExpandedHits = emptyMap(),
                    searchSelection = SelectionState.EMPTY,
                    memorySelection = SelectionState.EMPTY
                )
            }
            toast("已终止目标进程 [${pid}] ${target.label}")
        }
    }

    fun exitApp() {
        appContext?.let { ctx ->
            val intent = android.content.Intent(ctx, com.littlecodesauce.ggnext.service.FloatingIconService::class.java)
            ctx.stopService(intent)
        }
        _ui.update { it.copy(floatingBallVisible = false, panelVisible = false, sheetStack = emptyList()) }
        toast("修改器已退出")
    }

    fun copyMemory(fromAddr: Long, toAddr: Long, bytes: Long) {
        if (bytes <= 0L) {
            toast("字节数必须大于 0")
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = engine.copyMemory(fromAddr, toAddr, bytes)
            withContext(Dispatchers.Main) {
                if (ok) {
                    toast("已成功复制 $bytes 字节到 0x${java.lang.Long.toHexString(toAddr).uppercase()}")
                    goto(_ui.value.memoryBase, recordHistory = false, highlightTarget = false)
                    closeAllSheets()
                } else {
                    toast("复制内存失败，请检查地址范围是否可读写")
                }
            }
        }
    }

    // ============ Keystone / Capstone 独立汇编引擎扩展包管理 ============

    fun importAsmPackFromUri(uri: android.net.Uri) {
        val ctx = appContext ?: return
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val status = withContext(Dispatchers.IO) {
                AsmEngineManager.importFromUri(ctx, uri)
            }
            _ui.update { it.copy(isBatchBusy = false, asmPack = status) }
            if (status.fullyReady) {
                refreshMemoryDisasmAsync()
                toast("汇编引擎包已导入并加载 (${status.abi})")
            } else if (status.keystoneReady || status.capstoneReady) {
                refreshMemoryDisasmAsync()
                toast("已部分加载汇编引擎：${status.error.ifBlank { status.abi }}")
            } else {
                toast(status.error.ifBlank { "导入汇编引擎包失败" })
            }
        }
    }

    fun importAsmPackFromFile(path: String) {
        val ctx = appContext ?: return
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val status = withContext(Dispatchers.IO) {
                AsmEngineManager.importFromFile(ctx, File(path))
            }
            _ui.update { it.copy(isBatchBusy = false, asmPack = status) }
            if (status.fullyReady) {
                refreshMemoryDisasmAsync()
                toast("汇编引擎包已导入并加载 (${status.abi})")
            } else if (status.keystoneReady || status.capstoneReady) {
                refreshMemoryDisasmAsync()
                toast("已部分加载汇编引擎：${status.error.ifBlank { status.abi }}")
            } else {
                toast(status.error.ifBlank { "导入汇编引擎包失败" })
            }
        }
    }

    fun clearAsmPack() {
        val ctx = appContext ?: return
        scope.launch {
            val status = withContext(Dispatchers.IO) { AsmEngineManager.clearPack(ctx) }
            _ui.update { it.copy(asmPack = status, memoryDisasm = emptyMap()) }
            toast("已移除外置汇编引擎包")
        }
    }

    fun refreshMemoryDisasmAsync() {
        val st = _ui.value
        val arch = Formatter.activeAsmArch(st.valueMaskMemory)
        if (arch == null || st.memoryRows.isEmpty() || !st.attachedOk) {
            if (st.memoryDisasm.isNotEmpty()) {
                _ui.update { it.copy(memoryDisasm = emptyMap()) }
            }
            return
        }
        val base = st.memoryBase
        scope.launch(Dispatchers.IO) {
            val block = engine.readBlock(base, MEMORY_PAGE_BYTES)
            val disasm = AsmEngineManager.disassembleBlockMap(arch, base, block, MEMORY_PAGE_ROWS)
            _ui.update { cur ->
                if (cur.memoryBase == base) cur.copy(memoryDisasm = disasm) else cur
            }
        }
    }

    fun writeAssembly(
        address: Long,
        arch: AsmArch,
        asmCode: String,
        onDone: (Boolean, String) -> Unit
    ) {
        val clean = asmCode.trim()
        if (clean.isEmpty()) {
            onDone(false, "请输入汇编指令")
            return
        }
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val result = withContext(Dispatchers.IO) {
                val res = AsmEngineManager.assemble(arch, address, clean)
                if (res.isFailure) {
                    return@withContext false to (res.exceptionOrNull()?.message ?: "汇编失败")
                }
                val bytes = res.getOrThrow()
                if (bytes.isEmpty()) {
                    return@withContext false to "未生成有效机器码"
                }
                val ok = engine.writeBlock(address, bytes)
                if (!ok) {
                    return@withContext false to "写入目标地址内存失败（请确认权限或内存页可写）"
                }
                true to "已写入 ${bytes.size} 字节 (${AsmEngineManager.bytesToSpacedHex(bytes.take(12).toByteArray())}${if (bytes.size > 12) " …" else ""})"
            }
            _ui.update { it.copy(isBatchBusy = false) }
            if (result.first) {
                val pageBase = _ui.value.memoryBase
                if (_ui.value.memoryRows.isNotEmpty()) {
                    val (rows, disasm) = withContext(Dispatchers.IO) { readMemoryPageWithDisasm(pageBase) }
                    _ui.update { it.copy(memoryRows = rows, memoryDisasm = disasm) }
                }
                toast(result.second)
            }
            onDone(result.first, result.second)
        }
    }

    // ============ 硬件断点调试器 (Perf / Ptrace) ============

    fun breakpointBackendMode(st: Ui = _ui.value): BpMode {
        return when (st.settings["bp_backend"]) {
            "perf" -> BpMode.PERF
            "ptrace" -> BpMode.PTRACE
            else -> if (st.workMode == WorkMode.ROOT && st.hasRootPermission) BpMode.PERF else BpMode.PTRACE
        }
    }

    fun setBreakpointBackendMode(mode: BpMode) {
        val raw = if (mode == BpMode.PERF) "perf" else "ptrace"
        setSetting("bp_backend", raw)
        toast("已切换断点后端为 ${mode.label}")
    }

    fun modifyBreakpointRegister(
        bpId: Int,
        tid: Int,
        hitPc: Long,
        regName: String,
        hexValue: String,
        isFp: Boolean,
        onDone: (Boolean, String) -> Unit
    ) {
        scope.launch {
            val (ok, err, snap) = withContext(Dispatchers.IO) {
                val (wOk, wErr) = engine.bpSetRegister(bpId, tid, hitPc, regName, hexValue, isFp)
                val nextSnap = if (wOk) engine.bpSnapshot() else null
                Triple(wOk, wErr, nextSnap)
            }
            if (ok && snap != null) {
                _ui.update { it.copy(breakpoints = snap) }
                toast("已修改寄存器 $regName = ${hexValue.uppercase()}")
                onDone(true, "")
            } else {
                val msg = err.ifBlank { "寄存器写回失败" }
                toast(msg)
                onDone(false, msg)
            }
        }
    }

    fun defaultBreakpointAddressHex(): String {
        val st = _ui.value
        val addr = st.contextItem?.address
            ?: checkedItems().firstOrNull()?.address
            ?: st.memoryHighlightAddress
            ?: st.memoryBase
        return if (addr != 0L) java.lang.Long.toUnsignedString(addr, 16).uppercase() else ""
    }

    fun addBreakpoint(
        address: Long,
        type: BpType,
        sizeBytes: Int,
        mode: BpMode,
        captureCallstack: Boolean
    ) {
        if (!_ui.value.attachedOk) {
            toast("请先附加目标进程")
            return
        }
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val (bpId, err) = withContext(Dispatchers.IO) {
                engine.bpAdd(address, type, sizeBytes, mode, captureCallstack)
            }
            if (bpId > 0) {
                val snap = withContext(Dispatchers.IO) { engine.bpSnapshot() }
                _ui.update { st ->
                    st.copy(
                        isBatchBusy = false,
                        breakpoints = snap,
                        tab = NextTab.DEBUG,
                        sheetStack = emptyList()
                    )
                }
                ensureBpPolling()
                toast("已添加断点 #$bpId (${java.lang.Long.toUnsignedString(address, 16).uppercase()})")
            } else {
                _ui.update { it.copy(isBatchBusy = false) }
                toast(err.ifBlank { "添加断点失败" })
            }
        }
    }

    fun removeBreakpoint(bpId: Int) {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                engine.bpRemove(bpId)
                engine.bpSnapshot()
            }
            _ui.update { st ->
                val hitPrefix = "$bpId:"
                st.copy(
                    breakpoints = snap,
                    selectedBpIds = st.selectedBpIds - bpId,
                    debugExpandedBps = st.debugExpandedBps - bpId,
                    debugExpandedHits = st.debugExpandedHits.filterKeys { !it.startsWith(hitPrefix) }
                )
            }
            if (snap.none { it.running }) {
                bpPollingJob?.cancel()
                bpPollingJob = null
            }
            toast("已移除断点 #$bpId")
        }
    }

    fun toggleDebugBpExpanded(bpId: Int, currentExpanded: Boolean) {
        _ui.update { st ->
            st.copy(debugExpandedBps = st.debugExpandedBps + (bpId to !currentExpanded))
        }
    }

    fun toggleDebugHitExpanded(hitKey: String) {
        _ui.update { st ->
            val cur = st.debugExpandedHits[hitKey] ?: false
            st.copy(debugExpandedHits = st.debugExpandedHits + (hitKey to !cur))
        }
    }

    fun removeSelectedBreakpoints() {
        val ids = _ui.value.selectedBpIds.ifEmpty { _ui.value.breakpoints.map { it.id }.toSet() }
        if (ids.isEmpty()) {
            toast("当前没有可删除的断点")
            return
        }
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                ids.forEach { engine.bpRemove(it) }
                engine.bpSnapshot()
            }
            _ui.update { st ->
                st.copy(
                    breakpoints = snap,
                    selectedBpIds = emptySet(),
                    debugExpandedBps = st.debugExpandedBps - ids,
                    debugExpandedHits = st.debugExpandedHits.filterKeys { key ->
                        ids.none { id -> key.startsWith("$id:") }
                    }
                )
            }
            if (snap.none { it.running }) {
                bpPollingJob?.cancel()
                bpPollingJob = null
            }
            toast("已删除 ${ids.size} 个断点")
        }
    }

    fun toggleBreakpointPause(bpId: Int) {
        val bp = _ui.value.breakpoints.firstOrNull { it.id == bpId } ?: return
        val nextPaused = !bp.paused
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                engine.bpSetPaused(bpId, nextPaused)
                engine.bpSnapshot()
            }
            _ui.update { it.copy(breakpoints = snap) }
            if (snap.any { it.running }) ensureBpPolling()
        }
    }

    fun toggleAllBreakpointsPause() {
        val list = _ui.value.breakpoints
        if (list.isEmpty()) return
        val anyRunning = list.any { it.isActive }
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                engine.bpSetAllPaused(anyRunning)
                engine.bpSnapshot()
            }
            _ui.update { it.copy(breakpoints = snap) }
            if (snap.any { it.running }) ensureBpPolling()
            toast(if (anyRunning) "已暂停全部断点" else "已恢复全部断点")
        }
    }

    fun clearBreakpointHits(bpId: Int = 0) {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                engine.bpClearHits(bpId)
                engine.bpSnapshot()
            }
            _ui.update { it.copy(breakpoints = snap) }
            toast("已清空断点命中记录")
        }
    }

    fun toggleBpSelection(bpId: Int) {
        _ui.update { st ->
            val next = if (bpId in st.selectedBpIds) st.selectedBpIds - bpId else st.selectedBpIds + bpId
            st.copy(selectedBpIds = next)
        }
    }

    fun selectAllBreakpoints() {
        _ui.update { st -> st.copy(selectedBpIds = st.breakpoints.map { it.id }.toSet()) }
    }

    fun invertBpSelection() {
        _ui.update { st ->
            val all = st.breakpoints.map { it.id }.toSet()
            st.copy(selectedBpIds = all - st.selectedBpIds)
        }
    }

    fun clearBpSelection() {
        _ui.update { it.copy(selectedBpIds = emptySet()) }
    }

    fun refreshBreakpointsNow() {
        scope.launch {
            val snap = withContext(Dispatchers.IO) { engine.bpSnapshot() }
            _ui.update { it.copy(breakpoints = snap) }
            if (snap.any { it.running }) ensureBpPolling()
        }
    }

    private val bpTraceLastSeqByBp = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun setBpTraceEnabled(enabled: Boolean, filterBpId: Int? = null) {
        _ui.update { st ->
            st.copy(
                bpTraceEnabled = enabled,
                bpTracePaused = if (enabled) false else st.bpTracePaused,
                bpTraceFilterBpId = filterBpId ?: st.bpTraceFilterBpId
            )
        }
        if (enabled) {
            ensureBpPolling()
        }
    }

    fun setBpTracePaused(paused: Boolean) {
        _ui.update { it.copy(bpTracePaused = paused) }
    }

    fun setBpTraceFilterBpId(bpId: Int) {
        _ui.update { it.copy(bpTraceFilterBpId = bpId) }
    }

    fun setBpTraceRegFilter(filter: String) {
        _ui.update { it.copy(bpTraceRegFilter = filter) }
    }

    fun setBpTraceShowFpr(show: Boolean) {
        _ui.update { it.copy(bpTraceShowFpr = show) }
    }

    fun clearBpTraceLogs() {
        val bpId = _ui.value.bpTraceFilterBpId
        if (bpId <= 0) bpTraceLastSeqByBp.clear() else bpTraceLastSeqByBp.remove(bpId)
        scope.launch(Dispatchers.IO) {
            engine.bpClearTrace(bpId)
        }
        _ui.update { st ->
            val remain = if (bpId <= 0) emptyList() else st.bpTraceLogs.filterNot { it.bpId == bpId }
            st.copy(bpTraceLogs = remain)
        }
        toast("已清空实时寄存器日志")
    }

    fun exportBpTraceLogs(regFilterSet: Set<String> = emptySet()) {
        val st = _ui.value
        val logs = if (st.bpTraceFilterBpId <= 0) st.bpTraceLogs else st.bpTraceLogs.filter { it.bpId == st.bpTraceFilterBpId }
        if (logs.isEmpty()) {
            toast("当前没有可导出的寄存器日志")
            return
        }
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File("/sdcard/GGNext/trace")
                    if (!dir.exists()) dir.mkdirs()
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    val outFile = java.io.File(dir, "bp_reg_trace_$ts.txt")
                    val content = logs.joinToString("\n") { it.formatFridaBlock(regFilterSet, st.bpTraceShowFpr) }
                    outFile.writeText(content, Charsets.UTF_8)
                    outFile.absolutePath
                }.getOrNull()
            }
            if (path != null) {
                toast("已导出 ${logs.size} 条寄存器日志至 $path")
            } else {
                toast("导出日志失败，请检查存储权限")
            }
        }
    }

    private fun ensureBpPolling() {
        if (bpPollingJob?.isActive == true) return
        bpPollingJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val stBefore = _ui.value
                val liveTracing = stBefore.bpTraceEnabled && !stBefore.bpTracePaused
                delay(if (liveTracing) 180L else 420L)
                val st = _ui.value
                if (!st.attachedOk) {
                    bpTraceLastSeqByBp.clear()
                    _ui.update {
                        it.copy(
                            breakpoints = emptyList(),
                            selectedBpIds = emptySet(),
                            debugExpandedBps = emptyMap(),
                            debugExpandedHits = emptyMap(),
                            bpTraceLogs = emptyList()
                        )
                    }
                    break
                }
                val snap = engine.bpSnapshot()
                if (st.bpTraceEnabled && !st.bpTracePaused && snap.isNotEmpty()) {
                    val fetchedAll = ArrayList<BpTraceEvent>()
                    for (bp in snap) {
                        val lastSeq = bpTraceLastSeqByBp[bp.id] ?: 0L
                        val batch = engine.bpTraceEvents(bpId = bp.id, sinceSeq = lastSeq, limit = 80)
                        if (batch.isNotEmpty()) {
                            var maxS = lastSeq
                            for (ev in batch) {
                                if (ev.seq > maxS) maxS = ev.seq
                                fetchedAll.add(ev)
                            }
                            bpTraceLastSeqByBp[bp.id] = maxS
                        }
                    }
                    if (fetchedAll.isNotEmpty()) {
                        fetchedAll.sortWith(compareBy<BpTraceEvent>({ it.timestampMs }, { it.seq }))
                        _ui.update { cur ->
                            val merged = (cur.bpTraceLogs + fetchedAll).takeLast(500)
                            cur.copy(breakpoints = snap, bpTraceLogs = merged)
                        }
                    } else {
                        _ui.update { it.copy(breakpoints = snap) }
                    }
                } else {
                    _ui.update { it.copy(breakpoints = snap) }
                }
                if (snap.isEmpty() || snap.none { it.running }) {
                    break
                }
            }
        }
    }

    // ============ MCP 服务桥接（复用真实引擎实例与状态同步） ============

    internal fun engineForMcp(): MemoryEngine = engine

    internal fun attachFromMcpSync(proc: TargetProcess): Boolean {
        val st = _ui.value
        val ok = engine.attach(
            targetPid = proc.pid,
            isRootMode = (st.workMode == WorkMode.ROOT),
            context = appContext
        )
        if (ok) {
            val freshRegions = engine.regions()
            _ui.update { cur ->
                cur.copy(
                    target = proc.copy(x64 = engine.x64),
                    attachedOk = true,
                    results = emptyList(),
                    totalCount = 0L
                )
            }
            // freshRegions 已刷新引擎 VMA 缓存
        }
        return ok
    }

    internal fun syncSearchResultsFromMcp(items: List<MemoryItem>, totalCount: Long) {
        _ui.update { cur ->
            cur.copy(
                results = items,
                totalCount = totalCount
            )
        }
    }

    internal fun setSelectedRegionsFromMcp(regions: Set<String>) {
        if (regions.isEmpty()) return
        _ui.update { it.copy(selectedRegions = regions) }
        persistSelectedRegions(regions, _ui.value.target?.packageName)
    }

    private val mcpFrozenMap = java.util.concurrent.ConcurrentHashMap<Long, Pair<Int, Int>>()

    internal fun isAddressFrozenInMcp(address: Long): Pair<Boolean, Int> {
        mcpFrozenMap[address]?.let { return true to it.second }
        val savedItem = _ui.value.saved.firstOrNull { it.address == address && it.freeze }
        return if (savedItem != null) true to savedItem.freezeType else false to 0
    }

    internal fun syncFreezeStateFromMcp(
        address: Long,
        flags: Int,
        value: Long,
        enabled: Boolean,
        freezeType: Int = 0,
        freezeFrom: Long = 0L,
        freezeTo: Long = 0L
    ) {
        if (enabled) {
            mcpFrozenMap[address] = flags to freezeType
        } else {
            mcpFrozenMap.remove(address)
        }
        var savedChanged = false
        var nextSavedSnapshot: List<MemoryItem> = emptyList()
        _ui.update { cur ->
            val nextSaved = cur.saved.map { item ->
                if (item.address == address) {
                    savedChanged = true
                    item.copy(
                        value = if (enabled) value else item.value,
                        flags = if (enabled) (flags and 0x7F) else item.flags,
                        freeze = enabled,
                        freezeType = if (enabled) freezeType else item.freezeType,
                        freezeFrom = if (enabled && freezeType == 3) freezeFrom else item.freezeFrom,
                        freezeTo = if (enabled && freezeType == 3) freezeTo else item.freezeTo
                    )
                } else item
            }
            nextSavedSnapshot = nextSaved
            if (savedChanged) syncFrozenSnapshot(nextSaved)
            if (savedChanged) cur.copy(saved = nextSaved) else cur
        }
        if (savedChanged) persistSavedListAsync(nextSavedSnapshot)
    }

    internal fun clearAllFreezeStateFromMcp() {
        mcpFrozenMap.clear()
        var savedChanged = false
        var nextSavedSnapshot: List<MemoryItem> = emptyList()
        _ui.update { cur ->
            val nextSaved = cur.saved.map { item ->
                if (item.freeze) {
                    savedChanged = true
                    item.copy(freeze = false)
                } else item
            }
            nextSavedSnapshot = nextSaved
            if (savedChanged) syncFrozenSnapshot(nextSaved)
            if (savedChanged) cur.copy(saved = nextSaved) else cur
        }
        if (savedChanged) persistSavedListAsync(nextSavedSnapshot)
    }

    internal fun syncWrittenAddressFromMcp(address: Long, newValue: Long) {
        var savedChanged = false
        var nextSavedSnapshot: List<MemoryItem> = emptyList()
        _ui.update { cur ->
            val nextRaw = cur.rawResults.map { if (it.address == address) it.copy(previousValue = it.value, value = newValue, manuallyEdited = true) else it }
            val nextRes = cur.results.map { if (it.address == address) it.copy(previousValue = it.value, value = newValue, manuallyEdited = true) else it }
            val nextSaved = cur.saved.map {
                if (it.address == address) {
                    savedChanged = true
                    it.copy(previousValue = it.value, value = newValue, manuallyEdited = true)
                } else it
            }
            val nextMem = cur.memoryRows.map { if (it.address == address) it.copy(previousValue = it.value, value = newValue, manuallyEdited = true) else it }
            nextSavedSnapshot = nextSaved
            cur.copy(rawResults = nextRaw, results = nextRes, saved = nextSaved, memoryRows = nextMem)
        }
        if (savedChanged) persistSavedListAsync(nextSavedSnapshot)
    }

    internal fun removeSearchAddressesFromMcp(addresses: Set<Long>): Int {
        if (addresses.isEmpty()) return 0
        engine.markRemoved(addresses)
        val curSnap = _ui.value
        val nextRawLocal = curSnap.rawResults.filterNot { it.address in addresses }
        val removedCount = (curSnap.results.size - curSnap.results.count { it.address !in addresses }).coerceAtLeast(0)
        val nextTotal = (curSnap.totalCount - addresses.size.toLong()).coerceAtLeast(nextRawLocal.size.toLong())
        val refillCap = computeNativeFetchLimit(curSnap.filter)
        val finalRaw = if (engine.supportsNativeScan && nextTotal > nextRawLocal.size.toLong() && nextRawLocal.size < refillCap) {
            val fetched = engine.fetchStoredCandidates(limit = refillCap, defaultType = curSnap.searchType, useCandidateDt = true)
            if (fetched.isNotEmpty()) fetched else nextRawLocal
        } else nextRawLocal
        val nextRes = filterList(finalRaw, curSnap.filter)
        _ui.update { cur ->
            cur.copy(
                rawResults = finalRaw,
                results = nextRes,
                totalCount = nextTotal.coerceAtLeast(finalRaw.size.toLong()),
                hintVisible = nextRes.isEmpty()
            )
        }
        return if (removedCount > 0) removedCount else addresses.size
    }

    internal fun saveItemsFromMcp(items: List<MemoryItem>): Int {
        if (items.isEmpty()) return 0
        var added = 0
        _ui.update { cur ->
            val existingMap = cur.saved.associateBy { it.address to (it.flags and 0x7F) }.toMutableMap()
            for (item in items) {
                val key = item.address to (item.flags and 0x7F)
                if (!existingMap.containsKey(key)) added++
                existingMap[key] = item
            }
            cur.copy(saved = existingMap.values.toList())
        }
        persistSavedListAsync(_ui.value.saved)
        return added
    }

    internal fun removeSavedAddressesFromMcp(addresses: Set<Long>): Int {
        if (addresses.isEmpty()) return 0
        addresses.forEach { mcpFrozenMap.remove(it) }
        engine.unlockBatch(addresses)
        var removed = 0
        _ui.update { cur ->
            val filtered = cur.saved.filterNot { it.address in addresses }
            removed = cur.saved.size - filtered.size
            cur.copy(saved = filtered)
        }
        persistSavedListAsync(_ui.value.saved)
        return removed
    }

    internal fun updateSavedNoteFromMcp(address: Long, note: String): Boolean {
        var found = false
        _ui.update { cur ->
            val next = cur.saved.map { item ->
                if (item.address == address) {
                    found = true
                    item.copy(name = note, note = note)
                } else item
            }
            cur.copy(saved = next)
        }
        if (found) persistSavedListAsync(_ui.value.saved)
        return found
    }

    internal fun clearSavedFromMcp(): Int {
        val count = _ui.value.saved.size
        mcpFrozenMap.clear()
        engine.clearNativeFreezes()
        _ui.update { it.copy(saved = emptyList()) }
        persistSavedListAsync(emptyList())
        return count
    }

    internal fun controlProcessFromMcp(action: String): Pair<Boolean, String> {
        val st = _ui.value
        val pid = engine.pid
        if (!engine.attachedOk || pid <= 0) return false to "No target process attached"
        val asRoot = (st.workMode == WorkMode.ROOT && Shell.hasRoot())
        return when (action) {
            "pause" -> {
                engine.setFreezePaused(true)
                val res = Shell.runAs("kill -STOP $pid", asRoot = asRoot)
                if (res.exitCode == 0) {
                    _ui.update { it.copy(processPaused = true) }
                    true to "Process $pid paused"
                } else false to res.out.joinToString(" ").ifBlank { "Failed to pause process $pid" }
            }
            "resume" -> {
                val res = Shell.runAs("kill -CONT $pid", asRoot = asRoot)
                engine.setFreezePaused(false)
                if (res.exitCode == 0) {
                    _ui.update { it.copy(processPaused = false) }
                    true to "Process $pid resumed"
                } else false to res.out.joinToString(" ").ifBlank { "Failed to resume process $pid" }
            }
            "kill" -> {
                engine.clearNativeFreezes()
                engine.bpStopAll()
                val res = Shell.runAs("kill -9 $pid", asRoot = asRoot)
                if (res.exitCode == 0) {
                    engine.detach()
                    _ui.update { it.copy(attachedOk = false, processPaused = false, target = null, results = emptyList(), totalCount = 0L) }
                    true to "Process $pid terminated"
                } else false to res.out.joinToString(" ").ifBlank { "Failed to terminate process $pid" }
            }
            else -> false to "Unsupported action: $action"
        }
    }

    // ============ 内置 Lua 5.5.1 引擎桥接（复用真实引擎与 UI 状态同步） ============

    internal fun appContextForLua(): Application? = appContext

    internal fun syncSearchResultsFromLua(items: List<MemoryItem>, totalCount: Long) {
        val filter = _ui.value.filter
        val filtered = filterList(items, filter)
        _ui.update { cur ->
            cur.copy(
                rawResults = items,
                results = filtered,
                totalCount = totalCount.coerceAtLeast(items.size.toLong()),
                hintVisible = filtered.isEmpty(),
                searchSelection = SelectionState.EMPTY
            )
        }
    }

    internal fun getSelectedResultsForLua(): List<MemoryItem> {
        val st = _ui.value
        return st.results.filter { st.searchSelection.isSelected(SelectionState.itemKey(it)) }
    }

    internal fun getSelectedSavedItemsForLua(): List<MemoryItem> {
        val st = _ui.value
        return st.saved.filter { st.savedSelection.isSelected(SelectionState.itemKey(it)) }
    }

    internal fun getSelectedMemoryAddressesForLua(): List<Long> {
        val st = _ui.value
        return st.memoryRows
            .filter { st.memorySelection.isSelected(SelectionState.memoryRowKey(it)) }
            .map { it.address }
    }
}
