package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.data.ProcessScanner
import com.littlecodesauce.ggnext.ui.components.ItemEnter
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableNoRipple
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.regionColor

/**
 * 改进4：“内存类型”（选择内存范围）去除左侧的 checkbox，
 * 通过行背景色高亮与选中态边框清晰表达已选内存区域。
 */
@Composable
fun RegionSheet(ui: Ui) {
    NextSheetCard(
        title = "选择内存范围",
        subtitle = ui.target?.let { "\"${it.label}\"" } ?: "未选择进程",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 660
    ) {
        Column {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                itemsIndexed(ui.regions, key = { _, r -> r.code }) { idx, r ->
                    RegionRow(idx, r.code, r.name, r.size, ui.selectedRegions.contains(r.code))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("重置", Modifier.weight(1f)) { Session.resetRegions() }
                RoseButton("取消", Modifier.weight(1f)) { Session.closeSheet() }
                RoseButton("保存", Modifier.weight(1f), filled = true) { Session.saveRegions() }
            }
        }
    }
}

@Composable
private fun RegionRow(index: Int, code: String, name: String, size: Long, selected: Boolean) {
    // 需求 2：所有内存区域（包括 J、S、As、V 等）颜色严格使用 regionColor，拒绝死板硬编码绿色
    val color: Color = regionColor(code)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.98f else 1f, Motion.Fast, label = "rScale"
    )
    val bg by animateColorAsState(
        if (selected) color.copy(alpha = if (Rose.IsDark) 0.22f else 0.16f) else Rose.Row,
        Motion.ColorNormal,
        label = "rBg"
    )
    val borderColor by animateColorAsState(
        if (selected) color.copy(alpha = 0.72f) else Color.Transparent,
        Motion.ColorNormal,
        label = "rBorder"
    )
    ItemEnter(code, index) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(GgControlHeight.RowTall)
                .scale(scale)
                .background(bg, GgShapes.Md)
                .border(if (selected) GgBorder.Selected else 0.dp, borderColor, GgShapes.Md)
                .clickableNoRipple(interaction, onClick = { Session.toggleRegion(code) })
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$code: $name",
                color = color,
                fontSize = fsp(15f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "[${ProcessScanner.sizeText(size)}]",
                color = if (selected) color else Rose.TextSecond,
                fontSize = fsp(14f),
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}
