package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.rememberPersistentLazyListState
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.regionColor
import com.littlecodesauce.ggnext.ui.theme.typeColor

@Composable
fun SearchListScreen(ui: Ui) {
    if (ui.results.isEmpty()) {
        GgEmptyState(
            icon = Icons.Rounded.Search,
            message = if (ui.target == null) "未选择进程。\n点击进程栏选择目标。" else "打开搜索面板开始。"
        )
        return
    }
    val state = rememberPersistentLazyListState("search_list")
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            itemsIndexed(
                items = ui.results,
                key = { _, i -> (i.address shl 8) xor (i.flags and 0xFF).toLong() },
                contentType = { _, _ -> "addr_row" }
            ) { idx, item ->
                AddressRow(item, ui.isSearchItemSelected(item), ui.valueMask, idx)
            }
        }
        VerticalFastScrollbar(
            listState = state,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/**
 * 搜索结果行：
 * 全面使用与滚动容器协同的 clickableLongPress / clickableWithTooltip，
 * 当手指按在任意地址或按钮上滑动屏幕时，列表平滑滚动绝不卡顿。
 */
@Composable
fun AddressRow(item: MemoryItem, checked: Boolean, mask: Int, index: Int) {
    val currentItem by rememberUpdatedState(item)
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val isDark = Rose.IsDark
    val accent = remember(item.flags, isDark) { typeColor(item.flags) }
    val regColor = remember(item.region, isDark) { regionColor(item.region) }
    val shortType = remember(item.flags) { ValueType.shortName(item.flags) }
    val bg by animateColorAsState(
        if (checked) Rose.Primary.copy(alpha = if (isDark) 0.18f else 0.20f)
        else Rose.Row.copy(alpha = (bgOpacity * 0.88f).coerceIn(0.18f, 1.0f)),
        Motion.ColorFast,
        label = "rowBg"
    )
    ItemEnter(item.address, index) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .background(bg, GgShapes.Md)
                .clickableLongPress(
                    onClick = { Session.toggle(currentItem) },
                    onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GgCheckBox(
                checked = checked,
                onCheckedChange = { Session.toggle(currentItem) },
                size = 21.dp,
                accentColor = Rose.Primary
            )
            Spacer(Modifier.width(10.dp))
            Text(item.cachedAddressHex, style = monoStyle(14, Rose.Primary))
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .weight(1f)
                    .clickableLongPress(
                        onClick = { Session.openSheet(Sheet.EDITOR, currentItem, singleEdit = true) },
                        onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                    )
            ) {
                ScaledNumber(item, mask)
            }
            if (item.pointer > 0) {
                Text("${item.pointer}", style = monoStyle(11, Rose.TextDim))
                Spacer(Modifier.width(6.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(shortType, color = accent, fontSize = fsp(11.5f))
                Text(item.region, style = monoStyle(10, regColor))
            }
            Spacer(Modifier.width(6.dp))
            GgIconActionButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "移除",
                tint = Rose.Red.copy(alpha = 0.82f),
                boxSize = 28.dp,
                iconSize = 17.dp,
                onClick = { Session.deleteResultItem(currentItem) }
            )
        }
    }
}
