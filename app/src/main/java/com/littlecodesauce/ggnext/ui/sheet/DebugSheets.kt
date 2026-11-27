package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.engine.AsmEngineManager
import com.littlecodesauce.ggnext.model.AsmArch
import com.littlecodesauce.ggnext.model.BpType
import com.littlecodesauce.ggnext.ui.components.BuiltInKeyboard
import com.littlecodesauce.ggnext.ui.components.GgBadge
import com.littlecodesauce.ggnext.ui.components.GgInputBox
import com.littlecodesauce.ggnext.ui.components.GgSegmentTab
import com.littlecodesauce.ggnext.ui.components.GgSettingSwitchRow
import com.littlecodesauce.ggnext.ui.components.GgTextField
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 添加硬件断点弹窗：
 * - 自动填充上下文/选中/当前内存地址
 * - 支持 只读 / 只写 / 读写 / 执行 四种类型，1 / 2 / 4 / 8 字节监控长度
 * - 支持 Perf (Root) 与 Ptrace (支持免 Root 同 UID) 双后端模式
 * - 支持开启/关闭调用链回溯与寄存器采集
 */
@Composable
fun BreakpointSheet(ui: Ui) {
    val initHex = remember(ui.contextItem, ui.memoryHighlightAddress, ui.memoryBase) {
        Session.defaultBreakpointAddressHex()
    }
    var addrTf by remember(initHex) {
        mutableStateOf(TextFieldValue(initHex, TextRange(0, initHex.length)))
    }
    var selectedType by remember { mutableStateOf(BpType.RW) }
    var selectedSize by remember { mutableIntStateOf(4) }
    var captureCallstack by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(true) }

    val parsedAddr = remember(addrTf.text) { parseHexAddressOrNull(addrTf.text) }

    NextSheetCard(
        title = "添加硬件断点",
        subtitle = ui.target?.let { "目标：[${it.pid}] ${it.label}" } ?: "未附加目标进程",
        onDismiss = Session::popSheet,
        maxBodyHeight = 760,
        showSideKeyboard = showKeyboard,
        sideKeyboard = {
            BuiltInKeyboard(
                value = addrTf,
                onValueChange = { addrTf = it },
                onHistory = { Session.openSheet(Sheet.HISTORY) },
                onDismiss = { showKeyboard = false }
            )
        }
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GgSpacing.Lg)
        ) {
            // 1. 断点地址输入
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(tr("断点地址 (HEX)"), color = Rose.TextSecond, fontSize = fsp(12f))
                GgInputBox(
                    height = GgControlHeight.Row,
                    shape = GgShapes.Md,
                    borderColor = if (parsedAddr != null || addrTf.text.isBlank()) Rose.Primary.copy(alpha = 0.55f) else Rose.Red,
                    prefix = {
                        Text("0x", style = monoStyle(14, Rose.TextDim))
                        Spacer(Modifier.width(GgSpacing.Xs))
                    },
                    trailing = {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clickableWithTooltip(tooltip = tr("键盘")) {
                                    showKeyboard = !showKeyboard
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Keyboard,
                                contentDescription = tr("键盘"),
                                tint = if (showKeyboard) Rose.Primary else Rose.TextSecond,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                ) {
                    GgTextField(
                        value = addrTf,
                        onValueChange = { addrTf = it },
                        modifier = Modifier.weight(1f),
                        textStyle = monoStyle(15, Rose.Text),
                        cursorColor = Rose.Primary,
                        suppressSystemKeyboard = showKeyboard,
                        placeholder = "输入十六进制地址…"
                    )
                }
            }

            // 2. 断点类型：读写 / 只读 / 只写 / 执行
            Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.Sm)) {
                Text(tr("断点类型"), color = Rose.TextSecond, fontSize = fsp(12f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
                ) {
                    listOf(BpType.RW, BpType.READ, BpType.WRITE, BpType.EXEC).forEach { bpType ->
                        GgSegmentTab(
                            label = bpType.label,
                            selected = selectedType == bpType,
                            modifier = Modifier.weight(1f),
                            height = GgControlHeight.Chip,
                            shape = GgShapes.Md,
                            horizontalPadding = GgSpacing.Xs
                        ) {
                            selectedType = bpType
                            if (bpType == BpType.EXEC && selectedSize < 4) {
                                selectedSize = 4
                            }
                        }
                    }
                }
            }

            // 3. 断点大小：1 / 2 / 4 / 8 字节
            Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.Sm)) {
                Text(tr("监视大小 (字节)"), color = Rose.TextSecond, fontSize = fsp(12f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
                ) {
                    listOf(1, 2, 4, 8).forEach { sz ->
                        val enabled = selectedType != BpType.EXEC || sz == 4
                        GgSegmentTab(
                            label = "$sz ${tr("字节")}",
                            selected = selectedSize == sz,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            height = 36.dp,
                            shape = GgShapes.Md,
                            fontSize = 12f,
                            translate = false,
                            horizontalPadding = GgSpacing.Xs
                        ) { selectedSize = sz }
                    }
                }
            }

            // 4. 采集回溯与寄存器开关（统一采用 GgSettingSwitchRow + GgSwitch）
            GgSettingSwitchRow(
                title = "采集寄存器与调用链回溯",
                subtitle = "命中断点时自动记录完整寄存器状态及栈回溯",
                checked = captureCallstack,
                onCheckedChange = { captureCallstack = it },
                containerBackground = Rose.CardHigh,
                shape = GgShapes.Md,
                horizontalPadding = GgSpacing.Lg,
                verticalPadding = GgSpacing.Md
            )

            // 5. 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                RoseButton("取消", Modifier.width(84.dp).height(GgControlHeight.Chip)) {
                    Session.popSheet()
                }
                Spacer(Modifier.width(GgSpacing.MdPlus))
                RoseButton(
                    label = "确认添加",
                    filled = true,
                    enabled = parsedAddr != null && !ui.isBatchBusy,
                    modifier = Modifier.width(104.dp).height(GgControlHeight.Chip)
                ) {
                    val addr = parsedAddr ?: return@RoseButton
                    Session.addBreakpoint(
                        address = addr,
                        type = selectedType,
                        sizeBytes = selectedSize,
                        mode = Session.breakpointBackendMode(ui),
                        captureCallstack = captureCallstack
                    )
                }
            }
        }
    }
}

/**
 * 写入汇编弹窗（Keystone 实时汇编预览 + 原子写入内存）：
 * - 支持 ARM64 (A8) / ARM (A) / Thumb (T) / x86 / x86_64
 * - 实时展示编译后的机器码十六进制与字节数，语法错误即时提示
 */
@Composable
fun WriteAsmSheet(ui: Ui) {
    val initHex = remember(ui.contextItem, ui.memoryHighlightAddress, ui.memoryBase) {
        Session.defaultBreakpointAddressHex()
    }
    var addrText by remember(initHex) { mutableStateOf(initHex) }
    val defaultArch = remember(ui.target?.x64) {
        AsmEngineManager.defaultArchForDevice(ui.target?.x64 ?: true)
    }
    var selectedArch by remember(defaultArch) { mutableStateOf(defaultArch) }
    var codeText by remember { mutableStateOf("") }
    var previewBytes by remember { mutableStateOf<ByteArray?>(null) }
    var previewError by remember { mutableStateOf("") }

    val parsedAddr = remember(addrText) { parseHexAddressOrNull(addrText) }

    LaunchedEffect(selectedArch, parsedAddr, codeText, ui.asmPack.keystoneReady) {
        val clean = codeText.trim()
        if (clean.isEmpty()) {
            previewBytes = null
            previewError = ""
            return@LaunchedEffect
        }
        val res = withContext(Dispatchers.IO) {
            AsmEngineManager.assemble(selectedArch, parsedAddr ?: 0L, clean)
        }
        if (res.isSuccess) {
            previewBytes = res.getOrNull()
            previewError = ""
        } else {
            previewBytes = null
            previewError = res.exceptionOrNull()?.message ?: "汇编失败"
        }
    }

    val quickSnippets = remember(selectedArch) {
        when (selectedArch) {
            AsmArch.ARM64 -> listOf("NOP", "RET", "MOV X0, #1", "MOV X0, #0", "MOV W0, #1\nRET")
            AsmArch.ARM -> listOf("NOP", "BX LR", "MOV R0, #1", "MOV R0, #0", "MOV R0, #1\nBX LR")
            AsmArch.THUMB -> listOf("NOP", "BX LR", "MOVS R0, #1", "MOVS R0, #0")
            AsmArch.X86 -> listOf("NOP", "RET", "MOV EAX, 1\nRET", "XOR EAX, EAX\nRET")
            AsmArch.X86_64 -> listOf("NOP", "RET", "MOV EAX, 1\nRET", "XOR RAX, RAX\nRET")
        }
    }

    NextSheetCard(
        title = "写入汇编指令",
        subtitle = "${selectedArch.displayName} · ${if (ui.asmPack.keystoneReady) "Keystone 已就绪" else "请先在主页导入汇编引擎包"}",
        onDismiss = Session::popSheet,
        maxBodyHeight = 760
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GgSpacing.MdPlus)
        ) {
            if (!ui.asmPack.keystoneReady) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(GgShapes.Md)
                        .background(Rose.Orange.copy(alpha = 0.14f))
                        .border(GgBorder.Thin, Rose.Orange.copy(alpha = 0.45f), GgShapes.Md)
                        .padding(horizontal = GgSpacing.MdPlus, vertical = GgSpacing.Md)
                ) {
                    Text(
                        text = tr("未检测到 Keystone 汇编引擎：请先返回软件主页导入 ggnext-asm-engine-pack.zip 压缩包。"),
                        color = Rose.Orange,
                        fontSize = fsp(11.5f),
                        lineHeight = fsp(16f)
                    )
                }
            }

            // 目标地址
            Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.Xs)) {
                Text(tr("写入地址 (HEX)"), color = Rose.TextSecond, fontSize = fsp(12f))
                GgInputBox(
                    height = GgControlHeight.ButtonLg,
                    shape = GgShapes.Md,
                    borderColor = if (parsedAddr != null) Rose.Divider else Rose.Red,
                    horizontalPadding = GgSpacing.MdPlus,
                    prefix = {
                        Text("0x", style = monoStyle(13, Rose.TextDim))
                        Spacer(Modifier.width(GgSpacing.Xs))
                    }
                ) {
                    BasicTextField(
                        value = addrText,
                        onValueChange = { addrText = it.uppercase().filter { ch -> ch in "0123456789ABCDEF" } },
                        singleLine = true,
                        textStyle = monoStyle(14, Rose.Text),
                        cursorBrush = SolidColor(Rose.Primary),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 架构选择
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(tr("指令集架构"), color = Rose.TextSecond, fontSize = fsp(12f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
                ) {
                    AsmArch.entries.forEach { arch ->
                        GgSegmentTab(
                            label = arch.shortCode,
                            selected = selectedArch == arch,
                            modifier = Modifier.weight(1f),
                            height = GgControlHeight.Compact,
                            shape = GgShapes.Sm,
                            fontSize = 12f,
                            monospace = true,
                            translate = false,
                            horizontalPadding = GgSpacing.Xs
                        ) { selectedArch = arch }
                    }
                }
            }

            // 快捷指令模板
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
            ) {
                quickSnippets.forEach { snippet ->
                    val label = snippet.replace('\n', ';')
                    GgBadge(
                        text = label,
                        textColor = Rose.Cyan,
                        backgroundColor = Rose.Chip,
                        borderColor = Rose.Divider,
                        shape = GgShapes.Xs,
                        fontSize = 11f,
                        monospace = true,
                        fontWeight = FontWeight.Normal,
                        horizontalPadding = 9.dp,
                        verticalPadding = 5.dp,
                        translate = false,
                        onClick = { codeText = snippet }
                    )
                }
            }

            // 多行汇编输入框
            Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.Xs)) {
                Text(tr("汇编指令（支持分号 ; 或换行分隔多条）"), color = Rose.TextSecond, fontSize = fsp(12f))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 84.dp, max = 140.dp)
                        .clip(GgShapes.Md)
                        .background(Rose.CardHigh)
                        .border(GgBorder.Default, Rose.Primary.copy(alpha = 0.5f), GgShapes.Md)
                        .padding(GgSpacing.MdPlus)
                ) {
                    if (codeText.isEmpty()) {
                        Text(
                            text = "例如：MOV X0, #1; RET",
                            style = monoStyle(13, Rose.TextDim)
                        )
                    }
                    BasicTextField(
                        value = codeText,
                        onValueChange = { codeText = it },
                        textStyle = monoStyle(14, Rose.Text),
                        cursorBrush = SolidColor(Rose.Primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 实时机器码预览区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(GgShapes.Md)
                    .background(Rose.Row)
                    .border(
                        width = GgBorder.Thin,
                        color = when {
                            previewError.isNotEmpty() -> Rose.Red.copy(alpha = 0.5f)
                            previewBytes != null -> Rose.Green.copy(alpha = 0.45f)
                            else -> Rose.Divider
                        },
                        shape = GgShapes.Md
                    )
                    .padding(horizontal = GgSpacing.MdPlus, vertical = GgSpacing.Md)
            ) {
                when {
                    previewError.isNotEmpty() -> {
                        Text(
                            text = previewError,
                            color = Rose.Red,
                            fontSize = fsp(11.5f),
                            lineHeight = fsp(16f)
                        )
                    }
                    previewBytes != null && previewBytes!!.isNotEmpty() -> {
                        val b = previewBytes!!
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "${tr("机器码预览")} (${b.size} ${tr("字节")})",
                                color = Rose.Green,
                                fontSize = fsp(11f),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = AsmEngineManager.bytesToSpacedHex(b),
                                style = monoStyle(12.5f.toInt(), Rose.Text),
                                lineHeight = fsp(17f)
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = tr("输入指令后自动生成机器码预览；亦可在编辑框中使用 ~A8 / ~A / ~T / ~X86 / ~X64 前缀直接写入"),
                            color = Rose.TextDim,
                            fontSize = fsp(11f),
                            lineHeight = fsp(15f)
                        )
                    }
                }
            }

            // 底部确认按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                RoseButton("取消", Modifier.width(84.dp).height(GgControlHeight.Chip)) {
                    Session.popSheet()
                }
                Spacer(Modifier.width(GgSpacing.MdPlus))
                RoseButton(
                    label = "写入汇编",
                    filled = true,
                    enabled = parsedAddr != null && previewBytes != null && previewBytes!!.isNotEmpty() && !ui.isBatchBusy,
                    modifier = Modifier.width(104.dp).height(GgControlHeight.Chip)
                ) {
                    val addr = parsedAddr ?: return@RoseButton
                    Session.writeAssembly(addr, selectedArch, codeText) { ok, err ->
                        if (ok) {
                            Session.popSheet()
                        } else {
                            previewError = err
                        }
                    }
                }
            }
        }
    }
}

private fun parseHexAddressOrNull(raw: String): Long? {
    val clean = raw.trim()
        .removePrefix("0x")
        .removePrefix("0X")
        .removeSuffix("h")
        .removeSuffix("H")
    if (clean.isEmpty()) return null
    return clean.toULongOrNull(16)?.toLong()
}
