package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.GgEmptyState
import com.littlecodesauce.ggnext.ui.components.GgIconActionButton
import com.littlecodesauce.ggnext.ui.components.ItemEnter
import com.littlecodesauce.ggnext.ui.components.ScaledNumber
import com.littlecodesauce.ggnext.ui.components.VerticalFastScrollbar
import com.littlecodesauce.ggnext.ui.components.clickableLongPress
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.rememberPersistentLazyListState
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.regionColor
import com.littlecodesauce.ggnext.ui.theme.typeColor

@Composable
fun SavedListScreen(ui: Ui) {
    if (ui.saved.isEmpty()) {
        GgEmptyState(
            icon = Icons.Rounded.Save,
            message = "保存列表为空。\n在搜索列表中勾选后按「保存」加入。"
        )
        return
    }
    val listState = rememberPersistentLazyListState("saved_list")
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            itemsIndexed(
                items = ui.saved,
                key = { _, i -> (i.address shl 8) xor (i.flags and 0xFF).toLong() },
                contentType = { _, _ -> "saved_row" }
            ) { idx, item ->
                SavedRow(item, ui.isSavedItemSelected(item), ui.valueMaskSaved, idx)
            }
        }
        VerticalFastScrollbar(
            listState = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/**
 * 保存列表行：
 * - 复选框、冻结锁图标、地址/数值区、删除按钮的手势区域完全独立隔离，
 *   且全部采用与滚动容器协同的 clickableWithTooltip / clickableLongPress，手指在任意按钮上滑动均可顺畅滚动列表。
 */
@Composable
private fun SavedRow(item: MemoryItem, checked: Boolean, mask: Int, index: Int) {
    val currentItem by rememberUpdatedState(item)
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val isDark = Rose.IsDark
    val accent = remember(item.flags, isDark) { typeColor(item.flags) }
    val regColor = remember(item.region, isDark) { regionColor(item.region) }
    val shortType = remember(item.flags) { ValueType.shortName(item.flags) }
    val rowBg by animateColorAsState(
        if (checked) Rose.Primary.copy(alpha = if (isDark) 0.18f else 0.20f)
        else Rose.Row.copy(alpha = (bgOpacity * 0.88f).coerceIn(0.18f, 1.0f)),
        Motion.ColorFast,
        label = "savedRowBg"
    )

    var lockPressed by remember { mutableStateOf(false) }
    val lockScale by animateFloatAsState(
        targetValue = when {
            lockPressed -> 0.82f
            item.freeze -> 1.08f
            else -> 1.0f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "lockScale"
    )
    val lockTint by animateColorAsState(
        targetValue = if (item.freeze) Rose.Frozen else Rose.TextDim,
        animationSpec = Motion.ColorFast,
        label = "lockTint"
    )

    ItemEnter(item.address, index) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .background(rowBg, GgShapes.Md)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 左侧独立勾选框区域
            GgCheckBox(
                checked = checked,
                onCheckedChange = { Session.toggle(currentItem) },
                size = 21.dp,
                accentColor = Rose.Primary
            )
            Spacer(Modifier.width(8.dp))

            // 2. 独立冻结锁按钮（绝不触发勾选，且不阻碍滚动）
            Box(
                Modifier
                    .size(28.dp)
                    .scale(lockScale)
                    .clickableWithTooltip(
                        onPressChange = { lockPressed = it },
                        onClick = { Session.toggleFreeze(currentItem) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (item.freeze) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    contentDescription = "冻结",
                    tint = lockTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(6.dp))

            // 3. 地址区域（轻点切换勾选，长按打开上下文菜单，颜色跟随当前主题色）
            Text(
                text = item.cachedAddressHex,
                style = monoStyle(14, Rose.Primary),
                modifier = Modifier.clickableLongPress(
                    onClick = { Session.toggle(currentItem) },
                    onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                )
            )
            Spacer(Modifier.width(12.dp))

            // 4. 数值与备注区域（轻点编辑数值，长按打开上下文菜单）
            Box(
                Modifier
                    .weight(1f)
                    .clickableLongPress(
                        onClick = { Session.openSheet(Sheet.EDITOR, currentItem, singleEdit = true) },
                        onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScaledNumber(item, mask)
                    val displayNote = item.note.ifEmpty { item.name }
                    if (displayNote.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(displayNote, color = Rose.TextSecond, fontSize = fsp(11f))
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(shortType, color = accent, fontSize = fsp(11.5f))
                Text(item.region, style = monoStyle(10, regColor))
            }
            Spacer(Modifier.width(6.dp))

            // 5. 独立删除按钮
            GgIconActionButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "删除",
                tint = Rose.Red,
                boxSize = 28.dp,
                iconSize = 17.dp,
                onClick = { Session.deleteSaved(currentItem) }
            )
        }
    }
}
