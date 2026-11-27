package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

/**
 * 内置专用软键盘（5 行 × 7 列）：
 * - 点击第 4 行右侧的 `fx` 按钮后，直接在键盘内原地切换为表达式/符号键盘，且原先 `fx` 字样变成 `123`；
 * - 点击 `123` 按钮即切回数字/十六进制主键盘。
 */
@Composable
fun BuiltInKeyboard(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onHistory: () -> Unit,
    onFunction: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var symbolMode by remember { mutableStateOf(false) }
    var compactRows by remember { mutableStateOf(false) }
    var showHistoryTray by remember { mutableStateOf(false) }
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val isLandscapeCompact = screenHeightDp < 480
    val gap = if (isLandscapeCompact) 4.dp else 5.dp
    val rowHeight = when {
        isLandscapeCompact && compactRows -> 28.dp
        isLandscapeCompact && screenHeightDp >= 370 -> 36.dp
        isLandscapeCompact -> 31.dp
        compactRows -> 36.dp
        else -> 43.dp
    }
    val corner = LocalUiMetrics.current.dialogCorner.dp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fillAvailableHeight = constraints.hasFixedHeight && constraints.maxHeight < Int.MAX_VALUE
        val rowModifier: androidx.compose.foundation.layout.ColumnScope.() -> Modifier = {
            if (fillAvailableHeight) Modifier.weight(1f) else Modifier
        }
        val effectiveHeight = if (fillAvailableHeight) Dp.Unspecified else rowHeight

        Column(
            Modifier
                .then(if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .background(Rose.CardHigh, RoundedCornerShape(corner))
                .border(GgBorder.Default, Rose.Divider, RoundedCornerShape(corner))
                .padding(gap)
        ) {
        // Bug 7 优化：点击历史按钮直接在键盘上方展开快捷历史列表，不关闭当前正在编辑或搜索的界面
        AnimatedVisibility(showHistoryTray, enter = expandEnter(), exit = expandExit()) {
            val historyList = com.littlecodesauce.ggnext.core.Session.ui.collectAsState().value.history
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tr("输入历史 (点击直接填入):"),
                        color = Rose.TextSecond,
                        fontSize = fsp(11.5f),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        tr("完整管理"),
                        color = Rose.Primary,
                        fontSize = fsp(11.5f),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            showHistoryTray = false
                            onHistory()
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (historyList.isEmpty()) {
                    Text(tr("暂无输入历史"), color = Rose.TextDim, fontSize = fsp(11.5f), modifier = Modifier.padding(vertical = 4.dp))
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        historyList.take(12).forEach { histItem ->
                            Box(
                                Modifier
                                    .height(28.dp)
                                    .background(Rose.Card, GgShapes.Xs)
                                    .border(GgBorder.Thin, Rose.Divider, GgShapes.Xs)
                                    .clickable {
                                        onValueChange(TextFieldValue(histItem, TextRange(histItem.length)))
                                        showHistoryTray = false
                                    }
                                    .padding(horizontal = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(histItem, style = monoStyle(12, Rose.Text))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        if (!symbolMode) {
            // ==================== 主键盘模式（数字 / 十六进制，第4行右为 fx） ====================
            KeyRow(gap, modifier = rowModifier()) {
                listOf("1", "2", "3", "4", "5", ":").forEach { c ->
                    CharKey(c, Modifier.weight(1f), effectiveHeight) {
                        insert(value, c, onValueChange)
                    }
                }
                IconKey(GgIcons.BackspaceLeft, "退格删除", Modifier.weight(1f), effectiveHeight, tint = Rose.Text) {
                    onValueChange(backspace(value))
                }
            }
            KeyRow(gap, modifier = rowModifier()) {
                listOf("6", "7", "8", "9", "0", ";").forEach { c ->
                    CharKey(c, Modifier.weight(1f), effectiveHeight) {
                        insert(value, c, onValueChange)
                    }
                }
                IconKey(GgIcons.DeleteRight, "删除光标后字符", Modifier.weight(1f), effectiveHeight, tint = Rose.Text) {
                    onValueChange(deleteForward(value))
                }
            }
            KeyRow(gap, modifier = rowModifier()) {
                listOf("A", "B", "C", "D", "E", "F").forEach { c ->
                    CharKey(c, Modifier.weight(1f), effectiveHeight) {
                        insert(value, c, onValueChange)
                    }
                }
                IconKey(GgIcons.SelectAll, "全选输入内容", Modifier.weight(1f), effectiveHeight, tint = Rose.Text) {
                    onValueChange(value.copy(selection = TextRange(0, value.text.length)))
                }
            }
            KeyRow(gap, modifier = rowModifier()) {
                listOf("Q", "h", "r", "W", "X", "~").forEach { c ->
                    CharKey(c, Modifier.weight(1f), effectiveHeight) {
                        insert(value, c, onValueChange)
                    }
                }
                CharKey(
                    label = "fx",
                    modifier = Modifier.weight(1f),
                    height = effectiveHeight,
                    accent = Rose.ChipSelected,
                    italic = true,
                    onClick = { symbolMode = true }
                )
            }
            KeyRow(gap, lastRow = true, modifier = rowModifier()) {
                CharKey(",", Modifier.weight(1f), effectiveHeight) { insert(value, ",", onValueChange) }
                CharKey(".", Modifier.weight(1f), effectiveHeight) { insert(value, ".", onValueChange) }
                CharKey("-", Modifier.weight(1f), effectiveHeight) { insert(value, "-", onValueChange) }
                CharKey("←", Modifier.weight(1f), effectiveHeight) { onValueChange(moveCursor(value, -1)) }
                CharKey("→", Modifier.weight(1f), effectiveHeight) { onValueChange(moveCursor(value, 1)) }
                IconKey(Icons.Rounded.History, "输入历史记录", Modifier.weight(1f), effectiveHeight, tint = if (showHistoryTray) Rose.Primary else Rose.Text, onClick = { showHistoryTray = !showHistoryTray })
                IconKey(
                    icon = GgIcons.CollapseVertical,
                    desc = "缩放/收起键盘",
                    modifier = Modifier.weight(1f),
                    height = effectiveHeight,
                    tint = Rose.Text
                ) {
                    if (fillAvailableHeight) {
                        onDismiss()
                    } else if (!compactRows) {
                        compactRows = true
                    } else {
                        compactRows = false
                        onDismiss()
                    }
                }
            }
        } else {
            // ==================== 符号/表达式键盘模式（fx 变为 123） ====================
            KeyRow(gap, modifier = rowModifier()) {
                listOf("=", "+", "-", "&", "(", ")").forEach { c ->
                    CharKey(c, Modifier.weight(1f), effectiveHeight) {
                        insert(value, c, onValueChange)
                    }
                }
                IconKey(GgIcons.BackspaceLeft, "退格删除", Modifier.weight(1f), effectiveHeight, tint = Rose.Text) {
                    onValueChange(backspace(value))
                }
            }
            KeyRow(gap, modifier = rowModifier()) {
                listOf(".", "*", "/", "|", "<", ">").forEach { c ->
                    CharKey(c, Modifier.weight(1f), effectiveHeight) {
                        insert(value, c, onValueChange)
                    }
                }
                IconKey(GgIcons.DeleteRight, "删除光标后字符 / 清空", Modifier.weight(1f), effectiveHeight, tint = Rose.Text) {
                    onValueChange(deleteForward(value))
                }
            }
            KeyRow(gap, modifier = rowModifier()) {
                CharKey("~", Modifier.weight(1f), effectiveHeight) { insert(value, "~", onValueChange) }
                CharKey("^", Modifier.weight(1f), effectiveHeight) { insert(value, "^", onValueChange) }
                CharKey("%", Modifier.weight(1f), effectiveHeight) { insert(value, "%", onValueChange) }
                CharKey(tr("空格"), Modifier.weight(1f), effectiveHeight) { insert(value, " ", onValueChange) }
                CharKey(";", Modifier.weight(1f), effectiveHeight) { insert(value, ";", onValueChange) }
                CharKey("'", Modifier.weight(1f), effectiveHeight) { insert(value, "'", onValueChange) }
                IconKey(Icons.Rounded.History, "输入历史记录", Modifier.weight(1f), effectiveHeight, tint = if (showHistoryTray) Rose.Primary else Rose.Text, onClick = { showHistoryTray = !showHistoryTray })
            }
            KeyRow(gap, modifier = rowModifier()) {
                listOf("\\", "#", "[", "]", "{", "}").forEach { c ->
                    CharKey(c, Modifier.weight(1f), effectiveHeight) {
                        insert(value, c, onValueChange)
                    }
                }
                CharKey(
                    label = "123",
                    modifier = Modifier.weight(1f),
                    height = effectiveHeight,
                    accent = Rose.ChipSelected,
                    onClick = { symbolMode = false }
                )
            }
            KeyRow(gap, lastRow = true, modifier = rowModifier()) {
                CharKey(",", Modifier.weight(1.1f), effectiveHeight) { insert(value, ",", onValueChange) }
                CharKey(";", Modifier.weight(1.1f), effectiveHeight) { insert(value, ";", onValueChange) }
                CharKey("\"", Modifier.weight(1.1f), effectiveHeight) { insert(value, "\"", onValueChange) }
                CharKey("<", Modifier.weight(1.2f), effectiveHeight) { onValueChange(moveCursor(value, -1)) }
                CharKey(">", Modifier.weight(1.2f), effectiveHeight) { onValueChange(moveCursor(value, 1)) }
                IconKey(
                    icon = Icons.Rounded.Close,
                    desc = "收起键盘",
                    modifier = Modifier.weight(1.3f),
                    height = effectiveHeight,
                    tint = Rose.Text,
                    onClick = onDismiss
                )
            }
        }
        }
    }
}

@Composable
private fun KeyRow(
    gap: Dp,
    lastRow: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = if (lastRow) 0.dp else gap),
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content
    )
}

@Composable
private fun CharKey(
    label: String,
    modifier: Modifier = Modifier,
    height: Dp,
    accent: Color = Rose.Chip,
    italic: Boolean = false,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.91f else 1f, Motion.Fast, label = "k")
    val bg by animateColorAsState(if (pressed) Rose.PrimaryDeep else accent, Motion.ColorFast, label = "kb")

    Box(
        modifier
            .then(if (height == Dp.Unspecified) Modifier.fillMaxHeight() else Modifier.height(height))
            .scale(scale)
            .background(bg, GgShapes.Sm)
            .clickableWithTooltip(
                onPressChange = { pressed = it },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Rose.Text,
            fontSize = ((if (label.length >= 2) 13.5f else 15.5f) * LocalUiMetrics.current.fontScale).sp,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (italic || label == "123") FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun IconKey(
    icon: ImageVector,
    desc: String,
    modifier: Modifier = Modifier,
    height: Dp,
    tint: Color = Rose.Text,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.91f else 1f, Motion.Fast, label = "k")
    val bg by animateColorAsState(if (pressed) Rose.PrimaryDeep else Rose.Chip, Motion.ColorFast, label = "kb")

    Box(
        modifier
            .then(if (height == Dp.Unspecified) Modifier.fillMaxHeight() else Modifier.height(height))
            .scale(scale)
            .background(bg, GgShapes.Sm)
            .clickableWithTooltip(
                onPressChange = { pressed = it },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            desc,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

// ---------- 文本编辑工具（光标安全） ----------

fun insert(value: TextFieldValue, text: String, onChange: (TextFieldValue) -> Unit) {
    val sel = value.selection
    val start = sel.start.coerceIn(0, value.text.length)
    val end = sel.end.coerceIn(0, value.text.length)
    val minPos = minOf(start, end)
    val maxPos = maxOf(start, end)
    val newText = value.text.replaceRange(minPos, maxPos, text)
    onChange(value.copy(text = newText, selection = TextRange(minPos + text.length)))
}

fun backspace(value: TextFieldValue): TextFieldValue {
    val sel = value.selection
    val start = sel.start.coerceIn(0, value.text.length)
    val end = sel.end.coerceIn(0, value.text.length)
    if (start != end) {
        val minPos = minOf(start, end)
        val maxPos = maxOf(start, end)
        val newText = value.text.removeRange(minPos, maxPos)
        return value.copy(text = newText, selection = TextRange(minPos))
    }
    if (start == 0) return value
    val newText = value.text.removeRange(start - 1, start)
    return value.copy(text = newText, selection = TextRange(start - 1))
}

fun deleteForward(value: TextFieldValue): TextFieldValue {
    val sel = value.selection
    val start = sel.start.coerceIn(0, value.text.length)
    val end = sel.end.coerceIn(0, value.text.length)
    if (start != end) {
        val minPos = minOf(start, end)
        val maxPos = maxOf(start, end)
        val newText = value.text.removeRange(minPos, maxPos)
        return value.copy(text = newText, selection = TextRange(minPos))
    }
    if (start >= value.text.length) {
        return TextFieldValue("", TextRange(0))
    }
    val newText = value.text.removeRange(start, start + 1)
    return value.copy(text = newText, selection = TextRange(start))
}

fun moveCursor(value: TextFieldValue, delta: Int): TextFieldValue {
    val cur = value.selection.end.coerceIn(0, value.text.length)
    val next = (cur + delta).coerceIn(0, value.text.length)
    return value.copy(selection = TextRange(next))
}
