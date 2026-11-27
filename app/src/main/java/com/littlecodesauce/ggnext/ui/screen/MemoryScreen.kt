package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.GgEmptyState
import com.littlecodesauce.ggnext.ui.components.VerticalFastScrollbar
import com.littlecodesauce.ggnext.ui.components.clickableLongPress
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.rememberPersistentLazyListState
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.pointerColor
import com.littlecodesauce.ggnext.ui.theme.pointerTypeLabel
import com.littlecodesauce.ggnext.ui.theme.typeColor
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(ui: Ui) {
    val listState = rememberPersistentLazyListState("memory_list")
    val scope = rememberCoroutineScope()
    var lastHandledMemoryScrollTrigger by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableIntStateOf(-1)
    }

    val firstAddr = ui.memoryRows.firstOrNull()?.address ?: 0L
    val lastAddr = ui.memoryRows.lastOrNull()?.address ?: 0L
    val hasTopBoundary = ui.memoryRows.isNotEmpty() && java.lang.Long.compareUnsigned(firstAddr, 4L) >= 0
    val topJumpOffset = if (hasTopBoundary) 1 else 0

    LaunchedEffect(ui.memoryScrollTrigger, ui.memoryHighlightAddress, ui.memoryRows.size) {
        if (ui.memoryScrollTrigger != lastHandledMemoryScrollTrigger && ui.memoryRows.isNotEmpty()) {
            lastHandledMemoryScrollTrigger = ui.memoryScrollTrigger
            val targetAddr = ui.memoryHighlightAddress
            if (targetAddr != null) {
                val idx = ui.memoryRows.indexOfFirst { it.address == targetAddr }
                if (idx >= 0) {
                    val listIdx = idx + topJumpOffset
                    val scrollIdx = when {
                        idx == 0 -> listIdx
                        idx >= ui.memoryRows.lastIndex - 1 -> (listIdx - 4).coerceAtLeast(0)
                        else -> (listIdx - 2).coerceAtLeast(0)
                    }
                    listState.scrollToItem(scrollIdx)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        MemoryMiniBar(
            ui = ui,
            onLocateAddress = {
                val targetAddr = ui.memoryHighlightAddress ?: ui.memoryBase
                val idx = ui.memoryRows.indexOfFirst { it.address == targetAddr }
                if (idx >= 0) {
                    val listIdx = idx + topJumpOffset
                    scope.launch {
                        listState.animateScrollToItem((listIdx - 2).coerceAtLeast(0))
                    }
                    Session.toast("已定位到地址 0x${java.lang.Long.toHexString(targetAddr).uppercase()}")
                } else {
                    Session.focusHighlightedMemoryAddress()
                }
            }
        )
        if (ui.memoryPointerChain.isNotEmpty()) {
            MemoryPointerPathBar(ui = ui)
        }
        if (ui.memoryRows.isEmpty()) {
            GgEmptyState(
                icon = Icons.Rounded.Code,
                message = "内存编辑器\n使用工具栏「转到地址」加载内存视图。"
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 2.dp, horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (hasTopBoundary) {
                        val prevAddr = (firstAddr - 4L) and -4L
                        item(key = "mem_boundary_top", contentType = "mem_boundary") {
                            MemoryBoundaryJumpButton(
                                targetAddress = prevAddr,
                                isUpward = true
                            )
                        }
                    }
                    itemsIndexed(
                        items = ui.memoryRows,
                        key = { _, i -> i.address },
                        contentType = { _, _ -> "mem_row" }
                    ) { idx, item ->
                        val nextVal = ui.memoryRows.getOrNull(idx + 1)?.value ?: 0L
                        MemoryRow(
                            item = item,
                            nextRowValue = nextVal,
                            checked = ui.isMemoryRowSelected(item),
                            mask = ui.valueMaskMemory,
                            disasmText = ui.memoryDisasm[item.address],
                            isJumpTarget = (item.address == ui.memoryHighlightAddress)
                        )
                    }
                    val nextAddr = (lastAddr + 4L) and -4L
                    item(key = "mem_boundary_bottom", contentType = "mem_boundary") {
                        MemoryBoundaryJumpButton(
                            targetAddress = nextAddr,
                            isUpward = false
                        )
                    }
                }
                VerticalFastScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

/**
 * 内存页多级指针跳转路径栏（非破坏性导航）：
 * - 顺序展示 A → B → C → D 跳转层级，高亮当前所在层；
 * - 点击任意层快速切换目标地址，绝不删除后续层级；
 * - 仅手动点击节点 ×、右侧清空按钮，或点击工具栏「后退」按钮时移除对应层级。
 */
@Composable
private fun MemoryPointerPathBar(ui: Ui) {
    val chain = ui.memoryPointerChain
    if (chain.isEmpty()) return
    val scrollState = rememberScrollState()
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)

    LaunchedEffect(chain.size, ui.memoryPointerIndex) {
        if (ui.memoryPointerIndex >= chain.lastIndex) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Rose.Card.copy(alpha = (bgOpacity * 0.92f).coerceIn(0.35f, 0.96f)))
            .border(GgBorder.Hairline, Rose.Divider.copy(alpha = 0.7f))
            .padding(horizontal = GgSpacing.Sm, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = tr("指针链"),
            color = Rose.TextDim,
            fontSize = fsp(10.5f),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 6.dp)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            chain.forEachIndexed { idx, hop ->
                if (idx > 0) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Rose.TextDim,
                        modifier = Modifier.size(14.dp)
                    )
                }
                val isCurrent = (idx == ui.memoryPointerIndex)
                val labelHex = remember(hop.address) {
                    java.lang.Long.toUnsignedString(hop.address, 16).uppercase()
                }
                Row(
                    modifier = Modifier
                        .clip(GgShapes.Sm)
                        .background(
                            if (isCurrent) Rose.ChipSelected
                            else Rose.CardHigh.copy(alpha = (bgOpacity * 0.9f).coerceIn(0.4f, 0.95f))
                        )
                        .border(
                            width = if (isCurrent) GgBorder.Default else GgBorder.Hairline,
                            color = if (isCurrent) Rose.Primary else Rose.Divider,
                            shape = GgShapes.Sm
                        )
                        .clickable { Session.jumpToPointerChainIndex(idx) }
                        .padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "L${idx + 1}",
                        color = if (isCurrent) Rose.Primary else Rose.TextDim,
                        fontSize = fsp(9.5f),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = labelHex,
                        style = monoStyle(10, if (isCurrent) Rose.Text else Rose.TextSecond),
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = tr("移除此层"),
                        tint = if (isCurrent) Rose.Primary.copy(alpha = 0.85f) else Rose.TextDim,
                        modifier = Modifier
                            .size(13.dp)
                            .clip(GgShapes.Xs)
                            .clickable { Session.removePointerChainIndex(idx) }
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(GgShapes.Sm)
                .clickableWithTooltip(
                    tooltip = "清空路径",
                    onClick = { Session.clearPointerChain() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = tr("清空路径"),
                tint = Rose.TextDim,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun MemoryBoundaryJumpButton(
    targetAddress: Long,
    isUpward: Boolean
) {
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val addrHex = remember(targetAddress) {
        "0x" + java.lang.Long.toHexString(targetAddress).uppercase()
    }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, Motion.Fast, label = "memBoundaryScale")

    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .background(
                Rose.CardHigh.copy(alpha = (bgOpacity * 0.95f).coerceIn(0.25f, 1.0f)),
                GgShapes.Sm
            )
            .border(GgBorder.Default, Rose.Primary.copy(alpha = 0.40f), GgShapes.Sm)
            .clickableWithTooltip(
                onPressChange = { pressed = it },
                onClick = { Session.gotoBoundary(targetAddress, isUpward = isUpward) }
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${tr("转到此地址：")}$addrHex",
            style = monoStyle(13, Rose.Primary, weight = FontWeight.SemiBold)
        )
    }
}

/**
 * 左上角仅显示跳转地址 `0x...`，点击该地址自动定位到该地址（界面调整 5：已移除右侧快捷按钮）
 */
@Composable
private fun MemoryMiniBar(
    ui: Ui,
    onLocateAddress: () -> Unit
) {
    val displayAddr = ui.memoryHighlightAddress ?: ui.memoryBase
    val addrHexText = remember(displayAddr) {
        "0x" + java.lang.Long.toHexString(displayAddr).uppercase()
    }
    var addrPressed by remember { mutableStateOf(false) }
    val addrScale by animateFloatAsState(if (addrPressed) 0.93f else 1f, Motion.Fast, label = "addrJumpScale")

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .scale(addrScale)
                .background(Rose.CardHigh.copy(alpha = ui.metrics.bgOpacity.coerceIn(0.25f, 1.0f)), GgShapes.Xs)
                .clickableWithTooltip(
                    onPressChange = { addrPressed = it },
                    onClick = onLocateAddress
                )
                .padding(horizontal = 7.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = addrHexText,
                style = monoStyle(12, Rose.Primary, weight = FontWeight.SemiBold)
            )
        }
        Spacer(Modifier.weight(1f))
        val formatSummary = remember(ui.valueMaskMemory) { Formatter.maskSummary(ui.valueMaskMemory) }
        val hasAsmFormat = remember(ui.valueMaskMemory) { Formatter.activeAsmArch(ui.valueMaskMemory) != null }
        Box(
            Modifier
                .background(
                    if (hasAsmFormat) Rose.ChipSelected else Rose.CardHigh.copy(alpha = ui.metrics.bgOpacity.coerceIn(0.25f, 1.0f)),
                    GgShapes.Xs
                )
                .clickableWithTooltip(tooltip = tr("值格式")) {
                    Session.openSheet(Sheet.VALUE_FORMAT)
                }
                .padding(horizontal = 7.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formatSummary,
                style = monoStyle(11, if (hasAsmFormat) Rose.Cyan else Rose.TextSecond, weight = FontWeight.Medium)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryRow(
    item: MemoryItem,
    nextRowValue: Long,
    checked: Boolean,
    mask: Int,
    disasmText: String?,
    isJumpTarget: Boolean
) {
    val currentItem by rememberUpdatedState(item)
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val isDark = Rose.IsDark
    val hexColor = remember(item.pointer, isDark) { pointerColor(item.pointer) }
    val ptrLabel = remember(item.pointer) { pointerTypeLabel(item.pointer) }
    val qwordBits = remember(item.value, nextRowValue) {
        (item.value and 0xFFFFFFFFL) or ((nextRowValue and 0xFFFFFFFFL) shl 32)
    }
    val hexVal = remember(item.value, item.size) { Formatter.hex(item.value, item.size) + "h" }
    val rhexVal = remember(item.value, item.size, mask) {
        if (mask and Formatter.F_RHEX != 0) Formatter.reverseHex(item.value, item.size) + "r" else ""
    }
    val asciiVal = remember(item.value, item.size, mask) {
        if (mask and Formatter.F_STRING != 0) Formatter.bytesToAscii(item.value, item.size) else ""
    }
    val javaVal = remember(item.value, item.size, mask) {
        if (mask and Formatter.F_JAVA != 0) Formatter.bytesToUtf16(item.value, item.size) else ""
    }
    val asmArch = remember(mask) { Formatter.activeAsmArch(mask) }
    val resolvedAsm = remember(disasmText, asmArch, item.address, item.value) {
        if (asmArch == null) ""
        else if (!disasmText.isNullOrBlank()) disasmText
        else com.littlecodesauce.ggnext.engine.AsmEngineManager.disassembleWord(asmArch, item.address, item.value, 4)
    }

    val typedTokens = remember(item.value, qwordBits, item.address, mask) {
        val list = ArrayList<Pair<String, Int>>(7)
        if (mask and Formatter.F_DWORD != 0) list.add("${Formatter.decimal(item.value, 4)}D" to com.littlecodesauce.ggnext.model.ValueType.DWORD)
        if (mask and Formatter.F_FLOAT != 0) list.add("${Formatter.floatText(item.value)}F" to com.littlecodesauce.ggnext.model.ValueType.FLOAT)
        if (mask and Formatter.F_DOUBLE != 0) list.add("${Formatter.doubleText(qwordBits)}E" to com.littlecodesauce.ggnext.model.ValueType.DOUBLE)
        if (mask and Formatter.F_QWORD != 0) list.add("${Formatter.decimal(qwordBits, 8)}Q" to com.littlecodesauce.ggnext.model.ValueType.QWORD)
        if (mask and Formatter.F_WORD != 0) list.add("${Formatter.decimal(item.value, 2)}W" to com.littlecodesauce.ggnext.model.ValueType.WORD)
        if (mask and Formatter.F_BYTE != 0) list.add("${Formatter.decimal(item.value, 1)}B" to com.littlecodesauce.ggnext.model.ValueType.BYTE)
        if (mask and Formatter.F_XOR != 0) {
            val xorVal = ((item.value and 0xFFFFFFFFL) xor (item.address and 0xFFFFFFFFL)).toInt()
            list.add("${xorVal}X" to com.littlecodesauce.ggnext.model.ValueType.XOR)
        }
        if (list.isEmpty() && (mask and (Formatter.F_HEX or Formatter.F_RHEX or Formatter.F_STRING or Formatter.F_JAVA)) == 0 && asmArch == null) {
            list.add("${Formatter.decimal(item.value, 4)}D" to com.littlecodesauce.ggnext.model.ValueType.DWORD)
        }
        list
    }
    val showHex = (mask and Formatter.F_HEX != 0) || (typedTokens.isEmpty() && rhexVal.isEmpty() && asciiVal.isEmpty() && javaVal.isEmpty() && resolvedAsm.isEmpty())

    val rowBgColor = when {
        isJumpTarget && checked -> Rose.JumpHighlight.copy(alpha = 0.42f)
        isJumpTarget -> Rose.JumpHighlight
        checked -> Rose.Primary.copy(alpha = if (isDark) 0.18f else 0.20f)
        else -> Rose.Row.copy(alpha = (bgOpacity * 0.88f).coerceIn(0.18f, 1.0f))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(rowBgColor, GgShapes.Sm)
            .then(
                if (isJumpTarget) Modifier.border(GgBorder.Default, Rose.JumpBorder, GgShapes.Sm)
                else Modifier
            )
            .clickableLongPress(
                onClick = { Session.toggle(currentItem) },
                onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GgCheckBox(
            checked = checked,
            onCheckedChange = { Session.toggle(currentItem) },
            size = 19.dp,
            accentColor = Rose.Primary
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.clickableLongPress(
                    onClick = { Session.openSheet(Sheet.EDITOR, currentItem, singleEdit = true) },
                    onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                )
            ) {
                Text(
                    text = item.cachedAddressHex,
                    style = monoStyle(
                        size = 13,
                        color = Rose.Primary,
                        weight = if (isJumpTarget) FontWeight.Bold else FontWeight.Normal
                    )
                )
                if (showHex) {
                    Text(
                        text = hexVal,
                        style = monoStyle(
                            size = 12,
                            color = hexColor,
                            weight = if (item.pointer != 0 && item.pointer != 4) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                }
                if (rhexVal.isNotEmpty()) {
                    Text(text = rhexVal, style = monoStyle(12, Rose.TextSecond))
                }
                typedTokens.forEach { (tokenText, typeFlag) ->
                    Text(
                        text = tokenText,
                        style = monoStyle(12.5f.toInt(), typeColor(typeFlag), weight = FontWeight.Medium)
                    )
                }
                if (asciiVal.isNotEmpty()) {
                    Text("'$asciiVal'", style = monoStyle(11.5f.toInt(), Rose.Yellow, weight = FontWeight.Medium))
                }
                if (javaVal.isNotEmpty()) {
                    Text("\"$javaVal\"", style = monoStyle(11.5f.toInt(), Rose.Yellow, weight = FontWeight.Medium))
                }
            }
            if (resolvedAsm.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickableLongPress(
                        onClick = { Session.openSheet(Sheet.WRITE_ASM, currentItem) },
                        onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                    )
                ) {
                    Text(
                        text = resolvedAsm,
                        style = monoStyle(12, Rose.Opcode, weight = FontWeight.Medium),
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        if (ptrLabel.isNotEmpty()) {
            Text(
                text = ptrLabel,
                style = monoStyle(11, hexColor, weight = FontWeight.Bold)
            )
        } else {
            val regColor = remember(item.region, isDark) { com.littlecodesauce.ggnext.ui.theme.regionColor(item.region) }
            Text(
                text = item.region,
                style = monoStyle(11, regColor, weight = FontWeight.SemiBold)
            )
        }
    }
}
