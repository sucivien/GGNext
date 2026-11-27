package com.littlecodesauce.ggnext.lua

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import kotlin.math.hypot
import kotlin.math.roundToInt
import com.littlecodesauce.ggnext.ui.components.GgBadge
import com.littlecodesauce.ggnext.ui.components.GgDivider
import com.littlecodesauce.ggnext.ui.components.GgInputBox
import com.littlecodesauce.ggnext.ui.components.GgModalOverlay
import com.littlecodesauce.ggnext.ui.components.GgSurfaceCard
import com.littlecodesauce.ggnext.ui.components.GgSwitch
import com.littlecodesauce.ggnext.ui.components.GgTextField
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.WavySlider
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.ggGlassSurface
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Rose
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

/**
 * GGNext Lua 交互弹窗与声明式插件界面宿主覆盖层：
 * - 统一挂载于悬浮窗面板顶层，复用 `GgModalOverlay` / `NextSheetCard` / `GgPrimitives` / `Rose` 主题；
 * - 依次支持：
 *   1. 原版 GG 弹窗：`gg.alert`、`gg.choice`、`gg.multiChoice`、`gg.prompt`、`gg.makeRequest` 联网确认
 *   2. 声明式 UI 协议：`ui.show(...)` / `ui.update(...)` 渲染的实时交互面板（后续可直接复用于独立插件页）
 */
@Composable
fun LuaOverlayHost(modifier: Modifier = Modifier) {
    val luaState by LuaScriptEngine.state.collectAsState()
    val dialog = luaState.activeDialog
    val uiPanel = luaState.activeUiPanel

    // 1. 声明式 UI 面板（优先级低于即时弹窗，弹窗可叠加在面板之上）
    GgModalOverlay(
        visible = uiPanel != null,
        onDismiss = {
            if (uiPanel?.dismissible != false) {
                LuaScriptEngine.closeUiPanel(fromUser = true)
            }
        },
        modifier = modifier,
        zIndex = 28f,
        dismissOnScrimClick = uiPanel?.dismissible != false
    ) {
        if (uiPanel != null) {
            LuaDeclarativePanel(panel = uiPanel)
        }
    }

    // 2. 原版 GG 同步交互弹窗
    GgModalOverlay(
        visible = dialog != null,
        onDismiss = {
            when (dialog) {
                is LuaDialogRequest.Alert -> LuaScriptEngine.submitDialogResult(dialog.requestId, 0)
                is LuaDialogRequest.Choice -> LuaScriptEngine.submitDialogResult(dialog.requestId, null)
                is LuaDialogRequest.MultiChoice -> LuaScriptEngine.submitDialogResult(dialog.requestId, null)
                is LuaDialogRequest.Prompt -> LuaScriptEngine.submitDialogResult(dialog.requestId, null)
                is LuaDialogRequest.NetworkConsent -> LuaScriptEngine.submitDialogResult(dialog.requestId, false)
                null -> Unit
            }
        },
        modifier = modifier,
        zIndex = 32f,
        dismissOnScrimClick = true
    ) {
        when (dialog) {
            is LuaDialogRequest.Alert -> LuaAlertDialog(dialog)
            is LuaDialogRequest.Choice -> LuaChoiceDialog(dialog)
            is LuaDialogRequest.MultiChoice -> LuaMultiChoiceDialog(dialog)
            is LuaDialogRequest.Prompt -> LuaPromptDialog(dialog)
            is LuaDialogRequest.NetworkConsent -> LuaNetworkConsentDialog(dialog)
            null -> Unit
        }
    }
}

/**
 * 悬浮在主面板上的脚本运行控制按钮（`Sx` 与纯图标「停止脚本」）：
 * - 两个按钮各自独立支持自由拖拽与屏幕边界约束；
 * - 严格基于 `viewConfiguration.touchSlop` 区分点击与拖拽，拖动绝不误触停止或打开脚本菜单；
 * - 精简为 32.dp 紧凑体积，停止按钮移除文字仅保留清晰停止图标与无障碍/长按提示。
 */
@Composable
fun LuaFloatingOverlayControls(modifier: Modifier = Modifier) {
    val luaState by LuaScriptEngine.state.collectAsState()
    if (!luaState.running && !luaState.uiButtonVisible) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val btnSizeDp = 32.dp
        val btnSizePx = with(density) { btnSizeDp.toPx() }
        val marginPx = with(density) { 8.dp.toPx() }
        val topInitPx = with(density) { 54.dp.toPx() }
        val gapPx = with(density) { 8.dp.toPx() }
        val maxW = constraints.maxWidth.toFloat().coerceAtLeast(btnSizePx + marginPx * 2f)
        val maxH = constraints.maxHeight.toFloat().coerceAtLeast(btnSizePx + marginPx * 2f)
        val minX = marginPx
        val maxX = (maxW - btnSizePx - marginPx).coerceAtLeast(minX)
        val minY = marginPx
        val maxY = (maxH - btnSizePx - marginPx).coerceAtLeast(minY)

        val defaultStopX = maxX
        val defaultStopY = topInitPx.coerceIn(minY, maxY)
        val defaultSxX = (defaultStopX - btnSizePx - gapPx).coerceIn(minX, maxX)
        val defaultSxY = defaultStopY

        if (luaState.uiButtonVisible) {
            val sxPosX = (defaultSxX + luaState.sxOffsetX).coerceIn(minX, maxX)
            val sxPosY = (defaultSxY + luaState.sxOffsetY).coerceIn(minY, maxY)
            val sxDesc = tr("脚本按钮 Sx")
            DraggableMiniControlButton(
                xPx = sxPosX,
                yPx = sxPosY,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                baseX = defaultSxX,
                baseY = defaultSxY,
                contentDesc = sxDesc,
                borderColor = Rose.Primary,
                baseColor = Rose.ChipSelected,
                onDragOffsetChanged = { ox, oy -> LuaScriptEngine.updateSxOffset(ox, oy) },
                onClick = { LuaScriptEngine.onUiButtonClicked() },
                onLongPress = { Session.toast(sxDesc) }
            ) {
                Text(
                    text = "Sx",
                    color = Rose.Primary,
                    fontSize = fsp(12f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        if (luaState.running) {
            val stopPosX = (defaultStopX + luaState.stopOffsetX).coerceIn(minX, maxX)
            val stopPosY = (defaultStopY + luaState.stopOffsetY).coerceIn(minY, maxY)
            val stopDesc = tr("停止脚本")
            DraggableMiniControlButton(
                xPx = stopPosX,
                yPx = stopPosY,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                baseX = defaultStopX,
                baseY = defaultStopY,
                contentDesc = stopDesc,
                borderColor = Rose.Red.copy(alpha = 0.65f),
                baseColor = Rose.CardHigh,
                onDragOffsetChanged = { ox, oy -> LuaScriptEngine.updateStopOffset(ox, oy) },
                onClick = { LuaScriptEngine.stopScript() },
                onLongPress = { Session.toast(stopDesc) }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = stopDesc,
                    tint = Rose.Red,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun DraggableMiniControlButton(
    xPx: Float,
    yPx: Float,
    minX: Float,
    maxX: Float,
    minY: Float,
    maxY: Float,
    baseX: Float,
    baseY: Float,
    contentDesc: String,
    borderColor: Color,
    baseColor: Color,
    onDragOffsetChanged: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    content: @Composable () -> Unit
) {
    var curX by remember { mutableFloatStateOf(xPx) }
    var curY by remember { mutableFloatStateOf(yPx) }
    // 同步外部边界变化，避免旋转屏幕后越界
    val clampedX = xPx.coerceIn(minX, maxX)
    val clampedY = yPx.coerceIn(minY, maxY)
    if (kotlin.math.abs(curX - clampedX) > 1f || kotlin.math.abs(curY - clampedY) > 1f) {
        curX = clampedX
        curY = clampedY
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(curX.roundToInt(), curY.roundToInt()) }
            .size(32.dp)
            .semantics { contentDescription = contentDesc }
            .ggGlassSurface(
                shape = GgShapes.Md,
                baseColor = baseColor,
                borderColor = borderColor,
                borderWidth = GgBorder.Default,
                respectBgOpacity = true,
                bgOpacityScale = 0.94f
            )
            .pointerInput(minX, maxX, minY, maxY, baseX, baseY) {
                val slop = viewConfiguration.touchSlop.coerceAtLeast(10f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    var totalDx = 0f
                    var totalDy = 0f
                    var dragging = false
                    var pointerId = down.id
                    var releasedNormally = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull()
                            ?: break
                        pointerId = change.id

                        if (!change.pressed) {
                            releasedNormally = true
                            change.consume()
                            break
                        }

                        val delta = change.positionChange()
                        if (delta.x != 0f || delta.y != 0f) {
                            totalDx += delta.x
                            totalDy += delta.y
                            if (!dragging && hypot(totalDx.toDouble(), totalDy.toDouble()) > slop.toDouble()) {
                                dragging = true
                            }
                            if (dragging) {
                                change.consume()
                                val nextX = (curX + delta.x).coerceIn(minX, maxX)
                                val nextY = (curY + delta.y).coerceIn(minY, maxY)
                                curX = nextX
                                curY = nextY
                                onDragOffsetChanged(nextX - baseX, nextY - baseY)
                            }
                        }
                    }

                    if (!dragging && releasedNormally) {
                        val heldMs = System.currentTimeMillis() - downTime
                        if (heldMs >= 460L) {
                            onLongPress()
                        } else {
                            onClick()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// ============================================================================
// 1. 原版 GG 弹窗实现
// ============================================================================

@Composable
private fun LuaAlertDialog(req: LuaDialogRequest.Alert) {
    NextSheetCard(
        title = "脚本提示",
        onDismiss = { LuaScriptEngine.submitDialogResult(req.requestId, 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GgSpacing.Md)
        ) {
            Text(
                text = req.text,
                color = Rose.Text,
                fontSize = fsp(13.5f),
                lineHeight = fsp(20f)
            )
            Spacer(Modifier.height(GgSpacing.Xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
            ) {
                if (!req.neutral.isNullOrEmpty()) {
                    RoseButton(
                        label = req.neutral,
                        modifier = Modifier.weight(1f)
                    ) { LuaScriptEngine.submitDialogResult(req.requestId, 3) }
                }
                if (!req.negative.isNullOrEmpty()) {
                    RoseButton(
                        label = req.negative,
                        modifier = Modifier.weight(1f)
                    ) { LuaScriptEngine.submitDialogResult(req.requestId, 2) }
                }
                RoseButton(
                    label = req.positive.ifBlank { "确定" },
                    filled = true,
                    modifier = Modifier.weight(1f)
                ) { LuaScriptEngine.submitDialogResult(req.requestId, 1) }
            }
        }
    }
}

@Composable
private fun LuaChoiceDialog(req: LuaDialogRequest.Choice) {
    NextSheetCard(
        title = req.message?.takeIf { it.isNotBlank() } ?: "请选择",
        onDismiss = { LuaScriptEngine.submitDialogResult(req.requestId, null) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GgSpacing.Xs)
        ) {
            req.items.forEachIndexed { idx, label ->
                val isSelected = idx == req.selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .ggGlassSurface(
                            shape = GgShapes.Md,
                            baseColor = if (isSelected) Rose.ChipSelected else Rose.CardHigh,
                            borderColor = if (isSelected) Rose.Primary else Rose.Divider,
                            borderWidth = if (isSelected) GgBorder.Emphasis else GgBorder.Thin
                        )
                        .clickable { LuaScriptEngine.submitDialogResult(req.requestId, idx) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Rose.Primary else Rose.Text,
                        fontSize = fsp(13.5f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Rose.Primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(GgSpacing.Md))
        RoseButton(
            label = "取消",
            modifier = Modifier.fillMaxWidth()
        ) { LuaScriptEngine.submitDialogResult(req.requestId, null) }
    }
}

@Composable
private fun LuaMultiChoiceDialog(req: LuaDialogRequest.MultiChoice) {
    val selected = remember(req.requestId) {
        mutableStateListOf<Boolean>().apply {
            req.items.indices.forEach { add(it in req.initialSelection) }
        }
    }
    NextSheetCard(
        title = req.message?.takeIf { it.isNotBlank() } ?: "多项选择",
        onDismiss = { LuaScriptEngine.submitDialogResult(req.requestId, null) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GgSpacing.Xs)
        ) {
            req.items.forEachIndexed { idx, label ->
                val checked = selected.getOrElse(idx) { false }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .ggGlassSurface(
                            shape = GgShapes.Md,
                            baseColor = if (checked) Rose.ChipSelected else Rose.CardHigh,
                            borderColor = if (checked) Rose.Primary else Rose.Divider,
                            borderWidth = GgBorder.Thin
                        )
                        .clickable { selected[idx] = !checked }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (checked) Rose.Primary else Rose.TextSecond,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = label,
                        color = if (checked) Rose.Text else Rose.TextSecond,
                        fontSize = fsp(13.5f),
                        fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(Modifier.height(GgSpacing.Md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
        ) {
            RoseButton(
                label = "取消",
                modifier = Modifier.weight(1f)
            ) { LuaScriptEngine.submitDialogResult(req.requestId, null) }
            RoseButton(
                label = "确定",
                filled = true,
                modifier = Modifier.weight(1f)
            ) {
                val chosen = selected.mapIndexedNotNull { i, b -> if (b) i else null }.toSet()
                LuaScriptEngine.submitDialogResult(req.requestId, chosen)
            }
        }
    }
}

@Composable
private fun LuaPromptDialog(req: LuaDialogRequest.Prompt) {
    val textStates = remember(req.requestId) {
        mutableStateListOf<TextFieldValue>().apply {
            req.fields.forEach { add(TextFieldValue(it.defaultValue)) }
        }
    }
    val boolStates = remember(req.requestId) {
        mutableStateListOf<Boolean>().apply {
            req.fields.forEach { add(it.defaultValue.equals("true", ignoreCase = true) || it.defaultValue == "1") }
        }
    }
    val sliderStates = remember(req.requestId) {
        mutableStateListOf<Float>().apply {
            req.fields.forEach { f ->
                val v = f.defaultValue.toFloatOrNull() ?: f.seekMin.toFloat()
                add(v.coerceIn(f.seekMin.toFloat(), f.seekMax.toFloat()))
            }
        }
    }

    NextSheetCard(
        title = "脚本参数输入",
        onDismiss = { LuaScriptEngine.submitDialogResult(req.requestId, null) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GgSpacing.Md)
        ) {
            req.fields.forEachIndexed { idx, field ->
                when (field.type) {
                    "checkbox" -> {
                        val checked = boolStates[idx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .ggGlassSurface(
                                    shape = GgShapes.Md,
                                    baseColor = if (checked) Rose.ChipSelected else Rose.CardHigh,
                                    borderColor = if (checked) Rose.Primary.copy(alpha = 0.5f) else Rose.Divider,
                                    borderWidth = GgBorder.Thin
                                )
                                .clickable { boolStates[idx] = !checked }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = field.label,
                                color = Rose.Text,
                                fontSize = fsp(13.5f),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(GgSpacing.Sm))
                            GgSwitch(checked = checked, onCheckedChange = { boolStates[idx] = it })
                        }
                    }
                    "seekbar" -> {
                        val v = sliderStates[idx]
                        val minF = field.seekMin.toFloat()
                        val maxF = field.seekMax.toFloat().coerceAtLeast(minF + 1f)
                        val display = if (field.seekMin % 1.0 == 0.0 && field.seekMax % 1.0 == 0.0) {
                            v.roundToLong().toString()
                        } else {
                            String.format(Locale.US, "%.2f", v)
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = field.label,
                                    color = Rose.Text,
                                    fontSize = fsp(13f),
                                    modifier = Modifier.weight(1f)
                                )
                                GgBadge(text = display, translate = false, monospace = true)
                            }
                            Spacer(Modifier.height(4.dp))
                            WavySlider(
                                value = v,
                                onValueChange = { sliderStates[idx] = it },
                                valueRange = minF..maxF,
                                activeColor = Rose.Primary,
                                inactiveColor = Rose.CardHigh
                            )
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = field.label,
                                    color = Rose.TextSecond,
                                    fontSize = fsp(12.5f),
                                    modifier = Modifier.weight(1f)
                                )
                                if (field.type != "text") {
                                    GgBadge(text = field.type, translate = false, fontSize = 10.5f)
                                }
                            }
                            GgInputBox(height = GgControlHeight.Input) {
                                GgTextField(
                                    value = textStates[idx],
                                    onValueChange = { textStates[idx] = it },
                                    placeholder = field.defaultValue,
                                    suppressSystemKeyboard = false,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(GgSpacing.Md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
        ) {
            RoseButton(
                label = "取消",
                modifier = Modifier.weight(1f)
            ) { LuaScriptEngine.submitDialogResult(req.requestId, null) }
            RoseButton(
                label = "确定",
                filled = true,
                modifier = Modifier.weight(1f)
            ) {
                val out = req.fields.mapIndexed { idx, field ->
                    when (field.type) {
                        "checkbox" -> boolStates[idx]
                        "seekbar" -> {
                            val v = sliderStates[idx]
                            if (field.seekMin % 1.0 == 0.0 && field.seekMax % 1.0 == 0.0) {
                                v.roundToLong().toString()
                            } else {
                                String.format(Locale.US, "%.2f", v)
                            }
                        }
                        else -> textStates[idx].text
                    }
                }
                LuaScriptEngine.submitDialogResult(req.requestId, out)
            }
        }
    }
}

@Composable
private fun LuaNetworkConsentDialog(req: LuaDialogRequest.NetworkConsent) {
    NextSheetCard(
        title = "脚本网络访问确认",
        onDismiss = { LuaScriptEngine.submitDialogResult(req.requestId, false) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.Sm)) {
            Text(
                text = tr("当前脚本请求访问以下网络地址，是否允许？"),
                color = Rose.Text,
                fontSize = fsp(13.5f)
            )
            GgSurfaceCard(contentPadding = PaddingValues(10.dp), backgroundColor = Rose.CardHigh) {
                Text(
                    text = req.url,
                    style = monoStyle(12, Rose.Cyan),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(GgSpacing.Xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
            ) {
                RoseButton(
                    label = "拒绝",
                    modifier = Modifier.weight(1f)
                ) { LuaScriptEngine.submitDialogResult(req.requestId, false) }
                RoseButton(
                    label = "允许",
                    filled = true,
                    modifier = Modifier.weight(1f)
                ) { LuaScriptEngine.submitDialogResult(req.requestId, true) }
            }
        }
    }
}

// ============================================================================
// 2. 声明式 Lua -> Compose UI 协议渲染器 (`ui.*`)
//    直接映射至 GGNext GgPrimitives + Rose 主题，零硬编码颜色，供脚本面板与后续插件页复用
// ============================================================================

@Composable
fun LuaDeclarativePanel(panel: LuaUiPanelState) {
    NextSheetCard(
        title = panel.title,
        subtitle = panel.subtitle.takeIf { it.isNotBlank() },
        onDismiss = {
            if (panel.dismissible) {
                LuaScriptEngine.closeUiPanel(fromUser = true)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
        ) {
            LuaUiNodeView(node = panel.root)
        }
    }
}

@Composable
fun LuaUiNodeView(node: LuaUiNode, modifier: Modifier = Modifier) {
    when (node.type) {
        "column" -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(node.spacing.dp)
            ) {
                node.children.forEach { child -> LuaUiNodeView(child) }
            }
        }
        "row" -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(node.spacing.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                node.children.forEach { child ->
                    Box(modifier = Modifier.weight(1f, fill = child.type == "button" || child.type == "input")) {
                        LuaUiNodeView(child)
                    }
                }
            }
        }
        "card" -> {
            GgSurfaceCard(
                modifier = modifier,
                contentPadding = PaddingValues(12.dp),
                backgroundColor = Rose.CardHigh
            ) {
                if (!node.label.isNullOrBlank()) {
                    Text(
                        text = node.label,
                        color = Rose.Primary,
                        fontSize = fsp(12.5f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(node.spacing.dp)) {
                    node.children.forEach { child -> LuaUiNodeView(child) }
                }
            }
        }
        "scroll" -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(node.spacing.dp)
            ) {
                node.children.forEach { child -> LuaUiNodeView(child) }
            }
        }
        "text" -> {
            val col = toneColor(node.tone)
            if (node.monospace) {
                Text(
                    text = node.text.orEmpty(),
                    style = monoStyle(12, col),
                    modifier = modifier
                )
            } else {
                Text(
                    text = node.text.orEmpty(),
                    color = col,
                    fontSize = fsp(13.5f),
                    lineHeight = fsp(19f),
                    modifier = modifier
                )
            }
        }
        "badge" -> {
            val col = toneColor(if (node.tone == "default") "primary" else node.tone)
            GgBadge(
                text = node.text ?: node.label.orEmpty(),
                textColor = col,
                monospace = node.monospace,
                translate = false,
                modifier = modifier
            )
        }
        "code" -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .ggGlassSurface(
                        shape = GgShapes.Md,
                        baseColor = Rose.Surface,
                        borderColor = Rose.Divider,
                        borderWidth = GgBorder.Thin
                    )
                    .padding(10.dp)
            ) {
                Text(
                    text = node.text.orEmpty(),
                    style = monoStyle(11, Rose.Cyan)
                )
            }
        }
        "button" -> {
            RoseButton(
                label = node.label ?: node.text ?: "确定",
                filled = node.filled,
                enabled = node.enabled,
                modifier = modifier.fillMaxWidth()
            ) {
                node.onClickId?.let { LuaScriptEngine.dispatchUiCallback(it, true) }
            }
        }
        "input" -> {
            var tfv by remember(node.id, node.value) {
                mutableStateOf(TextFieldValue(node.value.orEmpty()))
            }
            Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!node.label.isNullOrBlank()) {
                    Text(text = node.label, color = Rose.TextSecond, fontSize = fsp(12f))
                }
                GgInputBox(height = GgControlHeight.Input) {
                    GgTextField(
                        value = tfv,
                        onValueChange = { next ->
                            tfv = next
                            node.onChangeId?.let { LuaScriptEngine.dispatchUiCallback(it, next.text) }
                        },
                        placeholder = node.placeholder.orEmpty(),
                        suppressSystemKeyboard = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        "switch" -> {
            var checked by remember(node.id, node.checked) { mutableStateOf(node.checked) }
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .ggGlassSurface(
                        shape = GgShapes.Md,
                        baseColor = Rose.CardHigh,
                        borderColor = if (checked) Rose.Primary.copy(alpha = 0.45f) else Rose.Divider,
                        borderWidth = GgBorder.Thin
                    )
                    .clickable(enabled = node.enabled) {
                        checked = !checked
                        node.onChangeId?.let { LuaScriptEngine.dispatchUiCallback(it, checked) }
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = node.label ?: node.text.orEmpty(), color = Rose.Text, fontSize = fsp(13.5f))
                    if (!node.subtitle.isNullOrBlank()) {
                        Text(text = node.subtitle, color = Rose.TextSecond, fontSize = fsp(11.5f))
                    }
                }
                Spacer(Modifier.width(GgSpacing.Sm))
                GgSwitch(
                    checked = checked,
                    enabled = node.enabled,
                    onCheckedChange = { next ->
                        checked = next
                        node.onChangeId?.let { LuaScriptEngine.dispatchUiCallback(it, next) }
                    }
                )
            }
        }
        "checkbox" -> {
            var checked by remember(node.id, node.checked) { mutableStateOf(node.checked) }
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .clickable(enabled = node.enabled) {
                        checked = !checked
                        node.onChangeId?.let { LuaScriptEngine.dispatchUiCallback(it, checked) }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (checked) Rose.Primary else Rose.TextSecond,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = node.label ?: node.text.orEmpty(),
                    color = Rose.Text,
                    fontSize = fsp(13.5f)
                )
            }
        }
        "slider" -> {
            val minF = node.min
            val maxF = node.max.coerceAtLeast(minF + 1f)
            var current by remember(node.id, node.sliderValue) {
                mutableFloatStateOf(node.sliderValue.coerceIn(minF, maxF))
            }
            Column(modifier = modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.label ?: node.text.orEmpty(),
                        color = Rose.Text,
                        fontSize = fsp(13f),
                        modifier = Modifier.weight(1f)
                    )
                    GgBadge(
                        text = String.format(Locale.US, "%.2f", current).trimEnd('0').trimEnd('.'),
                        translate = false,
                        monospace = true
                    )
                }
                WavySlider(
                    value = current,
                    onValueChange = { next ->
                        current = next
                        node.onChangeId?.let { LuaScriptEngine.dispatchUiCallback(it, next.toDouble()) }
                    },
                    valueRange = minF..maxF,
                    activeColor = Rose.Primary,
                    inactiveColor = Rose.CardHigh
                )
            }
        }
        "list" -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                node.items.forEachIndexed { idx, label ->
                    val sel = (idx + 1) == node.selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .ggGlassSurface(
                                shape = GgShapes.Sm,
                                baseColor = if (sel) Rose.ChipSelected else Rose.CardHigh,
                                borderColor = if (sel) Rose.Primary else Rose.Divider,
                                borderWidth = GgBorder.Thin
                            )
                            .clickable {
                                node.onSelectId?.let { LuaScriptEngine.dispatchUiCallback(it, idx + 1) }
                                    ?: node.onClickId?.let { LuaScriptEngine.dispatchUiCallback(it, idx + 1) }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            color = if (sel) Rose.Primary else Rose.Text,
                            fontSize = fsp(13f),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        "icon" -> {
            Icon(
                imageVector = resolveIcon(node.iconName),
                contentDescription = node.label,
                tint = toneColor(if (node.tone == "default") "primary" else node.tone),
                modifier = modifier.size(node.size.coerceIn(16, 48).dp)
            )
        }
        "image" -> {
            val bmp = remember(node.imagePath) {
                val p = node.imagePath ?: return@remember null
                val f = File(p)
                if (!f.exists() || !f.isFile) null
                else runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = node.label,
                    contentScale = ContentScale.Fit,
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(GgShapes.Md)
                )
            }
        }
        "divider" -> GgDivider(startInset = 0.dp, endInset = 0.dp, modifier = modifier)
        "spacer" -> Spacer(modifier = modifier.height(node.size.dp).width(node.size.dp))
        else -> {
            if (!node.text.isNullOrEmpty()) {
                Text(text = node.text, color = Rose.Text, fontSize = fsp(13f), modifier = modifier)
            }
        }
    }
}

@Composable
private fun toneColor(tone: String): Color = when (tone) {
    "primary" -> Rose.Primary
    "success" -> Rose.Green
    "warning" -> Rose.Orange
    "danger", "error" -> Rose.Red
    "dim", "secondary" -> Rose.TextSecond
    else -> Rose.Text
}

private fun resolveIcon(name: String?): ImageVector = when (name?.lowercase()) {
    "play" -> Icons.Rounded.PlayArrow
    "stop" -> Icons.Rounded.Stop
    "search" -> Icons.Rounded.Search
    "memory" -> Icons.Rounded.Memory
    "code" -> Icons.Rounded.Code
    "terminal" -> Icons.Rounded.Terminal
    "plugin", "extension" -> Icons.Rounded.Extension
    "bug", "debug" -> Icons.Rounded.BugReport
    "settings" -> Icons.Rounded.Settings
    "folder" -> Icons.Rounded.Folder
    "speed" -> Icons.Rounded.Speed
    "build", "tool" -> Icons.Rounded.Build
    "add" -> Icons.Rounded.Add
    "edit" -> Icons.Rounded.Edit
    "delete" -> Icons.Rounded.Delete
    "refresh" -> Icons.Rounded.Refresh
    "warning" -> Icons.Rounded.Warning
    "close" -> Icons.Rounded.Close
    else -> Icons.Rounded.Info
}
