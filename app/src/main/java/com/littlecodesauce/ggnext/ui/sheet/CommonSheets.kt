package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.FileBrowserTarget
import com.littlecodesauce.ggnext.core.FileSheetMode
import com.littlecodesauce.ggnext.core.NextTab
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.components.BuiltInKeyboard
import com.littlecodesauce.ggnext.ui.components.GgBadge
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.GgIcons
import com.littlecodesauce.ggnext.ui.components.GgSegmentTab
import com.littlecodesauce.ggnext.ui.components.GgSurfaceCard
import com.littlecodesauce.ggnext.ui.components.GgTextField
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableNoRipple
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.expandEnter
import com.littlecodesauce.ggnext.ui.components.expandExit
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.components.valueText
import com.littlecodesauce.ggnext.ui.model.ToolId
import com.littlecodesauce.ggnext.ui.model.toolIcon
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import com.littlecodesauce.ggnext.engine.Shell
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.typeColor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// 单地址编辑 vs 批量修改功能页
// - 界面调整 12：数据类型旁的三角箭头未展开时朝右（▸），展开时朝下（▾）
// ============================================================
@Composable
fun EditSheet(ui: Ui) {
    val targets = remember(ui.editing, ui.editSingleOnly, ui.results, ui.saved, ui.memoryRows, ui.tab) {
        Session.itemsToEdit()
    }
    val firstItem = targets.firstOrNull() ?: ui.editing
    if (firstItem == null) {
        Session.closeSheet()
        return
    }

    val isSingleEdit = ui.editSingleOnly || (targets.size == 1 && ui.selectedCount <= 1)

    var selectedType by remember(firstItem) {
        mutableIntStateOf(firstItem.flags and 0x7F)
    }
    var showTypeDropdown by remember { mutableStateOf(false) }

    val initialValueText = remember(firstItem, targets.size) {
        when (selectedType) {
            ValueType.FLOAT -> Formatter.floatText(firstItem.value)
            ValueType.DOUBLE -> Formatter.doubleText(firstItem.value)
            else -> Formatter.signed(firstItem.value, ValueType.sizeOf(selectedType)).toString()
        }
    }

    // 编辑地址数值时，仅默认主数值输入栏为全选状态，其余输入框不进行全选
    var valueTf by remember(firstItem) {
        mutableStateOf(TextFieldValue(initialValueText, TextRange(0, initialValueText.length)))
    }
    var stepTf by remember {
        mutableStateOf(TextFieldValue("", TextRange(0)))
    }
    var noteTf by remember(firstItem) {
        mutableStateOf(TextFieldValue(firstItem.note, TextRange(firstItem.note.length)))
    }
    var moreExpanded by remember { mutableStateOf(false) }
    var activeFillIndex by remember { mutableIntStateOf(-1) }
    var addNotReplace by remember { mutableStateOf(false) }
    var freezeItems by remember(firstItem) { mutableStateOf(firstItem.freeze) }
    var freezeType by remember(firstItem) { mutableIntStateOf(firstItem.freezeType) }
    var freezeFromTf by remember(firstItem) {
        val initFrom = if (firstItem.freezeType == 3) {
            when (selectedType) {
                ValueType.FLOAT -> Formatter.floatText(firstItem.freezeFrom)
                ValueType.DOUBLE -> Formatter.doubleText(firstItem.freezeFrom)
                else -> Formatter.signed(firstItem.freezeFrom, ValueType.sizeOf(selectedType)).toString()
            }
        } else ""
        mutableStateOf(TextFieldValue(initFrom, TextRange(initFrom.length)))
    }
    var freezeToTf by remember(firstItem) {
        val initTo = if (firstItem.freezeType == 3) {
            when (selectedType) {
                ValueType.FLOAT -> Formatter.floatText(firstItem.freezeTo)
                ValueType.DOUBLE -> Formatter.doubleText(firstItem.freezeTo)
                else -> Formatter.signed(firstItem.freezeTo, ValueType.sizeOf(selectedType)).toString()
            }
        } else ""
        mutableStateOf(TextFieldValue(initTo, TextRange(initTo.length)))
    }

    // 0=备注(系统键盘), 1=主数值, 2=步进, 3=范围下限, 4=范围上限
    var keyboardTarget by remember { mutableIntStateOf(1) }
    var showBuiltInKeyboard by remember { mutableStateOf(true) }

    val currentTypeColor = typeColor(selectedType)
    val typeRangeText = remember(selectedType) {
        ValueType.rangeText(selectedType)
    }
    val typeArrowRot by animateFloatAsState(
        targetValue = if (showTypeDropdown) 0f else -90f,
        animationSpec = Motion.Fast,
        label = "editTypeArrow"
    )

    fun applyFillPreset(presetIdx: Int) {
        activeFillIndex = presetIdx
        val filled = buildFillString(targets, selectedType, presetIdx)
        valueTf = TextFieldValue(filled, TextRange(filled.length))
    }

    NextSheetCard(
        title = if (isSingleEdit) "单项修改" else "批量修改",
        subtitle = if (isSingleEdit) {
            firstItem.cachedAddressHex
        } else {
            "修改选中 ${targets.size}"
        },
        onDismiss = Session::closeSheet,
        maxBodyHeight = 760,
        showSideKeyboard = showBuiltInKeyboard && keyboardTarget != 0,
        sideKeyboard = {
            val activeKeyboardTf = when (keyboardTarget) {
                2 -> stepTf
                3 -> freezeFromTf
                4 -> freezeToTf
                else -> valueTf
            }
            BuiltInKeyboard(
                value = activeKeyboardTf,
                onValueChange = { next ->
                    when (keyboardTarget) {
                        2 -> stepTf = next
                        3 -> freezeFromTf = next
                        4 -> freezeToTf = next
                        else -> valueTf = next
                    }
                },
                onHistory = { Session.openSheet(Sheet.HISTORY) },
                onDismiss = { showBuiltInKeyboard = false }
            )
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = typeRangeText,
                style = monoStyle(12, currentTypeColor),
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
            )

            // 主数值输入框
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Rose.CardHigh, GgShapes.Lg)
                    .border(1.dp, Rose.Divider, GgShapes.Lg)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .background(Rose.Chip, GgShapes.Sm)
                        .clickableWithTooltip {
                            showTypeDropdown = !showTypeDropdown
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ValueType.shortName(selectedType),
                        color = currentTypeColor,
                        fontSize = fsp(16f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = "选择类型",
                        tint = currentTypeColor,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = typeArrowRot }
                    )
                }
                Spacer(Modifier.width(10.dp))
                GgTextField(
                    value = valueTf,
                    onValueChange = {
                        keyboardTarget = 1
                        valueTf = it
                        activeFillIndex = -1
                    },
                    onFocus = { keyboardTarget = 1 },
                    initialFocus = true,
                    modifier = Modifier.weight(1f).height(40.dp),
                    textStyle = monoStyle(16, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showBuiltInKeyboard,
                    placeholder = "输入修改值…"
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .clickableWithTooltip(tooltip = tr("键盘")) {
                            if (keyboardTarget == 1) {
                                showBuiltInKeyboard = !showBuiltInKeyboard
                            } else {
                                keyboardTarget = 1
                                showBuiltInKeyboard = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = tr("键盘"),
                        tint = if (showBuiltInKeyboard && keyboardTarget == 1) Rose.Primary else Rose.TextSecond,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            // 原版 GG 内联汇编实时预览（支持 ~A8 / ~A / ~T / ~X86 / ~X64 前缀）
            val inlineAsmParsed = remember(valueTf.text) {
                com.littlecodesauce.ggnext.engine.AsmEngineManager.parseGgAsmPrefix(valueTf.text)
            }
            if (inlineAsmParsed != null) {
                val (asmArch, asmBody) = inlineAsmParsed
                var asmHex by remember { mutableStateOf("") }
                var asmErr by remember { mutableStateOf("") }
                LaunchedEffect(asmArch, asmBody, firstItem.address) {
                    if (asmBody.isBlank()) {
                        asmHex = ""
                        asmErr = "请输入 ${asmArch.shortCode} 指令，例如 ${asmArch.ggPrefix} NOP"
                    } else {
                        val res = withContext(Dispatchers.IO) {
                            com.littlecodesauce.ggnext.engine.AsmEngineManager.assemble(asmArch, firstItem.address, asmBody)
                        }
                        if (res.isSuccess) {
                            val b = res.getOrThrow()
                            asmHex = "${com.littlecodesauce.ggnext.engine.AsmEngineManager.bytesToSpacedHex(b)} (${b.size}B)"
                            asmErr = ""
                        } else {
                            asmHex = ""
                            asmErr = res.exceptionOrNull()?.message ?: "汇编失败"
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (asmErr.isEmpty()) Rose.Green.copy(alpha = 0.12f) else Rose.Red.copy(alpha = 0.12f),
                            GgShapes.Sm
                        )
                        .border(
                            0.8.dp,
                            if (asmErr.isEmpty()) Rose.Green.copy(alpha = 0.45f) else Rose.Red.copy(alpha = 0.45f),
                            GgShapes.Sm
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (asmErr.isEmpty()) "${asmArch.shortCode} 机器码：$asmHex" else asmErr,
                        style = monoStyle(11.5f.toInt(), if (asmErr.isEmpty()) Rose.Green else Rose.Red),
                        maxLines = 2
                    )
                }
            }

            // 类型下拉选择面板
            AnimatedVisibility(showTypeDropdown, enter = expandEnter(), exit = expandExit()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ValueType.order.forEach { t ->
                        val on = selectedType == t
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(
                                    if (on) Rose.ChipSelected else Rose.CardHigh,
                                    GgShapes.Sm
                                )
                                .border(
                                    width = if (on) 1.dp else 0.dp,
                                    color = if (on) typeColor(t) else Color.Transparent,
                                    shape = GgShapes.Sm
                                )
                                .clickableWithTooltip {
                                    selectedType = t
                                    showTypeDropdown = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                ValueType.shortName(t),
                                color = typeColor(t),
                                fontSize = fsp(14f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (isSingleEdit) {
                Spacer(Modifier.height(10.dp))
                FieldRow(
                    label = "备注",
                    value = noteTf,
                    suppressSystemKeyboard = false,
                    placeholder = "描述（可选）",
                    onFocus = { keyboardTarget = 0 }
                ) {
                    keyboardTarget = 0
                    noteTf = it
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .clickableWithTooltip {
                            freezeItems = !freezeItems
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GgCheckBox(
                        checked = freezeItems,
                        onCheckedChange = { freezeItems = it },
                        size = 20.dp,
                        accentColor = Rose.Primary
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(tr("冻结并保存"), color = Rose.Text, fontSize = fsp(13.5f))
                }

                Row(
                    Modifier
                        .clickableWithTooltip {
                            addNotReplace = !addNotReplace
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GgCheckBox(
                        checked = addNotReplace,
                        onCheckedChange = { addNotReplace = it },
                        size = 20.dp,
                        accentColor = Rose.Primary
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(tr("加到值内"), color = Rose.Text, fontSize = fsp(13.5f))
                }
            }

            // 4 种冻结模式：0=正常冻结, 1=递增(允许增大), 2=递减(允许减小), 3=范围钳制
            AnimatedVisibility(freezeItems, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            0 to "正常冻结",
                            1 to "允许增大",
                            2 to "允许减小",
                            3 to "范围限制"
                        ).forEach { (mode, label) ->
                            val on = freezeType == mode
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .background(
                                        if (on) Rose.ChipSelected else Rose.CardHigh,
                                        GgShapes.Sm
                                    )
                                    .border(
                                        width = if (on) 1.dp else 1.dp,
                                        color = if (on) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableWithTooltip { freezeType = mode },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    tr(label),
                                    color = if (on) Rose.Primary else Rose.TextSecond,
                                    fontSize = fsp(11.5f),
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    AnimatedVisibility(freezeType == 3, enter = expandEnter(), exit = expandExit()) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.weight(1f)) {
                                FieldRow(
                                    label = "从",
                                    value = freezeFromTf,
                                    suppressSystemKeyboard = showBuiltInKeyboard,
                                    placeholder = "最小值",
                                    onFocus = { keyboardTarget = 3 }
                                ) {
                                    keyboardTarget = 3
                                    freezeFromTf = it
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                FieldRow(
                                    label = "到",
                                    value = freezeToTf,
                                    suppressSystemKeyboard = showBuiltInKeyboard,
                                    placeholder = "最大值",
                                    onFocus = { keyboardTarget = 4 }
                                ) {
                                    keyboardTarget = 4
                                    freezeToTf = it
                                }
                            }
                        }
                    }
                }
            }

            // “更多”展开区域
            AnimatedVisibility(moreExpanded, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))

                    if (!isSingleEdit) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .background(Rose.CardHigh, GgShapes.Lg)
                                .border(1.dp, Rose.Divider, GgShapes.Lg)
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GgTextField(
                                value = stepTf,
                                onValueChange = {
                                    keyboardTarget = 2
                                    stepTf = it
                                },
                                onFocus = { keyboardTarget = 2 },
                                modifier = Modifier.weight(1f).height(40.dp),
                                textStyle = monoStyle(15, Rose.Text),
                                cursorColor = Rose.Primary,
                                suppressSystemKeyboard = showBuiltInKeyboard,
                                placeholder = "可选增量步进值"
                            )
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clickableWithTooltip(tooltip = tr("键盘")) {
                                        if (keyboardTarget == 2) {
                                            showBuiltInKeyboard = !showBuiltInKeyboard
                                        } else {
                                            keyboardTarget = 2
                                            showBuiltInKeyboard = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Keyboard,
                                    contentDescription = tr("键盘"),
                                    tint = if (showBuiltInKeyboard && keyboardTarget == 2) Rose.Primary else Rose.TextSecond,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GgSegmentTab(
                            label = "值列表",
                            selected = activeFillIndex == 0,
                            modifier = Modifier.weight(1.1f),
                            height = GgControlHeight.Chip,
                            shape = GgShapes.Sm,
                            accent = Rose.Text,
                            selectedBackground = Rose.ChipSelected,
                            unselectedBackground = Rose.Chip,
                            fontSize = 13f,
                            horizontalPadding = 12.dp
                        ) { applyFillPreset(0) }
                        GgSegmentTab(
                            label = "UTF-8",
                            selected = activeFillIndex == 1,
                            modifier = Modifier.weight(1f),
                            height = GgControlHeight.Chip,
                            shape = GgShapes.Sm,
                            accent = Rose.Text,
                            selectedBackground = Rose.ChipSelected,
                            unselectedBackground = Rose.Chip,
                            fontSize = 13f,
                            horizontalPadding = 12.dp
                        ) { applyFillPreset(1) }
                        GgSegmentTab(
                            label = "UTF-16LE",
                            selected = activeFillIndex == 2,
                            modifier = Modifier.weight(1.2f),
                            height = GgControlHeight.Chip,
                            shape = GgShapes.Sm,
                            accent = Rose.Text,
                            selectedBackground = Rose.ChipSelected,
                            unselectedBackground = Rose.Chip,
                            fontSize = 13f,
                            horizontalPadding = 12.dp
                        ) { applyFillPreset(2) }
                        GgSegmentTab(
                            label = "HEX",
                            selected = activeFillIndex == 3,
                            modifier = Modifier.weight(0.9f),
                            height = GgControlHeight.Chip,
                            shape = GgShapes.Sm,
                            accent = Rose.Text,
                            selectedBackground = Rose.ChipSelected,
                            unselectedBackground = Rose.Chip,
                            fontSize = 13f,
                            horizontalPadding = 12.dp
                        ) { applyFillPreset(3) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GgSegmentTab(
                            label = "HEX + UTF-8",
                            selected = activeFillIndex == 4,
                            height = GgControlHeight.Chip,
                            shape = GgShapes.Sm,
                            accent = Rose.Text,
                            selectedBackground = Rose.ChipSelected,
                            unselectedBackground = Rose.Chip,
                            fontSize = 13f,
                            horizontalPadding = 12.dp
                        ) { applyFillPreset(4) }
                        GgSegmentTab(
                            label = "HEX + UTF-16LE",
                            selected = activeFillIndex == 5,
                            height = GgControlHeight.Chip,
                            shape = GgShapes.Sm,
                            accent = Rose.Text,
                            selectedBackground = Rose.ChipSelected,
                            unselectedBackground = Rose.Chip,
                            fontSize = 13f,
                            horizontalPadding = 12.dp
                        ) { applyFillPreset(5) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GgSegmentTab(
                            label = "HEX + UTF-8 + UTF-16LE",
                            selected = activeFillIndex == 6,
                            height = GgControlHeight.Chip,
                            shape = GgShapes.Sm,
                            accent = Rose.Text,
                            selectedBackground = Rose.ChipSelected,
                            unselectedBackground = Rose.Chip,
                            fontSize = 13f,
                            horizontalPadding = 12.dp
                        ) { applyFillPreset(6) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSingleEdit) {
                    RoseButton(
                        label = "恢复",
                        modifier = Modifier.width(68.dp).height(38.dp),
                        filled = false
                    ) {
                        val origStr = when (selectedType and 0x7F) {
                            ValueType.FLOAT -> Formatter.floatText(firstItem.previousValue)
                            ValueType.DOUBLE -> Formatter.doubleText(firstItem.previousValue)
                            else -> Formatter.signed(firstItem.previousValue, ValueType.sizeOf(selectedType)).toString()
                        }
                        valueTf = TextFieldValue(origStr, TextRange(origStr.length))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                RoseButton(
                    label = if (moreExpanded) "收起" else "更多",
                    modifier = Modifier.width(68.dp).height(38.dp),
                    filled = false
                ) {
                    moreExpanded = !moreExpanded
                }
                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = if (ui.isBatchBusy) "处理中" else "修改",
                    modifier = Modifier.width(72.dp).height(38.dp),
                    filled = true,
                    enabled = !ui.isBatchBusy
                ) {
                    Session.batchEdit(
                        targets = if (isSingleEdit) listOf(firstItem) else targets,
                        rawInput = valueTf.text,
                        targetType = selectedType,
                        rawStep = if (moreExpanded && !isSingleEdit) stepTf.text else "",
                        addNotReplace = addNotReplace,
                        freeze = freezeItems,
                        freezeType = freezeType,
                        freezeFromRaw = if (freezeItems && freezeType == 3) freezeFromTf.text else "",
                        freezeToRaw = if (freezeItems && freezeType == 3) freezeToTf.text else "",
                        customNote = if (isSingleEdit) noteTf.text.trim() else null,
                        onComplete = { ok -> if (ok) Session.popSheet() }
                    )
                }
            }
        }
    }
}

private fun buildFillString(
    items: List<MemoryItem>,
    type: Int,
    presetIdx: Int
): String {
    if (items.isEmpty()) return ""
    val slice = items.take(32)
    return when (presetIdx) {
        0 -> slice.joinToString(";") { valueText(it) }
        1 -> {
            val bytes = slice.map { (it.value and 0xFF).toByte() }.toByteArray()
            val clean = bytes.map { b -> if (b in 32..126) b.toInt().toChar() else 'd' }.joinToString("")
            ":$clean"
        }
        2 -> {
            val chars = slice.map {
                val c = (it.value and 0xFFFF).toInt()
                if (c in 32..126) c.toChar() else 'd'
            }.joinToString("")
            ";${chars.take(16).ifEmpty { "d" }}"
        }
        3 -> {
            "h " + slice.joinToString(" ") { String.format("%02X", it.value and 0xFF) }
        }
        4 -> {
            val hex = slice.take(8).joinToString(" ") { String.format("%02X", it.value and 0xFF) }
            val ascii = slice.take(8).map {
                val b = (it.value and 0xFF).toInt()
                if (b in 32..126) b.toChar() else '.'
            }.joinToString("")
            "Q $hex '$ascii'"
        }
        5 -> {
            val hex = slice.take(8).joinToString(" ") { String.format("%02X", it.value and 0xFF) }
            val u16 = slice.take(8).map {
                val b = (it.value and 0xFF).toInt()
                if (b in 32..126) b.toChar() else '.'
            }.joinToString("")
            "Q $hex \"$u16\""
        }
        else -> {
            val hex = slice.take(6).joinToString(" ") { String.format("%02X", it.value and 0xFF) }
            "Q $hex"
        }
    }
}

// ============================================================
// 值列表（长按地址条目上下文菜单），全部工具图标严格与工具栏 toolIcon(ToolId.*) 保持一致
// ============================================================
@Composable
fun ContextSheet(ui: Ui) {
    val item = ui.contextItem ?: return
    val clip = LocalClipboardManager.current
    val addr = item.cachedAddressHex
    val value = valueText(item)
    val hex = Formatter.hex(item.value, item.size)
    val rhex = hex.chunked(2).reversed().joinToString("")

    NextSheetCard(title = "值列表 [$addr]", onDismiss = Session::closeSheet, maxBodyHeight = 640) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MenuAction(toolIcon(ToolId.EDIT), "编辑此地址数值 ($value)") {
                Session.openSheet(Sheet.EDITOR, item, singleEdit = true)
            }
            MenuAction(toolIcon(ToolId.BREAKPOINT), "设置硬件断点") {
                Session.openSheet(Sheet.BREAKPOINT, item)
            }
            MenuAction(toolIcon(ToolId.WRITE_ASM), "写入汇编指令") {
                Session.openSheet(Sheet.WRITE_ASM, item)
            }
            MenuAction(toolIcon(ToolId.OFFSET), "偏移量计算器") {
                Session.openSheet(Sheet.OFFSET, item)
            }
            MenuAction(toolIcon(ToolId.CALC_OFFSET), "计算偏移、异或") {
                Session.openSheet(Sheet.CALC_OFFSET, item)
            }
            MenuAction(toolIcon(ToolId.POINTER), "指针搜索") {
                Session.openSheet(Sheet.POINTER_SEARCH, item)
            }
            MenuAction(toolIcon(ToolId.SEARCH), "附近") {
                Session.updateSearch { it.copy(rangeMode = 2, nearbyAddress = addr) }
                Session.openSheet(Sheet.SEARCH, item)
            }
            MenuAction(toolIcon(ToolId.GOTO), "转到此地址：$addr") {
                Session.goto(item.address, recordHistory = true, highlightTarget = true)
            }
            val dPtr = item.value and 0xFFFFFFFFL
            val dPtrHex = Formatter.hex(dPtr, 4)
            val qPtrRaw by produceState<Long>(initialValue = item.value, key1 = item.address) {
                this.value = Session.readQwordAt(item.address)
            }
            val qPtrClean = qPtrRaw and 0x00FFFFFFFFFFFFFFL
            val qPtrHex = java.lang.Long.toUnsignedString(qPtrClean, 16).uppercase()
            MenuAction(toolIcon(ToolId.GOTO), "跳转到 D 指针：$dPtrHex") {
                Session.goto(dPtr, recordHistory = true, highlightTarget = true, sourceAddress = item.address)
            }
            MenuAction(toolIcon(ToolId.GOTO), "跳转到 Q 指针：$qPtrHex") {
                Session.goto(qPtrClean, recordHistory = true, highlightTarget = true, sourceAddress = item.address)
            }
            MenuAction(toolIcon(ToolId.ADDRESS), "更改地址 / 类型") {
                Session.openSheet(Sheet.ADDRESS_EDIT, item)
            }
            MenuAction(Icons.Rounded.ContentCopy, "复制地址：$addr") {
                clip.setText(AnnotatedString(addr)); Session.toast("已复制地址 $addr"); Session.closeSheet()
            }
            MenuAction(Icons.Rounded.ContentCopy, "复制十进制值：$value") {
                clip.setText(AnnotatedString(value)); Session.toast("已复制值 $value"); Session.closeSheet()
            }
            MenuAction(Icons.Rounded.Code, "复制十六进制：$hex") {
                clip.setText(AnnotatedString(hex)); Session.toast("已复制 HEX $hex"); Session.closeSheet()
            }
            MenuAction(Icons.Rounded.Code, "复制反向十六进制：$rhex") {
                clip.setText(AnnotatedString(rhex)); Session.toast("已复制反向 HEX $rhex"); Session.closeSheet()
            }
        }
    }
}

@Composable
fun MenuAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(
                if (pressed) Rose.CardHigh else Rose.Row,
                GgShapes.Lg
            )
            .clickableNoRipple(interaction, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Rose.Primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(tr(label), color = Rose.Text, fontSize = fsp(14f))
    }
}

// ============================================================
// 类型选择（界面调整 11：不显示数值范围）
// ============================================================
@Composable
fun TypePickSheet(ui: Ui) {
    val checked = remember(ui.tab, ui.results, ui.saved, ui.memoryRows, ui.selectedCount) {
        Session.checkedItems()
    }
    val item = ui.editing ?: ui.contextItem
    val isBatch = ui.editing == null && ui.contextItem == null && checked.size > 1
    val targets = when {
        isBatch -> checked
        item != null -> listOf(item)
        checked.isNotEmpty() -> checked
        else -> emptyList()
    }
    val commonType = targets.firstOrNull()?.let { first ->
        val ft = first.flags and 0x7F
        if (targets.all { (it.flags and 0x7F) == ft }) ft else 0
    } ?: 0

    NextSheetCard(
        title = if (targets.size > 1) "批量更改类型（已选 ${targets.size} 项）" else "选择数据类型",
        subtitle = if (targets.size > 1) "将按新类型重读数值并同步已冻结锁值" else null,
        onDismiss = Session::popSheet
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ValueType.order.forEach { t ->
                val on = commonType == t
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(if (on) Rose.ChipSelected else Rose.CardHigh, GgShapes.Sm)
                        .clickableWithTooltip {
                            if (targets.size > 1) {
                                Session.batchChangeType(targets, t)
                            } else if (targets.size == 1) {
                                Session.changeType(targets.first(), t)
                            }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        ValueType.shortName(t),
                        color = typeColor(t),
                        fontSize = fsp(15f),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(30.dp)
                    )
                    Text(ValueType.longName(t), color = typeColor(t), fontSize = fsp(14f))
                }
            }
        }
    }
}

// ============================================================
// 更改地址 / 类型（支持单选改地址+类型，或多选批量改类型）
// ============================================================
@Composable
fun AddressEditSheet(ui: Ui) {
    val checked = remember(ui.tab, ui.results, ui.saved, ui.memoryRows, ui.selectedCount) {
        Session.checkedItems()
    }
    val item = ui.editing ?: ui.contextItem ?: checked.firstOrNull()
    val isBatchTypeMode = checked.size > 1 && ui.contextItem == null && ui.editing == null
    val initAddr = item?.cachedAddressHex
        ?: java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
    var addr by remember {
        mutableStateOf(TextFieldValue(initAddr, TextRange(0, initAddr.length)))
    }
    var showKeyboard by remember { mutableStateOf(!isBatchTypeMode) }
    NextSheetCard(
        title = if (isBatchTypeMode) "批量更改类型（已选 ${checked.size} 项）" else "更改地址 / 类型",
        subtitle = if (isBatchTypeMode) "多选状态下可一键统一变更所有已选地址的数据类型" else null,
        onDismiss = Session::popSheet,
        showSideKeyboard = !isBatchTypeMode && showKeyboard,
        sideKeyboard = {
            BuiltInKeyboard(
                value = addr,
                onValueChange = { addr = it },
                onHistory = { Session.openSheet(Sheet.HISTORY) },
                onDismiss = { showKeyboard = false }
            )
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (!isBatchTypeMode) {
                FieldRow("地址", addr, suppressSystemKeyboard = showKeyboard) { addr = it }
                Spacer(Modifier.height(10.dp))
            }
            Text(tr("选择新类型"), color = Rose.TextSecond, fontSize = fsp(12f))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ValueType.order.take(4).forEach { t ->
                    GgBadge(
                        text = ValueType.shortName(t),
                        modifier = Modifier.height(36.dp).width(46.dp),
                        textColor = typeColor(t),
                        backgroundColor = Rose.CardHigh,
                        shape = GgShapes.Sm,
                        fontSize = 14f,
                        fontWeight = FontWeight.Medium,
                        horizontalPadding = 0.dp,
                        verticalPadding = 0.dp,
                        translate = false
                    ) {
                        if (isBatchTypeMode) {
                            Session.batchChangeType(checked, t)
                        } else if (item != null) {
                            Session.changeType(item, t)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ValueType.order.drop(4).forEach { t ->
                    GgBadge(
                        text = ValueType.shortName(t),
                        modifier = Modifier.height(36.dp).width(46.dp),
                        textColor = typeColor(t),
                        backgroundColor = Rose.CardHigh,
                        shape = GgShapes.Sm,
                        fontSize = 14f,
                        fontWeight = FontWeight.Medium,
                        horizontalPadding = 0.dp,
                        verticalPadding = 0.dp,
                        translate = false
                    ) {
                        if (isBatchTypeMode) {
                            Session.batchChangeType(checked, t)
                        } else if (item != null) {
                            Session.changeType(item, t)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!isBatchTypeMode) {
                RoseButton("确定", Modifier.fillMaxWidth(), filled = true) {
                    val parsedAddr = addr.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong()
                    if (item != null && parsedAddr != null && parsedAddr != item.address) {
                        Session.changeAddress(item, parsedAddr, item.flags)
                    } else {
                        Session.popSheet()
                    }
                }
            } else {
                RoseButton("取消", Modifier.fillMaxWidth()) { Session.popSheet() }
            }
        }
    }
}

@Composable
fun FieldRow(
    label: String,
    value: TextFieldValue,
    suppressSystemKeyboard: Boolean = false,
    placeholder: String = "",
    onFocus: () -> Unit = {},
    onChange: (TextFieldValue) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Rose.CardHigh, GgShapes.Md)
            .border(1.dp, Rose.Divider, GgShapes.Md)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr(label), color = Rose.TextSecond, fontSize = fsp(13f))
        Spacer(Modifier.width(10.dp))
        GgTextField(
            value = value,
            onValueChange = onChange,
            onFocus = onFocus,
            modifier = Modifier.weight(1f).height(36.dp),
            textStyle = monoStyle(15, Rose.Text),
            cursorColor = Rose.Primary,
            suppressSystemKeyboard = suppressSystemKeyboard,
            placeholder = placeholder
        )
    }
}

// ============================================================
// 数值格式
// ============================================================
@Composable
fun FormatSheet(ui: Ui) {
    val targetTab = when (ui.tab) {
        NextTab.SAVED_LIST -> NextTab.SAVED_LIST
        NextTab.MEMORY -> NextTab.MEMORY
        else -> NextTab.SEARCH_LIST
    }
    val isMemory = targetTab == NextTab.MEMORY
    val formatItems = if (isMemory) Formatter.items else Formatter.auxItems
    val initialMask = if (isMemory) ui.valueMaskMemory else (ui.maskForTab(targetTab) and Formatter.AUX_FORMAT_MASK)
    var mask by remember(targetTab) { mutableIntStateOf(initialMask) }
    val subtitleText = when (targetTab) {
        NextTab.SAVED_LIST -> "已保存列表显示格式"
        NextTab.MEMORY -> "内存页显示格式"
        else -> "搜索列表显示格式"
    }
    NextSheetCard(
        title = "数值格式",
        subtitle = subtitleText,
        onDismiss = Session::closeSheet
    ) {
        Column {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                if (isMemory) {
                    formatItems.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { it2 ->
                                val on = mask and it2.bit != 0
                                val isAsm = it2.bit in setOf(Formatter.F_ARM, Formatter.F_THUMB, Formatter.F_ARM8, Formatter.F_X86, Formatter.F_X64)
                                val labelColor = when {
                                    isAsm -> Rose.Opcode
                                    on -> Rose.Text
                                    else -> Rose.TextSecond
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .background(if (on) Rose.ChipSelected else Rose.CardHigh, GgShapes.Sm)
                                        .clickableWithTooltip {
                                            mask = mask xor it2.bit
                                        },
                                    contentAlignment = Alignment.Center
                                ) { Text(tr(it2.label), color = labelColor, fontSize = fsp(12f)) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                } else {
                    // 原版 GG 保存页风格：单列清晰勾选辅助显示格式（不含多数据类型）
                    formatItems.forEach { it2 ->
                        val on = mask and it2.bit != 0
                        val isAsm = it2.bit in setOf(Formatter.F_ARM, Formatter.F_THUMB, Formatter.F_ARM8)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableWithTooltip { mask = mask xor it2.bit }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GgCheckBox(checked = on, onCheckedChange = { mask = mask xor it2.bit }, size = 20.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = tr(it2.label),
                                color = if (isAsm) Rose.Opcode else Rose.Text,
                                fontSize = fsp(13.5f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("${tr("当前：")}${Formatter.maskSummary(mask)}", style = monoStyle(11, Rose.TextDim))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("重置", Modifier.weight(1f)) {
                    mask = if (isMemory) Formatter.DEFAULT_MASK else 0
                }
                RoseButton("取消", Modifier.weight(1f)) {
                    Session.closeSheet()
                }
                RoseButton("确定", Modifier.weight(1f), filled = true) {
                    Session.setValueMask(mask, targetTab)
                    Session.closeSheet()
                }
            }
        }
    }
}

// ============================================================
// 偏移量 & 异或计算
// ============================================================
// ============================================================
// 偏移量计算器 (OffsetSheet)
// ============================================================
@Composable
fun OffsetSheet(ui: Ui) {
    val clip = LocalClipboardManager.current
    val baseDefault = ui.contextItem?.cachedAddressHex
        ?: java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
    var baseHex by remember { mutableStateOf(TextFieldValue(baseDefault, TextRange(baseDefault.length))) }
    var offTf by remember { mutableStateOf(TextFieldValue("0", TextRange(0, 1))) }
    var focusField by remember { mutableIntStateOf(1) } // 0: baseHex, 1: offTf
    var isHex by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(true) }

    val baseAddr = baseHex.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
    val offStr = offTf.text.trim()
    val isNeg = offStr.startsWith("-")
    val cleanOff = offStr.removePrefix("-").removePrefix("+").removePrefix("0x").removeSuffix("h")
    val offAbs = if (isHex) cleanOff.toULongOrNull(16)?.toLong() ?: 0L else cleanOff.toLongOrNull() ?: 0L
    val offVal = if (isNeg) -offAbs else offAbs
    val targetAddr = baseAddr + offVal
    val targetHex = java.lang.Long.toHexString(targetAddr).uppercase()

    // 异步读取目标地址的多类型内存预览（杜绝主线程 I/O 阻塞）
    var rawBytes by remember { mutableStateOf(ByteArray(0)) }
    LaunchedEffect(targetAddr) {
        rawBytes = Session.readMemoryPreview(targetAddr, 8)
    }
    var qwordVal = 0L
    for (i in 0 until minOf(8, rawBytes.size)) {
        qwordVal = qwordVal or ((rawBytes[i].toLong() and 0xFF) shl (i * 8))
    }
    val dwordVal = (qwordVal and 0xFFFFFFFFL).toInt()
    val floatVal = java.lang.Float.intBitsToFloat(dwordVal)
    val doubleVal = java.lang.Double.longBitsToDouble(qwordVal)
    val wordVal = (qwordVal and 0xFFFFL).toShort()
    val byteVal = (qwordVal and 0xFFL).toByte()
    // 真实地址低 32 位异或（与 GGNext 引擎 XOR 语义一致）
    val xorVal = dwordVal xor (targetAddr and 0xFFFFFFFFL).toInt()

    val hexDisplay = String.format(java.util.Locale.US, "%016Xh", qwordVal)
    val revHexDisplay = Formatter.reverseHex(qwordVal, 8) + "r"
    val utf8Display = Formatter.bytesToAscii(qwordVal, 8)
    val utf16Display = Formatter.bytesToUtf16(qwordVal, 8)

    NextSheetCard(
        title = "偏移量计算器",
        subtitle = "输入偏移实时计算目标地址并展示各种类型的内存数值",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 760,
        showSideKeyboard = showKeyboard,
        sideKeyboard = {
            BuiltInKeyboard(
                value = if (focusField == 0) baseHex else offTf,
                onValueChange = { next ->
                    if (focusField == 0) baseHex = next else offTf = next
                },
                onHistory = { Session.openSheet(Sheet.HISTORY) },
                onDismiss = { showKeyboard = false }
            )
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // 地址: 可点击编辑基址
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Rose.CardHigh, GgShapes.Sm)
                    .border(
                        width = 1.dp,
                        color = if (focusField == 0) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                        shape = GgShapes.Sm
                    )
                    .clickableNoRipple(remember { MutableInteractionSource() }) { focusField = 0 }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("地址:"), color = Rose.TextSecond, fontSize = fsp(14f))
                Spacer(Modifier.width(10.dp))
                GgTextField(
                    value = baseHex,
                    onValueChange = {
                        focusField = 0
                        baseHex = it
                    },
                    onFocus = { focusField = 0 },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text, weight = FontWeight.SemiBold),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
            }

            Spacer(Modifier.height(6.dp))

            // 偏移量: 50
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Rose.CardHigh, GgShapes.Sm)
                    .border(
                        width = 1.dp,
                        color = if (focusField == 1) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                        shape = GgShapes.Sm
                    )
                    .clickableNoRipple(remember { MutableInteractionSource() }) { focusField = 1 }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("偏移量:"), color = Rose.TextSecond, fontSize = fsp(14f))
                Spacer(Modifier.width(10.dp))
                GgTextField(
                    value = offTf,
                    onValueChange = {
                        focusField = 1
                        offTf = it
                    },
                    onFocus = { focusField = 1 },
                    initialFocus = true,
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard,
                    placeholder = "0"
                )
            }

            Spacer(Modifier.height(6.dp))

            // [x] HEX   结果: 68000CAF0  [复制图标]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickableWithTooltip { isHex = !isHex }
                ) {
                    GgCheckBox(checked = isHex, onCheckedChange = { isHex = it }, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("HEX", color = Rose.Text, fontSize = fsp(13f), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(16.dp))
                Text(tr("结果:"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                Spacer(Modifier.width(6.dp))
                Text(targetHex, style = monoStyle(14, Rose.Primary, weight = FontWeight.Bold), modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(32.dp).clickableWithTooltip {
                        clip.setText(AnnotatedString(targetHex))
                        Session.toast("已复制结果地址: $targetHex")
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, tint = Rose.Primary, modifier = Modifier.size(17.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            // 实时展示目标地址下全部数据类型的数值卡片 (参考图 5 原版 GG)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Rose.Row, GgShapes.Md)
                    .border(1.dp, Rose.Divider, GgShapes.Md)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "$hexDisplay; $revHexDisplay; '$utf8Display'; \"$utf16Display\";",
                    style = monoStyle(12, Rose.TextSecond)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(java.util.Locale.US, "%,dD; ", dwordVal), style = monoStyle(12, typeColor(ValueType.DWORD)))
                    Text(String.format(java.util.Locale.US, "%.7eF; ", floatVal), style = monoStyle(12, typeColor(ValueType.FLOAT)))
                    Text(String.format(java.util.Locale.US, "%.14eE;", doubleVal), style = monoStyle(12, typeColor(ValueType.DOUBLE)))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(java.util.Locale.US, "%,dW; ", wordVal), style = monoStyle(12, typeColor(ValueType.WORD)))
                    Text(String.format(java.util.Locale.US, "%dB; ", byteVal), style = monoStyle(12, typeColor(ValueType.BYTE)))
                    Text(String.format(java.util.Locale.US, "%,dQ; ", qwordVal), style = monoStyle(12, typeColor(ValueType.QWORD)))
                    Text(String.format(java.util.Locale.US, "%,dX;", xorVal), style = monoStyle(12, typeColor(ValueType.XOR)))
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("复制", Modifier.weight(1f)) {
                    val fullDump = "$targetHex\n$hexDisplay; $revHexDisplay\n$dwordVal D; $floatVal F; $qwordVal Q"
                    clip.setText(AnnotatedString(fullDump))
                    Session.toast("已复制目标地址及内存数值")
                }
                RoseButton("取消", Modifier.weight(1f)) {
                    Session.closeSheet()
                }
                RoseButton("转到", Modifier.weight(1.2f), filled = true) {
                    Session.goto(targetAddr, recordHistory = true, highlightTarget = true)
                    Session.closeSheet()
                }
            }
        }
    }
}

// ============================================================
// 问题 9 重构：地址偏移/异或 (CalcOffsetXorSheet)
// - 严格参考图 4 重构为「地址偏移/异或」弹窗：
//   1. 顶部「偏移」/「异或」双模式切换胶囊按钮；
//   2. 自动对所选地址列表进行两两组合 (0 <= i < j < N) 计算：
//      - 偏移模式：展示 `12C00000 - 12C00014: 14`，对源地址、目标地址与核心偏移值施加语义高光；
//      - 异或模式：展示数值异或与地址异或结果，并对核心异或结果施加语义高光；
//   3. 点击任意结果行即可一键复制计算出的核心偏移量或异或值；
//   4. 右下角提供「关闭」按钮。
// ============================================================
@Composable
fun CalcOffsetXorSheet(ui: Ui) {
    val clip = LocalClipboardManager.current
    // 0 = 偏移, 1 = 异或
    var modeTab by remember { mutableIntStateOf(0) }

    val sourceItems = remember(ui.tab, ui.results, ui.saved, ui.memoryRows, ui.contextItem) {
        val activeChecked = Session.checkedItems()
        if (activeChecked.size >= 2) {
            activeChecked.take(24)
        } else {
            val allChecked = (ui.saved.filter { it.checked } +
                ui.results.filter { it.checked } +
                ui.memoryRows.filter { it.checked })
                .distinctBy { it.address }
            if (allChecked.size >= 2) {
                allChecked.take(24)
            } else {
                val activeList = when (ui.tab) {
                    NextTab.SAVED_LIST -> ui.saved
                    NextTab.MEMORY -> ui.memoryRows
                    else -> ui.results.ifEmpty { ui.saved }
                }
                val fallback = buildList {
                    ui.contextItem?.let { add(it) }
                    activeList.forEach { item ->
                        if (none { it.address == item.address } && size < 6) {
                            add(item)
                        }
                    }
                }
                fallback
            }
        }
    }

    val pairs = remember(sourceItems) {
        buildList {
            for (i in 0 until sourceItems.size) {
                for (j in (i + 1) until sourceItems.size) {
                    add(sourceItems[i] to sourceItems[j])
                }
            }
        }
    }

    NextSheetCard(
        title = "地址偏移/异或",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 680
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 顶部模式切换胶囊：「偏移」 / 「异或」
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GgSegmentTab(
                    label = "偏移",
                    selected = modeTab == 0,
                    modifier = Modifier.width(68.dp),
                    height = GgControlHeight.Chip,
                    shape = GgShapes.Md,
                    accent = Rose.Text,
                    selectedBackground = Rose.Primary.copy(alpha = if (Rose.IsDark) 0.38f else 0.24f),
                    unselectedBackground = Rose.Row,
                    fontSize = 14f,
                    onClick = { modeTab = 0 }
                )
                GgSegmentTab(
                    label = "异或",
                    selected = modeTab == 1,
                    modifier = Modifier.width(68.dp),
                    height = GgControlHeight.Chip,
                    shape = GgShapes.Md,
                    accent = Rose.Text,
                    selectedBackground = Rose.Primary.copy(alpha = if (Rose.IsDark) 0.38f else 0.24f),
                    unselectedBackground = Rose.Row,
                    fontSize = 14f,
                    onClick = { modeTab = 1 }
                )
                Spacer(Modifier.weight(1f))
                if (pairs.isNotEmpty()) {
                    Text(
                        text = tr("点击任意行复制结果"),
                        color = Rose.TextDim,
                        fontSize = fsp(11.5f)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (pairs.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Rose.Row, GgShapes.Md)
                        .border(1.dp, Rose.Divider, GgShapes.Md)
                        .padding(vertical = 28.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tr("请先在列表中勾选至少 2 个地址以计算两两偏移与异或"),
                        color = Rose.TextSecond,
                        fontSize = fsp(13.5f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pairs.forEach { (itemA, itemB) ->
                        val addrAHex = itemA.cachedAddressHex
                        val addrBHex = itemB.cachedAddressHex
                        // 按原版 GG 图 4 展示：较小地址在前或按列表顺序展示无符号差值，若 A > B 则带负号
                        val rawDiff = itemB.address - itemA.address
                        val absDiffHex = java.lang.Long.toHexString(kotlin.math.abs(rawDiff)).uppercase()
                        val offsetHexText = if (rawDiff >= 0L) absDiffHex else "-$absDiffHex"
                        val offsetDecText = if (rawDiff >= 0L) "+$rawDiff" else "$rawDiff"

                        val valA = Formatter.signed(itemA.value, itemA.size)
                        val valB = Formatter.signed(itemB.value, itemB.size)
                        val valXor = (itemA.value xor itemB.value) and (if (maxOf(itemA.size, itemB.size) <= 4) 0xFFFFFFFFL else -1L)
                        val valXorHex = java.lang.Long.toHexString(valXor).uppercase()
                        val addrXor = itemA.address xor itemB.address
                        val addrXorHex = java.lang.Long.toHexString(addrXor).uppercase()

                        if (modeTab == 0) {
                            // 偏移模式行：12C00000 - 12C00014: 14（关键数据高光）
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(GgShapes.Md)
                                    .background(Rose.Row)
                                    .border(1.dp, Rose.Divider.copy(alpha = 0.7f), GgShapes.Md)
                                    .clickableWithTooltip {
                                        clip.setText(AnnotatedString(offsetHexText))
                                        Session.toast("已复制偏移量: $offsetHexText")
                                    }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = addrAHex,
                                    style = monoStyle(14, Rose.Primary, FontWeight.SemiBold)
                                )
                                Text(
                                    text = " - ",
                                    style = monoStyle(14, Rose.TextSecond, FontWeight.Medium)
                                )
                                Text(
                                    text = addrBHex,
                                    style = monoStyle(14, Rose.Cyan, FontWeight.SemiBold)
                                )
                                Text(
                                    text = ": ",
                                    style = monoStyle(14, Rose.TextSecond, FontWeight.Bold)
                                )
                                // 核心偏移结果高光胶囊
                                Box(
                                    Modifier
                                        .background(
                                            Rose.Green.copy(alpha = if (Rose.IsDark) 0.18f else 0.14f),
                                            GgShapes.Xs
                                        )
                                        .border(
                                            1.dp,
                                            Rose.Green.copy(alpha = 0.45f),
                                            GgShapes.Xs
                                        )
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = offsetHexText,
                                        style = monoStyle(14, Rose.Green, FontWeight.ExtraBold)
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "($offsetDecText)",
                                    style = monoStyle(11, Rose.TextDim)
                                )
                            }
                        } else {
                            // 异或模式行：展示数值异或与地址异或，核心异或值高光显示
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(GgShapes.Md)
                                    .background(Rose.Row)
                                    .border(1.dp, Rose.Divider.copy(alpha = 0.7f), GgShapes.Md)
                                    .clickableWithTooltip {
                                        clip.setText(AnnotatedString(valXor.toString()))
                                        Session.toast("已复制异或值: $valXor (0x$valXorHex)")
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = addrAHex,
                                        style = monoStyle(13, Rose.Primary, FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = " ^ ",
                                        style = monoStyle(13, Rose.TextSecond, FontWeight.Medium)
                                    )
                                    Text(
                                        text = addrBHex,
                                        style = monoStyle(13, Rose.Cyan, FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = ": ",
                                        style = monoStyle(13, Rose.TextSecond, FontWeight.Bold)
                                    )
                                    Box(
                                        Modifier
                                            .background(
                                                Rose.Yellow.copy(alpha = if (Rose.IsDark) 0.18f else 0.14f),
                                                GgShapes.Xs
                                            )
                                            .border(
                                                1.dp,
                                                Rose.Yellow.copy(alpha = 0.45f),
                                                GgShapes.Xs
                                            )
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${addrXorHex}h",
                                            style = monoStyle(13, Rose.Yellow, FontWeight.ExtraBold)
                                        )
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$valA",
                                        style = monoStyle(12, typeColor(itemA.flags), FontWeight.Medium)
                                    )
                                    Text(
                                        text = " ^ ",
                                        style = monoStyle(12, Rose.TextDim)
                                    )
                                    Text(
                                        text = "$valB",
                                        style = monoStyle(12, typeColor(itemB.flags), FontWeight.Medium)
                                    )
                                    Text(
                                        text = " = ",
                                        style = monoStyle(12, Rose.TextSecond)
                                    )
                                    Box(
                                        Modifier
                                            .background(
                                                Rose.Green.copy(alpha = if (Rose.IsDark) 0.18f else 0.14f),
                                                GgShapes.Xs
                                            )
                                            .border(
                                                1.dp,
                                                Rose.Green.copy(alpha = 0.45f),
                                                GgShapes.Xs
                                            )
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$valXor (${valXorHex}h)",
                                            style = monoStyle(12, Rose.Green, FontWeight.ExtraBold)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 右下角「关闭」按钮（参考图 4）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                RoseButton(
                    label = "关闭",
                    modifier = Modifier.width(78.dp).height(40.dp),
                    filled = false
                ) {
                    Session.closeSheet()
                }
            }
        }
    }
}

// 移除
// ============================================================
@Composable
fun RemoveSheet(ui: Ui) {
    NextSheetCard(title = "移除", onDismiss = Session::closeSheet) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MenuAction(Icons.Rounded.Restore, "恢复并移除所选 (${ui.selectedCount})") {
                Session.removeChecked(all = false, revertFirst = true)
            }
            MenuAction(Icons.Rounded.Delete, "移除所选 (${ui.selectedCount})") {
                Session.removeChecked(all = false, revertFirst = false)
            }
            MenuAction(Icons.Rounded.Delete, "移除全部列表项") {
                Session.removeChecked(all = true, revertFirst = false)
            }
            Spacer(Modifier.height(4.dp))
            RoseButton("取消", Modifier.fillMaxWidth()) { Session.closeSheet() }
        }
    }
}

// ============================================================
// 以联合方式复制 (CopyGroupSheet) - 对标原版 GG
// ============================================================

private enum class CopyGroupByteMode {
    NONE,
    UTF8,
    UTF16LE,
    HEX,
    HEX_UTF8,
    HEX_UTF16LE,
    HEX_UTF8_UTF16LE
}

private fun extractItemsRawBytes(items: List<MemoryItem>): ByteArray {
    val sorted = items.sortedBy { it.address }
    val out = ArrayList<Byte>(sorted.sumOf { it.size.coerceIn(1, 8) })
    for (item in sorted) {
        val sz = item.size.coerceIn(1, 8)
        val v = item.value
        for (b in 0 until sz) {
            out.add(((v ushr (b * 8)) and 0xFFL).toByte())
        }
    }
    return ByteArray(out.size) { out[it] }
}

private fun buildMixedQString(bytes: ByteArray, allowUtf8: Boolean, allowUtf16: Boolean): String {
    if (bytes.isEmpty()) return "Q"
    val tokens = ArrayList<String>()
    var i = 0
    val n = bytes.size
    fun isPrintableAscii(b: Int): Boolean = b in 0x20..0x7E && b != 0x27 && b != 0x22
    while (i < n) {
        // 优先匹配 UTF-16LE 可打印 ASCII 连续串（至少 2 个双字节字符）
        if (allowUtf16 && i + 3 < n) {
            var j = i
            val sb16 = StringBuilder()
            while (j + 1 < n) {
                val lo = bytes[j].toInt() and 0xFF
                val hi = bytes[j + 1].toInt() and 0xFF
                if (hi == 0 && isPrintableAscii(lo)) {
                    sb16.append(lo.toChar())
                    j += 2
                } else break
            }
            if (sb16.length >= 2) {
                tokens.add("\"${sb16}\"")
                i = j
                continue
            }
        }
        // 匹配 UTF-8 可打印 ASCII 连续串（至少 2 个字节）
        if (allowUtf8) {
            var j = i
            val sb8 = StringBuilder()
            while (j < n && isPrintableAscii(bytes[j].toInt() and 0xFF)) {
                sb8.append((bytes[j].toInt() and 0xFF).toChar())
                j++
            }
            if (sb8.length >= 2) {
                tokens.add("'$sb8'")
                i = j
                continue
            }
        }
        tokens.add(String.format("%02X", bytes[i].toInt() and 0xFF))
        i++
    }
    return "Q " + tokens.joinToString(" ")
}

private fun buildGroupSearchExpression(
    items: List<MemoryItem>,
    hexFormat: Boolean,
    rHexFormat: Boolean,
    addTypes: Boolean,
    addGroupSize: Boolean,
    byteMode: CopyGroupByteMode
): String {
    if (items.isEmpty()) return ""
    val sorted = items.sortedBy { it.address }
    when (byteMode) {
        CopyGroupByteMode.UTF8 -> {
            val raw = extractItemsRawBytes(sorted)
            val text = String(raw, Charsets.UTF_8).replace("\u0000", "")
            return ":$text"
        }
        CopyGroupByteMode.UTF16LE -> {
            val raw = extractItemsRawBytes(sorted)
            val text = String(raw, Charsets.UTF_16LE).replace("\u0000", "")
            return ";$text"
        }
        CopyGroupByteMode.HEX -> {
            val raw = extractItemsRawBytes(sorted)
            return "h " + raw.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
        }
        CopyGroupByteMode.HEX_UTF8 -> {
            return buildMixedQString(extractItemsRawBytes(sorted), allowUtf8 = true, allowUtf16 = false)
        }
        CopyGroupByteMode.HEX_UTF16LE -> {
            return buildMixedQString(extractItemsRawBytes(sorted), allowUtf8 = false, allowUtf16 = true)
        }
        CopyGroupByteMode.HEX_UTF8_UTF16LE -> {
            return buildMixedQString(extractItemsRawBytes(sorted), allowUtf8 = true, allowUtf16 = true)
        }
        CopyGroupByteMode.NONE -> {
            val parts = sorted.map { item ->
                val numPart = when {
                    hexFormat -> Formatter.hex(item.value, item.size) + "h"
                    rHexFormat -> Formatter.reverseHex(item.value, item.size) + "r"
                    else -> valueText(item)
                }
                if (addTypes) "$numPart${item.typeShort}" else numPart
            }
            val joined = parts.joinToString(";")
            return if (addGroupSize && sorted.size > 1) {
                val minAddr = sorted.first().address
                val maxAddr = sorted.last().address
                val area = (maxAddr - minAddr + 1L).coerceIn(2L, 65536L)
                "$joined::$area"
            } else {
                joined
            }
        }
    }
}

@Composable
fun CopyGroupSheet(ui: Ui) {
    val clip = LocalClipboardManager.current
    val checked = remember(ui.tab, ui.searchSelection, ui.savedSelection, ui.memorySelection, ui.results, ui.saved, ui.memoryRows) {
        Session.checkedItems()
    }
    var hexFormat by remember { mutableStateOf(false) }
    var rHexFormat by remember { mutableStateOf(false) }
    var addTypes by remember { mutableStateOf(true) }
    var addGroupSize by remember { mutableStateOf(true) }
    var memorySelector by remember { mutableStateOf(false) }
    var byteMode by remember { mutableStateOf(CopyGroupByteMode.NONE) }

    fun clearByteMode() {
        byteMode = CopyGroupByteMode.NONE
    }

    fun selectByteMode(target: CopyGroupByteMode) {
        if (byteMode == target) {
            byteMode = CopyGroupByteMode.NONE
        } else {
            byteMode = target
            hexFormat = false
            rHexFormat = false
            addTypes = false
            addGroupSize = false
            memorySelector = false
        }
    }

    val previewExpr = remember(checked, hexFormat, rHexFormat, addTypes, addGroupSize, byteMode) {
        buildGroupSearchExpression(checked, hexFormat, rHexFormat, addTypes, addGroupSize, byteMode)
    }

    @Composable
    fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) Rose.ChipSelected else Rose.CardHigh, GgShapes.Sm)
                .clickableWithTooltip(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GgCheckBox(checked = selected, onCheckedChange = { onClick() }, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = tr(label),
                color = if (selected) Rose.Text else Rose.TextSecond,
                fontSize = fsp(13.5f),
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
        }
    }

    NextSheetCard(
        title = "以联合方式复制",
        subtitle = "${tr("为")} ${checked.size} ${tr("个已选项选择复制格式。")}",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 720
    ) {
        Column {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OptionRow("十六进制格式", hexFormat) {
                    clearByteMode()
                    hexFormat = !hexFormat
                    if (hexFormat) rHexFormat = false
                }
                OptionRow("反向十六进制格式", rHexFormat) {
                    clearByteMode()
                    rHexFormat = !rHexFormat
                    if (rHexFormat) hexFormat = false
                }
                OptionRow("添加类型", addTypes) {
                    clearByteMode()
                    addTypes = !addTypes
                }
                OptionRow("添加组列大小", addGroupSize) {
                    clearByteMode()
                    addGroupSize = !addGroupSize
                }
                OptionRow("内存选择器", memorySelector) {
                    clearByteMode()
                    memorySelector = !memorySelector
                }
                OptionRow("UTF-8", byteMode == CopyGroupByteMode.UTF8) {
                    selectByteMode(CopyGroupByteMode.UTF8)
                }
                OptionRow("UTF-16LE", byteMode == CopyGroupByteMode.UTF16LE) {
                    selectByteMode(CopyGroupByteMode.UTF16LE)
                }
                OptionRow("HEX", byteMode == CopyGroupByteMode.HEX) {
                    selectByteMode(CopyGroupByteMode.HEX)
                }
                OptionRow("HEX + UTF-8", byteMode == CopyGroupByteMode.HEX_UTF8) {
                    selectByteMode(CopyGroupByteMode.HEX_UTF8)
                }
                OptionRow("HEX + UTF-16LE", byteMode == CopyGroupByteMode.HEX_UTF16LE) {
                    selectByteMode(CopyGroupByteMode.HEX_UTF16LE)
                }
                OptionRow("HEX + UTF-8 + UTF-16LE", byteMode == CopyGroupByteMode.HEX_UTF8_UTF16LE) {
                    selectByteMode(CopyGroupByteMode.HEX_UTF8_UTF16LE)
                }
            }

            if (previewExpr.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Rose.Row, GgShapes.Xs)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = previewExpr,
                        style = monoStyle(11, Rose.Primary),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RoseButton("取消", Modifier.width(88.dp)) { Session.closeSheet() }
                Spacer(Modifier.width(8.dp))
                RoseButton("确定", Modifier.width(96.dp), filled = true) {
                    if (previewExpr.isEmpty()) {
                        Session.toast("请先勾选要复制为联合搜索的项目")
                    } else {
                        clip.setText(AnnotatedString(previewExpr))
                        Session.updateSearch { it.copy(text = previewExpr) }
                        if (memorySelector) {
                            val regions = checked.map { it.region }.filter { it.isNotBlank() }.toSet()
                            if (regions.isNotEmpty()) {
                                Session.setSelectedRegionsFromMcp(regions)
                            }
                        }
                        Session.closeSheet()
                        Session.toast("已复制联合搜索表达式：$previewExpr")
                    }
                }
            }
        }
    }
}

// ============================================================
// 转到地址 (GotoSheet) - 支持 .so 模块定位 / 区域模块列表 / Lib 列表
// ============================================================

private data class SoLibEntry(
    val name: String,
    val regionCode: String,
    val baseAddress: Long,
    val fullPath: String
) {
    val baseHex: String get() = java.lang.Long.toHexString(baseAddress).uppercase()
}

@Composable
fun GotoSheet() {
    val ui = Session.ui.collectAsState().value
    val scope = rememberCoroutineScope()
    val initHex = ui.gotoDraftText ?: java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
    var tf by remember { mutableStateOf(TextFieldValue(initHex, TextRange(0, initHex.length))) }
    LaunchedEffect(ui.gotoDraftText) {
        val draft = ui.gotoDraftText
        if (draft != null && draft != tf.text) {
            tf = TextFieldValue(draft, TextRange(draft.length))
        }
    }
    var showKeyboard by remember { mutableStateOf(true) }
    var showGotoHistory by remember { mutableStateOf(false) }

    // Lib 选择面板状态（点击“历史记录”右侧的 Lib 按钮时展示所有 .so，选中后将 so 名填入输入框）
    var showLibPicker by remember { mutableStateOf(false) }
    var soLibs by remember { mutableStateOf<List<SoLibEntry>>(emptyList()) }
    var libFilterQuery by remember { mutableStateOf(TextFieldValue("")) }
    var libLoading by remember { mutableStateOf(false) }

    val gotoHistory = ui.gotoHistory

    fun openLibPicker() {
        showGotoHistory = false
        if (showLibPicker) {
            showLibPicker = false
            return
        }
        showLibPicker = true
        libFilterQuery = TextFieldValue("")
        libLoading = true
        scope.launch {
            val all = Session.loadProcessRegions()
            val byName = LinkedHashMap<String, SoLibEntry>()
            for (r in all.sortedBy { it.start }) {
                if (r.path.isBlank() || !r.path.contains(".so", ignoreCase = true)) continue
                val afterBang = if (r.path.contains("!/")) r.path.substringAfterLast("!/") else r.path
                val fileName = afterBang.substringAfterLast('/').trim()
                if (fileName.isBlank()) continue
                val key = fileName.lowercase()
                if (!byName.containsKey(key)) {
                    byName[key] = SoLibEntry(
                        name = fileName,
                        regionCode = r.code,
                        baseAddress = r.start,
                        fullPath = r.path
                    )
                }
            }
            soLibs = byName.values.sortedBy { it.name.lowercase() }
            libLoading = false
            if (soLibs.isEmpty()) {
                Session.toast("当前进程未解析到 .so 模块")
            }
        }
    }

    NextSheetCard(
        title = "转到地址",
        subtitle = "输入十六进制地址或 .so 模块名（支持 +偏移）定位内存",
        onDismiss = {
            Session.saveGotoDraft(null)
            Session.closeSheet()
        },
        maxBodyHeight = 720,
        showSideKeyboard = showKeyboard && !showLibPicker,
        sideKeyboard = {
            BuiltInKeyboard(
                value = tf,
                onValueChange = { tf = it },
                onHistory = {
                    showLibPicker = false
                    showGotoHistory = !showGotoHistory
                },
                onDismiss = { showKeyboard = false }
            )
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // 输入行：地址 / so 模块名 + 键盘切换 + 全部内存模块列表 ☰
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, GgShapes.Md)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GgTextField(
                    value = tf,
                    onValueChange = { tf = it },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(14, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard,
                    placeholder = "地址或模块名 (如 libil2cpp.so+0x10)"
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .clickableWithTooltip(tooltip = tr("键盘")) { showKeyboard = !showKeyboard },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = tr("键盘"),
                        tint = if (showKeyboard) Rose.Primary else Rose.TextSecond,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Rose.Chip, GgShapes.Xs)
                        .clickableWithTooltip(tooltip = tr("选择内存模块")) {
                            Session.saveGotoDraft(tf.text)
                            Session.openModulePicker(target = "goto", regionFilter = null)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        GgIcons.MenuThreeLines,
                        contentDescription = "内存模块列表",
                        tint = Rose.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 第一行快捷按钮：历史记录 | Lib（位于历史记录右侧）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(if (showGotoHistory) Rose.ChipSelected else Rose.CardHigh, GgShapes.Sm)
                        .clickableWithTooltip {
                            showLibPicker = false
                            showGotoHistory = !showGotoHistory
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tr("历史记录"),
                        color = if (showGotoHistory) Rose.Primary else Rose.Text,
                        fontSize = fsp(13f),
                        fontWeight = if (showGotoHistory) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(if (showLibPicker) Rose.ChipSelected else Rose.CardHigh, GgShapes.Sm)
                        .clickableWithTooltip { openLibPicker() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Lib",
                        color = if (showLibPicker) Rose.Primary else Rose.Text,
                        fontSize = fsp(13f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // 第二行区域按钮：Ca | Cd | Cb | Xs | Xa（打开对应区域模块列表，支持权限/路径/用户/搜索过滤且选中后回填输入框）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Ca", "Cd", "Cb", "Xs", "Xa").forEach { label ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(Rose.CardHigh, GgShapes.Sm)
                            .clickableWithTooltip {
                                Session.saveGotoDraft(tf.text)
                                Session.openModulePicker(target = "goto", regionFilter = label, initialUserOnly = false)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = Rose.Primary,
                            fontSize = fsp(13f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 历史记录面板
            AnimatedVisibility(showGotoHistory, enter = expandEnter(), exit = expandExit()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(Rose.CardHigh, GgShapes.Sm)
                        .padding(8.dp)
                ) {
                    Text(
                        tr("内存跳转历史 (点击直接填入):"),
                        color = Rose.TextSecond,
                        fontSize = fsp(12f)
                    )
                    Spacer(Modifier.height(6.dp))
                    if (gotoHistory.isEmpty()) {
                        Text(tr("无历史记录"), color = Rose.TextDim, fontSize = fsp(12f))
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            gotoHistory.forEach { h ->
                                Box(
                                    Modifier
                                        .height(30.dp)
                                        .background(Rose.Chip, GgShapes.Xs)
                                        .clickableWithTooltip {
                                            tf = TextFieldValue(h, TextRange(h.length))
                                            showGotoHistory = false
                                        }
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(h, style = monoStyle(12, Rose.Text))
                                }
                            }
                        }
                    }
                }
            }

            // Lib (.so 模块) 选择面板：点击某项将 so 名填入输入框
            AnimatedVisibility(showLibPicker, enter = expandEnter(), exit = expandExit()) {
                val q = libFilterQuery.text.trim()
                val shownLibs = remember(soLibs, q) {
                    if (q.isEmpty()) soLibs else soLibs.filter {
                        it.name.contains(q, ignoreCase = true) || it.fullPath.contains(q, ignoreCase = true)
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(Rose.CardHigh, GgShapes.Sm)
                        .padding(8.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .background(Rose.Row, GgShapes.Xs)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = Rose.TextDim, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        GgTextField(
                            value = libFilterQuery,
                            onValueChange = { libFilterQuery = it },
                            initialFocus = true,
                            suppressSystemKeyboard = false,
                            modifier = Modifier.weight(1f).height(32.dp),
                            textStyle = monoStyle(12, Rose.Text),
                            cursorColor = Rose.Primary,
                            placeholder = "筛选 .so 模块名..."
                        )
                        Text("${shownLibs.size}", style = monoStyle(11, Rose.TextDim))
                    }
                    Spacer(Modifier.height(6.dp))
                    if (libLoading) {
                        Text(tr("正在解析进程 .so 模块..."), color = Rose.TextDim, fontSize = fsp(12f), modifier = Modifier.padding(6.dp))
                    } else if (shownLibs.isEmpty()) {
                        Text(tr("未找到匹配的 .so 模块"), color = Rose.TextDim, fontSize = fsp(12f), modifier = Modifier.padding(6.dp))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(items = shownLibs, key = { "${it.name}_${it.baseAddress}" }) { lib ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Rose.Row, GgShapes.Xs)
                                        .clickableWithTooltip {
                                            tf = TextFieldValue(lib.name, TextRange(lib.name.length))
                                            showLibPicker = false
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                lib.name,
                                                style = monoStyle(12, Rose.Text, weight = FontWeight.SemiBold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                lib.regionCode,
                                                style = monoStyle(10, Rose.Primary, weight = FontWeight.Bold)
                                            )
                                        }
                                        Text(
                                            "0x${lib.baseHex}",
                                            style = monoStyle(10, Rose.TextDim)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 跳转 / 取消 按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("取消", Modifier.weight(1f)) {
                    Session.saveGotoDraft(null)
                    Session.closeSheet()
                }
                RoseButton("跳转", Modifier.weight(1f), filled = true) {
                    val rawExpr = tf.text.trim()
                    scope.launch {
                        val res = Session.resolveGotoExpression(rawExpr)
                        res.onSuccess { (addr, label) ->
                            Session.saveGotoDraft(null)
                            Session.recordGotoHistory(rawExpr)
                            Session.goto(addr, recordHistory = true, highlightTarget = true)
                            if (rawExpr.contains(".so", ignoreCase = true)) {
                                Session.toast("已定位 $label -> 0x${java.lang.Long.toHexString(addr).uppercase()}")
                            }
                        }.onFailure { err ->
                            Session.toast(err.message ?: "地址无效")
                        }
                    }
                }
            }
        }
    }
}

/** 模块项数据模型 (参考图 2) */
data class MemoryModuleItem(
    val regionCode: String,
    val startAddress: Long,
    val endAddress: Long,
    val perms: String,
    val path: String
) {
    val startHex: String get() = java.lang.Long.toHexString(startAddress).uppercase()
    val endHex: String get() = java.lang.Long.toHexString(endAddress).uppercase()
    val sizeBytes: Long get() = (endAddress - startAddress).coerceAtLeast(0L)
}

/** 判断内存路径是否属于系统模块（用于「用户」开关一键过滤系统内存，对标图 5） */
private fun isSystemModulePath(path: String): Boolean {
    val clean = path.trim().trim('\'').lowercase()
    if (clean.isEmpty()) return true
    if (clean.startsWith("/data/app/") ||
        clean.startsWith("/data/data/") ||
        clean.startsWith("/data/user/") ||
        clean.startsWith("/data/user_de/") ||
        clean.startsWith("/mnt/expand/") ||
        clean.startsWith("/sdcard/") ||
        clean.startsWith("/storage/")
    ) return false
    return clean.startsWith("/system/") ||
        clean.startsWith("/system_ext/") ||
        clean.startsWith("/vendor/") ||
        clean.startsWith("/apex/") ||
        clean.startsWith("/product/") ||
        clean.startsWith("/odm/") ||
        clean.startsWith("/dev/") ||
        clean.startsWith("/memfd:") ||
        clean.startsWith("[")
}

/** 内存模块选择列表 (ModuleListSheet) - LazyColumn 高性能渲染 + 权限/内存/路径/开始/用户/搜索多选控制 */
@Composable
fun ModuleListSheet() {
    val initialRegion = remember { Session.modulePickerRegionFilter }
    val initialUserOnly = remember { Session.modulePickerInitialUserOnly }
    val isPickerMode = remember { Session.hasModulePickerCallback() }
    val softwareKeyboard = LocalSoftwareKeyboardController.current

    var showPerms by remember { mutableStateOf(true) }
    var showRegion by remember { mutableStateOf(true) }
    var showPath by remember { mutableStateOf(true) }
    var showStart by remember { mutableStateOf(true) }
    var userOnly by remember { mutableStateOf(initialUserOnly) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }

    var liveModules by remember { mutableStateOf<List<MemoryModuleItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(initialRegion) {
        loading = true
        val regions = Session.loadProcessRegions()
        val mapped = withContext(Dispatchers.Default) {
            regions
                .asSequence()
                .filter { r ->
                    if (initialRegion != null) {
                        r.code.equals(initialRegion, ignoreCase = true)
                    } else {
                        r.path.isNotBlank()
                    }
                }
                .sortedBy { it.start }
                .map { r ->
                    MemoryModuleItem(
                        regionCode = r.code,
                        startAddress = r.start,
                        endAddress = r.end,
                        perms = r.perms,
                        path = if (r.path.isNotBlank()) "'${r.path}'" else ""
                    )
                }
                .toList()
        }
        liveModules = mapped
        loading = false
    }

    LaunchedEffect(showSearch) {
        if (showSearch) {
            delay(80L)
            softwareKeyboard?.show()
        }
    }

    val filteredList = remember(searchQuery.text, userOnly, liveModules) {
        val q = searchQuery.text.trim()
        liveModules.filter { item ->
            if (userOnly && isSystemModulePath(item.path)) return@filter false
            if (q.isEmpty()) true
            else item.path.contains(q, ignoreCase = true) ||
                item.regionCode.contains(q, ignoreCase = true) ||
                item.perms.contains(q, ignoreCase = true) ||
                item.startHex.contains(q, ignoreCase = true)
        }
    }

    val sheetTitle = if (initialRegion != null) "选择内存模块 ($initialRegion)" else "选择内存模块"
    val sheetSub = if (isPickerMode) {
        "点击顶部按钮控制显示/过滤，点击条目将地址填入输入框"
    } else {
        "点击顶部按钮控制显示/过滤，点击条目跳转定位"
    }

    NextSheetCard(
        title = sheetTitle,
        subtitle = sheetSub,
        onDismiss = {
            Session.clearModulePickerState()
            Session.popSheet()
        },
        maxBodyHeight = 720
    ) {
        Column {
            // 多选显示与过滤开关：权限 / 内存 / 路径 / 开始 / 用户 / 搜索
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val toggles = listOf(
                    Triple("权限", showPerms) { showPerms = !showPerms },
                    Triple("内存", showRegion) { showRegion = !showRegion },
                    Triple("路径", showPath) { showPath = !showPath },
                    Triple("开始", showStart) { showStart = !showStart },
                    Triple("用户", userOnly) { userOnly = !userOnly },
                    Triple("搜索", showSearch) { showSearch = !showSearch }
                )
                toggles.forEach { (tabName, isSel, onToggle) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(
                                if (isSel) Rose.ChipSelected else Rose.CardHigh,
                                GgShapes.Sm
                            )
                            .clickableWithTooltip(onClick = onToggle),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            tr(tabName),
                            color = if (isSel) Rose.Primary else Rose.TextSecond,
                            fontSize = fsp(12f),
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }

            if (showSearch) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Rose.CardHigh, GgShapes.Sm)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Rose.TextDim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    GgTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        initialFocus = true,
                        suppressSystemKeyboard = false,
                        modifier = Modifier.weight(1f).height(36.dp),
                        textStyle = monoStyle(14, Rose.Text),
                        cursorColor = Rose.Primary,
                        placeholder = "搜索模块名称、权限或路径..."
                    )
                    Text("${filteredList.size}", style = monoStyle(11, Rose.TextDim))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (loading) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tr("正在加载内存模块..."), color = Rose.TextDim, fontSize = fsp(13f))
                }
            } else if (filteredList.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tr("未找到符合条件的内存模块"), color = Rose.TextDim, fontSize = fsp(13f))
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = filteredList,
                        key = { "${it.startAddress}_${it.endAddress}_${it.perms}" }
                    ) { item ->
                        val hasHeader = showRegion || showStart || showPerms
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(Rose.Row, GgShapes.Sm)
                                .clickableWithTooltip {
                                    if (!Session.dispatchModulePicked(item.startAddress, item.endAddress)) {
                                        Session.goto(item.startAddress, recordHistory = true, highlightTarget = true)
                                        Session.toast("已跳转到模块起始地址：0x${item.startHex}")
                                        Session.closeSheet()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Column {
                                if (hasHeader) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (showRegion) {
                                            Text(
                                                "${item.regionCode}: ",
                                                color = Rose.Primary,
                                                fontSize = fsp(12.5f),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (showStart) {
                                            Text(
                                                "${item.startHex}-${item.endHex}",
                                                style = monoStyle(12, Rose.Text),
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (showPerms) Spacer(Modifier.width(8.dp))
                                        }
                                        if (showPerms) {
                                            Text(
                                                item.perms,
                                                style = monoStyle(11, Rose.TextSecond)
                                            )
                                        }
                                    }
                                }
                                if (showPath && item.path.isNotEmpty()) {
                                    if (hasHeader) Spacer(Modifier.height(3.dp))
                                    Text(
                                        item.path,
                                        color = Rose.TextDim,
                                        fontSize = fsp(11f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 执行脚本 (ScriptSheet)
// ============================================================
@Composable
fun ScriptSheet(ui: Ui) {
    val luaState by com.littlecodesauce.ggnext.lua.LuaScriptEngine.state.collectAsState()
    var scriptTf by remember {
        mutableStateOf(TextFieldValue(ui.scriptPath, TextRange(ui.scriptPath.length)))
    }
    LaunchedEffect(ui.scriptPath) {
        if (ui.scriptPath != scriptTf.text) {
            scriptTf = TextFieldValue(ui.scriptPath, TextRange(ui.scriptPath.length))
        }
    }
    var showRecent by remember { mutableStateOf(false) }
    var showPathSuggest by remember { mutableStateOf(false) }

    NextSheetCard(
        title = "执行脚本",
        subtitle = "运行本地明文脚本并管理脚本历史。",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 640
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Rose.CardHigh, GgShapes.Lg)
                    .border(1.dp, Rose.Divider, GgShapes.Lg)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                GgTextField(
                    value = scriptTf,
                    onValueChange = {
                        scriptTf = it
                        Session.setScriptPath(it.text)
                        showPathSuggest = it.text.startsWith("/")
                    },
                    onFocus = {
                        showPathSuggest = scriptTf.text.startsWith("/")
                    },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    textStyle = monoStyle(14, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = false,
                    placeholder = "/sdcard/Download/script.lua"
                )
            }

            PathAutocompleteDropdown(
                path = scriptTf.text,
                hasRoot = ui.hasRootPermission,
                directoriesOnly = false,
                visible = showPathSuggest,
                onSelect = { completed, isDir ->
                    scriptTf = TextFieldValue(completed, TextRange(completed.length))
                    Session.setScriptPath(completed)
                    showPathSuggest = isDir
                }
            )

            Spacer(Modifier.height(8.dp))
            if (luaState.running || luaState.logs.isNotEmpty() || luaState.lastError.isNotEmpty()) {
                LuaScriptLogConsole(luaState = luaState)
            }

            var editScriptDialogTarget by remember { mutableStateOf<String?>(null) }
            var editScriptTf by remember { mutableStateOf(TextFieldValue("")) }
            var editScriptNoteTf by remember { mutableStateOf(TextFieldValue("")) }

            AnimatedVisibility(showRecent, enter = expandEnter(), exit = expandExit()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("最近执行的脚本:"), color = Rose.TextSecond, fontSize = fsp(12.5f), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }

                    val sortedHistory = remember(ui.scriptHistory) {
                        ui.scriptHistory.sortedByDescending { it.pinned }
                    }

                    sortedHistory.forEach { entry ->
                        val path = entry.path
                        val isPinned = entry.pinned
                        val note = entry.note
                        key(path) {
                            var swipeOffsetX by remember { mutableFloatStateOf(0f) }
                            val animatedSwipe by animateFloatAsState(targetValue = swipeOffsetX, animationSpec = tween(140), label = "swipe")
                            val actionWidthPx = with(LocalDensity.current) { 156.dp.toPx() } // 3 actions * 52dp

                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(GgShapes.Sm)
                            ) {
                                // 左滑展示快捷操作按钮（固定、编辑、删除）
                                Row(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Rose.CardHigh),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 1. 固定 (Pin) - 持久化到 DataStore
                                    val pinCol = if (isPinned) Rose.Primary else Rose.Blue
                                    Box(
                                        Modifier
                                            .width(52.dp)
                                            .fillMaxHeight()
                                            .background(Rose.CardHigh)
                                            .clickable {
                                                swipeOffsetX = 0f
                                                Session.toggleScriptPin(path)
                                                Session.toast(if (isPinned) "已取消固定" else "已固定置顶")
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Rounded.PushPin, null, tint = pinCol, modifier = Modifier.size(18.dp))
                                            Text(tr(if (isPinned) "取消" else "固定"), color = pinCol, fontSize = fsp(11f), fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    // 2. 编辑 (Edit)
                                    Box(
                                        Modifier
                                            .width(52.dp)
                                            .fillMaxHeight()
                                            .background(Rose.CardHigh)
                                            .clickable {
                                                swipeOffsetX = 0f
                                                editScriptDialogTarget = path
                                                editScriptTf = TextFieldValue(path, TextRange(path.length))
                                                editScriptNoteTf = TextFieldValue(note)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Rounded.Edit, null, tint = Rose.Yellow, modifier = Modifier.size(18.dp))
                                            Text(tr("编辑"), color = Rose.Yellow, fontSize = fsp(11f), fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    // 3. 删除 (Delete)
                                    Box(
                                        Modifier
                                            .width(52.dp)
                                            .fillMaxHeight()
                                            .background(Rose.CardHigh)
                                            .clickable {
                                                swipeOffsetX = 0f
                                                Session.removeScriptHistory(path)
                                                Session.toast("已删除脚本记录")
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Rounded.Delete, null, tint = Rose.Red, modifier = Modifier.size(18.dp))
                                            Text(tr("删除"), color = Rose.Red, fontSize = fsp(11f), fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }

                                // 前景内容行
                                Row(
                                    Modifier
                                        .fillMaxSize()
                                        .offset { IntOffset(animatedSwipe.roundToInt(), 0) }
                                        .background(Rose.CardHigh)
                                        .pointerInput(path) {
                                            detectHorizontalDragGestures(
                                                onDragEnd = {
                                                    swipeOffsetX = if (swipeOffsetX < -actionWidthPx / 2f) -actionWidthPx else 0f
                                                },
                                                onDragCancel = { swipeOffsetX = 0f },
                                                onHorizontalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    swipeOffsetX = (swipeOffsetX + dragAmount).coerceIn(-actionWidthPx, 0f)
                                                }
                                            )
                                        }
                                        .clickable {
                                            if (swipeOffsetX != 0f) {
                                                swipeOffsetX = 0f
                                            } else {
                                                scriptTf = TextFieldValue(path, TextRange(path.length))
                                                Session.setScriptPath(path)
                                                showRecent = false
                                            }
                                        }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(GgIcons.FileLua, null, tint = Rose.Green, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isPinned) {
                                                Icon(Icons.Rounded.PushPin, "置顶", tint = Rose.Primary, modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = path,
                                                style = monoStyle(12, Rose.Text),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (note.isNotBlank()) {
                                            Text(
                                                text = note,
                                                color = Rose.TextSecond,
                                                fontSize = fsp(10.5f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (swipeOffsetX == 0f) {
                                        Icon(Icons.Rounded.ChevronLeft, "左滑快捷操作", tint = Rose.TextDim, modifier = Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    }

                    // 编辑脚本路径与备注对话框
                    if (editScriptDialogTarget != null) {
                        val targetOldPath = editScriptDialogTarget!!
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Rose.Card, GgShapes.Md)
                                .border(1.dp, Rose.Primary.copy(alpha = 0.5f), GgShapes.Md)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(tr("编辑脚本记录与备注"), color = Rose.Text, fontSize = fsp(14f), fontWeight = FontWeight.Bold)
                            FieldRow("路径", editScriptTf) { editScriptTf = it }
                            FieldRow("备注", editScriptNoteTf, placeholder = "添加可选备注/说明") { editScriptNoteTf = it }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RoseButton("取消", Modifier.weight(1f)) { editScriptDialogTarget = null }
                                RoseButton("保存", Modifier.weight(1f), filled = true) {
                                    val newP = editScriptTf.text.trim()
                                    if (newP.isNotEmpty()) {
                                        Session.updateScriptEntry(targetOldPath, newP, editScriptNoteTf.text.trim())
                                        Session.toast("已更新脚本记录")
                                    }
                                    editScriptDialogTarget = null
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var histPressed by remember { mutableStateOf(false) }
                val histScale by animateFloatAsState(if (histPressed) 0.88f else 1f, Motion.Fast, label = "scriptHist")
                Box(
                    Modifier
                        .size(38.dp)
                        .scale(histScale)
                        .clip(CircleShape)
                        .background(if (showRecent) Rose.ChipSelected else Color.Transparent, CircleShape)
                        .clickableWithTooltip(
                            onPressChange = { histPressed = it },
                            onClick = { showRecent = !showRecent }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = "历史记录",
                        tint = if (showRecent) Rose.Primary else Rose.TextSecond,
                        modifier = Modifier.size(21.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = "浏览",
                    modifier = Modifier.width(68.dp).height(38.dp),
                    filled = false
                ) {
                    Session.openFileBrowser(
                        initialPath = scriptTf.text,
                        selectDirectory = false,
                        returnSheet = Sheet.SCRIPT,
                        target = FileBrowserTarget.SCRIPT
                    )
                }
                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = if (luaState.running) "停止" else "执行",
                    modifier = Modifier.width(84.dp).height(38.dp),
                    filled = true,
                    enabled = luaState.running || scriptTf.text.isNotBlank()
                ) {
                    if (luaState.running) {
                        Session.stopScript()
                    } else {
                        Session.runScript(scriptTf.text)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LuaScriptLogConsole(
    luaState: com.littlecodesauce.ggnext.lua.LuaScriptState
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var selectedLevel by remember { mutableStateOf<com.littlecodesauce.ggnext.lua.LuaLogLevel?>(null) }
    var keyword by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val filteredLogs = remember(luaState.logs, selectedLevel, keyword) {
        val kw = keyword.trim()
        luaState.logs.filter { entry ->
            val levelMatch = (selectedLevel == null || entry.level == selectedLevel)
            val kwMatch = kw.isEmpty() ||
                entry.message.contains(kw, ignoreCase = true) ||
                entry.level.tag.contains(kw, ignoreCase = true)
            levelMatch && kwMatch
        }
    }

    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Rose.CardHigh, GgShapes.Sm)
            .border(
                1.dp,
                when {
                    luaState.running -> Rose.Primary.copy(alpha = 0.55f)
                    luaState.lastError.isNotEmpty() -> Rose.Red.copy(alpha = 0.55f)
                    else -> Rose.Divider
                },
                GgShapes.Sm
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 标题行 + 快捷操作按钮（自动滚底/暂停、复制全部、导出 .txt、清空）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = when {
                    luaState.running -> tr("脚本运行中：") + luaState.scriptName
                    luaState.lastError.isNotEmpty() -> tr("脚本执行出错")
                    else -> "${tr("脚本控制台输出")} (${filteredLogs.size}/${luaState.logs.size})"
                },
                color = when {
                    luaState.running -> Rose.Primary
                    luaState.lastError.isNotEmpty() -> Rose.Red
                    else -> Rose.TextSecond
                },
                fontSize = fsp(11.5f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // 自动滚底 / 暂停滚动
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(GgShapes.Xs)
                    .background(if (autoScroll) Rose.Primary.copy(alpha = 0.16f) else Rose.Surface)
                    .clickableWithTooltip(
                        tooltip = if (autoScroll) "暂停自动滚底" else "恢复自动滚底",
                        onClick = { autoScroll = !autoScroll }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (autoScroll) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = tr(if (autoScroll) "暂停自动滚底" else "恢复自动滚底"),
                    tint = if (autoScroll) Rose.Primary else Rose.TextSecond,
                    modifier = Modifier.size(14.dp)
                )
            }

            // 一键复制全部
            if (filteredLogs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(GgShapes.Xs)
                        .background(Rose.Surface)
                        .clickableWithTooltip(
                            tooltip = "复制全部日志",
                            onClick = {
                                val text = filteredLogs.joinToString("\n") { it.formatExportLine() }
                                clipboard.setText(AnnotatedString(text))
                                Session.toast("已复制 ${filteredLogs.size} 条日志")
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = tr("复制全部日志"),
                        tint = Rose.TextSecond,
                        modifier = Modifier.size(13.dp)
                    )
                }

                // 导出为 .txt
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(GgShapes.Xs)
                        .background(Rose.Surface)
                        .clickableWithTooltip(
                            tooltip = "导出日志 (.txt)",
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val res = com.littlecodesauce.ggnext.lua.LuaScriptEngine.exportLogsToFile(context, filteredLogs)
                                    withContext(Dispatchers.Main) {
                                        res.onSuccess { path ->
                                            Session.toast("已导出日志到 $path")
                                        }.onFailure { err ->
                                            Session.toast("导出日志失败：${err.message ?: "IO 错误"}")
                                        }
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SaveAlt,
                        contentDescription = tr("导出日志 (.txt)"),
                        tint = Rose.TextSecond,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // 清空日志
            if (luaState.logs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(GgShapes.Xs)
                        .background(Rose.Surface)
                        .clickableWithTooltip(
                            tooltip = "清空日志",
                            onClick = { com.littlecodesauce.ggnext.lua.LuaScriptEngine.clearLogs() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = tr("清空日志"),
                        tint = Rose.TextDim,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // 级别筛选芯片行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val allSelected = selectedLevel == null
            Box(
                modifier = Modifier
                    .clip(GgShapes.Xs)
                    .background(if (allSelected) Rose.ChipSelected else Rose.Surface)
                    .border(
                        width = if (allSelected) GgBorder.Default else GgBorder.Hairline,
                        color = if (allSelected) Rose.Primary else Rose.Divider,
                        shape = GgShapes.Xs
                    )
                    .clickable { selectedLevel = null }
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = tr("全部"),
                    color = if (allSelected) Rose.Primary else Rose.TextSecond,
                    fontSize = fsp(10f),
                    fontWeight = if (allSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            com.littlecodesauce.ggnext.lua.LuaLogLevel.entries.forEach { lvl ->
                val isSel = selectedLevel == lvl
                val lvlColor = when (lvl) {
                    com.littlecodesauce.ggnext.lua.LuaLogLevel.ERROR -> Rose.Red
                    com.littlecodesauce.ggnext.lua.LuaLogLevel.WARN -> Rose.Yellow
                    com.littlecodesauce.ggnext.lua.LuaLogLevel.INFO -> Rose.Primary
                    com.littlecodesauce.ggnext.lua.LuaLogLevel.PRINT -> Rose.Green
                }
                Box(
                    modifier = Modifier
                        .clip(GgShapes.Xs)
                        .background(if (isSel) lvlColor.copy(alpha = 0.16f) else Rose.Surface)
                        .border(
                            width = if (isSel) GgBorder.Default else GgBorder.Hairline,
                            color = if (isSel) lvlColor else Rose.Divider,
                            shape = GgShapes.Xs
                        )
                        .clickable { selectedLevel = if (isSel) null else lvl }
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = lvl.label,
                        color = if (isSel) lvlColor else Rose.TextSecond,
                        fontSize = fsp(10f),
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        // 关键词搜索框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(Rose.Surface, GgShapes.Xs)
                .border(GgBorder.Hairline, Rose.Divider, GgShapes.Xs)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = Rose.TextDim,
                modifier = Modifier.size(13.dp)
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (keyword.isEmpty()) {
                    Text(
                        text = tr("筛选日志关键词（长按单条可复制）…"),
                        color = Rose.TextDim,
                        fontSize = fsp(10.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    singleLine = true,
                    textStyle = monoStyle(11, Rose.Text),
                    cursorBrush = SolidColor(Rose.Primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (keyword.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = tr("清空"),
                    tint = Rose.TextDim,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { keyword = "" }
                )
            }
        }

        // 基于 LazyColumn 的高性能有界环形缓冲日志列表（支持长按单条复制）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(Rose.Surface, GgShapes.Xs)
                .border(GgBorder.Hairline, Rose.Divider.copy(alpha = 0.7f), GgShapes.Xs)
        ) {
            if (filteredLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = tr(if (luaState.logs.isEmpty()) "暂无日志" else "无匹配日志"),
                        color = Rose.TextDim,
                        fontSize = fsp(11f)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        items = filteredLogs,
                        key = { it.id }
                    ) { entry ->
                        val lvlColor = when (entry.level) {
                            com.littlecodesauce.ggnext.lua.LuaLogLevel.ERROR -> Rose.Red
                            com.littlecodesauce.ggnext.lua.LuaLogLevel.WARN -> Rose.Yellow
                            com.littlecodesauce.ggnext.lua.LuaLogLevel.INFO -> Rose.Primary
                            com.littlecodesauce.ggnext.lua.LuaLogLevel.PRINT -> Rose.Green
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(GgShapes.Xs)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        clipboard.setText(AnnotatedString(entry.formatExportLine()))
                                        Session.toast("已复制日志条目")
                                    }
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = entry.formatShortTime(),
                                style = monoStyle(9, Rose.TextDim),
                                maxLines = 1
                            )
                            Text(
                                text = entry.level.tag.take(1),
                                style = monoStyle(9, lvlColor),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = entry.message,
                                style = monoStyle(
                                    10,
                                    if (entry.level == com.littlecodesauce.ggnext.lua.LuaLogLevel.ERROR) Rose.Red else Rose.Text
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 内置文件浏览器 (FileBrowserSheet)
// ============================================================

private data class BrowserEntry(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val isParentLink: Boolean = false
) {
    val ext: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
}

/** 目录异步缓存（避免重复遍历与主线程卡顿，同时供文件浏览器与同级路径自动补全共享） */
private object DirListCache {
    private const val TTL_MS = 8_000L
    private val map = ConcurrentHashMap<String, Pair<Long, List<BrowserEntry>>>()

    fun get(dir: String): List<BrowserEntry>? {
        val hit = map[dir] ?: return null
        return if (System.currentTimeMillis() - hit.first <= TTL_MS) hit.second else null
    }

    fun put(dir: String, entries: List<BrowserEntry>) {
        if (map.size > 64) map.clear()
        map[dir] = System.currentTimeMillis() to entries
    }

    fun invalidate(dir: String? = null) {
        if (dir == null) map.clear() else map.remove(dir)
    }
}

private val LS_ISO_REGEX = Regex(
    "^([dlcbps-])\\S+\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}(?::\\d{2})?\\s+(.+)$"
)
private val LS_ALT_REGEX = Regex(
    "^([dlcbps-])\\S+\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+\\S+\\s+\\S+\\s+\\S+\\s+(.+)$"
)
private val KNOWN_DIR_SYMLINKS = setOf("sdcard", "storage", "vendor", "bin", "etc", "lib", "lib64", "odm", "product")

private fun needsAllFilesStorageAccess(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
}

private fun joinDirChild(parentDir: String, childName: String): String {
    return if (parentDir == "/") "/$childName" else "$parentDir/$childName"
}

/**
 * 仅列出指定目录的直接子项（绝不递归扫描子文件夹），必须在 Dispatchers.IO 调用。
 * Android 11+ 未授予 MANAGE_EXTERNAL_STORAGE 时，Scoped Storage 会隐藏非媒体文件（如 .lua/.txt/.ttf）；
 * 若具备 Root 权限，则通过单次 `ls -lnA` 补全全部目录与文件，保证性能与完整性。
 */
private fun listDirectoryDirect(
    dirPath: String,
    hasRoot: Boolean,
    forceRefresh: Boolean = false
): List<BrowserEntry> {
    val cleanDir = dirPath.trimEnd('/').ifEmpty { "/" }
    if (!forceRefresh) {
        DirListCache.get(cleanDir)?.let { return it }
    }

    val out = LinkedHashMap<String, BrowserEntry>()

    fun addFromJavaDir(target: File, baseDisplayDir: String) {
        val list = runCatching { target.listFiles() }.getOrNull() ?: return
        for (f in list) {
            val name = f.name
            if (name == "." || name == "..") continue
            val isDir = f.isDirectory
            out[name] = BrowserEntry(
                name = name,
                fullPath = joinDirChild(baseDisplayDir, name),
                isDirectory = isDir,
                sizeBytes = if (isDir) 0L else runCatching { f.length() }.getOrDefault(0L)
            )
        }
    }

    // 1. 优先尝试原生 File API（毫秒级，零子进程开销）
    runCatching {
        val dirFile = File(cleanDir)
        if (dirFile.exists() && dirFile.isDirectory) {
            addFromJavaDir(dirFile, cleanDir)
        }
    }

    // 2. 若 /sdcard 读取为空，回退读取 /storage/emulated/0
    if (out.isEmpty() && cleanDir.startsWith("/sdcard")) {
        runCatching {
            val emulatedPath = cleanDir.replaceFirst("/sdcard", "/storage/emulated/0")
            val emuFile = File(emulatedPath)
            if (emuFile.exists() && emuFile.isDirectory) {
                addFromJavaDir(emuFile, cleanDir)
            }
        }
    }

    // 3. 若处于 Scoped Storage 受限或目录无权读取，且具备 Root，使用单次 su ls 补全全部文件与文件夹
    val shouldUseRoot = hasRoot && (out.isEmpty() || needsAllFilesStorageAccess() || cleanDir == "/" || cleanDir.startsWith("/data"))
    if (shouldUseRoot) {
        val shellTarget = if (cleanDir == "/sdcard") "/storage/emulated/0"
        else if (cleanDir.startsWith("/sdcard/")) cleanDir.replaceFirst("/sdcard/", "/storage/emulated/0/")
        else cleanDir
        val escaped = shellTarget.replace("'", "'\\''")
        val res = Shell.runAs("ls -lnA '$escaped' 2>/dev/null", asRoot = true)
        var parsedCount = 0
        if (res.exitCode == 0 && res.out.isNotEmpty()) {
            for (rawLine in res.out) {
                val line = rawLine.trimEnd('\r')
                if (line.length < 10 || line.startsWith("total ")) continue
                val m = LS_ISO_REGEX.matchEntire(line) ?: LS_ALT_REGEX.matchEntire(line) ?: continue
                val typeChar = m.groupValues[1][0]
                val sizeVal = m.groupValues[2].toLongOrNull() ?: 0L
                val rawName = m.groupValues[3]
                val isSymlink = typeChar == 'l'
                val name = if (isSymlink && rawName.contains(" -> ")) {
                    rawName.substringBefore(" -> ")
                } else {
                    rawName
                }
                if (name.isEmpty() || name == "." || name == "..") continue
                val fullPath = joinDirChild(cleanDir, name)
                val isDir = when (typeChar) {
                    'd' -> true
                    'l' -> {
                        val target = rawName.substringAfter(" -> ", "")
                        runCatching { File(fullPath).isDirectory }.getOrDefault(false) ||
                            target.endsWith("/") ||
                            name in KNOWN_DIR_SYMLINKS
                    }
                    else -> false
                }
                val existing = out[name]
                if (existing == null || (!existing.isDirectory && sizeVal > 0L && existing.sizeBytes == 0L)) {
                    out[name] = BrowserEntry(
                        name = name,
                        fullPath = fullPath,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else sizeVal
                    )
                }
                parsedCount++
            }
        }
        // 兜底：若某些设备 ls -lnA 输出格式异常导致未解析，回退到最简洁的 ls -1pA
        if (parsedCount == 0 && out.isEmpty()) {
            val simpleRes = Shell.runAs("ls -1pA '$escaped' 2>/dev/null", asRoot = true)
            if (simpleRes.exitCode == 0) {
                for (rawLine in simpleRes.out) {
                    val trimmed = rawLine.trimEnd('\r')
                    if (trimmed.isEmpty() || trimmed == "./" || trimmed == "../") continue
                    val isDir = trimmed.endsWith("/")
                    val name = if (isDir) trimmed.dropLast(1) else trimmed
                    if (name.isEmpty() || name == "." || name == "..") continue
                    val fullPath = joinDirChild(cleanDir, name)
                    out[name] = BrowserEntry(
                        name = name,
                        fullPath = fullPath,
                        isDirectory = isDir || (cleanDir == "/" && name in KNOWN_DIR_SYMLINKS),
                        sizeBytes = if (isDir) 0L else runCatching { File(fullPath).length() }.getOrDefault(0L)
                    )
                }
            }
        }
    }

    // 4. 根目录在非 Root / SELinux 受限场景下提供标准挂载点入口
    if (out.isEmpty() && cleanDir == "/") {
        listOf("/sdcard", "/storage", "/data", "/system").forEach { stdPath ->
            val name = stdPath.removePrefix("/")
            out[name] = BrowserEntry(
                name = name,
                fullPath = stdPath,
                isDirectory = true,
                sizeBytes = 0L
            )
        }
    }

    val sorted = out.values.sortedWith(
        compareByDescending<BrowserEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
    )
    DirListCache.put(cleanDir, sorted)
    return sorted
}

/**
 * 严格同级目录路径自动补全下拉列表：
 * - 例如输入 `/sd` -> 仅匹配 `/` 下的直接子项 `/sdcard`，绝不越级展开到 `/sdcard/a/b/...`
 * - 例如输入 `/storage/emulated/0/` -> 展示 `/storage/emulated/0` 的直接子文件与子文件夹（格式 `.../<name>`）
 */
@Composable
private fun PathAutocompleteDropdown(
    path: String,
    hasRoot: Boolean,
    directoriesOnly: Boolean,
    visible: Boolean,
    onSelect: (completedPath: String, isDirectory: Boolean) -> Unit
) {
    if (!visible || !path.startsWith("/")) return

    val lastSlash = path.lastIndexOf('/')
    val parentDir = if (lastSlash <= 0) "/" else path.substring(0, lastSlash)
    val prefix = path.substring(lastSlash + 1)

    var parentEntries by remember(parentDir) {
        mutableStateOf(DirListCache.get(parentDir) ?: emptyList())
    }

    LaunchedEffect(parentDir, hasRoot, visible) {
        if (!visible) return@LaunchedEffect
        val cached = DirListCache.get(parentDir)
        if (cached != null) {
            parentEntries = cached
        } else {
            delay(70)
            val loaded = withContext(Dispatchers.IO) {
                listDirectoryDirect(parentDir, hasRoot = hasRoot, forceRefresh = false)
            }
            parentEntries = loaded
        }
    }

    val suggestions = remember(parentEntries, prefix, directoriesOnly, path) {
        parentEntries.asSequence()
            .filter { entry ->
                if (directoriesOnly && !entry.isDirectory) return@filter false
                if (prefix.isNotEmpty() && !entry.name.startsWith(prefix, ignoreCase = true)) return@filter false
                // 已完整选中单个文件后收起匹配项
                if (!entry.isDirectory && entry.fullPath.equals(path, ignoreCase = false)) return@filter false
                true
            }
            .sortedBy { it.name.lowercase() }
            .take(80)
            .toList()
    }

    if (suggestions.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(GgShapes.Md)
            .background(Rose.CardHigh)
            .border(1.dp, Rose.Divider, GgShapes.Md)
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
    ) {
        suggestions.forEach { entry ->
            val displayText = if (parentDir == "/") {
                "/${entry.name}"
            } else {
                ".../${entry.name}"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val completed = if (entry.isDirectory) {
                            if (directoriesOnly) entry.fullPath else "${entry.fullPath}/"
                        } else {
                            entry.fullPath
                        }
                        onSelect(completed, entry.isDirectory)
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Rounded.Folder else GgIcons.FileTxt,
                    contentDescription = null,
                    tint = if (entry.isDirectory) Rose.Yellow else Rose.TextSecond,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = displayText,
                    style = monoStyle(13, Rose.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!entry.isDirectory) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatFileSize(entry.sizeBytes),
                        style = monoStyle(10, Rose.TextDim)
                    )
                }
            }
        }
    }
}

@Composable
fun FileBrowserSheet(ui: Ui) {
    val ctx = LocalContext.current
    val cfg = ui.fileBrowser
    var currentDir by remember(cfg.currentDir) {
        mutableStateOf(cfg.currentDir.trimEnd('/').ifEmpty { "/sdcard" })
    }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var showHidden by remember { mutableStateOf(false) }
    var refreshNonce by remember { mutableIntStateOf(0) }

    var rawEntries by remember(currentDir) {
        mutableStateOf(DirListCache.get(currentDir) ?: emptyList())
    }
    var isLoading by remember(currentDir) {
        mutableStateOf(DirListCache.get(currentDir) == null)
    }

    LaunchedEffect(currentDir, ui.hasRootPermission, refreshNonce) {
        val force = refreshNonce > 0
        if (!force) {
            val cached = DirListCache.get(currentDir)
            if (cached != null) {
                rawEntries = cached
                isLoading = false
                return@LaunchedEffect
            }
        }
        isLoading = true
        val loaded = withContext(Dispatchers.IO) {
            listDirectoryDirect(currentDir, hasRoot = ui.hasRootPermission, forceRefresh = force)
        }
        rawEntries = loaded
        isLoading = false
    }

    val displayEntries = remember(rawEntries, currentDir, searchQuery.text, showHidden, cfg.selectDirectory) {
        val q = searchQuery.text.trim()
        val showDots = showHidden || q.startsWith(".")
        val filtered = rawEntries.filter { e ->
            if (!showDots && e.name.startsWith(".")) return@filter false
            if (cfg.selectDirectory && !e.isDirectory) return@filter false
            q.isEmpty() || e.name.contains(q, ignoreCase = true)
        }
        if (currentDir != "/" && q.isEmpty()) {
            val parentPath = currentDir.substringBeforeLast('/', "").ifEmpty { "/" }
            listOf(
                BrowserEntry(
                    name = "..",
                    fullPath = parentPath,
                    isDirectory = true,
                    isParentLink = true
                )
            ) + filtered
        } else {
            filtered
        }
    }

    val missingAllFilesPerm = remember(refreshNonce, ui.hasRootPermission) {
        !ui.hasRootPermission && needsAllFilesStorageAccess()
    }

    val pathSegments = remember(currentDir) {
        val clean = currentDir.trim('/').split('/').filter { it.isNotEmpty() }
        val result = mutableListOf<Pair<String, String>>(tr("根目录 /") to "/")
        var acc = ""
        for (seg in clean) {
            acc = "$acc/$seg"
            result.add(seg to acc)
        }
        result
    }

    NextSheetCard(
        title = when {
            cfg.target == FileBrowserTarget.FONT -> "选择 TTF 字体文件"
            cfg.selectDirectory -> "浏览并选择文件夹"
            else -> "文件浏览器"
        },
        onDismiss = { Session.openSheet(cfg.returnSheet) },
        maxBodyHeight = 760
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                pathSegments.forEachIndexed { idx, (label, path) ->
                    val isLast = idx == pathSegments.lastIndex
                    Box(
                        Modifier
                            .background(
                                if (isLast) Rose.ChipSelected else Rose.CardHigh,
                                GgShapes.Xs
                            )
                            .clickableWithTooltip { currentDir = path }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isLast) Rose.Primary else Rose.TextSecond,
                            fontSize = fsp(11.5f),
                            fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                    if (!isLast) {
                        Text("/", color = Rose.TextDim, fontSize = fsp(12f))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(Rose.CardHigh, GgShapes.Sm)
                        .border(1.dp, Rose.Divider, GgShapes.Sm)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Rose.TextDim, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    GgTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        textStyle = monoStyle(12, Rose.Text),
                        cursorColor = Rose.Primary,
                        suppressSystemKeyboard = false,
                        placeholder = "搜索文件名或后缀…"
                    )
                }
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(GgShapes.Sm)
                        .background(if (showHidden) Rose.ChipSelected else Rose.CardHigh)
                        .border(1.dp, if (showHidden) Rose.Primary.copy(alpha = 0.5f) else Rose.Divider, GgShapes.Sm)
                        .clickableWithTooltip(tooltip = "显示隐藏文件") { showHidden = !showHidden }
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ".*",
                        style = monoStyle(12, if (showHidden) Rose.Primary else Rose.TextSecond, FontWeight.SemiBold)
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(GgShapes.Sm)
                        .background(Rose.CardHigh)
                        .border(1.dp, Rose.Divider, GgShapes.Sm)
                        .clickableWithTooltip(tooltip = "刷新目录") {
                            DirListCache.invalidate(currentDir)
                            refreshNonce++
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = Rose.TextSecond, modifier = Modifier.size(17.dp))
                }
            }

            if (missingAllFilesPerm) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(GgShapes.Sm)
                        .background(Rose.Yellow.copy(alpha = if (Rose.IsDark) 0.14f else 0.12f))
                        .border(1.dp, Rose.Yellow.copy(alpha = 0.4f), GgShapes.Sm)
                        .clickable {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                            }.onFailure {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = Rose.Yellow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tr("未授予所有文件访问权限，可能无法显示部分文件 · 点击授权"),
                        color = Rose.Yellow,
                        fontSize = fsp(11.5f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (isLoading && displayEntries.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(
                            color = Rose.Primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(tr("正在读取目录…"), color = Rose.TextSecond, fontSize = fsp(12.5f))
                    }
                }
            } else if (displayEntries.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(Rose.Row, GgShapes.Md)
                        .border(1.dp, Rose.Divider, GgShapes.Md),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tr(if (searchQuery.text.isNotBlank()) "未找到匹配的文件或文件夹" else "当前目录为空或无访问权限"),
                        color = Rose.TextSecond,
                        fontSize = fsp(12.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    itemsIndexed(
                        displayEntries,
                        key = { _, it -> if (it.isParentLink) "__parent__" else it.fullPath }
                    ) { _, entry ->
                        BrowserEntryRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    currentDir = entry.fullPath
                                    searchQuery = TextFieldValue("")
                                } else {
                                    Session.onFileBrowserPicked(entry.fullPath)
                                }
                            }
                        )
                    }
                }
            }

            if (cfg.selectDirectory) {
                Spacer(Modifier.height(10.dp))
                RoseButton(
                    label = "选择当前文件夹",
                    filled = true,
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Session.onFileBrowserPicked(currentDir)
                }
            }
        }
    }
}

@Composable
private fun BrowserEntryRow(
    entry: BrowserEntry,
    onClick: () -> Unit
) {
    val folderBg = lerp(Rose.CardHigh, Rose.Primary, if (Rose.IsDark) 0.14f else 0.11f)
    val bg = when {
        entry.isParentLink -> Rose.CardHigh
        entry.isDirectory -> folderBg
        else -> Rose.Row
    }
    val borderColor = if (entry.isDirectory && !entry.isParentLink) {
        Rose.Primary.copy(alpha = 0.32f)
    } else {
        Rose.Divider
    }

    val isDark = Rose.IsDark
    val (icon, iconColor, badgeLabel) = remember(entry.isParentLink, entry.isDirectory, entry.ext, isDark) {
        when {
            entry.isParentLink -> Triple(Icons.Rounded.ArrowUpward, Rose.TextSecond, "UP")
            entry.isDirectory -> Triple(GgIcons.FolderIcon, Rose.Yellow, "DIR")
            else -> when (entry.ext) {
                "lua" -> Triple(GgIcons.FileLua, Rose.Green, "LUA")
                "so" -> Triple(GgIcons.FileSo, Rose.Violet, "SO")
                "txt", "log", "cfg" -> Triple(GgIcons.FileTxt, Rose.Blue, "TXT")
                "md" -> Triple(GgIcons.FileMd, Rose.Cyan, "MD")
                "bin", "dmp", "dat" -> Triple(GgIcons.FileBin, Rose.Orange, "BIN")
                "ttf", "otf", "ttc" -> Triple(GgIcons.FileTxt, Rose.Primary, entry.ext.uppercase())
                else -> Triple(GgIcons.FileTxt, Rose.TextSecond, entry.ext.uppercase().take(4).ifEmpty { "FILE" })
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(GgShapes.Md)
            .background(bg)
            .border(1.dp, borderColor, GgShapes.Md)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(
                    iconColor.copy(alpha = if (Rose.IsDark) 0.18f else 0.14f),
                    GgShapes.Sm
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, entry.name, tint = iconColor, modifier = Modifier.size(19.dp))
        }

        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = Rose.Text,
                fontSize = fsp(13.5f),
                fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    entry.isParentLink -> tr("返回上一级")
                    entry.isDirectory -> tr("文件夹")
                    else -> formatFileSize(entry.sizeBytes)
                },
                color = Rose.TextSecond,
                fontSize = fsp(11f)
            )
        }

        Box(
            Modifier
                .background(
                    iconColor.copy(alpha = if (Rose.IsDark) 0.20f else 0.15f),
                    GgShapes.Xs
                )
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(
                text = badgeLabel,
                style = monoStyle(10, iconColor, weight = FontWeight.Bold)
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024f * 1024f))
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}

// ============================================================
// 复制内存 (CopyMemorySheet) - 参考图 5 (将内存数据粘贴复制到另一块内存)
// ============================================================
@Composable
fun CopyMemorySheet(ui: Ui) {
    val initFrom = ui.copyFromDraft ?: java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
    val initTo = ui.copyToDraft ?: "00000000"
    val initBytes = ui.copyBytesDraft ?: "0"
    var fromTf by remember { mutableStateOf(TextFieldValue(initFrom, TextRange(0, initFrom.length))) }
    var toTf by remember { mutableStateOf(TextFieldValue(initTo, TextRange(initTo.length))) }
    var bytesTf by remember { mutableStateOf(TextFieldValue(initBytes, TextRange(initBytes.length))) }
    var focusField by remember { mutableStateOf(0) } // 0: from, 1: to, 2: bytes
    var showKeyboard by remember { mutableStateOf(true) }

    NextSheetCard(
        title = "复制内存",
        subtitle = "将指定起始地址的数据复制并粘贴覆盖到目标内存区域",
        onDismiss = {
            Session.saveCopyDraft(null, null, null)
            Session.closeSheet()
        },
        maxBodyHeight = 720,
        showSideKeyboard = showKeyboard,
        sideKeyboard = {
            BuiltInKeyboard(
                value = when (focusField) {
                    0 -> fromTf
                    1 -> toTf
                    else -> bytesTf
                },
                onValueChange = { next ->
                    when (focusField) {
                        0 -> fromTf = next
                        1 -> toTf = next
                        else -> bytesTf = next
                    }
                },
                onHistory = { Session.openSheet(Sheet.HISTORY) },
                onDismiss = { showKeyboard = false }
            )
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // 复制从 : [00000000]
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, GgShapes.Md)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("复制从 :"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = fromTf,
                    onValueChange = {
                        focusField = 0
                        fromTf = it
                    },
                    onFocus = { focusField = 0 },
                    initialFocus = true,
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Rose.Chip, GgShapes.Xs)
                        .clickableWithTooltip(tooltip = tr("选择内存模块")) {
                            Session.saveCopyDraft(fromTf.text, toTf.text, bytesTf.text)
                            Session.openModulePicker(target = "copy_from")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GgIcons.MenuThreeLines, contentDescription = tr("选择内存模块"), tint = Rose.Primary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 粘贴到 : [00000000]
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, GgShapes.Md)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("粘贴到 :"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = toTf,
                    onValueChange = {
                        focusField = 1
                        toTf = it
                    },
                    onFocus = { focusField = 1 },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Rose.Chip, GgShapes.Xs)
                        .clickableWithTooltip(tooltip = tr("选择内存模块")) {
                            Session.saveCopyDraft(fromTf.text, toTf.text, bytesTf.text)
                            Session.openModulePicker(target = "copy_to")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GgIcons.MenuThreeLines, contentDescription = tr("选择内存模块"), tint = Rose.Primary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 字节数 : [0]
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, GgShapes.Md)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("字节数 :"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = bytesTf,
                    onValueChange = {
                        focusField = 2
                        bytesTf = it
                    },
                    onFocus = { focusField = 2 },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clickableWithTooltip {
                            bytesTf = TextFieldValue("64", TextRange(2))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GgIcons.ContentDuplicate, null, tint = Rose.Primary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // 底部按钮：取消 / 确定 (图 5)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("取消", Modifier.weight(1f)) { Session.saveCopyDraft(null, null, null); Session.closeSheet() }
                RoseButton("确定", Modifier.weight(1f), filled = true) {
                    Session.saveCopyDraft(null, null, null)
                    val fromAddr = fromTf.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val toAddr = toTf.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val count = bytesTf.text.trim().removePrefix("0x").removeSuffix("h").toLongOrNull()
                        ?: bytesTf.text.trim().toLongOrNull(16)
                        ?: 0L
                    if (count <= 0) {
                        Session.toast("复制字节数必须大于 0")
                    } else {
                        Session.copyMemory(fromAddr, toAddr, count)
                    }
                }
            }
        }
    }
}

// ============================================================
// 界面调整 8：导出内存（去除快捷的 +4KB / +64KB / +1MB / +16MB 按钮，保留文件夹浏览按钮）
// ============================================================
@Composable
fun DumpSheet(ui: Ui) {
    var fromTf by remember {
        val hex = ui.dumpFromDraft ?: java.lang.Long.toHexString(ui.memoryBase).uppercase()
        mutableStateOf(TextFieldValue(hex, TextRange(hex.length)))
    }
    var toTf by remember {
        val endHex = ui.dumpToDraft ?: java.lang.Long.toHexString(ui.memoryBase + 0x10000L).uppercase()
        mutableStateOf(TextFieldValue(endHex, TextRange(0, endHex.length)))
    }
    var pathTf by remember {
        mutableStateOf(TextFieldValue(ui.dumpDirPath, TextRange(ui.dumpDirPath.length)))
    }
    LaunchedEffect(ui.dumpDirPath) {
        if (ui.dumpDirPath != pathTf.text) {
            pathTf = TextFieldValue(ui.dumpDirPath, TextRange(ui.dumpDirPath.length))
        }
    }
    var focusTarget by remember { mutableIntStateOf(1) } // 0=from, 1=to, 2=path
    var showKeyboard by remember { mutableStateOf(true) }
    var showPathSuggest by remember { mutableStateOf(false) }

    val startAddr = fromTf.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
    val endAddr = toTf.text.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
    val regionBytes = (endAddr - startAddr).coerceAtLeast(0L)

    NextSheetCard(
        title = "导出内存",
        subtitle = "将起始地址与结束地址之间的内存区域导出到文件",
        onDismiss = {
            Session.saveDumpDraft(null, null)
            Session.closeSheet()
        },
        maxBodyHeight = 760,
        showSideKeyboard = showKeyboard && focusTarget != 2,
        sideKeyboard = {
            BuiltInKeyboard(
                value = if (focusTarget == 1) toTf else fromTf,
                onValueChange = { next ->
                    if (focusTarget == 1) toTf = next else fromTf = next
                },
                onHistory = { Session.openSheet(Sheet.HISTORY) },
                onDismiss = { showKeyboard = false }
            )
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    FieldRow(
                        label = "起始地址",
                        value = fromTf,
                        suppressSystemKeyboard = showKeyboard,
                        onFocus = {
                            focusTarget = 0
                            showPathSuggest = false
                        }
                    ) {
                        focusTarget = 0
                        showPathSuggest = false
                        fromTf = it
                    }
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(36.dp)
                        .background(Rose.Chip, GgShapes.Xs)
                        .clickableWithTooltip(tooltip = tr("选择内存模块")) {
                            Session.saveDumpDraft(fromTf.text, toTf.text)
                            Session.openModulePicker(target = "dump_from")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GgIcons.MenuThreeLines, contentDescription = tr("选择内存模块"), tint = Rose.Primary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    FieldRow(
                        label = "结束地址",
                        value = toTf,
                        suppressSystemKeyboard = showKeyboard,
                        onFocus = {
                            focusTarget = 1
                            showPathSuggest = false
                        }
                    ) {
                        focusTarget = 1
                        showPathSuggest = false
                        toTf = it
                    }
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(36.dp)
                        .background(Rose.Chip, GgShapes.Xs)
                        .clickableWithTooltip(tooltip = tr("选择内存模块")) {
                            Session.saveDumpDraft(fromTf.text, toTf.text)
                            Session.openModulePicker(target = "dump_to")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(GgIcons.MenuThreeLines, contentDescription = tr("选择内存模块"), tint = Rose.Primary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    FieldRow(
                        label = "路径",
                        value = pathTf,
                        suppressSystemKeyboard = false,
                        onFocus = {
                            focusTarget = 2
                            showPathSuggest = pathTf.text.startsWith("/")
                        }
                    ) {
                        focusTarget = 2
                        pathTf = it
                        Session.setDumpDirPath(it.text)
                        showPathSuggest = it.text.startsWith("/")
                    }
                }
                RoseButton(
                    label = "浏览",
                    modifier = Modifier.width(64.dp).height(48.dp)
                ) {
                    Session.openFileBrowser(
                        initialPath = pathTf.text,
                        selectDirectory = true,
                        returnSheet = Sheet.DUMP,
                        target = FileBrowserTarget.DUMP
                    )
                }
            }

            PathAutocompleteDropdown(
                path = pathTf.text,
                hasRoot = ui.hasRootPermission,
                directoriesOnly = true,
                visible = showPathSuggest && focusTarget == 2,
                onSelect = { completed, _ ->
                    pathTf = TextFieldValue(completed, TextRange(completed.length))
                    Session.setDumpDirPath(completed)
                }
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = tr(
                    if (endAddr >= startAddr) {
                        "导出区间: 0x${java.lang.Long.toHexString(startAddr).uppercase()} - 0x${java.lang.Long.toHexString(endAddr).uppercase()} ($regionBytes 字节)"
                    } else {
                        "结束地址必须大于或等于起始地址"
                    }
                ),
                style = monoStyle(12, if (endAddr >= startAddr) Rose.Green else Rose.Red)
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("取消", Modifier.weight(1f)) { Session.closeSheet() }
                RoseButton("导出", Modifier.weight(1f), filled = true) {
                    if (endAddr <= startAddr) {
                        Session.toast("结束地址必须大于起始地址")
                    } else {
                        Session.dumpMemory(startAddr, endAddr, pathTf.text)
                    }
                }
            }
        }
    }
}

// ============================================================
// 分配内存页 (AllocateSheet)
// ============================================================
@Composable
fun AllocateSheet(ui: Ui) {
    var allowRead by remember { mutableStateOf(true) }
    var allowWrite by remember { mutableStateOf(true) }
    var allowExec by remember { mutableStateOf(true) }
    var addrTf by remember { mutableStateOf(TextFieldValue("0", TextRange(0, 1))) }
    var showRegionPicker by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(true) }

    NextSheetCard(
        title = "分配内存页",
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
        Column(Modifier.verticalScroll(rememberScrollState())) {
            AllocPermRow("也许可读", allowRead) { allowRead = it }
            AllocPermRow("也许可写", allowWrite) { allowWrite = it }
            AllocPermRow("也许可执行", allowExec) { allowExec = it }

            Spacer(Modifier.height(6.dp))
            Text(tr("地址:"), color = Rose.Text, fontSize = fsp(14f))
            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        GgTextField(
                            value = addrTf,
                            onValueChange = { addrTf = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = monoStyle(16, Rose.Text),
                            cursorColor = Rose.Primary,
                            suppressSystemKeyboard = showKeyboard
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Rose.Primary)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(38.dp)
                        .background(
                            if (showRegionPicker) Rose.ChipSelected else Rose.CardHigh,
                            GgShapes.Sm
                        )
                        .clickableWithTooltip {
                            showRegionPicker = !showRegionPicker
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = GgIcons.RegionPicker,
                        contentDescription = "选择内存段",
                        tint = Rose.Text,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }

            AnimatedVisibility(showRegionPicker, enter = expandEnter(), exit = expandExit()) {
                var regionSamples by remember { mutableStateOf(listOf("0 (系统自动分配)" to "0")) }
                LaunchedEffect(showRegionPicker, ui.target?.pid) {
                    if (showRegionPicker) {
                        val regions = Session.loadProcessRegions()
                        regionSamples = buildList {
                            add("0 (系统自动分配)" to "0")
                            listOf(
                                "Ca" to "C++ alloc",
                                "A" to "Anonymous",
                                "Xa" to "Code app"
                            ).forEach { (code, desc) ->
                                val base = regions.firstOrNull { it.code == code }?.start
                                if (base != null && base != 0L) {
                                    val hex = java.lang.Long.toHexString(base).uppercase()
                                    add("$code: $desc (0x$hex)" to hex)
                                }
                            }
                        }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    regionSamples.forEach { (label, hexVal) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(Rose.CardHigh, GgShapes.Sm)
                                .clickableWithTooltip {
                                    addrTf = TextFieldValue(hexVal, TextRange(hexVal.length))
                                    showRegionPicker = false
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr(label), color = Rose.Text, fontSize = fsp(12.5f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoseButton(
                    label = "取消",
                    modifier = Modifier.width(74.dp).height(38.dp)
                ) {
                    Session.popSheet()
                }
                Spacer(Modifier.width(10.dp))
                RoseButton(
                    label = "分配",
                    filled = true,
                    enabled = !ui.busy,
                    modifier = Modifier.width(88.dp).height(38.dp)
                ) {
                    val clean = addrTf.text.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
                    val addr = if (clean.isEmpty() || clean == "0") 0L else (clean.toULongOrNull(16)?.toLong() ?: 0L)
                    Session.allocateMemoryPage(addr, allowRead, allowWrite, allowExec)
                }
            }
        }
    }
}

@Composable
private fun AllocPermRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableWithTooltip { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GgCheckBox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            size = 22.dp,
            accentColor = Rose.Primary
        )
        Spacer(Modifier.width(10.dp))
        Text(tr(label), color = Rose.Text, fontSize = fsp(14.5f))
    }
}

// ============================================================
// 保存/载入列表文件界面（显式区分载入与保存/导出，杜绝误覆盖）
// ============================================================
@Composable
fun FileSheet(ui: Ui) {
    var tf by remember {
        mutableStateOf(TextFieldValue(ui.fileManagerPath, TextRange(ui.fileManagerPath.length)))
    }
    LaunchedEffect(ui.fileManagerPath) {
        if (ui.fileManagerPath != tf.text) {
            tf = TextFieldValue(ui.fileManagerPath, TextRange(ui.fileManagerPath.length))
        }
    }
    var showPathSuggest by remember { mutableStateOf(false) }
    val mode = ui.fileSheetMode
    val sheetTitle = when (mode) {
        FileSheetMode.LOAD -> "从文件载入列表"
        FileSheetMode.SAVE -> "保存列表到文件"
        FileSheetMode.EXPORT -> "导出选定项到文件"
    }
    NextSheetCard(
        title = sheetTitle,
        subtitle = "选择或输入列表文件路径",
        onDismiss = Session::popSheet
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    FieldRow(
                        label = "路径",
                        value = tf,
                        onFocus = { showPathSuggest = tf.text.startsWith("/") }
                    ) {
                        tf = it
                        Session.setFileManagerPath(it.text)
                        showPathSuggest = it.text.startsWith("/")
                    }
                }
                RoseButton(
                    label = "浏览",
                    modifier = Modifier.width(68.dp).height(48.dp)
                ) {
                    Session.openFileBrowser(
                        initialPath = tf.text,
                        selectDirectory = false,
                        returnSheet = Sheet.FILE_MANAGER,
                        target = FileBrowserTarget.FILE_MANAGER
                    )
                }
            }
            PathAutocompleteDropdown(
                path = tf.text,
                hasRoot = ui.hasRootPermission,
                directoriesOnly = false,
                visible = showPathSuggest,
                onSelect = { completed, isDir ->
                    tf = TextFieldValue(completed, TextRange(completed.length))
                    Session.setFileManagerPath(completed)
                    showPathSuggest = isDir
                }
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("取消", Modifier.weight(0.8f)) { Session.popSheet() }
                if (mode != FileSheetMode.EXPORT) {
                    RoseButton(
                        label = "载入",
                        modifier = Modifier.weight(1f),
                        filled = mode == FileSheetMode.LOAD,
                        enabled = !ui.isBatchBusy
                    ) {
                        Session.loadListFromFile(tf.text)
                    }
                }
                RoseButton(
                    label = if (mode == FileSheetMode.EXPORT) "导出选中" else "保存",
                    modifier = Modifier.weight(1f),
                    filled = mode != FileSheetMode.LOAD,
                    enabled = !ui.isBatchBusy
                ) {
                    Session.saveListToFile(tf.text, onlyChecked = mode == FileSheetMode.EXPORT)
                }
            }
        }
    }
}

// ============================================================
// 游戏变速（基于 ShadowHook + xDL Agent 拦截 clock_gettime / gettimeofday / usleep / nanosleep）
// ============================================================
@Composable
fun SpeedSheet(ui: Ui) {
    var speed by remember(ui.speedMultiplier) { mutableStateOf(ui.speedMultiplier) }
    NextSheetCard(
        title = "游戏变速",
        subtitle = ui.target?.let { "目标：[${it.pid}] ${it.label}" } ?: "调整时间倍率",
        onDismiss = Session::popSheet
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = String.format(java.util.Locale.US, "%.2fx", speed).trimEnd('0').let {
                    if (it.endsWith(".")) "${it}0x" else if (!it.endsWith("x")) "${it}x" else it
                },
                color = Rose.Primary,
                fontSize = fsp(36f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.25f, 0.5f, 1.0f, 1.5f).forEach { v ->
                    val selected = kotlin.math.abs(speed - v) < 0.01f
                    RoseButton("${v}x", Modifier.weight(1f), filled = selected) { speed = v }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2.0f, 5.0f, 10.0f, 50.0f).forEach { v ->
                    val selected = kotlin.math.abs(speed - v) < 0.01f
                    RoseButton("${v.toInt()}x", Modifier.weight(1f), filled = selected) { speed = v }
                }
            }
            if (ui.hasRootPermission && ui.target != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Rose.CardHigh, GgShapes.Sm)
                        .clickableWithTooltip { Session.setSpeedWrapEnabled(!ui.speedWrapEnabled) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GgCheckBox(
                        checked = ui.speedWrapEnabled,
                        onCheckedChange = { Session.setSpeedWrapEnabled(it) },
                        size = 20.dp,
                        accentColor = Rose.Primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("启用重启预加载 (Root)"), color = Rose.Text, fontSize = fsp(12.5f))
                        Text(
                            tr("重启目标应用后生效"),
                            color = Rose.TextDim,
                            fontSize = fsp(10.5f),
                            lineHeight = fsp(14.5f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("重置 1.0x", Modifier.weight(1f)) {
                    speed = 1.0f
                    Session.resetSpeed()
                }
                RoseButton("应用变速", Modifier.weight(1f), filled = true) {
                    Session.setSpeedMultiplier(speed)
                }
            }
        }
    }
}

// ============================================================
// 界面调整 9：语言选择弹窗（仅保留简体中文、繁體中文和 English，并实现实时语言切换）
// ============================================================
@Composable
fun LanguageSheet() {
    val ui = Session.ui.collectAsState().value
    val cur = if (ui.settings["language"] == "1") "1" else "0"
    val langs = listOf(
        "0" to "简体中文",
        "1" to "English"
    )
    NextSheetCard(title = "选择语言", onDismiss = Session::closeSheet) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            langs.forEach { (code, name) ->
                val selected = cur == code
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(if (selected) Rose.ChipSelected else Rose.CardHigh, GgShapes.Md)
                        .clickableWithTooltip {
                            Session.setSetting("language", code)
                            Session.toast("已切换语言：$name")
                            Session.closeSheet()
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, color = Rose.Text, fontSize = fsp(15f), modifier = Modifier.weight(1f))
                    if (selected) {
                        Icon(Icons.Rounded.Check, null, tint = Rose.Primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ============================================================
// 历史 / 日志 / 悬浮窗 / 信息
// ============================================================
@Composable
fun HistorySheet(ui: Ui) {
    NextSheetCard(title = "输入历史", onDismiss = Session::popSheet, maxBodyHeight = 560) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (ui.history.isEmpty()) Text(tr("无历史记录"), color = Rose.TextDim, fontSize = fsp(13f))
            ui.history.forEach { h ->
                val pinned = Session.isHistoryPinned(h)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(Rose.CardHigh, GgShapes.Sm)
                        .clickableWithTooltip { Session.pickHistory(h) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pinned) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = "已置顶",
                            tint = Rose.Primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(h, style = monoStyle(13, Rose.Text), modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(28.dp)
                            .clickableWithTooltip { Session.toggleHistoryPin(h) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = if (pinned) "取消置顶" else "置顶",
                            tint = if (pinned) Rose.Primary else Rose.TextDim,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Box(
                        Modifier
                            .size(28.dp)
                            .clickableWithTooltip { Session.removeHistory(h) },
                        contentAlignment = Alignment.Center
                    ) { Text("×", color = Rose.TextDim, fontSize = fsp(15f)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            RoseButton("清空", Modifier.fillMaxWidth()) { Session.clearHistory() }
        }
    }
}

@Composable
fun FunctionSheet() {
    Session.closeSheet()
}

@Composable
fun LogSheet() {
    // Bug 9 修复：使日志内容成为可观察状态，清空时立即响应刷新界面
    var lines by remember { mutableStateOf(AppLog.snapshot()) }
    NextSheetCard(title = "运行日志", onDismiss = Session::closeSheet, maxBodyHeight = 560) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 380.dp)
                    .background(Rose.CardHigh, GgShapes.Sm)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    if (lines.isEmpty()) tr("暂无日志") else lines.joinToString("\n"),
                    style = monoStyle(11, Rose.TextSecond),
                    lineHeight = fsp(16f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("清空", Modifier.weight(1f)) {
                    AppLog.clear()
                    lines = emptyList()
                    Session.toast("日志已清空")
                }
                RoseButton("关闭", Modifier.weight(1f), filled = true) { Session.closeSheet() }
            }
        }
    }
}

@Composable
fun OverlaySheet(ui: Ui) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    NextSheetCard(title = "悬浮窗管理", subtitle = "短按悬浮球打开面板，长按悬浮球调节变速", onDismiss = Session::closeSheet) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                tr(
                    if (com.littlecodesauce.ggnext.service.OverlayManager.canDraw(ctx)) "已授予系统悬浮窗权限"
                    else "未授予系统悬浮窗权限，请先在系统设置中开启"
                ),
                color = if (com.littlecodesauce.ggnext.service.OverlayManager.canDraw(ctx)) Rose.Green else Rose.Yellow,
                fontSize = fsp(12.5f)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoseButton("关闭悬浮窗", Modifier.weight(1f)) {
                    Session.toggleOverlay(ctx.applicationContext as android.app.Application, false)
                    Session.closeSheet()
                }
                RoseButton("启动悬浮窗", Modifier.weight(1f), filled = true) {
                    Session.toggleOverlay(ctx.applicationContext as android.app.Application, true)
                    Session.closeSheet()
                }
            }
        }
    }
}

@Composable
fun InfoSheet(title: String, text: String) {
    NextSheetCard(title = title, onDismiss = Session::closeSheet, maxBodyHeight = 560) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(tr(text), color = Rose.TextSecond, fontSize = fsp(13f), lineHeight = fsp(20f))
            Spacer(Modifier.height(12.dp))
            RoseButton("关闭", Modifier.fillMaxWidth(), filled = true) { Session.closeSheet() }
        }
    }
}

@Composable
fun AboutSheet(ui: Ui) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val projectUrl = "https://github.com/2304974523/GGNext"
    val aggUrl = "https://github.com/AGameGuardian/agameguardian.github.io"
    val ggUrl = "https://gameguardian.net"
    val qqGroupNumber = "1131034753"

    fun openExternalUrl(url: String) {
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.isSuccess
        if (!opened) {
            clip.setText(AnnotatedString(url))
            Session.toast("无法直接打开浏览器，已复制链接")
        }
    }

    fun openOrCopyQqGroup() {
        clip.setText(AnnotatedString(qqGroupNumber))
        val qqUri = Uri.parse(
            "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$qqGroupNumber&card_type=group&source=qrcode"
        )
        val opened = runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, qqUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        Session.toast(if (opened) "正在跳转 QQ 群：$qqGroupNumber" else "已复制 QQ 群号：$qqGroupNumber")
    }

    NextSheetCard(title = "关于 GGNext", onDismiss = Session::closeSheet, maxBodyHeight = 580) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Rose.CardHigh, GgShapes.Lg)
                    .border(1.dp, Rose.Divider, GgShapes.Lg)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("GGNext v0.10.0", color = Rose.Text, fontSize = fsp(15.5f), fontWeight = FontWeight.SemiBold)
                Text(
                    tr("开源内存分析与调试工具，遵循 GNU General Public License v3.0 协议发布。"),
                    color = Rose.TextSecond,
                    fontSize = fsp(12.5f),
                    lineHeight = fsp(18f)
                )
            }

            AboutActionCard(
                label = "官方群组",
                title = "${tr("QQ 群")}：$qqGroupNumber",
                description = "加入交流反馈、获取更新动态与使用技巧",
                trailingHint = "点击加入或复制",
                onClick = { openOrCopyQqGroup() }
            )

            AboutActionCard(
                label = "开源仓库",
                title = "GGNext GitHub",
                description = "查看源代码、提交 Issue 与获取版本发布",
                trailingHint = "点击前往",
                onClick = { openExternalUrl(projectUrl) }
            )

            AboutActionCard(
                label = "特别鸣谢",
                title = "AGameGuardian",
                description = "界面布局与交互设计灵感源自 AGameGuardian 优秀的界面设计。",
                trailingHint = "点击访问",
                onClick = { openExternalUrl(aggUrl) }
            )

            AboutActionCard(
                label = "特别鸣谢",
                title = "GameGuardian",
                description = "致敬经典 Android 内存分析与修改工具 GameGuardian，其功能范式与交互理念为本项目提供了重要启发。",
                trailingHint = "点击访问",
                onClick = { openExternalUrl(ggUrl) }
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Rose.Surface.copy(alpha = 0.55f), GgShapes.Md)
                    .border(1.dp, Rose.Divider, GgShapes.Md)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "${tr("工作模式")}：${tr(ui.workMode.title)}",
                    color = Rose.TextSecond,
                    fontSize = fsp(12f)
                )
                Text(
                    "${tr("当前目标")}：${ui.target?.label ?: tr("未选择")}",
                    color = Rose.TextSecond,
                    fontSize = fsp(12f)
                )
            }

            RoseButton("关闭", Modifier.fillMaxWidth(), filled = true) { Session.closeSheet() }
        }
    }
}

@Composable
private fun AboutActionCard(
    label: String,
    title: String,
    description: String,
    trailingHint: String,
    onClick: () -> Unit
) {
    GgSurfaceCard(
        shape = GgShapes.Lg,
        backgroundColor = Rose.CardHigh,
        borderColor = Rose.Divider,
        borderWidth = GgBorder.Default,
        contentPadding = PaddingValues(12.dp),
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tr(label), color = Rose.Primary, fontSize = fsp(11.5f), fontWeight = FontWeight.SemiBold)
            Text(tr(trailingHint), color = Rose.TextDim, fontSize = fsp(11f))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, color = Rose.Text, fontSize = fsp(14f), fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(tr(description), color = Rose.TextSecond, fontSize = fsp(12f), lineHeight = fsp(17.5f))
    }
}
