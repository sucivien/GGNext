package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.FilterState
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.components.BuiltInKeyboard
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.GgDivider
import com.littlecodesauce.ggnext.ui.components.GgIcons
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
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.typeColor

// ============================================================================
// 精确搜索面板
// - 界面调整 11：选择数据类型时，展开的列表里不显示数值范围（仅显示缩写与类型名称）
// - 界面调整 12：调整搜索条件与数据类型时，旁边的三角符号未展开时朝右（▸，旋转 -90°），展开时朝下（▾，0°）
// ============================================================================

@Composable
fun SearchSheet(ui: Ui) {
    // 界面问题 7：统一提升搜索面板全部输入框状态，支持在不同输入框（主搜索词 / 从 / 到 / 附近地址 / 附近距离 / 地址蒙版）间自由切换焦点与光标
    var tf by remember { mutableStateOf(TextFieldValue(ui.search.text, TextRange(0, ui.search.text.length))) }
    var fromTf by remember {
        mutableStateOf(TextFieldValue(ui.search.rangeFrom, TextRange(ui.search.rangeFrom.length)))
    }
    var toTf by remember {
        mutableStateOf(TextFieldValue(ui.search.rangeTo, TextRange(ui.search.rangeTo.length)))
    }
    var addrTf by remember {
        val cur = ui.search.nearbyAddress.ifEmpty {
            java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
        }
        mutableStateOf(TextFieldValue(cur, TextRange(cur.length)))
    }
    var distTf by remember {
        mutableStateOf(TextFieldValue(ui.search.nearbyDistance, TextRange(ui.search.nearbyDistance.length)))
    }
    var maskTf by remember {
        mutableStateOf(TextFieldValue(ui.search.mask, TextRange(ui.search.mask.length)))
    }
    // 0: 主搜索框 tf, 1: 从(fromTf), 2: 到(toTf), 3: 附近地址(addrTf), 4: 附近距离(distTf), 5: 地址蒙版(maskTf)
    var activeField by remember { mutableIntStateOf(0) }

    var showSign by remember { mutableStateOf(false) }
    var showType by remember { mutableStateOf(false) }
    val extended = ui.search.expanded
    val keyboardOn = ui.search.keyboardVisible
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    var autoSwitchedExternal by remember { mutableStateOf(false) }

    fun needsExternalKeyboardForPrefix(raw: String): Boolean {
        val t = raw.trimStart()
        if (t.isEmpty()) return false
        val first = t[0]
        return first == ':' || first == ';' || first == '\'' || first == '"' || first == '~'
    }

    LaunchedEffect(tf.text, activeField) {
        if (activeField != 0) return@LaunchedEffect
        val trimmed = tf.text.trimStart()
        if (trimmed.isEmpty()) {
            autoSwitchedExternal = false
        } else if (needsExternalKeyboardForPrefix(trimmed) && !autoSwitchedExternal) {
            autoSwitchedExternal = true
            if (keyboardOn) {
                Session.updateSearch { s -> s.copy(keyboardVisible = false) }
            }
            kotlinx.coroutines.delay(60L)
            softwareKeyboard?.show()
        }
    }

    val activeTf = when (activeField) {
        1 -> fromTf
        2 -> toTf
        3 -> addrTf
        4 -> distTf
        5 -> maskTf
        else -> tf
    }

    fun updateActiveField(next: TextFieldValue) {
        when (activeField) {
            1 -> {
                fromTf = next
                Session.updateSearch { s -> s.copy(rangeFrom = next.text) }
            }
            2 -> {
                toTf = next
                Session.updateSearch { s -> s.copy(rangeTo = next.text) }
            }
            3 -> {
                addrTf = next
                Session.updateSearch { s -> s.copy(nearbyAddress = next.text) }
            }
            4 -> {
                distTf = next
                Session.updateSearch { s -> s.copy(nearbyDistance = next.text) }
            }
            5 -> {
                maskTf = next
                Session.updateSearch { s -> s.copy(mask = next.text) }
            }
            else -> {
                tf = next
                Session.updateSearch { s -> s.copy(text = next.text) }
            }
        }
    }

    val currentTypeColor = typeColor(ui.search.type)
    val typeRangeText = remember(ui.search.type) {
        ValueType.rangeText(ui.search.type)
    }

    val signArrowRotation by animateFloatAsState(
        targetValue = if (showSign) 0f else -90f,
        animationSpec = Motion.Fast,
        label = "signArrowRot"
    )
    val typeArrowRotation by animateFloatAsState(
        targetValue = if (showType) 0f else -90f,
        animationSpec = Motion.Fast,
        label = "typeArrowRot"
    )

    val isGroupQuery = remember(tf.text) {
        com.littlecodesauce.ggnext.engine.NumberParser.isGroupExpression(tf.text)
    }
    val (groupOrdered, groupArea) = remember(tf.text) {
        com.littlecodesauce.ggnext.engine.NumberParser.extractGroupMeta(tf.text)
    }

    NextSheetCard(
        title = "精确搜索",
        subtitle = "支持单值、范围、联合、字符串 (: UTF-8 / ; UTF-16) 与汇编 (~A8 / ~A / ~T)",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 780,
        showSideKeyboard = keyboardOn,
        sideKeyboard = {
            BuiltInKeyboard(
                value = activeTf,
                onValueChange = ::updateActiveField,
                onHistory = { Session.openSheet(Sheet.HISTORY) },
                onDismiss = { Session.updateSearch { s -> s.copy(keyboardVisible = false) } }
            )
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = typeRangeText,
                style = monoStyle(12, currentTypeColor),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Rose.CardHigh, GgShapes.Lg)
                    .border(
                        width = 1.dp,
                        color = if (activeField == 0) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                        shape = GgShapes.Lg
                    )
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .height(38.dp)
                        .clickableWithTooltip {
                            showSign = !showSign; showType = false
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(ValueType.signName(ui.search.sign), color = Rose.Primary, fontSize = fsp(17f))
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = Rose.TextDim,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = signArrowRotation }
                    )
                }

                Row(
                    Modifier
                        .height(38.dp)
                        .clickableWithTooltip {
                            showType = !showType; showSign = false
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        ValueType.shortName(ui.search.type),
                        color = currentTypeColor,
                        fontSize = fsp(16f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = currentTypeColor,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = typeArrowRotation }
                    )
                }
                Spacer(Modifier.width(6.dp))
                GgTextField(
                    value = tf,
                    onValueChange = {
                        activeField = 0
                        tf = it
                        Session.updateSearch { s -> s.copy(text = it.text) }
                    },
                    onFocus = { activeField = 0 },
                    initialFocus = true,
                    modifier = Modifier.weight(1f).height(40.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = keyboardOn,
                    placeholder = ""
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .clickableWithTooltip {
                            Session.updateSearch { s -> s.copy(keyboardVisible = !s.keyboardVisible) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Keyboard, "键盘",
                        tint = if (keyboardOn) Rose.Primary else Rose.TextSecond,
                        modifier = Modifier.size(19.dp)
                    )
                }
                RoseButton(
                    if (extended) "收起" else "更多",
                    Modifier.width(60.dp).height(36.dp),
                    filled = true
                ) {
                    val nextExp = !ui.search.expanded
                    if (!nextExp) activeField = 0
                    Session.updateSearch { s -> s.copy(expanded = nextExp) }
                }
            }

            // 汇编操作码搜索实时机器码预览（支持 ~A8 / ~A / ~T / ~X86 / ~X64 前缀）
            val asmSearchParsed = remember(tf.text) {
                com.littlecodesauce.ggnext.engine.AsmEngineManager.parseGgAsmPrefix(tf.text)
            }
            AnimatedVisibility(asmSearchParsed != null, enter = expandEnter(), exit = expandExit()) {
                val parsed = asmSearchParsed
                if (parsed != null) {
                    val (arch, body) = parsed
                    val asmResult = remember(arch, body) {
                        if (body.isBlank()) null
                        else com.littlecodesauce.ggnext.engine.AsmEngineManager.assemble(arch, 0L, body)
                    }
                    val (previewText, previewColor) = when {
                        body.isBlank() -> "${arch.ggPrefix} ${tr("请输入汇编指令，如")} ${arch.ggPrefix} MOV X0, #1" to Rose.TextDim
                        asmResult == null -> "" to Rose.TextDim
                        asmResult.isSuccess -> {
                            val bytes = asmResult.getOrThrow()
                            "${arch.shortCode} ${tr("机器码")} (${bytes.size}B): ${com.littlecodesauce.ggnext.engine.AsmEngineManager.bytesToSpacedHex(bytes)}" to Rose.Opcode
                        }
                        else -> "${arch.shortCode} ${tr("解析失败")}: ${asmResult.exceptionOrNull()?.message ?: ""}" to Rose.Red
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .background(Rose.CardHigh, GgShapes.Xs)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(previewText, style = monoStyle(11, previewColor))
                    }
                }
            }

            // 联合搜索快捷选项：显示「按顺序」勾选与默认/当前冒号距离（如 :512 或 ::512）
            AnimatedVisibility(isGroupQuery, enter = expandEnter(), exit = expandExit()) {
                val sep = if (groupOrdered) "::" else ":"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 4.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickableWithTooltip {
                            val nextExpr = com.littlecodesauce.ggnext.engine.NumberParser.normalizeGroupExpression(
                                tf.text,
                                ordered = !groupOrdered,
                                area = groupArea
                            )
                            activeField = 0
                            tf = TextFieldValue(nextExpr, TextRange(nextExpr.length))
                            Session.updateSearch { s -> s.copy(text = nextExpr) }
                        }
                    ) {
                        GgCheckBox(
                            checked = groupOrdered,
                            onCheckedChange = { checked ->
                                val nextExpr = com.littlecodesauce.ggnext.engine.NumberParser.normalizeGroupExpression(
                                    tf.text,
                                    ordered = checked,
                                    area = groupArea
                                )
                                activeField = 0
                                tf = TextFieldValue(nextExpr, TextRange(nextExpr.length))
                                Session.updateSearch { s -> s.copy(text = nextExpr) }
                            },
                            size = 19.dp,
                            accentColor = Rose.Primary
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(tr("按顺序"), color = Rose.Text, fontSize = fsp(13.5f))
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .height(30.dp)
                            .background(Rose.ChipSelected, GgShapes.Sm)
                            .border(1.dp, Rose.Primary.copy(alpha = 0.45f), GgShapes.Sm)
                            .clickableWithTooltip(tooltip = tr("点击编辑联合搜索距离")) {
                                val nextExpr = com.littlecodesauce.ggnext.engine.NumberParser.normalizeGroupExpression(
                                    tf.text,
                                    ordered = groupOrdered,
                                    area = groupArea
                                )
                                val sepIdx = nextExpr.lastIndexOf(sep)
                                val selStart = if (sepIdx >= 0) sepIdx + sep.length else nextExpr.length
                                activeField = 0
                                tf = TextFieldValue(nextExpr, TextRange(selStart, nextExpr.length))
                                Session.updateSearch { s -> s.copy(text = nextExpr) }
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$sep$groupArea",
                            style = monoStyle(13, Rose.Primary, FontWeight.SemiBold)
                        )
                    }
                }
            }

            AnimatedVisibility(showType, enter = expandEnter(), exit = expandExit()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    (ValueType.order + ValueType.AUTO).forEach { t ->
                        val on = ui.search.type == t
                        val isAuto = t == ValueType.AUTO
                        val shortName = if (isAuto) "A" else ValueType.shortName(t)
                        val col = if (isAuto) Rose.Text else typeColor(t)
                        val bgCol by animateColorAsState(
                            if (on) Rose.ChipSelected else Rose.CardHigh,
                            Motion.ColorFast,
                            label = "typeChipBg"
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(bgCol, GgShapes.Sm)
                                .border(
                                    width = if (on) 1.dp else 0.dp,
                                    color = if (on) col else Color.Transparent,
                                    shape = GgShapes.Sm
                                )
                                .clickableNoRipple(remember { MutableInteractionSource() }) {
                                    Session.updateSearch { s -> s.copy(type = t) }
                                    showType = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                shortName,
                                color = col,
                                fontSize = fsp(14f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(showSign, enter = expandEnter(), exit = expandExit()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        ValueType.SIGN_EQUAL to "=",
                        ValueType.SIGN_NOT_EQUAL to "≠",
                        ValueType.SIGN_GREATER_EQ to "≥",
                        ValueType.SIGN_LESS_EQ to "≤"
                    ).forEach { (s, symbol) ->
                        val on = (ui.search.sign and ValueType.SIGN_MASK) == s
                        val bgCol by animateColorAsState(
                            if (on) Rose.ChipSelected else Rose.CardHigh,
                            Motion.ColorFast,
                            label = "signChipBg"
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(bgCol, GgShapes.Sm)
                                .border(
                                    width = if (on) 1.dp else 0.dp,
                                    color = if (on) Rose.Primary else Color.Transparent,
                                    shape = GgShapes.Sm
                                )
                                .clickableNoRipple(remember { MutableInteractionSource() }) {
                                    Session.updateSearch { it.copy(sign = s) }
                                    showSign = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                symbol,
                                color = if (on) Rose.Primary else Rose.Text,
                                fontSize = fsp(16f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(extended, enter = expandEnter(), exit = expandExit()) {
                Column {
                    Spacer(Modifier.height(8.dp))

                    // 其他改进 2：范围模式选择胶囊带平滑颜色、边框与按下缩放过渡动画
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            0 to "全部内存",
                            1 to "指定地址区间",
                            2 to "附近:"
                        ).forEach { (m, label) ->
                            val isSel = ui.search.rangeMode == m
                            var pillPressed by remember { mutableStateOf(false) }
                            val pillScale by animateFloatAsState(
                                if (pillPressed) 0.94f else 1f,
                                Motion.Fast,
                                label = "rangePillScale"
                            )
                            val pillBg by animateColorAsState(
                                if (isSel) Rose.ChipSelected else if (pillPressed) Rose.Chip else Rose.CardHigh,
                                Motion.ColorFast,
                                label = "rangePillBg"
                            )
                            val pillTextCol by animateColorAsState(
                                if (isSel) Rose.Primary else Rose.TextSecond,
                                Motion.ColorFast,
                                label = "rangePillTxt"
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .scale(pillScale)
                                    .background(pillBg, GgShapes.Sm)
                                    .border(
                                        width = if (isSel) 1.dp else 0.dp,
                                        color = if (isSel) Rose.Primary.copy(alpha = 0.45f) else Color.Transparent,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableWithTooltip(
                                        onPressChange = { pillPressed = it },
                                        onClick = {
                                            Session.updateSearch { s -> s.copy(rangeMode = m) }
                                            when (m) {
                                                1 -> if (activeField !in 1..2) activeField = 1
                                                2 -> if (activeField !in 3..4) activeField = 3
                                                else -> if (activeField in 1..4) activeField = 0
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    tr(label),
                                    color = pillTextCol,
                                    fontSize = fsp(12.5f),
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // 模式 1：指定地址区间（带平滑展开收起动画 + 焦点切换）
                    AnimatedVisibility(ui.search.rangeMode == 1, enter = expandEnter(), exit = expandExit()) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Rose.CardHigh, GgShapes.Sm)
                                    .border(
                                        width = 1.dp,
                                        color = if (activeField == 1) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableNoRipple(remember { MutableInteractionSource() }) { activeField = 1 }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("从:"), color = Rose.TextSecond, fontSize = fsp(13f), modifier = Modifier.width(36.dp))
                                GgTextField(
                                    value = fromTf,
                                    onValueChange = {
                                        activeField = 1
                                        fromTf = it
                                        Session.updateSearch { s -> s.copy(rangeFrom = it.text) }
                                    },
                                    onFocus = { activeField = 1 },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    textStyle = monoStyle(14, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = keyboardOn
                                )
                                Box(
                                    Modifier
                                        .size(30.dp)
                                        .clickableWithTooltip {
                                            activeField = 1
                                            fromTf = TextFieldValue("00000000", TextRange(8))
                                            Session.updateSearch { s -> s.copy(rangeFrom = "00000000") }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(GgIcons.ContentDuplicate, null, tint = Rose.Primary, modifier = Modifier.size(16.dp))
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Rose.CardHigh, GgShapes.Sm)
                                    .border(
                                        width = 1.dp,
                                        color = if (activeField == 2) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableNoRipple(remember { MutableInteractionSource() }) { activeField = 2 }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("到:"), color = Rose.TextSecond, fontSize = fsp(13f), modifier = Modifier.width(36.dp))
                                GgTextField(
                                    value = toTf,
                                    onValueChange = {
                                        activeField = 2
                                        toTf = it
                                        Session.updateSearch { s -> s.copy(rangeTo = it.text) }
                                    },
                                    onFocus = { activeField = 2 },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    textStyle = monoStyle(14, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = keyboardOn
                                )
                                Box(
                                    Modifier
                                        .size(30.dp)
                                        .clickableWithTooltip {
                                            activeField = 2
                                            toTf = TextFieldValue("FFFFFFFFFFFFFFFF", TextRange(16))
                                            Session.updateSearch { s -> s.copy(rangeTo = "FFFFFFFFFFFFFFFF") }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(GgIcons.ContentDuplicate, null, tint = Rose.Primary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    // 模式 2：附近（带平滑展开收起动画 + 焦点切换）
                    AnimatedVisibility(ui.search.rangeMode == 2, enter = expandEnter(), exit = expandExit()) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Rose.CardHigh, GgShapes.Sm)
                                    .border(
                                        width = 1.dp,
                                        color = if (activeField == 3) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableNoRipple(remember { MutableInteractionSource() }) { activeField = 3 }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("地址:"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                                Spacer(Modifier.width(8.dp))
                                GgTextField(
                                    value = addrTf,
                                    onValueChange = {
                                        activeField = 3
                                        addrTf = it
                                        Session.updateSearch { s -> s.copy(nearbyAddress = it.text) }
                                    },
                                    onFocus = { activeField = 3 },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    textStyle = monoStyle(14, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = keyboardOn
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickableWithTooltip {
                                        Session.updateSearch { s -> s.copy(nearbyBefore = !s.nearbyBefore) }
                                    }
                                ) {
                                    GgCheckBox(checked = ui.search.nearbyBefore, onCheckedChange = {
                                        Session.updateSearch { s -> s.copy(nearbyBefore = it) }
                                    }, size = 18.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(tr("之前"), color = Rose.Text, fontSize = fsp(13f))
                                }
                                Spacer(Modifier.width(20.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickableWithTooltip {
                                        Session.updateSearch { s -> s.copy(nearbyAfter = !s.nearbyAfter) }
                                    }
                                ) {
                                    GgCheckBox(checked = ui.search.nearbyAfter, onCheckedChange = {
                                        Session.updateSearch { s -> s.copy(nearbyAfter = it) }
                                    }, size = 18.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(tr("后"), color = Rose.Text, fontSize = fsp(13f))
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Rose.CardHigh, GgShapes.Sm)
                                    .border(
                                        width = 1.dp,
                                        color = if (activeField == 4) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableNoRipple(remember { MutableInteractionSource() }) { activeField = 4 }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("距离:"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                                Spacer(Modifier.width(8.dp))
                                GgTextField(
                                    value = distTf,
                                    onValueChange = {
                                        activeField = 4
                                        distTf = it
                                        Session.updateSearch { s -> s.copy(nearbyDistance = it.text) }
                                    },
                                    onFocus = { activeField = 4 },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    textStyle = monoStyle(14, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = keyboardOn,
                                    placeholder = "500"
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    FieldRow(
                        label = "地址蒙版",
                        value = maskTf,
                        suppressSystemKeyboard = keyboardOn,
                        onFocus = { activeField = 5 }
                    ) {
                        activeField = 5
                        maskTf = it
                        Session.updateSearch { s -> s.copy(mask = it.text) }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickableWithTooltip {
                        Session.updateSearch { s -> s.copy(encrypted = !s.encrypted) }
                    }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("加密值搜索"), color = Rose.Text, fontSize = fsp(14.5f), modifier = Modifier.weight(1f))
                GgCheckBox(
                    checked = ui.search.encrypted,
                    onCheckedChange = { Session.updateSearch { s -> s.copy(encrypted = it) } },
                    size = 22.dp,
                    accentColor = Rose.Primary
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoseButton(
                    label = "改善",
                    modifier = Modifier.width(72.dp).height(38.dp),
                    enabled = ui.rawResults.isNotEmpty()
                ) { Session.runSearch(false) }
                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = "新搜索",
                    modifier = Modifier.width(78.dp).height(38.dp),
                    filled = true
                ) { Session.runSearch(true) }
            }
        }
    }
}

// ============================================================================
// 过滤/筛选面板
// ============================================================================

enum class FilterFocusField {
    MAX_SHOW, ADDR_GREAT, ADDR_LESS, VAL_GREAT, VAL_LESS, FRACTIONAL
}

@Composable
fun FilterSearchSheet(ui: Ui) {
    var st by remember(ui.filter, ui.valueMask) {
        mutableStateOf(
            ui.filter.copy(
                formatHex = (ui.valueMask and com.littlecodesauce.ggnext.engine.Formatter.F_HEX) != 0,
                formatRHex = (ui.valueMask and com.littlecodesauce.ggnext.engine.Formatter.F_RHEX) != 0,
                formatString = (ui.valueMask and com.littlecodesauce.ggnext.engine.Formatter.F_STRING) != 0,
                formatUtf16 = (ui.valueMask and com.littlecodesauce.ggnext.engine.Formatter.F_JAVA) != 0,
                formatArm = (ui.valueMask and com.littlecodesauce.ggnext.engine.Formatter.F_ARM) != 0,
                formatThumb = (ui.valueMask and com.littlecodesauce.ggnext.engine.Formatter.F_THUMB) != 0,
                formatArm64 = (ui.valueMask and com.littlecodesauce.ggnext.engine.Formatter.F_ARM8) != 0
            )
        )
    }
    var focusedField by remember { mutableStateOf(FilterFocusField.MAX_SHOW) }
    var showKeyboard by remember { mutableStateOf(true) }
    var showTypePicker by remember { mutableStateOf(false) }
    var showPointerPicker by remember { mutableStateOf(false) }

    var maxShowTf by remember(ui.filter.maxShow) {
        val s = ui.filter.maxShow.toString()
        mutableStateOf(TextFieldValue(s, TextRange(0, s.length)))
    }
    var addrGreatTf by remember(ui.filter.addrGreat) {
        mutableStateOf(TextFieldValue(ui.filter.addrGreat, TextRange(ui.filter.addrGreat.length)))
    }
    var addrLessTf by remember(ui.filter.addrLess) {
        mutableStateOf(TextFieldValue(ui.filter.addrLess, TextRange(ui.filter.addrLess.length)))
    }
    var valGreatTf by remember(ui.filter.valGreat) {
        mutableStateOf(TextFieldValue(ui.filter.valGreat, TextRange(ui.filter.valGreat.length)))
    }
    var valLessTf by remember(ui.filter.valLess) {
        mutableStateOf(TextFieldValue(ui.filter.valLess, TextRange(ui.filter.valLess.length)))
    }
    var fracTf by remember(ui.filter.fractional) {
        mutableStateOf(TextFieldValue(ui.filter.fractional, TextRange(ui.filter.fractional.length)))
    }

    val activeTf = when (focusedField) {
        FilterFocusField.MAX_SHOW -> maxShowTf
        FilterFocusField.ADDR_GREAT -> addrGreatTf
        FilterFocusField.ADDR_LESS -> addrLessTf
        FilterFocusField.VAL_GREAT -> valGreatTf
        FilterFocusField.VAL_LESS -> valLessTf
        FilterFocusField.FRACTIONAL -> fracTf
    }

    fun onActiveTfChange(next: TextFieldValue) {
        when (focusedField) {
            FilterFocusField.MAX_SHOW -> {
                maxShowTf = next
                st = st.copy(maxShow = next.text.trim().toIntOrNull() ?: 2000)
            }
            FilterFocusField.ADDR_GREAT -> {
                addrGreatTf = next
                st = st.copy(addrGreat = next.text, addrGreatEnabled = next.text.isNotEmpty() || st.addrGreatEnabled)
            }
            FilterFocusField.ADDR_LESS -> {
                addrLessTf = next
                st = st.copy(addrLess = next.text, addrLessEnabled = next.text.isNotEmpty() || st.addrLessEnabled)
            }
            FilterFocusField.VAL_GREAT -> {
                valGreatTf = next
                st = st.copy(valGreat = next.text, valGreatEnabled = next.text.isNotEmpty() || st.valGreatEnabled)
            }
            FilterFocusField.VAL_LESS -> {
                valLessTf = next
                st = st.copy(valLess = next.text, valLessEnabled = next.text.isNotEmpty() || st.valLessEnabled)
            }
            FilterFocusField.FRACTIONAL -> {
                fracTf = next
                st = st.copy(fractional = next.text, fractionalEnabled = next.text.isNotEmpty() || st.fractionalEnabled)
            }
        }
    }

    val typePickerArrowRot by animateFloatAsState(
        targetValue = if (showTypePicker) 0f else -90f,
        animationSpec = Motion.Fast,
        label = "filterTypeArrow"
    )
    val ptrPickerArrowRot by animateFloatAsState(
        targetValue = if (showPointerPicker) 0f else -90f,
        animationSpec = Motion.Fast,
        label = "filterPtrArrow"
    )

    val metrics = LocalUiMetrics.current
    val corner = metrics.dialogCorner.dp
    val cardAlpha = (metrics.bgOpacity + 0.04f).coerceIn(0.55f, 1.0f)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenWidthDp > configuration.screenHeightDp
        val fallbackHeightDp = configuration.screenHeightDp.coerceAtLeast(240)
        val availHeightDp = if (maxHeight.value.isFinite() && maxHeight.value > 120f) {
            maxHeight.value.toInt()
        } else {
            fallbackHeightDp
        }
        val maxCardHeight = (availHeightDp - 10).coerceAtLeast(160).dp
        val useSideKeyboard = isLandscape && showKeyboard

        Row(
            Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .widthIn(max = if (useSideKeyboard) 880.dp else 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                .clickableNoRipple(remember { MutableInteractionSource() }) {}
                .background(Rose.Card.copy(alpha = cardAlpha), RoundedCornerShape(corner))
                .border(1.dp, Rose.Divider, RoundedCornerShape(corner))
                .padding(top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                Modifier
                    .weight(if (useSideKeyboard) 1.12f else 1f)
                    .heightIn(max = maxCardHeight)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp)
                ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr("最大显示记录: "), color = Rose.Text, fontSize = fsp(14.5f))
                    UnderlinedFilterInput(
                        value = maxShowTf,
                        focused = focusedField == FilterFocusField.MAX_SHOW,
                        suppressSystemKeyboard = showKeyboard,
                        onFocus = {
                            focusedField = FilterFocusField.MAX_SHOW
                        },
                        onValueChange = {
                            focusedField = FilterFocusField.MAX_SHOW
                            onActiveTfChange(it)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(32.dp)
                            .clickableWithTooltip { showKeyboard = !showKeyboard },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Keyboard,
                            contentDescription = "内置键盘",
                            tint = if (showKeyboard) Rose.Primary else Rose.TextSecond,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                GgDivider(startInset = 0.dp, endInset = 0.dp, modifier = Modifier.padding(vertical = 4.dp))

                FilterInputCheckRow(
                    checked = st.addrGreatEnabled,
                    onCheckChange = { st = st.copy(addrGreatEnabled = it) },
                    label = "地址:  ≥",
                    value = addrGreatTf,
                    focused = focusedField == FilterFocusField.ADDR_GREAT,
                    suppressSystemKeyboard = showKeyboard,
                    onFocus = {
                        focusedField = FilterFocusField.ADDR_GREAT
                    },
                    onValueChange = {
                        focusedField = FilterFocusField.ADDR_GREAT
                        onActiveTfChange(it)
                    }
                )
                FilterInputCheckRow(
                    checked = st.addrLessEnabled,
                    onCheckChange = { st = st.copy(addrLessEnabled = it) },
                    label = "地址:  ≤",
                    value = addrLessTf,
                    focused = focusedField == FilterFocusField.ADDR_LESS,
                    suppressSystemKeyboard = showKeyboard,
                    onFocus = {
                        focusedField = FilterFocusField.ADDR_LESS
                    },
                    onValueChange = {
                        focusedField = FilterFocusField.ADDR_LESS
                        onActiveTfChange(it)
                    }
                )

                GgDivider(startInset = 0.dp, endInset = 0.dp, modifier = Modifier.padding(vertical = 4.dp))

                FilterInputCheckRow(
                    checked = st.valGreatEnabled,
                    onCheckChange = { st = st.copy(valGreatEnabled = it) },
                    label = "数值:  ≥",
                    value = valGreatTf,
                    focused = focusedField == FilterFocusField.VAL_GREAT,
                    suppressSystemKeyboard = showKeyboard,
                    onFocus = {
                        focusedField = FilterFocusField.VAL_GREAT
                    },
                    onValueChange = {
                        focusedField = FilterFocusField.VAL_GREAT
                        onActiveTfChange(it)
                    }
                )
                FilterInputCheckRow(
                    checked = st.valLessEnabled,
                    onCheckChange = { st = st.copy(valLessEnabled = it) },
                    label = "数值:  ≤",
                    value = valLessTf,
                    focused = focusedField == FilterFocusField.VAL_LESS,
                    suppressSystemKeyboard = showKeyboard,
                    onFocus = {
                        focusedField = FilterFocusField.VAL_LESS
                    },
                    onValueChange = {
                        focusedField = FilterFocusField.VAL_LESS
                        onActiveTfChange(it)
                    }
                )

            GgDivider(startInset = 0.dp, endInset = 0.dp, modifier = Modifier.padding(vertical = 4.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GgCheckBox(
                    checked = st.typeEnabled,
                    onCheckedChange = { st = st.copy(typeEnabled = it) },
                    size = 20.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(tr("类型: "), color = Rose.Text, fontSize = fsp(14.5f))
                Spacer(Modifier.width(6.dp))
                val typeSummary = if (st.types.isEmpty()) tr("点击选择") else st.types.joinToString(",") { ValueType.shortName(it) }
                Row(
                    Modifier
                        .height(36.dp)
                        .background(if (st.types.isNotEmpty()) Rose.ChipSelected else Rose.CardHigh, GgShapes.Xs)
                        .clickableWithTooltip {
                            showTypePicker = !showTypePicker
                            st = st.copy(typeEnabled = true)
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(typeSummary, color = Rose.Text, fontSize = fsp(12.5f), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = Rose.TextSecond,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = typePickerArrowRot }
                    )
                }
            }

            AnimatedVisibility(showTypePicker, enter = expandEnter(), exit = expandExit()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    ValueType.order.forEach { t ->
                        val on = t in st.types
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .background(
                                    if (on) Rose.ChipSelected else Rose.CardHigh,
                                    GgShapes.Xs
                                )
                                .clickableWithTooltip {
                                    val nextSet = if (on) st.types - t else st.types + t
                                    st = st.copy(types = nextSet, typeEnabled = nextSet.isNotEmpty())
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(ValueType.shortName(t), color = typeColor(t), fontSize = fsp(13f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GgCheckBox(
                    checked = st.fractionalEnabled,
                    onCheckedChange = { st = st.copy(fractionalEnabled = it) },
                    size = 20.dp
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.clickableWithTooltip {
                        Session.toast("实数 x 的小数部分为小数点后的数字")
                    },
                    verticalAlignment = Alignment.Top
                ) {
                    Text("{x}", color = Rose.Text, fontSize = fsp(14.5f))
                    Spacer(Modifier.width(2.dp))
                    Box(
                        Modifier
                            .size(13.dp)
                            .background(Rose.Primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("i", color = Rose.OnPrimary, fontSize = fsp(9f), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(width = 44.dp, height = 36.dp)
                        .background(Rose.CardHigh, GgShapes.Xs)
                        .clickableWithTooltip {
                            st = st.copy(
                                fractionalNotEqual = !st.fractionalNotEqual,
                                fractionalEnabled = true
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (st.fractionalNotEqual) "≠" else "=",
                        color = Rose.Primary,
                        fontSize = fsp(16f),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                UnderlinedFilterInput(
                    value = fracTf,
                    focused = focusedField == FilterFocusField.FRACTIONAL,
                    suppressSystemKeyboard = showKeyboard,
                    onFocus = {
                        focusedField = FilterFocusField.FRACTIONAL
                    },
                    onValueChange = {
                        focusedField = FilterFocusField.FRACTIONAL
                        onActiveTfChange(it)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GgCheckBox(
                    checked = st.pointerEnabled,
                    onCheckedChange = { st = st.copy(pointerEnabled = it) },
                    size = 20.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(tr("指针: "), color = Rose.Text, fontSize = fsp(14.5f))
                Spacer(Modifier.width(6.dp))
                val ptrMap = listOf(4 to "-", 8 to "RO", 16 to "W", 2 to "X", 1 to "WX")
                val ptrSummary = if (st.pointers.isEmpty()) tr("点击选择") else ptrMap.filter { it.first in st.pointers }.joinToString(",") { it.second }
                Row(
                    Modifier
                        .height(36.dp)
                        .background(if (st.pointers.isNotEmpty()) Rose.ChipSelected else Rose.CardHigh, GgShapes.Xs)
                        .clickableWithTooltip {
                            showPointerPicker = !showPointerPicker
                            st = st.copy(pointerEnabled = true)
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(ptrSummary, color = Rose.Text, fontSize = fsp(12.5f), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = Rose.TextSecond,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = ptrPickerArrowRot }
                    )
                }
            }

            AnimatedVisibility(showPointerPicker, enter = expandEnter(), exit = expandExit()) {
                val ptrItems = listOf(
                    Triple(4, "-: 非指针", Rose.TextSecond),
                    Triple(8, "RO: 只读", Rose.PointerRO),
                    Triple(16, "W: 可写", Rose.PointerW),
                    Triple(2, "X: 可执行", Rose.PointerX),
                    Triple(1, "WX: 读写执行", Rose.PointerWX)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    ptrItems.forEach { (code, label, col) ->
                        val on = code in st.pointers
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .background(
                                    if (on) Rose.ChipSelected else Rose.CardHigh,
                                    GgShapes.Xs
                                )
                                .clickableWithTooltip {
                                    val nextSet = if (on) st.pointers - code else st.pointers + code
                                    st = st.copy(pointers = nextSet, pointerEnabled = nextSet.isNotEmpty())
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label.substringBefore(':'), color = col, fontSize = fsp(12f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            GgDivider(startInset = 0.dp, endInset = 0.dp, modifier = Modifier.padding(vertical = 4.dp))

            FormatCheckItem("十六进制格式 (little-endian)", st.formatHex, Rose.Text) {
                st = st.copy(formatHex = it)
            }
            FormatCheckItem("反向十六进制格式 (big-endian)", st.formatRHex, Rose.Text) {
                st = st.copy(formatRHex = it)
            }
            FormatCheckItem("字符串表达式", st.formatString, Rose.Text) {
                st = st.copy(formatString = it)
            }
            FormatCheckItem("UTF-16LE", st.formatUtf16, Rose.Text) {
                st = st.copy(formatUtf16 = it)
            }
            FormatCheckItem("ARM (x32) 操作码", st.formatArm, Rose.Opcode) {
                st = st.copy(formatArm = it)
            }
            FormatCheckItem("Thumb 操作码", st.formatThumb, Rose.Opcode) {
                st = st.copy(formatThumb = it)
            }
            FormatCheckItem("ARM (x64) 操作码", st.formatArm64, Rose.Opcode) {
                st = st.copy(formatArm64 = it)
            }

            if (!useSideKeyboard) {
                AnimatedVisibility(showKeyboard, enter = expandEnter(), exit = expandExit()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        BuiltInKeyboard(
                            value = activeTf,
                            onValueChange = ::onActiveTfChange,
                            onHistory = { Session.openSheet(Sheet.HISTORY) },
                            onDismiss = { showKeyboard = false }
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoseButton(
                label = "重置",
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                val def = FilterState()
                st = def
                maxShowTf = TextFieldValue("2000", TextRange(0, 4))
                addrGreatTf = TextFieldValue("")
                addrLessTf = TextFieldValue("")
                valGreatTf = TextFieldValue("")
                valLessTf = TextFieldValue("")
                fracTf = TextFieldValue("")
                Session.resetFilter()
            }
            RoseButton(
                label = "取消",
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Session.closeSheet()
            }
            RoseButton(
                label = "应用",
                filled = true,
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Session.applyFilter(st)
            }
        }
            }
            if (useSideKeyboard) {
                Box(
                    Modifier
                        .weight(0.92f)
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BuiltInKeyboard(
                        value = activeTf,
                        onValueChange = ::onActiveTfChange,
                        onHistory = { Session.openSheet(Sheet.HISTORY) },
                        onDismiss = { showKeyboard = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterInputCheckRow(
    checked: Boolean,
    onCheckChange: (Boolean) -> Unit,
    label: String,
    value: TextFieldValue,
    focused: Boolean,
    suppressSystemKeyboard: Boolean,
    onFocus: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GgCheckBox(checked = checked, onCheckedChange = onCheckChange, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = tr(label),
            color = Rose.Text,
            fontSize = fsp(14.5f),
            modifier = Modifier.clickableWithTooltip {
                onCheckChange(!checked)
            }
        )
        Spacer(Modifier.width(8.dp))
        UnderlinedFilterInput(
            value = value,
            focused = focused,
            suppressSystemKeyboard = suppressSystemKeyboard,
            onFocus = onFocus,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun UnderlinedFilterInput(
    value: TextFieldValue,
    focused: Boolean,
    suppressSystemKeyboard: Boolean,
    onFocus: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clickableNoRipple(remember { MutableInteractionSource() }, onClick = onFocus)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            GgTextField(
                value = value,
                onValueChange = {
                    onFocus()
                    onValueChange(it)
                },
                onFocus = onFocus,
                modifier = Modifier.fillMaxWidth(),
                textStyle = monoStyle(15, Rose.Text),
                cursorColor = Rose.Primary,
                suppressSystemKeyboard = suppressSystemKeyboard
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 2.dp else 1.dp)
                .background(if (focused) Rose.Primary else Rose.Divider)
        )
    }
}

@Composable
private fun FormatCheckItem(
    label: String,
    checked: Boolean,
    textColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableWithTooltip { onCheckedChange(!checked) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GgCheckBox(checked = checked, onCheckedChange = onCheckedChange, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(tr(label), color = textColor, fontSize = fsp(14f))
    }
}

// ============================================================================
// 模糊搜索功能页
// - 界面调整 11：选择数据类型时，展开的列表里不显示数值范围（仅显示缩写与类型名称）
// - 界面调整 12：数据类型选择按钮右侧三角箭头未展开时朝右（▸），展开时朝下（▾）
// ============================================================================

@Composable
fun FuzzySearchSheet(ui: Ui) {
    val fz = ui.fuzzy
    val hasResults = ui.rawResults.isNotEmpty() || fz.active
    var showTypeList by remember { mutableStateOf(true) }

    // 界面问题 7：统一提升模糊搜索全部输入框状态，支持多输入栏焦点与内置软键盘无缝切换
    var deltaTf by remember {
        val sel = if (hasResults) TextRange(0, fz.deltaText.length) else TextRange(fz.deltaText.length)
        mutableStateOf(TextFieldValue(fz.deltaText, sel))
    }
    var fromTf by remember {
        val sel = if (!hasResults) TextRange(0, fz.rangeFrom.length) else TextRange(fz.rangeFrom.length)
        mutableStateOf(TextFieldValue(fz.rangeFrom, sel))
    }
    var toTf by remember {
        mutableStateOf(TextFieldValue(fz.rangeTo, TextRange(fz.rangeTo.length)))
    }
    var addrTf by remember {
        val cur = fz.nearbyAddress.ifEmpty {
            java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
        }
        mutableStateOf(TextFieldValue(cur, TextRange(cur.length)))
    }
    var distTf by remember {
        mutableStateOf(TextFieldValue(fz.nearbyDistance, TextRange(fz.nearbyDistance.length)))
    }
    var onlyNthTf by remember {
        mutableStateOf(TextFieldValue(fz.onlyNth, TextRange(fz.onlyNth.length)))
    }
    // 0: deltaTf, 1: fromTf, 2: toTf, 3: addrTf, 4: distTf, 5: onlyNthTf
    var activeField by remember { mutableIntStateOf(if (hasResults) 0 else 1) }

    val activeFuzzyTf = when (activeField) {
        1 -> fromTf
        2 -> toTf
        3 -> addrTf
        4 -> distTf
        5 -> onlyNthTf
        else -> deltaTf
    }

    fun onActiveChange(next: TextFieldValue) {
        when (activeField) {
            1 -> {
                fromTf = next
                Session.updateFuzzy { it.copy(rangeFrom = next.text) }
            }
            2 -> {
                toTf = next
                Session.updateFuzzy { it.copy(rangeTo = next.text) }
            }
            3 -> {
                addrTf = next
                Session.updateFuzzy { it.copy(nearbyAddress = next.text) }
            }
            4 -> {
                distTf = next
                Session.updateFuzzy { it.copy(nearbyDistance = next.text) }
            }
            5 -> {
                onlyNthTf = next
                Session.updateFuzzy { it.copy(onlyNth = next.text) }
            }
            else -> {
                deltaTf = next
                Session.updateFuzzy { it.copy(deltaText = next.text) }
            }
        }
    }

    val typeArrowRot by animateFloatAsState(
        targetValue = if (showTypeList) 0f else -90f,
        animationSpec = Motion.Fast,
        label = "fuzzyTypeArrow"
    )

    val metrics = LocalUiMetrics.current
    val corner = metrics.dialogCorner.dp
    val cardAlpha = (metrics.bgOpacity + 0.04f).coerceIn(0.55f, 1.0f)
    val showFuzzyKeyboard = if (!hasResults) (fz.extended && fz.rangeMode != 0 && fz.keyboardVisible) else fz.keyboardVisible

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenWidthDp > configuration.screenHeightDp
        val fallbackHeightDp = configuration.screenHeightDp.coerceAtLeast(240)
        val availHeightDp = if (maxHeight.value.isFinite() && maxHeight.value > 120f) {
            maxHeight.value.toInt()
        } else {
            fallbackHeightDp
        }
        val maxCardHeight = (availHeightDp - 10).coerceAtLeast(160).dp
        val useSideKeyboard = isLandscape && showFuzzyKeyboard

        Row(
            Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .widthIn(max = if (useSideKeyboard) 880.dp else 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                .clickableNoRipple(remember { MutableInteractionSource() }) {}
                .background(Rose.Card.copy(alpha = cardAlpha), RoundedCornerShape(corner))
                .border(1.dp, Rose.Divider, RoundedCornerShape(corner)),
            verticalAlignment = Alignment.Top
        ) {
        Column(
            Modifier
                .weight(if (useSideKeyboard) 1.12f else 1f)
                .heightIn(max = maxCardHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
        if (!hasResults) {
            // 无搜索结果时的初始模糊搜索界面
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("模糊搜索"),
                    color = Rose.Text,
                    fontSize = fsp(18f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (fz.extended && fz.rangeMode != 0) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clickableWithTooltip {
                                Session.updateFuzzy { it.copy(keyboardVisible = !it.keyboardVisible) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Keyboard,
                            contentDescription = "内置键盘",
                            tint = if (fz.keyboardVisible) Rose.Primary else Rose.TextSecond,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Text(
                text = tr("选择要采样的数值类型，点击下方「搜索」按钮进行初始内存采样："),
                color = Rose.TextSecond,
                fontSize = fsp(12.5f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val selCol = if (fz.type == ValueType.AUTO) Rose.Text else typeColor(fz.type)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(Rose.CardHigh, GgShapes.Sm)
                    .border(1.dp, Rose.Divider, GgShapes.Sm)
                    .clickableWithTooltip { showTypeList = !showTypeList }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ValueType.shortName(fz.type),
                    color = selCol,
                    fontSize = fsp(15f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(32.dp)
                )
                Text(
                    text = ValueType.longName(fz.type),
                    color = selCol,
                    fontSize = fsp(14f),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = selCol,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = typeArrowRot }
                )
            }

            AnimatedVisibility(showTypeList, enter = expandEnter(), exit = expandExit()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    (ValueType.order + ValueType.AUTO).forEach { t ->
                        val on = fz.type == t
                        val isAuto = t == ValueType.AUTO
                        val shortName = if (isAuto) "A" else ValueType.shortName(t)
                        val col = if (isAuto) Rose.Text else typeColor(t)
                        val bgCol by animateColorAsState(
                            if (on) Rose.ChipSelected else Rose.CardHigh,
                            Motion.ColorFast,
                            label = "fzTypeBg"
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(bgCol, GgShapes.Sm)
                                .border(
                                    width = if (on) 1.dp else 0.dp,
                                    color = if (on) col else Color.Transparent,
                                    shape = GgShapes.Sm
                                )
                                .clickableWithTooltip {
                                    Session.updateFuzzy { it.copy(type = t) }
                                    showTypeList = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                shortName,
                                color = col,
                                fontSize = fsp(14f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(fz.extended, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.padding(top = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            0 to "全部内存",
                            1 to "指定地址区间",
                            2 to "附近:"
                        ).forEach { (m, label) ->
                            val isSel = fz.rangeMode == m
                            var pillPressed by remember { mutableStateOf(false) }
                            val pillScale by animateFloatAsState(
                                if (pillPressed) 0.94f else 1f,
                                Motion.Fast,
                                label = "fzPillScale"
                            )
                            val pillBg by animateColorAsState(
                                if (isSel) Rose.ChipSelected else if (pillPressed) Rose.Chip else Rose.CardHigh,
                                Motion.ColorFast,
                                label = "fzPillBg"
                            )
                            val pillTxt by animateColorAsState(
                                if (isSel) Rose.Primary else Rose.TextSecond,
                                Motion.ColorFast,
                                label = "fzPillTxt"
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .scale(pillScale)
                                    .background(pillBg, GgShapes.Sm)
                                    .border(
                                        width = if (isSel) 1.dp else 0.dp,
                                        color = if (isSel) Rose.Primary.copy(alpha = 0.45f) else Color.Transparent,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableWithTooltip(
                                        onPressChange = { pillPressed = it },
                                        onClick = {
                                            Session.updateFuzzy { it.copy(rangeMode = m) }
                                            when (m) {
                                                1 -> activeField = 1
                                                2 -> activeField = 3
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    tr(label),
                                    color = pillTxt,
                                    fontSize = fsp(12.5f),
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // 模式 1：指定地址区间
                    AnimatedVisibility(fz.rangeMode == 1, enter = expandEnter(), exit = expandExit()) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Rose.CardHigh, GgShapes.Sm)
                                    .border(
                                        width = 1.dp,
                                        color = if (activeField == 1) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableNoRipple(remember { MutableInteractionSource() }) { activeField = 1 }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("从:"), color = Rose.TextSecond, fontSize = fsp(13f), modifier = Modifier.width(36.dp))
                                GgTextField(
                                    value = fromTf,
                                    onValueChange = {
                                        activeField = 1
                                        fromTf = it
                                        Session.updateFuzzy { f -> f.copy(rangeFrom = it.text) }
                                    },
                                    onFocus = { activeField = 1 },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    textStyle = monoStyle(14, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = fz.keyboardVisible
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Rose.CardHigh, GgShapes.Sm)
                                    .border(
                                        width = 1.dp,
                                        color = if (activeField == 2) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableNoRipple(remember { MutableInteractionSource() }) { activeField = 2 }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("到:"), color = Rose.TextSecond, fontSize = fsp(13f), modifier = Modifier.width(36.dp))
                                GgTextField(
                                    value = toTf,
                                    onValueChange = {
                                        activeField = 2
                                        toTf = it
                                        Session.updateFuzzy { f -> f.copy(rangeTo = it.text) }
                                    },
                                    onFocus = { activeField = 2 },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    textStyle = monoStyle(14, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = fz.keyboardVisible
                                )
                            }
                        }
                    }

                    // 模式 2：附近
                    AnimatedVisibility(fz.rangeMode == 2, enter = expandEnter(), exit = expandExit()) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Rose.CardHigh, GgShapes.Sm)
                                    .border(
                                        width = 1.dp,
                                        color = if (activeField == 3) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableNoRipple(remember { MutableInteractionSource() }) { activeField = 3 }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("地址:"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                                Spacer(Modifier.width(8.dp))
                                GgTextField(
                                    value = addrTf,
                                    onValueChange = {
                                        activeField = 3
                                        addrTf = it
                                        Session.updateFuzzy { f -> f.copy(nearbyAddress = it.text) }
                                    },
                                    onFocus = { activeField = 3 },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    textStyle = monoStyle(14, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = fz.keyboardVisible
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickableWithTooltip {
                                        Session.updateFuzzy { f -> f.copy(nearbyBefore = !f.nearbyBefore) }
                                    }
                                ) {
                                    GgCheckBox(checked = fz.nearbyBefore, onCheckedChange = {
                                        Session.updateFuzzy { f -> f.copy(nearbyBefore = it) }
                                    }, size = 18.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(tr("之前"), color = Rose.Text, fontSize = fsp(13f))
                                }
                                Spacer(Modifier.width(20.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickableWithTooltip {
                                        Session.updateFuzzy { f -> f.copy(nearbyAfter = !f.nearbyAfter) }
                                    }
                                ) {
                                    GgCheckBox(checked = fz.nearbyAfter, onCheckedChange = {
                                        Session.updateFuzzy { f -> f.copy(nearbyAfter = it) }
                                    }, size = 18.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(tr("后"), color = Rose.Text, fontSize = fsp(13f))
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(Rose.CardHigh, GgShapes.Sm)
                                    .border(
                                        width = 1.dp,
                                        color = if (activeField == 4) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                                        shape = GgShapes.Sm
                                    )
                                    .clickableNoRipple(remember { MutableInteractionSource() }) { activeField = 4 }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("距离:"), color = Rose.TextSecond, fontSize = fsp(13.5f))
                                Spacer(Modifier.width(8.dp))
                                GgTextField(
                                    value = distTf,
                                    onValueChange = {
                                        activeField = 4
                                        distTf = it
                                        Session.updateFuzzy { f -> f.copy(nearbyDistance = it.text) }
                                    },
                                    onFocus = { activeField = 4 },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    textStyle = monoStyle(14, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = fz.keyboardVisible,
                                    placeholder = "500"
                                )
                            }
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
                    modifier = Modifier.width(68.dp).height(38.dp)
                ) {
                    Session.closeSheet()
                }
                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = if (fz.extended) "收起" else "更多",
                    modifier = Modifier.width(68.dp).height(38.dp)
                ) {
                    Session.updateFuzzy { it.copy(extended = !it.extended) }
                }
                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = "搜索",
                    filled = true,
                    modifier = Modifier.width(74.dp).height(38.dp)
                ) {
                    Session.runFuzzySearch(ValueType.SIGN_EQUAL, forceNew = true)
                }
            }

            if (!useSideKeyboard) {
                AnimatedVisibility(fz.extended && fz.rangeMode != 0 && fz.keyboardVisible, enter = expandEnter(), exit = expandExit()) {
                    Column(Modifier.padding(top = 8.dp)) {
                        BuiltInKeyboard(
                            value = activeFuzzyTf,
                            onValueChange = ::onActiveChange,
                            onHistory = { Session.openSheet(Sheet.HISTORY) },
                            onDismiss = { Session.updateFuzzy { it.copy(keyboardVisible = false) } }
                        )
                    }
                }
            }
        } else {
            // 已有搜索结果时的模糊筛选界面
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("值是否发生了改变?"),
                    color = Rose.Text,
                    fontSize = fsp(14f),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = 2.dp)
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clickableWithTooltip {
                            Session.updateFuzzy { it.copy(keyboardVisible = !it.keyboardVisible) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Keyboard,
                        contentDescription = "内置键盘",
                        tint = if (fz.keyboardVisible) Rose.Primary else Rose.TextSecond,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(fz.extended, enter = expandEnter(), exit = expandExit()) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(
                        "N - 新值;  O - 旧值;  D - 差值",
                        color = typeColor(fz.type),
                        fontSize = fsp(11.5f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("差值 (D): ", color = Rose.Text, fontSize = fsp(13.5f))
                        Box(Modifier.weight(1f)) {
                            Column {
                                GgTextField(
                                    value = deltaTf,
                                    onValueChange = {
                                        activeField = 0
                                        onActiveChange(it)
                                    },
                                    onFocus = { activeField = 0 },
                                    modifier = Modifier.fillMaxWidth().height(30.dp),
                                    textStyle = monoStyle(14, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = fz.keyboardVisible
                                )
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(if (activeField == 0) Rose.Primary else Rose.Divider)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("每 N 个取 1 个: "), color = Rose.TextSecond, fontSize = fsp(12.5f))
                        Box(Modifier.weight(1f)) {
                            Column {
                                GgTextField(
                                    value = onlyNthTf,
                                    onValueChange = {
                                        activeField = 5
                                        onActiveChange(it)
                                    },
                                    onFocus = { activeField = 5 },
                                    modifier = Modifier.fillMaxWidth().height(28.dp),
                                    textStyle = monoStyle(13, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = fz.keyboardVisible,
                                    placeholder = "1"
                                )
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(if (activeField == 5) Rose.Primary else Rose.Divider)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        ValueType.order.forEach { t ->
                            val on = fz.type == t
                            val bgCol by animateColorAsState(
                                if (on) Rose.ChipSelected else Rose.CardHigh,
                                Motion.ColorFast,
                                label = "fzResTypeBg"
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .background(bgCol, GgShapes.Xs)
                                    .clickableWithTooltip {
                                        Session.updateFuzzy { it.copy(type = t) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(ValueType.shortName(t), color = typeColor(t), fontSize = fsp(12.5f), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FuzzyOptionButton(
                    label = if (fz.extended) "N = O+D" else "无变化",
                    modifier = Modifier.weight(1f)
                ) {
                    Session.runFuzzySearch(ValueType.SIGN_EQUAL)
                }
                FuzzyOptionButton(
                    label = if (fz.extended) "N ≠ O+D" else "有变化",
                    modifier = Modifier.weight(1f)
                ) {
                    Session.runFuzzySearch(ValueType.SIGN_NOT_EQUAL)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FuzzyOptionButton(
                    label = if (fz.extended) "N > O+D" else "增加了",
                    modifier = Modifier.weight(1f)
                ) {
                    Session.runFuzzySearch(ValueType.SIGN_GREATER_EQ)
                }
                FuzzyOptionButton(
                    label = if (fz.extended) "N < O+D" else "减少了",
                    modifier = Modifier.weight(1f)
                ) {
                    Session.runFuzzySearch(ValueType.SIGN_LESS_EQ)
                }
            }

            Spacer(Modifier.height(10.dp))

            FuzzyOptionButton(
                label = fz.rangeLabel,
                height = 42,
                modifier = Modifier.fillMaxWidth()
            ) {
                Session.updateFuzzy { it.copy(rangeMode = (it.rangeMode + 1) % 3) }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FuzzyOptionButton(
                    label = "新搜索",
                    height = 42,
                    modifier = Modifier.weight(1f)
                ) {
                    Session.runFuzzySearch(ValueType.SIGN_EQUAL, forceNew = true)
                }
                FuzzyOptionButton(
                    label = "取消",
                    height = 42,
                    modifier = Modifier.weight(1f)
                ) {
                    Session.closeSheet()
                }
                FuzzyOptionButton(
                    label = if (fz.extended) "收起" else "更多",
                    height = 42,
                    modifier = Modifier.weight(1f)
                ) {
                    activeField = 0
                    Session.updateFuzzy { it.copy(extended = !it.extended) }
                }
            }

            if (!useSideKeyboard) {
                AnimatedVisibility(fz.keyboardVisible, enter = expandEnter(), exit = expandExit()) {
                    Column(Modifier.padding(top = 8.dp)) {
                        BuiltInKeyboard(
                            value = activeFuzzyTf,
                            onValueChange = ::onActiveChange,
                            onHistory = { Session.openSheet(Sheet.HISTORY) },
                            onDismiss = { Session.updateFuzzy { it.copy(keyboardVisible = false) } }
                        )
                    }
                }
            }
        }
        }
        if (useSideKeyboard) {
            Box(
                Modifier
                    .weight(0.92f)
                    .padding(end = 10.dp, top = 8.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                BuiltInKeyboard(
                    value = activeFuzzyTf,
                    onValueChange = ::onActiveChange,
                    onHistory = { Session.openSheet(Sheet.HISTORY) },
                    onDismiss = { Session.updateFuzzy { it.copy(keyboardVisible = false) } }
                )
            }
        }
        }
    }
}

@Composable
private fun FuzzyOptionButton(
    label: String,
    height: Int = 44,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.Fast, label = "fzBtnScale")
    val bg by animateColorAsState(
        if (pressed) Rose.PrimaryDeep else Rose.CardHigh,
        Motion.ColorFast,
        label = "fzBtnBg"
    )
    Box(
        modifier
            .height(height.dp)
            .scale(scale)
            .background(bg, GgShapes.Sm)
            .border(1.dp, Rose.Divider, GgShapes.Sm)
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            tr(label),
            color = Rose.Text,
            fontSize = fsp(14.5f),
            fontWeight = FontWeight.Medium
        )
    }
}

// ============================================================================
// 蒙版搜索 (MaskSearchSheet) 与指针搜索 (PointerSearchSheet)
// ============================================================================

@Composable
fun MaskSearchSheet(ui: Ui) {
    var addrTf by remember {
        val baseHex = java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
        val fallbackPattern = if (baseHex.length > 3) baseHex.dropLast(3) + "???" else "$baseHex???"
        val init = ui.search.text.ifEmpty { fallbackPattern }
        mutableStateOf(TextFieldValue(init, TextRange(0, init.length)))
    }
    var maskTf by remember {
        mutableStateOf(TextFieldValue(ui.search.mask, TextRange(ui.search.mask.length)))
    }
    var focusMask by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(true) }

    val resultantMask = remember(addrTf.text, maskTf.text) {
        val patternMask = addrTf.text.trim().map { if (it == '?') '0' else 'F' }.joinToString("")
        val m1 = patternMask.toULongOrNull(16) ?: ULong.MAX_VALUE
        val m2 = maskTf.text.trim().removePrefix("0x").toULongOrNull(16) ?: ULong.MAX_VALUE
        (m1 and m2).toString(16).uppercase()
    }

    NextSheetCard(
        title = "地址蒙版搜索",
        subtitle = "支持使用 ? 通配符匹配特定偏移规律的内存地址",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 760,
        showSideKeyboard = showKeyboard,
        sideKeyboard = {
            BuiltInKeyboard(
                value = if (focusMask) maskTf else addrTf,
                onValueChange = { next ->
                    if (focusMask) {
                        maskTf = next
                        Session.updateSearch { s -> s.copy(mask = next.text) }
                    } else {
                        addrTf = next
                        Session.updateSearch { s -> s.copy(text = next.text) }
                    }
                },
                onHistory = { Session.openSheet(Sheet.HISTORY) },
                onDismiss = { showKeyboard = false }
            )
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, GgShapes.Md)
                    .border(
                        width = 1.dp,
                        color = if (!focusMask) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                        shape = GgShapes.Md
                    )
                    .clickableNoRipple(remember { MutableInteractionSource() }) { focusMask = false }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("地址:"), color = Rose.TextSecond, fontSize = fsp(13f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = addrTf,
                    onValueChange = {
                        focusMask = false
                        addrTf = it
                        Session.updateSearch { s -> s.copy(text = it.text) }
                    },
                    onFocus = { focusMask = false },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(34.dp)
                        .clickableWithTooltip { showKeyboard = !showKeyboard },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Keyboard,
                        contentDescription = "内置键盘",
                        tint = if (showKeyboard) Rose.Primary else Rose.TextSecond,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, GgShapes.Md)
                    .border(
                        width = 1.dp,
                        color = if (focusMask) Rose.Primary.copy(alpha = 0.55f) else Rose.Divider,
                        shape = GgShapes.Md
                    )
                    .clickableNoRipple(remember { MutableInteractionSource() }) { focusMask = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("蒙版:"), color = Rose.TextSecond, fontSize = fsp(13f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = maskTf,
                    onValueChange = {
                        focusMask = true
                        maskTf = it
                        Session.updateSearch { s -> s.copy(mask = it.text) }
                    },
                    onFocus = { focusMask = true },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(tr("最终合成蒙版: $resultantMask"), style = monoStyle(12, Rose.Green))
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoseButton(
                    label = "改善",
                    modifier = Modifier.width(72.dp).height(38.dp),
                    enabled = ui.rawResults.isNotEmpty()
                ) {
                    Session.runMaskSearch(false)
                }
                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = "新搜索",
                    modifier = Modifier.width(78.dp).height(38.dp),
                    filled = true
                ) {
                    Session.runMaskSearch(true)
                }
            }
        }
    }
}

@Composable
fun PointerSearchSheet(ui: Ui) {
    val defaultAddr = ui.contextItem?.cachedAddressHex
        ?: ui.editing?.cachedAddressHex
        ?: ui.search.text.ifEmpty {
            java.lang.Long.toHexString(ui.memoryHighlightAddress ?: ui.memoryBase).uppercase()
        }
    var addrTf by remember {
        mutableStateOf(TextFieldValue(defaultAddr, TextRange(0, defaultAddr.length)))
    }
    var offsetTf by remember {
        val offInit = ui.search.offset.ifEmpty { "200" }
        mutableStateOf(TextFieldValue(offInit, TextRange(offInit.length)))
    }
    var focusOffset by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(true) }

    NextSheetCard(
        title = "指针搜索",
        subtitle = "查找指向目标地址及最大偏移范围内的指针链",
        onDismiss = Session::closeSheet,
        maxBodyHeight = 760,
        showSideKeyboard = showKeyboard,
        sideKeyboard = {
            BuiltInKeyboard(
                value = if (focusOffset) offsetTf else addrTf,
                onValueChange = { next ->
                    if (focusOffset) {
                        offsetTf = next
                        Session.updateSearch { s -> s.copy(offset = next.text) }
                    } else {
                        addrTf = next
                        Session.updateSearch { s -> s.copy(text = next.text) }
                    }
                },
                onHistory = { Session.openSheet(Sheet.HISTORY) },
                onDismiss = { showKeyboard = false }
            )
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, GgShapes.Md)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("目标地址:"), color = Rose.TextSecond, fontSize = fsp(13f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = addrTf,
                    onValueChange = {
                        focusOffset = false
                        addrTf = it
                        Session.updateSearch { s -> s.copy(text = it.text) }
                    },
                    onFocus = { focusOffset = false },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, GgShapes.Md)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("最大偏移:"), color = Rose.TextSecond, fontSize = fsp(13f))
                Spacer(Modifier.width(8.dp))
                GgTextField(
                    value = offsetTf,
                    onValueChange = {
                        focusOffset = true
                        offsetTf = it
                        Session.updateSearch { s -> s.copy(offset = it.text) }
                    },
                    onFocus = { focusOffset = true },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = monoStyle(15, Rose.Text),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = showKeyboard
                )
                Box(
                    Modifier
                        .background(if (ui.search.offsetHex) Rose.ChipSelected else Rose.Chip, GgShapes.Xs)
                        .clickableWithTooltip {
                            Session.updateSearch { s -> s.copy(offsetHex = !s.offsetHex) }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (ui.search.offsetHex) "HEX" else "DEC", color = Rose.Text, fontSize = fsp(11f))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 需求 14：数据类型采用有类型颜色的胶囊（Dword / Qword，不显示 32、64 位字符）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Rose.CardHigh, GgShapes.Md)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("数据类型:"), color = Rose.TextSecond, fontSize = fsp(13f))
                Spacer(Modifier.width(10.dp))
                listOf(
                    ValueType.DWORD to "Dword",
                    ValueType.QWORD to "Qword"
                ).forEach { (vType, label) ->
                    val isSel = ui.search.pointerType == vType
                    val col = typeColor(vType)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .background(
                                if (isSel) col.copy(alpha = if (Rose.IsDark) 0.22f else 0.18f) else Rose.Chip,
                                GgShapes.Sm
                            )
                            .border(
                                width = if (isSel) 1.5.dp else 0.dp,
                                color = if (isSel) col else Color.Transparent,
                                shape = GgShapes.Sm
                            )
                            .clickableWithTooltip {
                                Session.updateSearch { s -> s.copy(pointerType = vType) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (isSel) col else Rose.TextSecond,
                            fontSize = fsp(13f),
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    if (vType == ValueType.DWORD) Spacer(Modifier.width(8.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoseButton(
                    label = "改善",
                    modifier = Modifier.width(72.dp).height(38.dp),
                    enabled = ui.rawResults.isNotEmpty()
                ) {
                    Session.updateSearch { s -> s.copy(text = addrTf.text, offset = offsetTf.text) }
                    Session.runPointerSearch(false)
                }
                Spacer(Modifier.width(8.dp))
                RoseButton(
                    label = "新搜索",
                    modifier = Modifier.width(78.dp).height(38.dp),
                    filled = true
                ) {
                    Session.updateSearch { s -> s.copy(text = addrTf.text, offset = offsetTf.text) }
                    Session.runPointerSearch(true)
                }
            }
        }
    }
}
