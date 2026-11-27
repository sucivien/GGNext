package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * 响应式多语言门面：
 * 统一委托给纯 Kotlin 字典 AppStrings，同时维护 Compose Snapshot State 保证整树实时热切换。
 */
object I18n {
    var currentLang by mutableStateOf(AppStrings.currentLang)

    init {
        AppStrings.onLangChanged = { lang ->
            if (currentLang != lang) currentLang = lang
        }
    }

    fun tr(text: String): String = AppStrings.tr(text, currentLang)
}

fun tr(text: String): String = I18n.tr(text)

/** 无涟漪点击：无背景闪烁，配合按下缩放动画 */
fun Modifier.clickableNoRipple(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = this.clickable(
    enabled = enabled,
    interactionSource = source,
    indication = null,
    onClick = onClick
)

/** 等宽文字样式（去除多余字体上下内边距；若用户导入了自定义 TTF 字体则同步应用） */
@Composable
fun monoStyle(size: Int, color: Color = Rose.Text, weight: FontWeight = FontWeight.Normal): TextStyle {
    val scaled = (size * LocalUiMetrics.current.fontScale).sp
    val customFamily = com.littlecodesauce.ggnext.ui.theme.LocalAppFontFamily.current
    val effectiveFamily = if (customFamily != FontFamily.Default) customFamily else FontFamily.Monospace
    return TextStyle(
        fontFamily = effectiveFamily,
        fontSize = scaled,
        lineHeight = scaled,
        color = color,
        fontWeight = weight,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
}

/**
 * 改进3：细腻动画的通用复选框组件 (GgCheckBox)
 * - 具备物理弹簧缩放回弹（按下微缩、勾选瞬间轻盈弹起）
 * - 平滑的背景填充与边框色彩过渡
 * - 渐进式对勾路径绘制动画（0f -> 1f 分两段顺滑勾出）
 * - 支持独立点击事件且不阻塞滚动
 */
@Composable
fun GgCheckBox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    accentColor: Color = Rose.Primary,
    tooltip: String = ""
) {
    var pressed by remember { mutableStateOf(false) }
    val checkProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "chkProgress"
    )
    val bounceScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.84f
            checked -> 1.0f
            else -> 0.96f
        },
        animationSpec = spring(
            dampingRatio = 0.56f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "chkScale"
    )
    val fillColor by animateColorAsState(
        targetValue = if (checked) accentColor else Color.Transparent,
        animationSpec = tween(170),
        label = "chkFill"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) accentColor else Rose.TextDim,
        animationSpec = tween(170),
        label = "chkBorder"
    )
    val checkMarkColor = Rose.CheckMark

    val clickMod = if (onCheckedChange != null) {
        Modifier.clickableWithTooltip(
            tooltip = tooltip,
            onPressChange = { pressed = it },
            onClick = { onCheckedChange(!checked) }
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(bounceScale)
            .then(clickMod),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = 1.8.dp.toPx()
            val cornerPx = 5.5.dp.toPx()
            val inset = strokePx / 2f
            val boxSize = Size(this.size.width - strokePx, this.size.height - strokePx)

            if (fillColor.alpha > 0.01f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(inset, inset),
                    size = boxSize,
                    cornerRadius = CornerRadius(cornerPx, cornerPx)
                )
            }
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(inset, inset),
                size = boxSize,
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(width = strokePx)
            )

            if (checkProgress > 0.01f) {
                val w = this.size.width
                val h = this.size.height
                val p1 = Offset(w * 0.24f, h * 0.52f)
                val p2 = Offset(w * 0.43f, h * 0.71f)
                val p3 = Offset(w * 0.77f, h * 0.31f)

                val path = Path()
                path.moveTo(p1.x, p1.y)
                if (checkProgress <= 0.42f) {
                    val t = (checkProgress / 0.42f).coerceIn(0f, 1f)
                    path.lineTo(
                        p1.x + (p2.x - p1.x) * t,
                        p1.y + (p2.y - p1.y) * t
                    )
                } else {
                    path.lineTo(p2.x, p2.y)
                    val t = ((checkProgress - 0.42f) / 0.58f).coerceIn(0f, 1f)
                    path.lineTo(
                        p2.x + (p3.x - p2.x) * t,
                        p2.y + (p3.y - p2.y) * t
                    )
                }
                drawPath(
                    path = path,
                    color = checkMarkColor,
                    style = Stroke(
                        width = 2.1.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

/**
 * 专用无外置键盘干扰、光标严格居中对齐的输入框组件：
 * - 3.2：获得焦点或初次打开包含非空文本的输入界面时，自动全选输入框内的内容；
 * - 3.6：长按输入框弹出「全选 / 复制 / 粘贴 / 剪切 / 清空」快捷菜单。
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

/**
 * 安全获取光标矩形：在文本刚变更但 BasicTextField 尚未回调新 TextLayoutResult 的过渡帧内，
 * 按 layout 自身文本长度再次裁剪偏移量，彻底避免 IllegalArgumentException: offset out of bounds。
 */
private fun safeCursorRect(
    layout: androidx.compose.ui.text.TextLayoutResult,
    offset: Int
): androidx.compose.ui.geometry.Rect {
    val maxIdx = layout.layoutInput.text.length.coerceAtLeast(0)
    return layout.getCursorRect(offset.coerceIn(0, maxIdx))
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GgTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle = monoStyle(15, Rose.Text),
    cursorColor: Color = Rose.Primary,
    suppressSystemKeyboard: Boolean = true,
    autoSelectOnFocus: Boolean = false,
    initialFocus: Boolean = false,
    onFocus: (() -> Unit)? = null
) {
    val focusRequester = remember { FocusRequester() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    var wasFocused by remember { mutableStateOf(false) }
    var toolbarForceVisible by remember { mutableStateOf(false) }
    var toolbarDismissed by remember { mutableStateOf(false) }
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // 指针光标状态：在同一光标位置连续触发两次时显示可拖动指针光标，再次点击同位置/点击水滴/输入文本/失焦时恢复普通光标
    var pointerCursorActive by remember { mutableStateOf(false) }
    var isDraggingHandle by remember { mutableStateOf(false) }
    var isDraggingSelectionHandle by remember { mutableStateOf(false) }
    var lastTapCursorIndex by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val currentVal by androidx.compose.runtime.rememberUpdatedState(value)
    val currentOnValueChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val currentSuppress by androidx.compose.runtime.rememberUpdatedState(suppressSystemKeyboard)
    val currentPointerActive by androidx.compose.runtime.rememberUpdatedState(pointerCursorActive)
    val currentLayout by androidx.compose.runtime.rememberUpdatedState(textLayoutResult)

    var wakeNonce by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    // 文本内容变化时自动恢复为普通光标，并重置工具栏隐藏状态
    androidx.compose.runtime.LaunchedEffect(value.text) {
        if (pointerCursorActive && !isDraggingHandle) {
            pointerCursorActive = false
        }
        lastTapCursorIndex = -1
        if (value.text.isEmpty()) {
            toolbarForceVisible = false
            toolbarDismissed = false
        }
    }

    // 选区变化时自动刷新工具栏可见性（修复：移动光标或调整选区后快捷工具栏不再出现的问题）
    androidx.compose.runtime.LaunchedEffect(value.selection) {
        if (!value.selection.collapsed) {
            pointerCursorActive = false
            toolbarDismissed = false
        } else {
            toolbarDismissed = false
            if (!isDraggingHandle) {
                toolbarForceVisible = false
            }
        }
    }

    fun wakeExternalKeyboardIfAllowed() {
        if (!currentSuppress) {
            wakeNonce++
            view.requestFocus()
            keyboardController?.show()
            val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    if (initialFocus) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
            wakeExternalKeyboardIfAllowed()
        }
    }

    androidx.compose.runtime.LaunchedEffect(suppressSystemKeyboard, wasFocused, wakeNonce) {
        if (wasFocused) {
            if (suppressSystemKeyboard) {
                keyboardController?.hide()
            } else {
                keyboardController?.show()
                val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        InterceptPlatformTextInput(
            interceptor = { request, nextHandler ->
                androidx.compose.runtime.snapshotFlow { currentSuppress to wakeNonce }
                    .collectLatest { (suppress, _) ->
                        if (suppress) {
                            keyboardController?.hide()
                            awaitCancellation()
                        } else {
                            keyboardController?.show()
                            nextHandler.startInputMethod(request)
                        }
                    }
                awaitCancellation()
            }
        ) {
            val hiddenHandleSelectionColors = remember(cursorColor) {
                TextSelectionColors(
                    handleColor = Color.Transparent,
                    backgroundColor = cursorColor.copy(alpha = 0.28f)
                )
            }
            CompositionLocalProvider(LocalTextSelectionColors provides hiddenHandleSelectionColors) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(cursorColor),
                onTextLayout = { textLayoutResult = it },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && !wasFocused) {
                            wasFocused = true
                            toolbarDismissed = false
                            onFocus?.invoke()
                            wakeExternalKeyboardIfAllowed()
                        } else if (!focusState.isFocused) {
                            wasFocused = false
                            pointerCursorActive = false
                            isDraggingHandle = false
                            isDraggingSelectionHandle = false
                            toolbarForceVisible = false
                            toolbarDismissed = false
                            lastTapCursorIndex = -1
                        }
                    },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { containerSize = it },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = tr(placeholder),
                                style = textStyle.copy(color = Rose.TextDim),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            innerTextField()
                        }
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = {
                                            focusRequester.requestFocus()
                                            onFocus?.invoke()
                                            wakeExternalKeyboardIfAllowed()
                                            pointerCursorActive = false
                                            toolbarDismissed = false
                                            toolbarForceVisible = true
                                        },
                                        onTap = { tapOffset ->
                                            focusRequester.requestFocus()
                                            onFocus?.invoke()
                                            wakeExternalKeyboardIfAllowed()
                                            toolbarForceVisible = false
                                            val text = currentVal.text
                                            if (text.isEmpty()) {
                                                pointerCursorActive = false
                                                lastTapCursorIndex = -1
                                                currentOnValueChange(
                                                    currentVal.copy(selection = androidx.compose.ui.text.TextRange.Zero)
                                                )
                                                return@detectTapGestures
                                            }
                                            val layout = currentLayout
                                            val targetOffset = if (layout != null) {
                                                val centerY = layout.size.height / 2f
                                                layout.getOffsetForPosition(Offset(tapOffset.x, centerY))
                                                    .coerceIn(0, text.length)
                                            } else {
                                                text.length
                                            }
                                            val wasCollapsed = currentVal.selection.collapsed
                                            val prevCursor = currentVal.selection.end.coerceIn(0, text.length)
                                            val sameByOffset = wasCollapsed && targetOffset == prevCursor
                                            val sameByProximity = if (wasCollapsed && layout != null) {
                                                val rect = safeCursorRect(layout, prevCursor)
                                                val caretCenterX = (rect.left + rect.right) / 2f
                                                kotlin.math.abs(tapOffset.x - caretCenterX) <= 14.dp.toPx()
                                            } else false
                                            val isSamePositionTap = wasFocused && (sameByOffset || sameByProximity) &&
                                                (lastTapCursorIndex == prevCursor || currentPointerActive)

                                            if (currentPointerActive) {
                                                // 指针光标已出现：再次点击即恢复为普通光标（若点在其他位置同时将光标移至新位置）
                                                pointerCursorActive = false
                                                lastTapCursorIndex = if (isSamePositionTap) -1 else targetOffset
                                                if (!isSamePositionTap) {
                                                    currentOnValueChange(
                                                        currentVal.copy(
                                                            selection = androidx.compose.ui.text.TextRange(targetOffset)
                                                        )
                                                    )
                                                }
                                            } else if (isSamePositionTap) {
                                                // 光标在同一位置连续被触发两次 -> 激活可拖动指针光标
                                                pointerCursorActive = true
                                                lastTapCursorIndex = prevCursor
                                            } else {
                                                lastTapCursorIndex = targetOffset
                                                currentOnValueChange(
                                                    currentVal.copy(
                                                        selection = androidx.compose.ui.text.TextRange(targetOffset)
                                                    )
                                                )
                                            }
                                        }
                                    )
                                }
                        )

                        val layout = textLayoutResult
                        val boxW = containerSize.width.toFloat().coerceAtLeast(1f)
                        val boxH = containerSize.height.toFloat().coerceAtLeast(1f)

                        // 1. 单光标可拖动水滴指针（通过 Popup 渲染，彻底避免被输入框高度裁切并精准正对光标正下方）
                        val showPointerHandle = pointerCursorActive &&
                            wasFocused &&
                            value.text.isNotEmpty() &&
                            value.selection.collapsed
                        if (showPointerHandle && layout != null) {
                            val cursorIdx = value.selection.end.coerceIn(0, value.text.length)
                            val cursorRect = safeCursorRect(layout, cursorIdx)
                            val caretCenterX = ((cursorRect.left + cursorRect.right) / 2f).coerceIn(0f, boxW)
                            val textTopOffset = ((boxH - layout.size.height) / 2f).coerceAtLeast(0f)
                            val caretBottomY = textTopOffset + cursorRect.bottom

                            val touchBoxSize = 36.dp
                            val touchBoxPx = with(density) { touchBoxSize.toPx() }
                            val handleOffsetX = (caretCenterX - touchBoxPx / 2f).roundToInt()
                            val handleOffsetY = (caretBottomY + with(density) { 1.5.dp.toPx() }).roundToInt()
                            val latestHandleOffsetX by androidx.compose.runtime.rememberUpdatedState(handleOffsetX)

                            val handleScale by animateFloatAsState(
                                targetValue = if (isDraggingHandle) 1.18f else 1f,
                                animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
                                label = "ptrHandleScale"
                            )

                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(handleOffsetX, handleOffsetY),
                                properties = PopupProperties(focusable = false, clippingEnabled = false)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(touchBoxSize)
                                        .scale(handleScale)
                                        .pointerInput(Unit) {
                                            val slop = viewConfiguration.touchSlop
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                down.consume()
                                                val activeLayout = currentLayout ?: return@awaitEachGesture
                                                val startIdx = currentVal.selection.end.coerceIn(0, currentVal.text.length)
                                                val startRect = safeCursorRect(activeLayout, startIdx)
                                                val startCaretX = (startRect.left + startRect.right) / 2f
                                                val downAnchorX = latestHandleOffsetX + down.position.x
                                                var moved = false
                                                isDraggingHandle = true
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.firstOrNull() ?: break
                                                    if (!ch.pressed) break
                                                    val curAnchorX = latestHandleOffsetX + ch.position.x
                                                    val totalDx = curAnchorX - downAnchorX
                                                    if (!moved && kotlin.math.abs(totalDx) > slop * 0.45f) {
                                                        moved = true
                                                    }
                                                    if (moved) {
                                                        val curLayout = currentLayout ?: activeLayout
                                                        val curText = currentVal.text
                                                        val targetX = (startCaretX + totalDx)
                                                            .coerceIn(0f, curLayout.size.width.toFloat().coerceAtLeast(1f))
                                                        val nextOffset = curLayout.getOffsetForPosition(
                                                            Offset(targetX, curLayout.size.height / 2f)
                                                        ).coerceIn(0, curText.length)
                                                        if (nextOffset != currentVal.selection.end) {
                                                            currentOnValueChange(
                                                                currentVal.copy(
                                                                    selection = androidx.compose.ui.text.TextRange(nextOffset)
                                                                )
                                                            )
                                                        }
                                                    }
                                                    ch.consume()
                                                }
                                                isDraggingHandle = false
                                                if (!moved) {
                                                    pointerCursorActive = false
                                                    lastTapCursorIndex = -1
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    TeardropCursorHandle(
                                        color = cursorColor,
                                        dragging = isDraggingHandle
                                    )
                                }
                            }
                        }

                        // 2. 选区起止水滴手柄：拖动调整选区范围后，顶部快捷工具栏会自动重新出现
                        val hasSelection = !value.selection.collapsed && value.text.isNotEmpty()
                        if (hasSelection && wasFocused && layout != null) {
                            val selMin = value.selection.min.coerceIn(0, value.text.length)
                            val selMax = value.selection.max.coerceIn(0, value.text.length)
                            val textTopOffset = ((boxH - layout.size.height) / 2f).coerceAtLeast(0f)
                            val touchBoxSize = 32.dp
                            val touchBoxPx = with(density) { touchBoxSize.toPx() }
                            val yGapPx = with(density) { 1.5.dp.toPx() }

                            val startRect = safeCursorRect(layout, selMin)
                            val startCenterX = ((startRect.left + startRect.right) / 2f).coerceIn(0f, boxW)
                            val startOffsetX = (startCenterX - touchBoxPx / 2f).roundToInt()
                            val startOffsetY = (textTopOffset + startRect.bottom + yGapPx).roundToInt()
                            val latestStartOffsetX by androidx.compose.runtime.rememberUpdatedState(startOffsetX)

                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(startOffsetX, startOffsetY),
                                properties = PopupProperties(focusable = false, clippingEnabled = false)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(touchBoxSize)
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                down.consume()
                                                val activeLayout = currentLayout ?: return@awaitEachGesture
                                                val initialMin = currentVal.selection.min.coerceIn(0, currentVal.text.length)
                                                val initialRect = safeCursorRect(activeLayout, initialMin)
                                                val startX = (initialRect.left + initialRect.right) / 2f
                                                val downAnchorX = latestStartOffsetX + down.position.x
                                                isDraggingSelectionHandle = true
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.firstOrNull() ?: break
                                                    if (!ch.pressed) break
                                                    val curLayout = currentLayout ?: activeLayout
                                                    val curText = currentVal.text
                                                    val totalDx = (latestStartOffsetX + ch.position.x) - downAnchorX
                                                    val targetX = (startX + totalDx).coerceIn(0f, curLayout.size.width.toFloat().coerceAtLeast(1f))
                                                    val newIdx = curLayout.getOffsetForPosition(Offset(targetX, curLayout.size.height / 2f))
                                                        .coerceIn(0, curText.length)
                                                    val anchorEnd = currentVal.selection.max.coerceIn(0, curText.length)
                                                    if (newIdx != currentVal.selection.min && newIdx != anchorEnd) {
                                                        currentOnValueChange(
                                                            currentVal.copy(
                                                                selection = androidx.compose.ui.text.TextRange(
                                                                    minOf(newIdx, anchorEnd),
                                                                    maxOf(newIdx, anchorEnd)
                                                                )
                                                            )
                                                        )
                                                    }
                                                    ch.consume()
                                                }
                                                isDraggingSelectionHandle = false
                                                toolbarDismissed = false
                                            }
                                        },
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    TeardropCursorHandle(color = cursorColor, dragging = false, compact = true)
                                }
                            }

                            val endRect = safeCursorRect(layout, selMax)
                            val endCenterX = ((endRect.left + endRect.right) / 2f).coerceIn(0f, boxW)
                            val endOffsetX = (endCenterX - touchBoxPx / 2f).roundToInt()
                            val endOffsetY = (textTopOffset + endRect.bottom + yGapPx).roundToInt()
                            val latestEndOffsetX by androidx.compose.runtime.rememberUpdatedState(endOffsetX)

                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(endOffsetX, endOffsetY),
                                properties = PopupProperties(focusable = false, clippingEnabled = false)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(touchBoxSize)
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                down.consume()
                                                val activeLayout = currentLayout ?: return@awaitEachGesture
                                                val initialMax = currentVal.selection.max.coerceIn(0, currentVal.text.length)
                                                val initialRect = safeCursorRect(activeLayout, initialMax)
                                                val startX = (initialRect.left + initialRect.right) / 2f
                                                val downAnchorX = latestEndOffsetX + down.position.x
                                                isDraggingSelectionHandle = true
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.firstOrNull() ?: break
                                                    if (!ch.pressed) break
                                                    val curLayout = currentLayout ?: activeLayout
                                                    val curText = currentVal.text
                                                    val totalDx = (latestEndOffsetX + ch.position.x) - downAnchorX
                                                    val targetX = (startX + totalDx).coerceIn(0f, curLayout.size.width.toFloat().coerceAtLeast(1f))
                                                    val newIdx = curLayout.getOffsetForPosition(Offset(targetX, curLayout.size.height / 2f))
                                                        .coerceIn(0, curText.length)
                                                    val anchorStart = currentVal.selection.min.coerceIn(0, curText.length)
                                                    if (newIdx != currentVal.selection.max && newIdx != anchorStart) {
                                                        currentOnValueChange(
                                                            currentVal.copy(
                                                                selection = androidx.compose.ui.text.TextRange(
                                                                    minOf(anchorStart, newIdx),
                                                                    maxOf(anchorStart, newIdx)
                                                                )
                                                            )
                                                        )
                                                    }
                                                    ch.consume()
                                                }
                                                isDraggingSelectionHandle = false
                                                toolbarDismissed = false
                                            }
                                        },
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    TeardropCursorHandle(color = cursorColor, dragging = false, compact = true)
                                }
                            }
                        }

                        // 3. 悬浮快捷操作工具栏（固定定位在输入框文本正上方；选区变更或拖动选区结束后自动重新显示）
                        val showToolbar = wasFocused &&
                            !isDraggingHandle &&
                            !isDraggingSelectionHandle &&
                            !toolbarDismissed &&
                            (hasSelection || toolbarForceVisible)
                        if (showToolbar) {
                            val anchorCenterX = when {
                                hasSelection && layout != null -> {
                                    val r1 = safeCursorRect(layout, value.selection.min.coerceIn(0, value.text.length))
                                    val r2 = safeCursorRect(layout, value.selection.max.coerceIn(0, value.text.length))
                                    (((r1.left + r2.right) / 2f)).coerceIn(0f, boxW)
                                }
                                layout != null -> {
                                    val r = safeCursorRect(layout, value.selection.end.coerceIn(0, value.text.length))
                                    ((r.left + r.right) / 2f).coerceIn(0f, boxW)
                                }
                                else -> boxW / 2f
                            }
                            val estW = toolbarSize.width.takeIf { it > 0 } ?: with(density) { 216.dp.roundToPx() }
                            val estH = toolbarSize.height.takeIf { it > 0 } ?: with(density) { 34.dp.roundToPx() }
                            val padGap = with(density) { 10.dp.roundToPx() }
                            val minX = -with(density) { 12.dp.roundToPx() }
                            val maxX = (boxW - estW + with(density) { 12.dp.roundToPx() }).roundToInt().coerceAtLeast(minX)
                            val toolbarOffsetX = (anchorCenterX - estW / 2f).roundToInt().coerceIn(minX, maxX)
                            val toolbarOffsetY = -(estH + padGap)

                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(toolbarOffsetX, toolbarOffsetY),
                                properties = PopupProperties(focusable = false, clippingEnabled = false)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .onSizeChanged { toolbarSize = it }
                                        .shadow(8.dp, com.littlecodesauce.ggnext.ui.theme.GgShapes.Lg)
                                        .background(Rose.CardHigh, com.littlecodesauce.ggnext.ui.theme.GgShapes.Lg)
                                        .border(1.dp, Rose.Primary.copy(alpha = 0.35f), com.littlecodesauce.ggnext.ui.theme.GgShapes.Lg)
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    ContextToolbarBtn(tr("全选"), Rose.Text) {
                                        val t = currentVal.text
                                        if (t.isNotEmpty()) {
                                            toolbarDismissed = false
                                            currentOnValueChange(currentVal.copy(selection = androidx.compose.ui.text.TextRange(0, t.length)))
                                        }
                                    }
                                    ContextToolbarDivider()
                                    ContextToolbarBtn(tr("复制"), Rose.Text) {
                                        toolbarForceVisible = false
                                        toolbarDismissed = true
                                        val sel = currentVal.selection
                                        val textToCopy = if (!sel.collapsed) {
                                            currentVal.text.substring(sel.min.coerceAtLeast(0), sel.max.coerceAtMost(currentVal.text.length))
                                        } else {
                                            currentVal.text
                                        }
                                        if (textToCopy.isNotEmpty()) {
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                                            com.littlecodesauce.ggnext.core.Session.toast("已复制: $textToCopy")
                                        }
                                    }
                                    ContextToolbarDivider()
                                    ContextToolbarBtn(tr("粘贴"), Rose.Text) {
                                        toolbarForceVisible = false
                                        toolbarDismissed = true
                                        val clipText = clipboard.getText()?.text.orEmpty()
                                        if (clipText.isNotEmpty()) {
                                            val sel = currentVal.selection
                                            val start = sel.min.coerceIn(0, currentVal.text.length)
                                            val end = sel.max.coerceIn(start, currentVal.text.length)
                                            val nextText = currentVal.text.substring(0, start) + clipText + currentVal.text.substring(end)
                                            val nextCursor = start + clipText.length
                                            currentOnValueChange(
                                                TextFieldValue(
                                                    text = nextText,
                                                    selection = androidx.compose.ui.text.TextRange(nextCursor)
                                                )
                                            )
                                        }
                                    }
                                    ContextToolbarDivider()
                                    ContextToolbarBtn(tr("剪切"), Rose.Text) {
                                        toolbarForceVisible = false
                                        toolbarDismissed = true
                                        val sel = currentVal.selection
                                        if (!sel.collapsed) {
                                            val start = sel.min.coerceIn(0, currentVal.text.length)
                                            val end = sel.max.coerceIn(start, currentVal.text.length)
                                            val cutStr = currentVal.text.substring(start, end)
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(cutStr))
                                            val nextText = currentVal.text.removeRange(start, end)
                                            currentOnValueChange(TextFieldValue(text = nextText, selection = androidx.compose.ui.text.TextRange(start)))
                                        } else if (currentVal.text.isNotEmpty()) {
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(currentVal.text))
                                            currentOnValueChange(TextFieldValue(text = "", selection = androidx.compose.ui.text.TextRange(0)))
                                        }
                                    }
                                    ContextToolbarDivider()
                                    ContextToolbarBtn(tr("清空"), Rose.Red) {
                                        toolbarForceVisible = false
                                        toolbarDismissed = true
                                        currentOnValueChange(TextFieldValue(text = "", selection = androidx.compose.ui.text.TextRange(0)))
                                    }
                                }
                            }
                        }
                    }
                }
            )
            }
        }
    }
}

@Composable
private fun TeardropCursorHandle(
    color: Color,
    dragging: Boolean,
    compact: Boolean = false
) {
    val canvasSize = if (compact) 16.dp else 20.dp
    Canvas(modifier = Modifier.size(canvasSize)) {
        val cx = this.size.width / 2f
        val tipY = 0.5.dp.toPx()
        val bulbRadius = (if (compact) 4.4f else 5.2f).dp.toPx()
        val bulbCenterY = tipY + bulbRadius * 1.46f

        if (dragging) {
            drawCircle(
                color = color.copy(alpha = 0.24f),
                radius = bulbRadius * 1.62f,
                center = Offset(cx, bulbCenterY)
            )
        }

        val dropPath = Path().apply {
            moveTo(cx, tipY)
            cubicTo(
                cx + bulbRadius * 0.92f, tipY + bulbRadius * 0.62f,
                cx + bulbRadius, bulbCenterY - bulbRadius * 0.2f,
                cx + bulbRadius, bulbCenterY
            )
            cubicTo(
                cx + bulbRadius, bulbCenterY + bulbRadius * 0.95f,
                cx + bulbRadius * 0.55f, bulbCenterY + bulbRadius,
                cx, bulbCenterY + bulbRadius
            )
            cubicTo(
                cx - bulbRadius * 0.55f, bulbCenterY + bulbRadius,
                cx - bulbRadius, bulbCenterY + bulbRadius * 0.95f,
                cx - bulbRadius, bulbCenterY
            )
            cubicTo(
                cx - bulbRadius, bulbCenterY - bulbRadius * 0.2f,
                cx - bulbRadius * 0.92f, tipY + bulbRadius * 0.62f,
                cx, tipY
            )
            close()
        }
        drawPath(path = dropPath, color = color)
        drawCircle(
            color = Rose.OnPrimary.copy(alpha = 0.85f),
            radius = (if (compact) 1.4f else 1.7f).dp.toPx(),
            center = Offset(cx, bulbCenterY)
        )
    }
}

@Composable
private fun ContextToolbarBtn(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = color,
            fontSize = fsp(12f),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun ContextToolbarDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 14.dp)
            .background(Rose.Divider)
    )
}

/**
 * 持久化列表滚动位置状态（Bug 2.1 修复）：
 * 在切换标签页或最小化修改器后再次展开时，自动恢复列表此前的首条可见索引与像素偏移量。
 */
@Composable
fun rememberPersistentLazyListState(listKey: String): LazyListState {
    val saved = remember(listKey) {
        com.littlecodesauce.ggnext.core.Session.getListScrollPosition(listKey)
    }
    val listState = remember(listKey) {
        LazyListState(
            firstVisibleItemIndex = saved.first,
            firstVisibleItemScrollOffset = saved.second
        )
    }
    androidx.compose.runtime.LaunchedEffect(listState, listKey) {
        androidx.compose.runtime.snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, off) ->
            com.littlecodesauce.ggnext.core.Session.saveListScrollPosition(listKey, idx, off)
        }
    }
    androidx.compose.runtime.DisposableEffect(listState, listKey) {
        onDispose {
            com.littlecodesauce.ggnext.core.Session.saveListScrollPosition(
                listKey,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }
    return listState
}

/**
 * 列表右侧快速滑动条 (VerticalFastScrollbar)（Bug 2 修复）：
 * - 修复此前因 pointerInput(totalItems, visibleItems) 在滚动过程中随 visibleItems ±1 抖动重启协程导致拖拽中断的问题；
 * - 移除强制长按 80ms 才能拖动的限制：只要手指按在滑块 (thumb) 区域并垂直拖动即可立即顺滑跟手响应；
 * - 空白轨道区域不拦截点击或普通滑动，彻底兼顾防误触与直接拖动体验。
 */
@Composable
fun VerticalFastScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo.size
    if (totalItems <= 0 || visibleItems <= 0 || totalItems <= visibleItems) return

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    val liveFraction = run {
        val firstIndex = listState.firstVisibleItemIndex.toFloat()
        val maxIndex = (totalItems - visibleItems).coerceAtLeast(1).toFloat()
        (firstIndex / maxIndex).coerceIn(0f, 1f)
    }
    val scrollFraction = if (isDragging) dragFraction else liveFraction
    val currentLiveFraction by androidx.compose.runtime.rememberUpdatedState(liveFraction)
    val currentTotalItems by androidx.compose.runtime.rememberUpdatedState(totalItems)
    val currentVisibleItems by androidx.compose.runtime.rememberUpdatedState(visibleItems)

    val thumbAlpha by animateFloatAsState(
        targetValue = when {
            isDragging -> 0.96f
            listState.isScrollInProgress -> 0.82f
            else -> 0.48f
        },
        animationSpec = tween(150),
        label = "sbAlpha"
    )
    val thumbWidthDp by animateFloatAsState(
        targetValue = if (isDragging) 6.5f else 4.0f,
        animationSpec = tween(140),
        label = "sbWidth"
    )
    val primaryColor = Rose.Primary
    val trackColor = Rose.Divider.copy(alpha = if (isDragging) 0.34f else 0.14f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(18.dp)
            .pointerInput(Unit) {
                val minThumbPx = 30.dp.toPx()
                val hitPadPx = 14.dp.toPx()

                awaitPointerEventScope {
                    while (true) {
                        val downEvent = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val downChange = downEvent.changes.firstOrNull() ?: continue
                        if (!downChange.pressed || downChange.isConsumed) continue

                        val curTotal = currentTotalItems
                        val curVisible = currentVisibleItems
                        if (curTotal <= curVisible || curTotal <= 0) continue

                        val trackH = size.height.toFloat().coerceAtLeast(1f)
                        val thumbRatio = (curVisible.toFloat() / curTotal.toFloat()).coerceIn(0.08f, 0.55f)
                        val thumbH = (trackH * thumbRatio).coerceAtLeast(minThumbPx)
                        val availRange = (trackH - thumbH).coerceAtLeast(1f)
                        val startFrac = currentLiveFraction
                        val curThumbTop = availRange * startFrac

                        val downX = downChange.position.x
                        val downY = downChange.position.y
                        // 仅当按下位置命中滑块 (thumb) 上下容差范围时接管拖拽，空白轨道不拦截普通列表滑动
                        val hitThumb = downY >= (curThumbTop - hitPadPx) && downY <= (curThumbTop + thumbH + hitPadPx)
                        if (!hitThumb) {
                            while (true) {
                                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (ev.changes.none { it.pressed }) break
                            }
                            continue
                        }

                        var activated = false
                        var abortedHorizontal = false
                        var grabAnchorY = downY
                        var anchorFrac = startFrac

                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val ch = event.changes.firstOrNull() ?: break
                            if (!ch.pressed) break

                            val dx = kotlin.math.abs(ch.position.x - downX)
                            val dy = kotlin.math.abs(ch.position.y - downY)

                            if (!activated && !abortedHorizontal) {
                                if (dx > 12.dp.toPx() && dx > dy * 1.5f) {
                                    abortedHorizontal = true
                                } else if (dy >= 0.5.dp.toPx()) {
                                    activated = true
                                    isDragging = true
                                    grabAnchorY = downY
                                    anchorFrac = startFrac
                                    dragFraction = startFrac
                                }
                            }

                            if (abortedHorizontal) {
                                continue
                            }

                            if (activated) {
                                val latestTotal = currentTotalItems
                                val latestVis = currentVisibleItems.coerceAtLeast(1)
                                val deltaY = ch.position.y - grabAnchorY
                                val nextFrac = (anchorFrac + deltaY / availRange).coerceIn(0f, 1f)
                                dragFraction = nextFrac
                                val maxIdx = (latestTotal - latestVis).coerceAtLeast(1)
                                val nextIdx = (nextFrac * maxIdx).toInt().coerceIn(0, (latestTotal - 1).coerceAtLeast(0))
                                scope.launch { listState.scrollToItem(nextIdx) }
                                ch.consume()
                            }
                        }
                        isDragging = false
                    }
                }
            }
    ) {
        val density = LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val thumbRatio = (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(0.08f, 0.55f)
        val thumbHeightPx = (trackHeightPx * thumbRatio).coerceAtLeast(with(density) { 30.dp.toPx() })
        val availableRangePx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val thumbTopPx = availableRangePx * scrollFraction

        Canvas(modifier = Modifier.fillMaxSize()) {
            val wPx = thumbWidthDp.dp.toPx()
            val xLeft = this.size.width - wPx - 2.dp.toPx()
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(xLeft, 4.dp.toPx()),
                size = Size(wPx, (this.size.height - 8.dp.toPx()).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(wPx / 2f, wPx / 2f)
            )
            drawRoundRect(
                color = primaryColor.copy(alpha = thumbAlpha),
                topLeft = Offset(xLeft, thumbTopPx),
                size = Size(wPx, thumbHeightPx),
                cornerRadius = CornerRadius(wPx / 2f, wPx / 2f)
            )
        }
    }
}
