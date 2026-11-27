package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.data.ProcessScanner
import com.littlecodesauce.ggnext.model.TargetProcess
import com.littlecodesauce.ggnext.ui.components.Chip
import com.littlecodesauce.ggnext.ui.components.GgBadge
import com.littlecodesauce.ggnext.ui.components.GgIconActionButton
import com.littlecodesauce.ggnext.ui.components.GgInputBox
import com.littlecodesauce.ggnext.ui.components.GgTextField
import com.littlecodesauce.ggnext.ui.components.ItemEnter
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.TargetProcessIcon
import com.littlecodesauce.ggnext.ui.components.VerticalFastScrollbar
import com.littlecodesauce.ggnext.ui.components.clickableNoRipple
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.ggGlassSurface
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

@Composable
fun ProcessSheet(ui: Ui) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(ui.workMode, ui.hasRootPermission) {
        (ctx.applicationContext as? android.app.Application)?.let { app ->
            Session.loadProcesses(app)
        }
    }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val list = remember(ui.processes, ui.processFilter, ui.processSortBySize, query.text) {
        val filteredByCategory = when (ui.processFilter) {
            1 -> ui.processes.filter { !it.system }
            2 -> ui.processes.filter { it.system }
            else -> ui.processes
        }
        val sorted = if (ui.processSortBySize) {
            filteredByCategory.sortedByDescending { it.sizeBytes }
        } else {
            filteredByCategory.sortedWith(
                compareBy<TargetProcess> { it.system }
                    .thenByDescending { it.sizeBytes }
                    .thenBy { it.pid }
            )
        }
        if (query.text.isBlank()) sorted
        else sorted.filter {
            it.label.contains(query.text, true) || it.packageName.contains(query.text, true) ||
                it.pid.toString().contains(query.text)
        }
    }

    val listState = rememberLazyListState()

    NextSheetCard(
        title = "进程列表",
        subtitle = "选择要附加的目标进程（显示应用图标与内存占用）",
        onDismiss = { Session.popSheet() },
        maxBodyHeight = 640
    ) {
        Column {
            GgInputBox(
                modifier = Modifier.padding(horizontal = GgSpacing.Xs, vertical = GgSpacing.Xs),
                height = GgControlHeight.Button,
                shape = GgShapes.Md,
                borderWidth = GgBorder.Thin,
                horizontalPadding = GgSpacing.Lg,
                prefix = {
                    Icon(Icons.Rounded.Search, null, tint = Rose.TextDim, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(GgSpacing.Md))
                },
                trailing = {
                    GgIconActionButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "刷新进程列表",
                        tint = Rose.TextSecond,
                        boxSize = 28.dp,
                        onClick = {
                            (ctx.applicationContext as? android.app.Application)?.let { app ->
                                Session.loadProcesses(app)
                            }
                        }
                    )
                }
            ) {
                GgTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).height(32.dp),
                    textStyle = TextStyle(color = Rose.Text, fontSize = fsp(14f)),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = false,
                    placeholder = "搜索名称 / 包名 / pid"
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    0 to "全部",
                    1 to "用户",
                    2 to "系统"
                ).forEach { (idx, label) ->
                    Chip(
                        label = label,
                        selected = ui.processFilter == idx,
                        modifier = Modifier.weight(1f)
                    ) {
                        Session.setProcessFilter(idx)
                    }
                }
                Chip(
                    label = "大小",
                    selected = ui.processSortBySize,
                    modifier = Modifier.weight(1f)
                ) {
                    Session.toggleProcessSortBySize()
                }
            }
            if (ui.processLoading) {
                SkeletonList()
            } else {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(list, key = { _, p -> p.pid }) { idx, p ->
                            val isSelected = ui.target?.pid == p.pid
                            ProcessRow(p, idx, isSelected) { Session.attach(p) }
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
}

@Composable
private fun ProcessRow(
    p: TargetProcess,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, Motion.Fast, label = "proc")
    ItemEnter(p.packageName + p.pid, index) {
        Row(
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .ggGlassSurface(
                    shape = GgShapes.Lg,
                    baseColor = if (isSelected) Rose.ChipSelected else Rose.Row,
                    borderColor = if (isSelected) Rose.Primary.copy(alpha = 0.72f) else Rose.Divider,
                    borderWidth = if (isSelected) GgBorder.Emphasis else GgBorder.Thin,
                    respectBgOpacity = true,
                    bgOpacityScale = 0.92f,
                    accentColor = Rose.Primary,
                    highlightStrength = if (isSelected) 1.15f else 0.75f
                )
                .clickableNoRipple(interaction, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TargetProcessIcon(
                process = p,
                size = 42.dp,
                shape = GgShapes.Lg,
                showActiveBorder = isSelected
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("[${p.pid}]", style = monoStyle(12, Rose.Green, weight = FontWeight.SemiBold))
                    Spacer(Modifier.width(6.dp))
                    if (p.isVirtual) {
                        Text(
                            text = "*",
                            color = Rose.VirtualBadge,
                            fontSize = fsp(15f),
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = p.label,
                        color = if (isSelected) Rose.Primary else Rose.Text,
                        fontSize = fsp(14.2f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        lineHeight = fsp(18f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (p.packageName.isNotBlank() && p.packageName != p.label) {
                    Spacer(Modifier.height(1.5.dp))
                    Text(
                        text = p.packageName,
                        color = Rose.TextDim,
                        fontSize = fsp(11f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        GgBadge(
                            text = "已选",
                            textColor = Rose.Primary,
                            backgroundColor = Rose.Primary.copy(alpha = 0.18f),
                            shape = GgShapes.Xxs,
                            fontSize = 10f,
                            fontWeight = FontWeight.SemiBold,
                            horizontalPadding = 5.dp,
                            verticalPadding = 1.dp
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text("[${if (p.x64) "x64" else "x86"}]", style = monoStyle(11, Rose.TextSecond))
                }
                Spacer(Modifier.height(2.dp))
                Text("[${ProcessScanner.sizeText(p.sizeBytes)}]", style = monoStyle(11, Rose.TextSecond))
            }
        }
    }
}

@Composable
private fun SkeletonList() {
    val transition = rememberInfiniteTransition(label = "sk")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(820), RepeatMode.Reverse),
        label = "skA"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(5) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .alpha(alpha)
                    .ggGlassSurface(
                        shape = GgShapes.Lg,
                        baseColor = Rose.Row,
                        borderWidth = GgBorder.Thin
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(42.dp).background(Rose.CardHigh, GgShapes.Lg))
                Spacer(Modifier.width(12.dp))
                Column {
                    Box(Modifier.width(140.dp).height(11.dp).background(Rose.CardHigh, GgShapes.Xxs))
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.width(84.dp).height(9.dp).background(Rose.CardHigh, GgShapes.Xxs))
                }
            }
        }
    }
}
