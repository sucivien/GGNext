package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.model.BpCallFrame
import com.littlecodesauce.ggnext.model.BpFpRegEntry
import com.littlecodesauce.ggnext.model.BpHitGroup
import com.littlecodesauce.ggnext.model.BpRegEntry
import com.littlecodesauce.ggnext.model.BpSession
import com.littlecodesauce.ggnext.model.BpTraceEvent
import com.littlecodesauce.ggnext.model.BpType
import com.littlecodesauce.ggnext.ui.components.GgBadge
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.GgEmptyState
import com.littlecodesauce.ggnext.ui.components.GgIconActionButton
import com.littlecodesauce.ggnext.ui.components.GgInputBox
import com.littlecodesauce.ggnext.ui.components.GgModalOverlay
import com.littlecodesauce.ggnext.ui.components.GgSegmentTab
import com.littlecodesauce.ggnext.ui.components.GgSurfaceCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.expandEnter
import com.littlecodesauce.ggnext.ui.components.expandExit
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Rose

private data class EditingRegTarget(
    val bpId: Int,
    val tid: Int,
    val hitPc: Long,
    val regName: String,
    val hexValue: String,
    val isFp: Boolean,
    val numericValue: Long
)

/**
 * 硬件断点调试器主界面（NextTab.DEBUG）：
 * - 复用顶部统一 ToolBar（支持长按拖拽排序与工具自定义）
 * - 折叠式断点会话卡片，嵌套按 PC 聚合的命中指令列表
 * - 展开命中项内直接内联展示寄存器面板（点击任意寄存器可修改并写回）与调用链回溯
 */
@Composable
fun DebugScreen(ui: Ui) {
    LaunchedEffect(Unit) {
        Session.refreshBreakpointsNow()
    }

    var editingReg by remember { mutableStateOf<EditingRegTarget?>(null) }
    var savingReg by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (ui.breakpoints.isEmpty()) {
            GgEmptyState(
                icon = Icons.Rounded.BugReport,
                title = "硬件断点调试器",
                message = if (ui.attachedOk) {
                    "支持硬件断点监听，命中后自动采集寄存器与调用链回溯"
                } else {
                    "请先附加目标进程，然后从内存/搜索列表长按地址或点击上方 + 添加硬件断点"
                },
                iconInBadge = true,
                actionLabel = "添加硬件断点",
                actionEnabled = ui.attachedOk,
                onAction = { Session.openSheet(Sheet.BREAKPOINT) }
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                // 顶部视图切换：断点列表 | 实时寄存器终端（类 Frida 实时打印）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GgSpacing.Md, vertical = GgSpacing.Sm),
                    horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
                ) {
                    GgSegmentTab(
                        label = "断点列表 (${ui.breakpoints.size})",
                        selected = !ui.bpTraceEnabled,
                        modifier = Modifier.weight(1f),
                        height = 34.dp,
                        shape = GgShapes.Md,
                        accent = Rose.Primary,
                        fontSize = 12.5f
                    ) {
                        Session.setBpTraceEnabled(false)
                    }
                    GgSegmentTab(
                        label = if (ui.bpTraceLogs.isNotEmpty()) "实时寄存器 (${ui.bpTraceLogs.size})" else "实时寄存器终端",
                        selected = ui.bpTraceEnabled,
                        modifier = Modifier.weight(1f),
                        height = 34.dp,
                        shape = GgShapes.Md,
                        accent = Rose.Green,
                        fontSize = 12.5f
                    ) {
                        Session.setBpTraceEnabled(true)
                    }
                }

                if (ui.bpTraceEnabled) {
                    BpLiveTraceConsole(
                        ui = ui,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = GgSpacing.Md, end = GgSpacing.Md, top = GgSpacing.Xs, bottom = 52.dp),
                        verticalArrangement = Arrangement.spacedBy(GgSpacing.Md)
                    ) {
                        items(
                            items = ui.breakpoints,
                            key = { it.id }
                        ) { bp ->
                            // 统一 1 条与多条命中的默认层级：外层断点地址卡片默认展开显示捕获到的函数列表，
                            // 内层单个函数的详情（寄存器/调用链）默认保持折叠；且全部状态持久化在 Session 中。
                            val bpExpanded = ui.debugExpandedBps[bp.id] ?: bp.hits.isNotEmpty()
                            BreakpointSessionCard(
                                bp = bp,
                                selected = bp.id in ui.selectedBpIds,
                                expanded = bpExpanded,
                                expandedHits = ui.debugExpandedHits,
                                onToggleSelect = { Session.toggleBpSelection(bp.id) },
                                onToggleExpand = { Session.toggleDebugBpExpanded(bp.id, bpExpanded) },
                                onOpenLiveTrace = { Session.setBpTraceEnabled(true, filterBpId = bp.id) },
                                onTogglePause = { Session.toggleBreakpointPause(bp.id) },
                                onDelete = { Session.removeBreakpoint(bp.id) },
                                onToggleHit = { key -> Session.toggleDebugHitExpanded(key) },
                                onEditReg = { target -> editingReg = target }
                            )
                        }
                    }
                }
            }
        }

        GgModalOverlay(
            visible = editingReg != null,
            onDismiss = { if (!savingReg) editingReg = null }
        ) {
            val target = editingReg
            if (target != null) {
                RegisterEditCard(
                    target = target,
                    saving = savingReg,
                    onSavingChange = { savingReg = it },
                    onDismiss = {
                        savingReg = false
                        editingReg = null
                    }
                )
            }
        }
    }
}

@Composable
private fun BreakpointSessionCard(
    bp: BpSession,
    selected: Boolean,
    expanded: Boolean,
    expandedHits: Map<String, Boolean>,
    onToggleSelect: () -> Unit,
    onToggleExpand: () -> Unit,
    onOpenLiveTrace: () -> Unit,
    onTogglePause: () -> Unit,
    onDelete: () -> Unit,
    onToggleHit: (String) -> Unit,
    onEditReg: (EditingRegTarget) -> Unit
) {
    val typeAccent = bpTypeColor(bp.type)
    val statusText = when {
        bp.isActive -> tr("运行中")
        bp.paused && bp.running -> tr("已暂停")
        else -> tr("已停止")
    }
    val statusColor = when {
        bp.isActive -> Rose.Primary
        bp.paused && bp.running -> Rose.Yellow
        else -> Rose.TextDim
    }

    GgSurfaceCard(
        shape = GgShapes.Lg,
        borderColor = if (selected) Rose.Primary else Rose.Divider,
        borderWidth = if (selected) GgBorder.Emphasis else GgBorder.Thin,
        contentPadding = PaddingValues(horizontal = GgSpacing.MdPlus, vertical = 9.dp),
        respectBgOpacity = true,
        bgOpacityScale = 0.94f
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GgCheckBox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                size = 19.dp,
                accentColor = Rose.Primary
            )
            Spacer(Modifier.width(GgSpacing.Md))
            Text(
                text = "#${bp.id}",
                style = monoStyle(12, Rose.TextDim, weight = FontWeight.Medium)
            )
            Spacer(Modifier.width(GgSpacing.Md))
            Text(
                text = bp.addressHex,
                style = monoStyle(14, Rose.Text, weight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(GgSpacing.Md))
            Text(
                text = tr(bp.type.shortLabel),
                color = typeAccent,
                fontSize = fsp(12.5f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(GgSpacing.Xs))
            Text(
                text = "${bp.size}B",
                style = monoStyle(10.5f.toInt(), Rose.TextDim)
            )
            Spacer(Modifier.width(GgSpacing.Xs))
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Rose.TextSecond,
                modifier = Modifier.size(GgIconSize.Md)
            )
            Spacer(Modifier.weight(1f))
            GgIconActionButton(
                icon = Icons.Rounded.Terminal,
                contentDescription = "实时打印寄存器",
                tint = Rose.Green,
                onClick = onOpenLiveTrace
            )
            Spacer(Modifier.width(GgSpacing.Xxs))
            GgIconActionButton(
                icon = if (bp.isActive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (bp.isActive) "暂停" else "恢复",
                tint = if (bp.isActive) Rose.Text else Rose.Green,
                onClick = onTogglePause
            )
            Spacer(Modifier.width(GgSpacing.Xxs))
            GgIconActionButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "删除断点",
                tint = Rose.Red.copy(alpha = 0.85f),
                onClick = onDelete
            )
        }

        Spacer(Modifier.height(GgSpacing.Xs))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 27.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusText,
                color = statusColor,
                fontSize = fsp(11.5f),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "  ${tr("命中")} ${bp.totalHits}  ${tr("目标")} ${bp.pid}  ${tr("线程")} ${bp.attachedThreads}/${bp.totalThreads}",
                style = monoStyle(11, Rose.TextSecond),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (bp.lastError.isNotBlank()) {
            Spacer(Modifier.height(GgSpacing.Sm))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 27.dp)
                    .clip(GgShapes.Sm)
                    .background(Rose.Red.copy(alpha = 0.14f))
                    .border(GgBorder.Thin, Rose.Red.copy(alpha = 0.4f), GgShapes.Sm)
                    .padding(horizontal = GgSpacing.Md, vertical = 5.dp)
            ) {
                Text(
                    text = bp.lastError,
                    color = Rose.Red,
                    fontSize = fsp(11f),
                    lineHeight = fsp(15f)
                )
            }
        }

        AnimatedVisibility(visible = expanded, enter = expandEnter(), exit = expandExit()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = GgSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
            ) {
                if (bp.hits.isEmpty()) {
                    Text(
                        text = tr("正在监听中，等待目标进程访问此地址…"),
                        color = Rose.TextDim,
                        fontSize = fsp(11.5f),
                        modifier = Modifier.padding(start = 27.dp, top = GgSpacing.Xxs, bottom = GgSpacing.Xs)
                    )
                } else {
                    bp.hits.forEachIndexed { idx, hit ->
                        val hitKey = "${bp.id}_${hit.pc}_${hit.hitType.code}"
                        val isHitOpen = expandedHits[hitKey] ?: false
                        BpHitGroupRow(
                            bpId = bp.id,
                            hitIndex = idx + 1,
                            hit = hit,
                            expanded = isHitOpen,
                            onToggle = { onToggleHit(hitKey) },
                            onEditReg = onEditReg
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BpHitGroupRow(
    bpId: Int,
    hitIndex: Int,
    hit: BpHitGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEditReg: (EditingRegTarget) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var regTab by remember(hit.pc) { mutableIntStateOf(0) } // 0 = X (GPR), 1 = 浮点 (FP/SIMD)
    val hitTypeColor = bpTypeColor(hit.hitType)
    val regTabSelectedBg = Rose.Primary.copy(alpha = if (Rose.IsDark) 0.30f else 0.20f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GgShapes.Md)
            .background(Rose.Row.copy(alpha = 0.92f))
            .border(GgBorder.Thin, Rose.Divider.copy(alpha = 0.7f), GgShapes.Md)
            .padding(horizontal = GgSpacing.MdPlus, vertical = GgSpacing.Md)
    ) {
        // 命中汇总行：只读/只写 PC <hex> <module> 命中 <N> ▾
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr(hit.hitType.shortLabel),
                color = hitTypeColor,
                fontSize = fsp(12f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(GgSpacing.Md))
            Text(
                text = "PC ${hit.pcHex}",
                style = monoStyle(12.5f.toInt(), Rose.Text, weight = FontWeight.Medium)
            )
            Spacer(Modifier.width(GgSpacing.Md))
            Text(
                text = hit.moduleName,
                style = monoStyle(11.5f.toInt(), Rose.Primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(GgSpacing.Sm))
            Text(
                text = "${tr("命中")} ${hit.count}",
                style = monoStyle(11, Rose.TextSecond)
            )
            Spacer(Modifier.width(GgSpacing.Xs))
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Rose.TextSecond,
                modifier = Modifier.size(17.dp)
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandEnter(), exit = expandExit()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = GgSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(GgSpacing.Md)
            ) {
                // 指令反汇编与快捷跳转条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(GgShapes.Sm)
                        .background(Rose.CardHigh.copy(alpha = 0.85f))
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${tr("命中")} #$hitIndex",
                        style = monoStyle(11, Rose.TextSecond)
                    )
                    Spacer(Modifier.width(GgSpacing.Md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "PC ${hit.pcHex}",
                            style = monoStyle(11.5f.toInt(), Rose.Text)
                        )
                        if (hit.disasmText.isNotBlank()) {
                            Text(
                                text = hit.disasmText,
                                style = monoStyle(12.5f.toInt(), Rose.Primary, weight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    GgIconActionButton(
                        icon = Icons.Rounded.ContentCopy,
                        contentDescription = "复制指令信息",
                        tint = Rose.TextSecond,
                        boxSize = 28.dp,
                        iconSize = 15.dp,
                        onClick = {
                            val text = "PC 0x${hit.pcHex} (${hit.moduleName}+0x${java.lang.Long.toUnsignedString(hit.moduleOffset, 16).uppercase()}) ${hit.disasmText}"
                            clipboard.setText(AnnotatedString(text))
                            Session.toast("已复制命中指令")
                        }
                    )
                    Spacer(Modifier.width(GgSpacing.Xs))
                    GgIconActionButton(
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = "转到 PC 地址",
                        tint = Rose.Primary,
                        boxSize = 28.dp,
                        iconSize = GgIconSize.Sm,
                        onClick = {
                            Session.goto(hit.pc, recordHistory = true, highlightTarget = true)
                        }
                    )
                }

                // 精简元数据行：仅保留 PC / SP / LR
                val metaLine = buildString {
                    append("PC ").append(hit.pcHex)
                    if (hit.sp != 0L) append("   SP ").append(hit.spHex)
                    if (hit.lr != 0L) append("   LR ").append(hit.lrHex)
                }
                Text(
                    text = metaLine,
                    style = monoStyle(11, Rose.TextSecond),
                    lineHeight = fsp(16f)
                )

                // 寄存器面板（自带 X / 浮点 切换，点击任意寄存器可修改）
                if (hit.gprs.isNotEmpty() || hit.fprs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tr("寄存器"),
                            color = Rose.TextSecond,
                            fontSize = fsp(11.5f),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = tr("点击修改"),
                            color = Rose.TextDim,
                            fontSize = fsp(10.5f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)) {
                        GgSegmentTab(
                            label = "X",
                            selected = regTab == 0,
                            height = 28.dp,
                            shape = GgShapes.Sm,
                            selectedBackground = regTabSelectedBg,
                            fontSize = 11.5f,
                            translate = false,
                            horizontalPadding = GgSpacing.Lg
                        ) { regTab = 0 }
                        GgSegmentTab(
                            label = if (hit.fprs.isNotEmpty()) tr("浮点") else "${tr("浮点")} (${hit.fprs.size})",
                            selected = regTab == 1,
                            height = 28.dp,
                            shape = GgShapes.Sm,
                            selectedBackground = regTabSelectedBg,
                            fontSize = 11.5f,
                            translate = false,
                            horizontalPadding = GgSpacing.Lg
                        ) { regTab = 1 }
                    }
                    if (regTab == 0) {
                        RegisterGprGrid(
                            regs = hit.gprs,
                            onTapReg = { reg ->
                                onEditReg(
                                    EditingRegTarget(
                                        bpId = bpId,
                                        tid = hit.tid,
                                        hitPc = hit.pc,
                                        regName = reg.name,
                                        hexValue = reg.hex,
                                        isFp = false,
                                        numericValue = reg.value
                                    )
                                )
                            }
                        )
                    } else {
                        RegisterFprList(
                            fprs = hit.fprs,
                            onTapFpr = { fp ->
                                onEditReg(
                                    EditingRegTarget(
                                        bpId = bpId,
                                        tid = hit.tid,
                                        hitPc = hit.pc,
                                        regName = fp.name,
                                        hexValue = fp.hexValue,
                                        isFp = true,
                                        numericValue = 0L
                                    )
                                )
                            }
                        )
                    }
                }

                // 回溯调用链（自带在命中结果里，点击可直接跳入内存编辑器）
                if (hit.callstack.isNotEmpty()) {
                    Spacer(Modifier.height(GgSpacing.Xxs))
                    Text(
                        text = "${tr("回溯")} (${hit.callstack.size})",
                        color = Rose.TextSecond,
                        fontSize = fsp(11.5f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.Xs)) {
                        hit.callstack.forEachIndexed { fIdx, frame ->
                            CallstackFrameRow(
                                index = fIdx + 1,
                                frame = frame,
                                onJump = {
                                    Session.goto(frame.address, recordHistory = true, highlightTarget = true)
                                },
                                onCopy = {
                                    val txt = "${frame.addressHex} (${frame.moduleName}+${frame.offsetHex})"
                                    clipboard.setText(AnnotatedString(txt))
                                    Session.toast("已复制回溯帧 #${fIdx + 1}")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterGprGrid(
    regs: List<BpRegEntry>,
    onTapReg: (BpRegEntry) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        regs.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GgSpacing.Md)
            ) {
                pair.forEach { reg ->
                    val valColor = registerValueColor(reg)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(GgShapes.Xs)
                            .clickable(onClick = { onTapReg(reg) })
                            .padding(horizontal = GgSpacing.Xs, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = reg.name,
                            style = monoStyle(11, Rose.Primary.copy(alpha = 0.85f), weight = FontWeight.Medium),
                            modifier = Modifier.width(34.dp)
                        )
                        Text(
                            text = reg.hex,
                            style = monoStyle(11, valColor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RegisterEditCard(
    target: EditingRegTarget,
    saving: Boolean,
    onSavingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    // mode: 0 = HEX, 1 = DEC, 2 = F32, 3 = F64
    var inputMode by remember(target) { mutableIntStateOf(0) }
    var textValue by remember(target) { mutableStateOf(target.hexValue) }
    var errorMsg by remember(target) { mutableStateOf("") }
    val regTabSelectedBg = Rose.Primary.copy(alpha = if (Rose.IsDark) 0.30f else 0.20f)

    val formats = remember(target.isFp) {
        if (target.isFp) listOf(0 to "HEX", 2 to "F32", 3 to "F64")
        else listOf(0 to "HEX", 1 to "DEC", 2 to "F32", 3 to "F64")
    }

    fun formatValueForMode(mode: Int): String {
        val cleanHex = target.hexValue.trim().removePrefix("0x").removePrefix("0X")
        if (mode == 0) return cleanHex.uppercase()
        if (target.isFp) {
            val low16 = if (cleanHex.length >= 16) cleanHex.takeLast(16) else cleanHex.padStart(16, '0')
            val lowBits = low16.toULongOrNull(16)?.toLong() ?: 0L
            return when (mode) {
                2 -> Float.fromBits((lowBits and 0xFFFFFFFFL).toInt()).toString()
                3 -> Double.fromBits(lowBits).toString()
                else -> cleanHex.uppercase()
            }
        }
        return when (mode) {
            1 -> target.numericValue.toString()
            2 -> Float.fromBits((target.numericValue and 0xFFFFFFFFL).toInt()).toString()
            3 -> Double.fromBits(target.numericValue).toString()
            else -> cleanHex.uppercase()
        }
    }

    fun resolveHexToWrite(): String? {
        val raw = textValue.trim()
        if (raw.isEmpty()) return null
        return when (inputMode) {
            0 -> {
                val hex = raw.removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H").uppercase()
                if (hex.isEmpty() || !hex.all { it in "0123456789ABCDEF" }) return null
                if (target.isFp) {
                    if (hex.length > 32) return null
                    hex.padStart(32, '0')
                } else {
                    if (hex.length > 16) return null
                    hex
                }
            }
            1 -> {
                val v = raw.toLongOrNull() ?: raw.toULongOrNull()?.toLong() ?: return null
                java.lang.Long.toUnsignedString(v, 16).uppercase()
            }
            2 -> {
                val f = raw.toFloatOrNull() ?: return null
                val bits32 = f.toRawBits().toUInt().toString(16).uppercase().padStart(8, '0')
                if (target.isFp) {
                    val orig = target.hexValue.padStart(32, '0').take(24)
                    orig + bits32
                } else {
                    bits32
                }
            }
            3 -> {
                val d = raw.toDoubleOrNull() ?: return null
                val bits64 = d.toRawBits().toULong().toString(16).uppercase().padStart(16, '0')
                if (target.isFp) {
                    val orig = target.hexValue.padStart(32, '0').take(16)
                    orig + bits64
                } else {
                    bits64
                }
            }
            else -> null
        }
    }

    val canJump = !target.isFp && java.lang.Long.compareUnsigned(target.numericValue, 0x10000L) >= 0

    GgSurfaceCard(
        modifier = Modifier.padding(horizontal = 22.dp),
        shape = GgShapes.Xl,
        contentPadding = PaddingValues(GgSpacing.Xl)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.MdPlus)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${tr("修改寄存器")} ${target.regName}",
                    color = Rose.Text,
                    fontSize = fsp(16f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                GgBadge(
                    text = "复制",
                    textColor = Rose.TextSecond,
                    backgroundColor = Rose.CardHigh,
                    shape = GgShapes.Xs,
                    horizontalPadding = GgSpacing.Md,
                    verticalPadding = GgSpacing.Xs,
                    onClick = {
                        clipboard.setText(AnnotatedString(target.hexValue))
                        Session.toast("已复制 ${target.regName} = ${target.hexValue}")
                    }
                )
                if (canJump) {
                    Spacer(Modifier.width(GgSpacing.Sm))
                    GgBadge(
                        text = "转到地址",
                        textColor = Rose.Primary,
                        backgroundColor = Rose.Primary.copy(alpha = 0.16f),
                        shape = GgShapes.Xs,
                        horizontalPadding = GgSpacing.Md,
                        verticalPadding = GgSpacing.Xs,
                        onClick = {
                            onDismiss()
                            Session.goto(target.numericValue, recordHistory = true, highlightTarget = true)
                        }
                    )
                }
            }

            // 格式切换：HEX / DEC / F32 / F64
            Row(horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)) {
                formats.forEach { (modeId, label) ->
                    GgSegmentTab(
                        label = label,
                        selected = inputMode == modeId,
                        height = 28.dp,
                        shape = GgShapes.Sm,
                        selectedBackground = regTabSelectedBg,
                        fontSize = 11.5f,
                        translate = false,
                        horizontalPadding = GgSpacing.Lg
                    ) {
                        inputMode = modeId
                        textValue = formatValueForMode(modeId)
                        errorMsg = ""
                    }
                }
            }

            GgInputBox(
                borderColor = if (errorMsg.isEmpty()) Rose.Primary.copy(alpha = 0.55f) else Rose.Red,
                prefix = if (inputMode == 0) {
                    {
                        Text("0x", style = monoStyle(13, Rose.TextDim))
                        Spacer(Modifier.width(GgSpacing.Xs))
                    }
                } else {
                    null
                }
            ) {
                BasicTextField(
                    value = textValue,
                    onValueChange = {
                        textValue = it
                        errorMsg = ""
                    },
                    singleLine = true,
                    textStyle = monoStyle(14, Rose.Text),
                    cursorBrush = SolidColor(Rose.Primary),
                    modifier = Modifier.weight(1f)
                )
            }

            if (errorMsg.isNotEmpty()) {
                Text(
                    text = errorMsg,
                    color = Rose.Red,
                    fontSize = fsp(11.5f),
                    lineHeight = fsp(15f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GgSpacing.MdPlus)
            ) {
                RoseButton(
                    label = "取消",
                    enabled = !saving,
                    modifier = Modifier.weight(1f).height(GgControlHeight.Chip)
                ) { onDismiss() }
                RoseButton(
                    label = "写入",
                    filled = true,
                    enabled = !saving,
                    modifier = Modifier.weight(1f).height(GgControlHeight.Chip)
                ) {
                    val hexOut = resolveHexToWrite()
                    if (hexOut == null) {
                        errorMsg = tr("输入格式无效，请检查数值")
                        return@RoseButton
                    }
                    onSavingChange(true)
                    Session.modifyBreakpointRegister(
                        bpId = target.bpId,
                        tid = target.tid,
                        hitPc = target.hitPc,
                        regName = target.regName,
                        hexValue = hexOut,
                        isFp = target.isFp
                    ) { ok, err ->
                        onSavingChange(false)
                        if (ok) {
                            onDismiss()
                        } else {
                            errorMsg = err.ifBlank { tr("寄存器写回失败") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterFprList(
    fprs: List<BpFpRegEntry>,
    onTapFpr: (BpFpRegEntry) -> Unit
) {
    if (fprs.isEmpty()) {
        Text(
            text = tr("当前断点模式未采集浮点/向量寄存器（切换到 Ptrace 模式可采集完整 V0–V31 寄存器）"),
            color = Rose.TextDim,
            fontSize = fsp(11f),
            lineHeight = fsp(16f)
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        fprs.forEach { fp ->
            val isZero = fp.hexValue.all { it == '0' }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(GgShapes.Xs)
                    .clickable { onTapFpr(fp) }
                    .padding(horizontal = GgSpacing.Xs, vertical = GgSpacing.Xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fp.name,
                    style = monoStyle(11, Rose.Primary.copy(alpha = 0.85f), weight = FontWeight.Medium),
                    modifier = Modifier.width(34.dp)
                )
                Text(
                    text = fp.hexValue,
                    style = monoStyle(10.5f.toInt(), if (isZero) Rose.TextDim else Rose.Cyan),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CallstackFrameRow(
    index: Int,
    frame: BpCallFrame,
    onJump: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GgShapes.Xs)
            .background(Rose.CardHigh.copy(alpha = 0.72f))
            .clickable(onClick = onJump)
            .padding(horizontal = GgSpacing.Md, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$index",
            style = monoStyle(11, Rose.TextDim),
            modifier = Modifier.width(26.dp)
        )
        Text(
            text = frame.addressHex,
            style = monoStyle(11.5f.toInt(), Rose.PointerX, weight = FontWeight.Medium)
        )
        Spacer(Modifier.width(GgSpacing.Md))
        Text(
            text = if (frame.moduleName != "[anon]") "${frame.moduleName}+${frame.offsetHex}" else "[anon]",
            style = monoStyle(11, Rose.TextSecond),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        GgIconActionButton(
            icon = Icons.Rounded.ContentCopy,
            contentDescription = "复制回溯帧",
            tint = Rose.TextDim,
            boxSize = 24.dp,
            iconSize = 13.dp,
            shape = GgShapes.Xs,
            onClick = onCopy
        )
    }
}

private fun bpTypeColor(type: BpType): Color = when (type) {
    BpType.READ -> Rose.Yellow
    BpType.WRITE -> Rose.Red
    BpType.RW -> Rose.Violet
    BpType.EXEC -> Rose.Cyan
}

private fun registerValueColor(reg: BpRegEntry): Color {
    val n = reg.name.lowercase()
    if (reg.value == 0L) return Rose.TextDim
    if (n == "pc" || n == "lr" || n == "rip" || n == "eip") return Rose.PointerX
    if (n == "sp" || n == "fp" || n == "rsp" || n == "rbp" || n == "esp" || n == "ebp") return Rose.PointerW
    val u = reg.value
    return if (java.lang.Long.compareUnsigned(u, 0x10000000L) >= 0 &&
        java.lang.Long.compareUnsigned(u, 0x0000FFFFFFFFFFFFL) <= 0
    ) {
        Rose.PointerW
    } else {
        Rose.Text
    }
}


// ============================================================================
// 类 Frida 实时寄存器读取与打印终端 (BpLiveTraceConsole)
// - 180ms 增量拉取 C++ 环形缓冲区逐次命中事件
// - 自动对比同 PC 上一次命中的寄存器值并高亮变化项（便于一眼识别血量/金币/计数器）
// - 支持断点筛选、自定义寄存器名过滤（如 X0,X1,X8,X9）、浮点寄存器开关、暂停滚屏、复制与导出
// ============================================================================
@Composable
private fun BpLiveTraceConsole(
    ui: Ui,
    modifier: Modifier = Modifier
) {
    val clip = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var regFilterText by remember(ui.bpTraceRegFilter) { mutableStateOf(ui.bpTraceRegFilter) }

    val filterNames: Set<String> = remember(regFilterText) {
        regFilterText
            .split(',', ' ', ';', '|')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    val visibleLogs = remember(ui.bpTraceLogs, ui.bpTraceFilterBpId) {
        if (ui.bpTraceFilterBpId <= 0) ui.bpTraceLogs
        else ui.bpTraceLogs.filter { it.bpId == ui.bpTraceFilterBpId }
    }

    // 预计算每条记录相对“上一次同 PC 命中”发生变化的寄存器名集合，用于变化高亮
    val changedRegsByIndex = remember(visibleLogs) {
        val lastRegsByPc = HashMap<Long, Map<String, Long>>()
        val out = ArrayList<Set<String>>(visibleLogs.size)
        for (ev in visibleLogs) {
            val prev = lastRegsByPc[ev.pc]
            val changed = if (prev == null) {
                emptySet()
            } else {
                val diff = HashSet<String>()
                for (r in ev.gprs) {
                    val oldVal = prev[r.name]
                    if (oldVal != null && oldVal != r.value) diff.add(r.name)
                }
                diff
            }
            out.add(changed)
            val curMap = HashMap<String, Long>(ev.gprs.size * 2 + 4)
            for (r in ev.gprs) curMap[r.name] = r.value
            lastRegsByPc[ev.pc] = curMap
        }
        out
    }

    LaunchedEffect(visibleLogs.size, ui.bpTracePaused) {
        if (!ui.bpTracePaused && visibleLogs.isNotEmpty()) {
            listState.animateScrollToItem(visibleLogs.lastIndex)
        }
    }

    Column(
        modifier = modifier.padding(horizontal = GgSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
    ) {
        // 控制栏：暂停/继续 | 浮点 | 清空 | 复制全部 | 导出文件
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .background(
                        if (!ui.bpTracePaused) Rose.Green.copy(alpha = 0.18f) else Rose.CardHigh,
                        GgShapes.Sm
                    )
                    .border(
                        1.dp,
                        if (!ui.bpTracePaused) Rose.Green.copy(alpha = 0.55f) else Rose.Divider,
                        GgShapes.Sm
                    )
                    .clickable { Session.setBpTracePaused(!ui.bpTracePaused) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (ui.bpTracePaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = if (ui.bpTracePaused) Rose.Yellow else Rose.Green,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (ui.bpTracePaused) tr("已暂停") else tr("实时监听中"),
                        color = if (ui.bpTracePaused) Rose.Yellow else Rose.Green,
                        fontSize = fsp(11.5f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .height(30.dp)
                    .background(if (ui.bpTraceShowFpr) Rose.ChipSelected else Rose.CardHigh, GgShapes.Sm)
                    .clickable { Session.setBpTraceShowFpr(!ui.bpTraceShowFpr) }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "FPR",
                    style = monoStyle(11, if (ui.bpTraceShowFpr) Rose.Primary else Rose.TextSecond, weight = FontWeight.SemiBold)
                )
            }

            Spacer(Modifier.weight(1f))

            GgIconActionButton(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = "复制全部日志",
                tint = Rose.TextSecond,
                onClick = {
                    if (visibleLogs.isEmpty()) {
                        Session.toast("暂无可复制的寄存器日志")
                    } else {
                        val allText = visibleLogs.joinToString("\n") { it.formatFridaBlock(filterNames, ui.bpTraceShowFpr) }
                        clip.setText(AnnotatedString(allText))
                        Session.toast("已复制 ${visibleLogs.size} 条寄存器日志")
                    }
                }
            )
            GgIconActionButton(
                icon = Icons.Rounded.SaveAlt,
                contentDescription = "导出日志",
                tint = Rose.Primary,
                onClick = { Session.exportBpTraceLogs(filterNames) }
            )
            GgIconActionButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "清空日志",
                tint = Rose.Red.copy(alpha = 0.85f),
                onClick = { Session.clearBpTraceLogs() }
            )
        }

        // 断点筛选 + 寄存器预设筛选条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bpTabs = listOf(0 to tr("全部断点")) + ui.breakpoints.map { it.id to "BP#${it.id}" }
            bpTabs.forEach { (id, label) ->
                val sel = ui.bpTraceFilterBpId == id
                Box(
                    Modifier
                        .height(26.dp)
                        .background(if (sel) Rose.ChipSelected else Rose.CardHigh, GgShapes.Xs)
                        .clickable { Session.setBpTraceFilterBpId(id) }
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = monoStyle(11, if (sel) Rose.Primary else Rose.TextSecond, weight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    )
                }
            }
            val presets = listOf(
                "" to tr("全部寄存器"),
                "X0,X1,X2,X3,X4,X5,X6,X7" to "X0-X7",
                "X0,X1,X8,X9,X10,LR,SP" to "常用",
                "R0,R1,R2,R3,LR,SP" to "ARM32"
            )
            presets.forEach { (presetVal, label) ->
                val sel = regFilterText.equals(presetVal, ignoreCase = true)
                Box(
                    Modifier
                        .height(26.dp)
                        .background(if (sel) Rose.ChipSelected else Rose.Row, GgShapes.Xs)
                        .clickable {
                            regFilterText = presetVal
                            Session.setBpTraceRegFilter(presetVal)
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (sel) Rose.Primary else Rose.TextDim,
                        fontSize = fsp(11f)
                    )
                }
            }
        }

        // 自定义寄存器筛选输入框
        GgInputBox(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            backgroundColor = Rose.CardHigh,
            shape = GgShapes.Sm
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (regFilterText.isEmpty()) {
                    Text(
                        text = tr("筛选寄存器（逗号分隔，如 X0,X1,X8,X9,LR）..."),
                        color = Rose.TextDim,
                        fontSize = fsp(11.5f)
                    )
                }
                BasicTextField(
                    value = regFilterText,
                    onValueChange = {
                        regFilterText = it
                        Session.setBpTraceRegFilter(it)
                    },
                    singleLine = true,
                    textStyle = monoStyle(12, Rose.Text),
                    cursorBrush = SolidColor(Rose.Primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 实时终端日志流
        if (visibleLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Rose.CardHigh.copy(alpha = 0.6f), GgShapes.Lg)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tr("等待硬件断点触发… 命中后将按时间顺序实时打印寄存器快照（变化值自动高亮）"),
                    color = Rose.TextDim,
                    fontSize = fsp(12.5f)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Rose.Row.copy(alpha = 0.85f), GgShapes.Md)
                    .border(1.dp, Rose.Divider, GgShapes.Md),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(
                    items = visibleLogs,
                    key = { _, ev -> "${ev.bpId}_${ev.seq}" }
                ) { index, ev ->
                    val changedSet = changedRegsByIndex.getOrElse(index) { emptySet() }
                    val shownGprs = remember(ev.gprs, filterNames) {
                        if (filterNames.isEmpty()) ev.gprs else ev.gprs.filter { it.name.uppercase() in filterNames }
                    }
                    val shownFprs = remember(ev.fprs, filterNames, ui.bpTraceShowFpr) {
                        if (!ui.bpTraceShowFpr) emptyList()
                        else if (filterNames.isEmpty()) ev.fprs.take(8)
                        else ev.fprs.filter { it.name.uppercase() in filterNames }
                    }
                    val loc = if (ev.moduleName.isNotBlank() && ev.moduleName != "[anon]") {
                        "${ev.moduleName}+${ev.offsetHex}"
                    } else {
                        "0x${ev.pcHex}"
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Rose.CardHigh.copy(alpha = 0.72f), GgShapes.Sm)
                            .clickable {
                                val text = ev.formatFridaBlock(filterNames, ui.bpTraceShowFpr)
                                clip.setText(AnnotatedString(text))
                                Session.toast("已复制 #${ev.seq} 寄存器快照")
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "[${ev.formattedTime}] #${ev.seq} BP#${ev.bpId} T:${ev.tid}",
                                style = monoStyle(10, Rose.TextDim),
                                maxLines = 1
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = loc,
                                style = monoStyle(10.5f.toInt(), Rose.Primary, weight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (ev.disasmText.isNotBlank()) {
                            Text(
                                text = ev.disasmText,
                                style = monoStyle(11, Rose.Opcode, weight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (shownGprs.isNotEmpty()) {
                            Spacer(Modifier.height(3.dp))
                            shownGprs.chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { r ->
                                        val changed = r.name in changedSet
                                        val regColor = if (changed) Rose.Green else Rose.Text
                                        Text(
                                            text = "${r.name.padEnd(4)}=${r.hex}",
                                            style = monoStyle(
                                                10.5f.toInt(),
                                                regColor,
                                                weight = if (changed) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        if (shownFprs.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            shownFprs.forEach { fp ->
                                Text(
                                    text = "${fp.name.padEnd(4)}=${fp.hexValue}",
                                    style = monoStyle(10, Rose.TextSecond),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(44.dp))
    }
}
