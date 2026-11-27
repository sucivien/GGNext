package com.littlecodesauce.ggnext.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.littlecodesauce.ggnext.engine.AsmEngineManager
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.typeColor

/**
 * 搜索列表 / 保存列表数值展示组件（参照原版 GG）：
 * - 主值始终按条目自身数据类型展示（手动修改后括号显示旧值，如 `99 (100)`）；
 * - 仅附加当前页勾选的辅助格式（h / r / S / J 与反汇编操作码），不在搜索/保存页混显多种数据类型。
 */
@Composable
fun ScaledNumber(item: MemoryItem, mask: Int) {
    val text = remember(item.value, item.previousValue, item.manuallyEdited, item.flags, mask) {
        valueTextWithMask(item, mask)
    }
    val asmArch = remember(mask) { Formatter.activeAsmArch(mask) }
    val asmText = remember(asmArch, item.address, item.value) {
        if (asmArch == null) "" else AsmEngineManager.disassembleWord(asmArch, item.address, item.value, 4)
    }
    val isDark = Rose.IsDark
    val color = remember(item.flags, isDark) { typeColor(item.flags) }
    Column {
        Text(
            text = text,
            style = monoStyle(14.5f.toInt(), color),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (asmText.isNotEmpty()) {
            Text(
                text = "${asmArch?.shortCode ?: "ASM"} $asmText",
                style = monoStyle(11.5f.toInt(), Rose.Opcode, weight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun valueText(item: MemoryItem): String = when (item.flags and 0x7F) {
    ValueType.FLOAT -> Formatter.floatText(item.value)
    ValueType.DOUBLE -> Formatter.doubleText(item.value)
    ValueType.BYTE -> Formatter.signed(item.value, 1).toString()
    ValueType.WORD -> Formatter.signed(item.value, 2).toString()
    ValueType.QWORD -> Formatter.signed(item.value, 8).toString()
    else -> Formatter.signed(item.value, 4).toString()
}

fun previousValueText(item: MemoryItem): String = when (item.flags and 0x7F) {
    ValueType.FLOAT -> Formatter.floatText(item.previousValue)
    ValueType.DOUBLE -> Formatter.doubleText(item.previousValue)
    ValueType.BYTE -> Formatter.signed(item.previousValue, 1).toString()
    ValueType.WORD -> Formatter.signed(item.previousValue, 2).toString()
    ValueType.QWORD -> Formatter.signed(item.previousValue, 8).toString()
    else -> Formatter.signed(item.previousValue, 4).toString()
}

fun valueTextWithMask(item: MemoryItem, mask: Int): String {
    val curVal = valueText(item)
    val base = if (item.manuallyEdited && item.value != item.previousValue) {
        "$curVal (${previousValueText(item)})"
    } else {
        curVal
    }
    val extras = StringBuilder()
    if (mask and Formatter.F_HEX != 0) {
        extras.append("  ").append(Formatter.hex(item.value, item.size)).append("h")
    }
    if (mask and Formatter.F_RHEX != 0) {
        extras.append("  ").append(Formatter.reverseHex(item.value, item.size)).append("r")
    }
    if (mask and Formatter.F_STRING != 0) {
        val s = Formatter.bytesToAscii(item.value, item.size)
        if (s.isNotEmpty()) extras.append("  '").append(s).append('\'')
    }
    if (mask and Formatter.F_JAVA != 0) {
        val j = Formatter.bytesToUtf16(item.value, item.size)
        if (j.isNotEmpty()) extras.append("  \"").append(j).append('"')
    }
    if (extras.isEmpty()) return base
    return base + extras.toString()
}
