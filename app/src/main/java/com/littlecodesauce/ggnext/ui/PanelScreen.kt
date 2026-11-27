package com.littlecodesauce.ggnext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.littlecodesauce.ggnext.core.FileSheetMode
import com.littlecodesauce.ggnext.core.NextTab
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.lua.LuaFloatingOverlayControls
import com.littlecodesauce.ggnext.lua.LuaOverlayHost
import com.littlecodesauce.ggnext.ui.components.Hairline
import com.littlecodesauce.ggnext.ui.components.NavBar
import com.littlecodesauce.ggnext.ui.components.ProcessBar
import com.littlecodesauce.ggnext.ui.components.RegionSummaryBar
import com.littlecodesauce.ggnext.ui.components.ToolBar
import com.littlecodesauce.ggnext.ui.components.clickableNoRipple
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.ggGlassSurface
import com.littlecodesauce.ggnext.ui.components.ggPageBackdrop
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.model.RegionCatalog
import com.littlecodesauce.ggnext.ui.model.ToolId
import com.littlecodesauce.ggnext.ui.screen.DebugScreen
import com.littlecodesauce.ggnext.ui.screen.MemoryScreen
import com.littlecodesauce.ggnext.ui.screen.SavedListScreen
import com.littlecodesauce.ggnext.ui.screen.SearchListScreen
import com.littlecodesauce.ggnext.ui.screen.SettingsScreen
import com.littlecodesauce.ggnext.ui.sheet.SheetHost
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

/**
 * 修改器工作面板：
 * - 支持背景透明度（ui.metrics.bgOpacity，25% ~ 100%）实时调节
 * - 支持页面屏幕占比（ui.metrics.panelScale，40% ~ 100%）
 * - 界面调整 7：为顶部 ToolBar 接入 `onMoveTool = { from, to -> Session.moveTool(ui.tab, from, to) }`，支持长按工具栏按钮左右滑动直接调整顺序
 */
@Composable
fun PanelScreen(
    ui: Ui,
    onNav: (NextTab) -> Unit,
    onMenu: () -> Unit,
    onMinimize: () -> Unit,
    compact: Boolean = false
) {
    BackHandler(enabled = ui.sheet != Sheet.NONE) { Session.popSheet() }

    val sheetOpen = ui.sheet != Sheet.NONE
    val app = LocalContext.current.applicationContext as android.app.Application
    val clipboard = LocalClipboardManager.current
    var refreshRequested by remember { mutableStateOf(false) }

    LaunchedEffect(refreshRequested) {
        if (refreshRequested) {
            Session.loadProcesses(app)
            refreshRequested = false
        }
    }

    val contentScale by animateFloatAsState(if (sheetOpen) 0.975f else 1f, Motion.Normal, label = "cs")
    val contentAlpha by animateFloatAsState(if (sheetOpen) 0.55f else 1f, Motion.Normal, label = "ca")

    val checkedCount = ui.selectedCount

    val orderedSelectedRegions = remember(ui.selectedRegions) {
        RegionCatalog.ALL.map { it.code }.filter { ui.selectedRegions.contains(it) }
    }

    val panelScale = ui.metrics.panelScale.coerceIn(0.40f, 1.0f)
    val bgOpacity = ui.metrics.bgOpacity.coerceIn(0.25f, 1.0f)
    val isPartialScreen = panelScale < 0.99f
    val panelCorner = if (isPartialScreen) 16.dp else 0.dp

    val workArea: @Composable (Modifier) -> Unit = { mod ->
        Column(modifier = mod) {
            val savedFmtSummary = remember(ui.valueMaskSaved) {
                val aux = ui.valueMaskSaved and com.littlecodesauce.ggnext.engine.Formatter.AUX_FORMAT_MASK
                if (aux == 0) "F:" else "F:${com.littlecodesauce.ggnext.engine.Formatter.maskSummary(aux)}"
            }
            ProcessBar(
                target = ui.target,
                paused = ui.processPaused,
                countText = ui.countButtonText,
                busy = ui.busy,
                savedFormatLabel = if (ui.tab == NextTab.SAVED_LIST) savedFmtSummary else null,
                onSavedFormatClick = { Session.openSheet(Sheet.VALUE_FORMAT) },
                onPick = { Session.openSheet(Sheet.PROCESS) },
                onCountClick = { Session.openSheet(Sheet.FILTER) },
                onRefresh = {
                    Session.refreshLiveValues(manual = true)
                },
                onPause = { Session.toggleProcessPause() }
            )
            Hairline()
            if (ui.tab != NextTab.SETTINGS) {
                ToolBar(
                    flowingTools = ui.flowingTools,
                    pinnedTools = ui.currentPinnedTools,
                    ui = ui,
                    onTool = { id ->
                        handleTool(id, ui) { text ->
                            clipboard.setText(AnnotatedString(text))
                        }
                    },
                    onScript = { Session.openSheet(Sheet.SCRIPT) },
                    onMoreMenu = onMenu,
                    onMoveFlowingTool = { from, to ->
                        val fullList = Session.toolList(ui.tab).toMutableList()
                        val flowing = ui.flowingTools
                        if (from in flowing.indices && to in flowing.indices) {
                            val fromItem = flowing[from]
                            val toItem = flowing[to]
                            val idxFrom = fullList.indexOf(fromItem)
                            val idxTo = fullList.indexOf(toItem)
                            if (idxFrom != -1 && idxTo != -1) {
                                Session.moveTool(ui.tab, idxFrom, idxTo)
                            }
                        }
                    },
                    onMovePinnedTool = { from, to ->
                        Session.movePinnedTool(ui.tab, from, to)
                    }
                )
                Hairline()
            }
            AnimatedVisibility(visible = ui.busy) {
                com.littlecodesauce.ggnext.ui.components.InlineSearchProgressBar(ui = ui)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = ui.tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (fadeIn(tween(160)) + slideInVertically(tween(180)) { h -> if (forward) h / 14 else -h / 14 })
                            .togetherWith(fadeOut(tween(110)))
                    },
                    label = "panelTab",
                    modifier = Modifier.fillMaxSize()
                ) { tab ->
                    when (tab) {
                        NextTab.SEARCH_LIST -> SearchListScreen(ui)
                        NextTab.SAVED_LIST -> SavedListScreen(ui)
                        NextTab.MEMORY -> MemoryScreen(ui)
                        NextTab.SETTINGS -> SettingsScreen(ui, embedded = true)
                        NextTab.DEBUG -> DebugScreen(ui)
                    }
                }

                if (ui.tab != NextTab.DEBUG) {
                    RegionSummaryBar(
                        codes = orderedSelectedRegions,
                        checkedItemCount = checkedCount,
                        onLongPress = { Session.openSheet(Sheet.REGION) },
                        onClick = { Session.openSheet(Sheet.REGION) },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    // 无论页面占比是否为 100%，外层始终避让顶部状态栏（悬浮窗模式由 FloatingIconService 窗口安全区统一避让）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (compact) Modifier else Modifier.statusBarsPadding())
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (isPartialScreen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickableNoRipple(remember { MutableInteractionSource() }) {
                        if (ui.sheet != Sheet.NONE) {
                            Session.popSheet()
                        }
                    }
            )
        }

        val panelShape = RoundedCornerShape(panelCorner)
        Box(
            modifier = Modifier
                .fillMaxWidth(panelScale)
                .fillMaxHeight(panelScale)
                .clip(panelShape)
                .ggPageBackdrop(
                    baseColor = Rose.Surface,
                    respectBgOpacity = true,
                    opacityScale = 1.0f
                )
                .ggGlassSurface(
                    shape = panelShape,
                    baseColor = Rose.Surface,
                    borderColor = if (isPartialScreen) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                    borderWidth = if (isPartialScreen) GgBorder.Default else GgBorder.Hairline,
                    respectBgOpacity = true,
                    bgOpacityScale = 1.0f,
                    accentColor = Rose.Primary,
                    highlightStrength = 1.05f
                )
                .clickableNoRipple(remember { MutableInteractionSource() }) {}
        ) {
            val contentMod = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = contentScale
                    scaleY = contentScale
                    alpha = contentAlpha
                }

            Column(modifier = contentMod) {
                NavBar(
                    tab = ui.tab,
                    searchCount = ui.results.size,
                    savedCount = ui.saved.size,
                    debugCount = ui.breakpoints.count { it.isActive },
                    onTab = onNav,
                    onMinimize = onMinimize
                )
                workArea(Modifier.weight(1f).fillMaxWidth())
            }
        }

        SheetHost(ui)
        LuaFloatingOverlayControls(modifier = Modifier.fillMaxSize())
        LuaOverlayHost()
        PanelToastHost()
    }
}

@Composable
private fun PanelToastHost() {
    val toast by Session.toastState.collectAsStateWithLifecycle()
    toast?.let { msg ->
        ToastBubble(msg) { Session.consumeToast() }
    }
}

@Composable
fun ToastBubble(msg: String, onDone: () -> Unit) {
    LaunchedEffect(msg) {
        kotlinx.coroutines.delay(2200)
        onDone()
    }
    // 界面调整 4：结果打印输出在屏幕右下角显示（参考图7软件的效果）
    Box(
        Modifier
            .fillMaxSize()
            .padding(end = 16.dp, bottom = 24.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally(tween(220)) { it / 2 } + fadeIn(tween(220)) + scaleIn(tween(220), 0.92f),
            exit = slideOutHorizontally(tween(160)) { it / 2 } + fadeOut(tween(160))
        ) {
            Row(
                Modifier
                    .ggGlassSurface(
                        shape = GgShapes.Lg,
                        baseColor = Rose.ToastBg,
                        borderColor = if (Rose.IsDark) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                        borderWidth = GgBorder.Default,
                        respectBgOpacity = true,
                        bgOpacityScale = 0.96f,
                        accentColor = Rose.Primary,
                        highlightStrength = 1.15f
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Rose.Green)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "GGNext",
                        style = monoStyle(11, Rose.ToastBrand, weight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(1.5.dp))
                    Text(
                        text = tr(msg),
                        color = Rose.Text,
                        fontSize = fsp(12.5f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun handleTool(
    id: ToolId,
    ui: Ui,
    onCopyText: (String) -> Unit
) {
    when (id) {
        ToolId.SEARCH -> Session.openSheet(Sheet.SEARCH)
        ToolId.FUZZY -> Session.openSheet(Sheet.FUZZY)
        ToolId.MASK -> Session.openSheet(Sheet.MASK_SEARCH)
        ToolId.POINTER -> Session.openSheet(Sheet.POINTER_SEARCH)
        ToolId.FILTER -> Session.openSheet(Sheet.FILTER)
        ToolId.EDIT -> {
            val targets = Session.itemsToEdit()
            if (targets.isEmpty()) {
                Session.toast("无项目可修改，请先搜索或勾选项目")
            } else {
                Session.openSheet(Sheet.EDITOR, targets.first(), singleEdit = false)
            }
        }
        ToolId.SAVE -> Session.saveChecked()
        ToolId.REMOVE -> Session.openSheet(Sheet.REMOVE)
        ToolId.REVERT -> Session.revertChecked()
        ToolId.SELECT_ALL -> Session.selectAll()
        ToolId.INVERT -> Session.invertSelection()
        ToolId.CLEAR_SEL -> Session.clearSelection()
        ToolId.COPY_SEARCH -> {
            val checked = Session.checkedItems()
            if (checked.isEmpty()) {
                Session.toast("请先勾选要复制为联合搜索的项目")
            } else {
                Session.openSheet(Sheet.COPY_GROUP)
            }
        }
        ToolId.LOAD_RESULTS -> Session.loadCheckedAsResults()
        ToolId.LOAD_CHANGED -> Session.loadChangedAsResults()
        ToolId.OFFSET -> Session.openSheet(Sheet.OFFSET)
        ToolId.CALC_OFFSET -> Session.openSheet(Sheet.CALC_OFFSET)
        ToolId.ADDRESS -> {
            val checked = Session.checkedItems()
            val first = checked.firstOrNull() ?: ui.results.firstOrNull() ?: ui.saved.firstOrNull() ?: ui.memoryRows.firstOrNull()
            Session.openSheet(Sheet.ADDRESS_EDIT, first)
        }
        ToolId.LOAD -> Session.openFileSheet(FileSheetMode.LOAD)
        ToolId.SAVE_FILE -> Session.openFileSheet(FileSheetMode.SAVE)
        ToolId.EXPORT -> Session.openFileSheet(FileSheetMode.EXPORT)
        ToolId.GOTO -> Session.openSheet(Sheet.GOTO)
        ToolId.COPY_MEM -> Session.openSheet(Sheet.COPY_MEMORY)
        ToolId.DUMP -> Session.openSheet(Sheet.DUMP)
        ToolId.ALLOCATE -> Session.openSheet(Sheet.ALLOCATE)
        ToolId.BREAKPOINT -> {
            val first = Session.checkedItems().firstOrNull()
            Session.openSheet(Sheet.BREAKPOINT, first)
        }
        ToolId.WRITE_ASM -> {
            val first = Session.checkedItems().firstOrNull()
            Session.openSheet(Sheet.WRITE_ASM, first)
        }
        ToolId.BACK -> Session.memoryBack()
        ToolId.FORWARD -> Session.memoryForward()
        ToolId.SPEED -> Session.openSheet(Sheet.SPEED)
        ToolId.EXEC_SCRIPT -> Session.openSheet(Sheet.SCRIPT)
        ToolId.BP_ADD -> Session.openSheet(Sheet.BREAKPOINT)
        ToolId.BP_PAUSE_ALL -> Session.toggleAllBreakpointsPause()
        ToolId.BP_CLEAR_HITS -> Session.clearBreakpointHits(0)
        ToolId.BP_DELETE -> Session.removeSelectedBreakpoints()
        ToolId.MENU -> Session.openSheet(Sheet.TOOLBAR_ORDER)
    }
}
