package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.littlecodesauce.ggnext.core.NextTab
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.ui.components.GgBadge
import com.littlecodesauce.ggnext.ui.components.GgIcons
import com.littlecodesauce.ggnext.ui.components.GgSegmentTab
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.model.ALL_TOOLS
import com.littlecodesauce.ggnext.ui.model.ToolId
import com.littlecodesauce.ggnext.ui.model.toolIcon
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

private val TOOLBAR_TABS = listOf(NextTab.SEARCH_LIST, NextTab.SAVED_LIST, NextTab.MEMORY, NextTab.DEBUG)

/**
 * 工具栏顺序管理弹窗 (ToolbarSheet)
 * 1. 支持分为「固定工具栏 (右侧常驻)」与「流动工具栏 (滑动栏)」两大部分展示；
 * 2. 固定工具栏里的按钮点击后不再在流动工具栏里显示；
 * 3. 固定工具栏与流动工具栏均支持长按拖动快捷调整位置；
 * 4. 支持点击图标一键在固定工具栏与流动工具栏之间互相转移。
 */
@Composable
fun ToolbarSheet(ui: Ui) {
    var tab by remember {
        mutableStateOf(
            if (ui.tab == NextTab.SETTINGS) NextTab.SEARCH_LIST else ui.tab
        )
    }

    val pinnedList = ui.pinnedTools[tab] ?: Session.pinnedToolList(tab)
    val flowingList = (ui.tools[tab] ?: Session.toolList(tab)).filter { it !in pinnedList }

    val currentPinnedState by rememberUpdatedState(pinnedList)
    val currentFlowingState by rememberUpdatedState(flowingList)
    val currentTabState by rememberUpdatedState(tab)

    var draggingPinnedTool by remember { mutableStateOf<ToolId?>(null) }
    var fingerYInPinned by remember { mutableFloatStateOf(0f) }

    var draggingFlowingTool by remember { mutableStateOf<ToolId?>(null) }
    var fingerYInFlowing by remember { mutableFloatStateOf(0f) }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { GgControlHeight.Row.toPx() }
    val rowSpacingPx = with(density) { 5.dp.toPx() }
    val rowStepPx = rowHeightPx + rowSpacingPx

    NextSheetCard(
        title = "工具栏顺序",
        subtitle = "长按按钮上下拖动排序，支持将常用工具固定到右侧常驻栏",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 720
    ) {
        Column {
            // Tab 切换（搜索 / 保存 / 内存 / 调试）
            ToolbarTabSwitcher(selected = tab) { t ->
                draggingPinnedTool = null
                draggingFlowingTool = null
                fingerYInPinned = 0f
                fingerYInFlowing = 0f
                tab = t
            }
            Spacer(Modifier.height(GgSpacing.MdPlus))

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState, enabled = draggingPinnedTool == null && draggingFlowingTool == null)
            ) {
                // ==================== 第一区：固定工具栏 (右侧常驻) ====================
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = GgSpacing.Xs, vertical = GgSpacing.Xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = Rose.Primary,
                        modifier = Modifier.size(GgIconSize.Sm)
                    )
                    Spacer(Modifier.width(GgSpacing.Sm))
                    Text(
                        tr("固定工具栏"),
                        color = Rose.Primary,
                        fontSize = fsp(13.5f),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${pinnedList.size} ${tr("个工具")}",
                        color = Rose.TextDim,
                        fontSize = fsp(11.5f)
                    )
                }
                Spacer(Modifier.height(GgSpacing.Xs))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(tab) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startOffset ->
                                    val curList = currentPinnedState
                                    if (curList.isEmpty()) return@detectDragGesturesAfterLongPress
                                    val idx = (startOffset.y / rowStepPx).toInt().coerceIn(0, curList.lastIndex)
                                    draggingPinnedTool = curList[idx]
                                    fingerYInPinned = startOffset.y
                                },
                                onDragEnd = {
                                    draggingPinnedTool = null
                                    fingerYInPinned = 0f
                                },
                                onDragCancel = {
                                    draggingPinnedTool = null
                                    fingerYInPinned = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val activeTool = draggingPinnedTool ?: return@detectDragGesturesAfterLongPress
                                    val curList = currentPinnedState
                                    if (curList.isEmpty()) return@detectDragGesturesAfterLongPress
                                    fingerYInPinned = (fingerYInPinned + dragAmount.y)
                                        .coerceIn(0f, (curList.size * rowStepPx).coerceAtLeast(rowHeightPx))
                                    val targetIndex = (fingerYInPinned / rowStepPx).toInt().coerceIn(0, curList.lastIndex)
                                    val curIndex = curList.indexOf(activeTool)
                                    if (curIndex != -1 && targetIndex != curIndex) {
                                        Session.movePinnedTool(currentTabState, curIndex, targetIndex)
                                    }
                                }
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    pinnedList.forEachIndexed { i, id ->
                        key("pinned_${id.name}") {
                            val isDragging = draggingPinnedTool == id
                            val scale by animateFloatAsState(
                                if (isDragging) 1.03f else 1f,
                                Motion.Fast,
                                label = "pinnedDragScale"
                            )
                            val bg by animateColorAsState(
                                if (isDragging) Rose.ChipSelected else Rose.Row,
                                Motion.ColorFast,
                                label = "pinnedDragBg"
                            )
                            val rowCenterY = i * rowStepPx + rowHeightPx * 0.5f
                            val translationY = if (isDragging) (fingerYInPinned - rowCenterY) else 0f

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (isDragging) 10f else 0f)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        this.translationY = translationY
                                    }
                                    .height(GgControlHeight.Row)
                                    .background(bg, GgShapes.Md)
                                    .then(
                                        if (isDragging) Modifier.border(GgBorder.Default, Rose.Primary, GgShapes.Md)
                                        else Modifier
                                    )
                                    .padding(horizontal = GgSpacing.Lg),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${i + 1}",
                                    style = monoStyle(12, if (isDragging) Rose.Text else Rose.TextDim),
                                    modifier = Modifier.width(22.dp)
                                )
                                Icon(
                                    toolIcon(id),
                                    null,
                                    tint = if (isDragging) Rose.Primary else Rose.Primary.copy(alpha = 0.85f),
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(Modifier.width(GgSpacing.MdPlus))
                                Text(
                                    tr(id.label),
                                    color = Rose.Text,
                                    fontSize = fsp(14f),
                                    modifier = Modifier.weight(1f)
                                )

                                // 取消固定按钮（移回流动工具栏）
                                if (pinnedList.size > 1) {
                                    GgBadge(
                                        text = "移出",
                                        textColor = Rose.TextSecond,
                                        backgroundColor = Rose.CardHigh,
                                        shape = GgShapes.Xs,
                                        fontWeight = FontWeight.Normal,
                                        horizontalPadding = GgSpacing.Md,
                                        verticalPadding = GgSpacing.Xs,
                                        onClick = { Session.unpinTool(tab, id) }
                                    )
                                    Spacer(Modifier.width(GgSpacing.Md))
                                }

                                Icon(
                                    imageVector = GgIcons.DragUpDown,
                                    contentDescription = "长按上下拖动排序",
                                    tint = if (isDragging) Rose.Primary else Rose.TextSecond,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ==================== 第二区：流动工具栏 (滑动栏) ====================
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = GgSpacing.Xs, vertical = GgSpacing.Xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        GgIcons.MenuThreeLines,
                        contentDescription = null,
                        tint = Rose.TextSecond,
                        modifier = Modifier.size(GgIconSize.Sm)
                    )
                    Spacer(Modifier.width(GgSpacing.Sm))
                    Text(
                        tr("流动工具栏"),
                        color = Rose.Text,
                        fontSize = fsp(13.5f),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${flowingList.size} ${tr("个工具")}",
                        color = Rose.TextDim,
                        fontSize = fsp(11.5f)
                    )
                }
                Spacer(Modifier.height(GgSpacing.Xs))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(tab) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startOffset ->
                                    val curList = currentFlowingState
                                    if (curList.isEmpty()) return@detectDragGesturesAfterLongPress
                                    val idx = (startOffset.y / rowStepPx).toInt().coerceIn(0, curList.lastIndex)
                                    draggingFlowingTool = curList[idx]
                                    fingerYInFlowing = startOffset.y
                                },
                                onDragEnd = {
                                    draggingFlowingTool = null
                                    fingerYInFlowing = 0f
                                },
                                onDragCancel = {
                                    draggingFlowingTool = null
                                    fingerYInFlowing = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val activeTool = draggingFlowingTool ?: return@detectDragGesturesAfterLongPress
                                    val curList = currentFlowingState
                                    if (curList.isEmpty()) return@detectDragGesturesAfterLongPress
                                    fingerYInFlowing = (fingerYInFlowing + dragAmount.y)
                                        .coerceIn(0f, (curList.size * rowStepPx).coerceAtLeast(rowHeightPx))
                                    val targetIndex = (fingerYInFlowing / rowStepPx).toInt().coerceIn(0, curList.lastIndex)
                                    val curIndex = curList.indexOf(activeTool)
                                    if (curIndex != -1 && targetIndex != curIndex) {
                                        val fullList = ui.tools[currentTabState] ?: Session.toolList(currentTabState)
                                        val fullFrom = fullList.indexOf(activeTool)
                                        val targetTool = curList[targetIndex]
                                        val fullTo = fullList.indexOf(targetTool)
                                        if (fullFrom != -1 && fullTo != -1) {
                                            Session.moveTool(currentTabState, fullFrom, fullTo)
                                        }
                                    }
                                }
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    flowingList.forEachIndexed { i, id ->
                        key("flowing_${id.name}") {
                            val isDragging = draggingFlowingTool == id
                            val scale by animateFloatAsState(
                                if (isDragging) 1.03f else 1f,
                                Motion.Fast,
                                label = "flowingDragScale"
                            )
                            val bg by animateColorAsState(
                                if (isDragging) Rose.ChipSelected else Rose.Row,
                                Motion.ColorFast,
                                label = "flowingDragBg"
                            )
                            val rowCenterY = i * rowStepPx + rowHeightPx * 0.5f
                            val translationY = if (isDragging) (fingerYInFlowing - rowCenterY) else 0f

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (isDragging) 10f else 0f)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        this.translationY = translationY
                                    }
                                    .height(GgControlHeight.Row)
                                    .background(bg, GgShapes.Md)
                                    .then(
                                        if (isDragging) Modifier.border(GgBorder.Default, Rose.Primary, GgShapes.Md)
                                        else Modifier
                                    )
                                    .padding(horizontal = GgSpacing.Lg),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${i + 1}",
                                    style = monoStyle(12, if (isDragging) Rose.Text else Rose.TextDim),
                                    modifier = Modifier.width(22.dp)
                                )
                                Icon(
                                    toolIcon(id),
                                    null,
                                    tint = if (isDragging) Rose.Primary else Rose.TextSecond,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(Modifier.width(GgSpacing.MdPlus))
                                Text(
                                    tr(id.label),
                                    color = Rose.Text,
                                    fontSize = fsp(14f),
                                    modifier = Modifier.weight(1f)
                                )

                                // 固定到右侧常驻栏按钮
                                GgBadge(
                                    text = "+固定",
                                    textColor = Rose.Primary,
                                    backgroundColor = Rose.CardHigh,
                                    shape = GgShapes.Xs,
                                    fontWeight = FontWeight.SemiBold,
                                    horizontalPadding = GgSpacing.Md,
                                    verticalPadding = GgSpacing.Xs,
                                    onClick = { Session.pinTool(tab, id) }
                                )
                                Spacer(Modifier.width(GgSpacing.Md))

                                Icon(
                                    imageVector = GgIcons.DragUpDown,
                                    contentDescription = "长按上下拖动排序",
                                    tint = if (isDragging) Rose.Primary else Rose.TextSecond,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(GgSpacing.MdPlus))
            Row(horizontalArrangement = Arrangement.spacedBy(GgSpacing.Md)) {
                RoseButton("增删工具", Modifier.weight(1f)) { Session.openSheet(Sheet.TOOLBAR_PICK) }
                RoseButton("重置", Modifier.weight(1f)) { Session.resetTools(tab) }
                RoseButton("完成", Modifier.weight(1f), filled = true) { Session.closeSheet() }
            }
        }
    }
}

@Composable
fun ToolbarPickSheet(ui: Ui) {
    var tab by remember {
        mutableStateOf(
            if (ui.tab == NextTab.SETTINGS) NextTab.SEARCH_LIST else ui.tab
        )
    }
    val current = ui.tools[tab] ?: Session.toolList(tab)
    NextSheetCard(
        title = "增删工具",
        subtitle = "点击勾选或移除该页面的工具栏图标",
        onDismiss = { Session.openSheet(Sheet.TOOLBAR_ORDER) },
        maxBodyHeight = 660
    ) {
        Column {
            ToolbarTabSwitcher(selected = tab) { tab = it }
            Spacer(Modifier.height(GgSpacing.MdPlus))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                ALL_TOOLS.forEach { id ->
                    val on = current.contains(id)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(GgControlHeight.Row)
                            .background(
                                if (on) Rose.ChipSelected else Rose.CardHigh,
                                GgShapes.Md
                            )
                            .clickableWithTooltip { Session.toggleTool(tab, id) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            toolIcon(id), null,
                            tint = if (on) Rose.Primary else Rose.TextDim,
                            modifier = Modifier.size(GgIconSize.Md)
                        )
                        Spacer(Modifier.width(GgSpacing.Lg))
                        Text(tr(id.label), color = Rose.Text, fontSize = fsp(14f), modifier = Modifier.weight(1f))
                        Icon(
                            if (on) Icons.Rounded.Remove else Icons.Rounded.Add,
                            null,
                            tint = Rose.TextSecond,
                            modifier = Modifier.size(GgIconSize.Md)
                        )
                    }
                    Spacer(Modifier.height(GgSpacing.Xs))
                }
            }
            Spacer(Modifier.height(GgSpacing.Md))
            RoseButton("返回排序", Modifier.fillMaxWidth(), filled = true) {
                Session.openSheet(Sheet.TOOLBAR_ORDER)
            }
        }
    }
}

@Composable
private fun ToolbarTabSwitcher(
    selected: NextTab,
    onSelect: (NextTab) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)) {
        TOOLBAR_TABS.forEach { t ->
            GgSegmentTab(
                label = t.label,
                selected = selected == t,
                modifier = Modifier.weight(1f),
                height = GgControlHeight.Button,
                shape = GgShapes.Md,
                accent = Rose.Text,
                showBorder = false,
                fontSize = 13.5f,
                horizontalPadding = GgSpacing.Xs
            ) { onSelect(t) }
        }
    }
}
