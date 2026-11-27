package com.littlecodesauce.ggnext.ui.sheet

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.FileBrowserTarget
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.ui.components.GgIconActionButton
import com.littlecodesauce.ggnext.ui.components.GgSegmentTab
import com.littlecodesauce.ggnext.ui.components.GgSettingSliderRow
import com.littlecodesauce.ggnext.ui.components.GgSettingSwitchRow
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.DarkMode
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.ThemePreset
import kotlin.math.roundToInt

@Composable
fun AppearanceSheet(ui: Ui) {
    val m = ui.metrics
    val bgOpacityPct = (m.bgOpacity * 100f).roundToInt().coerceIn(25, 100)
    val panelScalePct = (m.panelScale * 100f).roundToInt().coerceIn(40, 100)

    NextSheetCard(
        title = "外观",
        subtitle = "主题配色、字体、深浅色模式、背景透明度与屏幕占比",
        onDismiss = { Session.popSheet() },
        maxBodyHeight = 720
    ) {
        Column {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(tr("主题色"), color = Rose.TextSecond, fontSize = fsp(13f))
                Spacer(Modifier.height(GgSpacing.Md))
                val isDark = Rose.IsDark
                Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.Md)) {
                    ThemePreset.entries.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(GgSpacing.Md)) {
                            row.forEach { p ->
                                val on = ui.preset == p
                                val swatch = p.seedFor(isDark)
                                val checkTint = if (swatch.luminance() > 0.55f) Rose.OnDarkInk else Color.White
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickableWithTooltip(tooltip = tr(p.label)) {
                                            Session.setPreset(p)
                                            Session.toast("${tr("已切换主题：")}${tr(p.label)}")
                                        },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        Modifier
                                            .size(GgControlHeight.Button)
                                            .background(swatch, CircleShape)
                                            .then(if (on) Modifier.border(2.dp, Rose.Text, CircleShape) else Modifier),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (on) {
                                            Icon(
                                                Icons.Rounded.Check, null,
                                                tint = checkTint,
                                                modifier = Modifier.size(GgIconSize.Md)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(GgSpacing.Xs))
                                    Text(
                                        tr(p.label),
                                        color = if (on) Rose.Text else Rose.TextSecond,
                                        fontSize = fsp(11f),
                                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(GgSpacing.Xl))
                Text(tr("深色模式"), color = Rose.TextSecond, fontSize = fsp(13f))
                Spacer(Modifier.height(GgSpacing.Md))
                Row(horizontalArrangement = Arrangement.spacedBy(GgSpacing.Md)) {
                    DarkMode.entries.forEach { d ->
                        GgSegmentTab(
                            label = d.label,
                            selected = ui.darkMode == d,
                            modifier = Modifier.weight(1f),
                            height = GgControlHeight.ButtonLg,
                            shape = GgShapes.Md,
                            accent = Rose.Text,
                            fontSize = 13f,
                            horizontalPadding = GgSpacing.Xs
                        ) {
                            Session.setDarkMode(d)
                            Session.toast("${tr("深色模式")}：${tr(d.label)}")
                        }
                    }
                }

                Spacer(Modifier.height(GgSpacing.Lg))
                GgSettingSwitchRow(
                    title = "动态取色",
                    subtitle = if (android.os.Build.VERSION.SDK_INT >= 31) "Android 12+ 从壁纸提取配色" else "需要 Android 12 及以上",
                    checked = ui.dynamicColor,
                    onCheckedChange = { next ->
                        Session.setDynamicColor(next)
                        Session.toast(if (next) "已开启壁纸动态取色" else "已关闭壁纸动态取色")
                    },
                    containerBackground = if (ui.dynamicColor) Rose.ChipSelected else Rose.CardHigh,
                    shape = GgShapes.Md,
                    horizontalPadding = 14.dp,
                    verticalPadding = GgSpacing.MdPlus
                )

                Spacer(Modifier.height(GgSpacing.Lg))
                ThemeFontImportCard(ui = ui)

                Spacer(Modifier.height(GgSpacing.Md))
                GgSettingSwitchRow(
                    title = "毛玻璃效果",
                    subtitle = "所有页面、卡片与悬浮窗实时毛玻璃折射",
                    checked = m.glassEnabled,
                    onCheckedChange = { next ->
                        Session.setSetting("glass_enabled", if (next) "1" else "0")
                    },
                    containerBackground = if (m.glassEnabled) Rose.ChipSelected else Rose.CardHigh,
                    shape = GgShapes.Md,
                    horizontalPadding = 14.dp,
                    verticalPadding = GgSpacing.Md
                )
                if (m.glassEnabled) {
                    GgSettingSliderRow("毛玻璃强度", m.glassBlur.toFloat(), 0f..36f, "${m.glassBlur}dp") {
                        Session.setSetting("glass_blur", it.roundToInt().coerceIn(0, 36).toString())
                    }
                }

                Spacer(Modifier.height(GgSpacing.Sm))
                GgSettingSliderRow("背景透明度", bgOpacityPct.toFloat(), 25f..100f, "$bgOpacityPct%") { pct ->
                    val ratio = (pct.roundToInt().coerceIn(25, 100)) / 100f
                    Session.setSetting("bg_opacity", String.format(java.util.Locale.US, "%.2f", ratio))
                }
                GgSettingSliderRow("页面屏幕占比", panelScalePct.toFloat(), 40f..100f, "$panelScalePct%") { pct ->
                    val ratio = (pct.roundToInt().coerceIn(40, 100)) / 100f
                    Session.setSetting("panel_scale", String.format(java.util.Locale.US, "%.2f", ratio))
                }
                GgSettingSliderRow("界面字体缩放", m.fontScale, 0.7f..1.3f, "%.2f".format(m.fontScale)) {
                    Session.setSetting("font_scale", "%.2f".format(it))
                }
                GgSettingSliderRow("伪弹窗圆角", m.dialogCorner.toFloat(), 0f..28f, "${m.dialogCorner}dp") {
                    Session.setSetting("dialog_corner", it.toInt().toString())
                }
            }
            Spacer(Modifier.height(GgSpacing.Md))
            RoseButton("完成", Modifier.fillMaxWidth(), filled = true) { Session.popSheet() }
        }
    }
}

@Composable
private fun ThemeFontImportCard(ui: Ui) {
    val activePath = ui.customFontPath
    val hasActivityResultOwner = LocalActivityResultRegistryOwner.current != null

    Column(
        Modifier
            .fillMaxWidth()
            .background(Rose.CardHigh, GgShapes.Md)
            .padding(horizontal = 14.dp, vertical = GgSpacing.MdPlus),
        verticalArrangement = Arrangement.spacedBy(GgSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.FontDownload,
                contentDescription = null,
                tint = Rose.Primary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(GgSpacing.MdPlus))
            Column(Modifier.weight(1f)) {
                Text(tr("字体管理"), color = Rose.Text, fontSize = fsp(14f), fontWeight = FontWeight.Medium)
                Text(
                    text = tr("支持导入多个 .ttf/.otf 字体并自由切换或删除"),
                    color = Rose.TextDim,
                    fontSize = fsp(10.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(GgSpacing.Sm))
            if (hasActivityResultOwner) {
                SafFontImportButton()
            } else {
                RoseButton(
                    label = "导入 TTF",
                    modifier = Modifier.height(32.dp),
                    filled = true
                ) {
                    Session.openFileBrowser(
                        initialPath = "/sdcard/Download",
                        selectDirectory = false,
                        returnSheet = Sheet.APPEARANCE,
                        target = FileBrowserTarget.FONT
                    )
                }
            }
        }

        // 系统默认字体项
        OverlayFontItemRow(
            name = tr("系统默认"),
            subtitle = tr("跟随系统内置字体"),
            selected = activePath.isBlank(),
            canDelete = false,
            onSelect = { Session.selectFont("") },
            onDelete = {}
        )

        // 用户已导入字体列表
        ui.managedFonts.forEach { font ->
            val sizeLabel = com.littlecodesauce.ggnext.ui.theme.AppFontCache.formatFontSize(font.sizeBytes)
            OverlayFontItemRow(
                name = font.displayName,
                subtitle = if (sizeLabel.isNotEmpty()) "${tr("已导入字体")} · $sizeLabel" else tr("已导入字体"),
                selected = activePath == font.path,
                canDelete = true,
                onSelect = { Session.selectFont(font.path, font.displayName) },
                onDelete = { Session.deleteManagedFont(font.path) }
            )
        }
    }
}

@Composable
private fun OverlayFontItemRow(
    name: String,
    subtitle: String,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) Rose.ChipSelected else Rose.Card,
                GgShapes.Sm
            )
            .border(
                width = if (selected) GgBorder.Default else GgBorder.Hairline,
                color = if (selected) Rose.Primary.copy(alpha = 0.65f) else Rose.Divider,
                shape = GgShapes.Sm
            )
            .clickableWithTooltip(onClick = onSelect)
            .padding(horizontal = GgSpacing.MdPlus, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(GgIconSize.Md)
                .background(
                    if (selected) Rose.Primary else Color.Transparent,
                    CircleShape
                )
                .border(
                    width = 1.4.dp,
                    color = if (selected) Rose.Primary else Rose.TextDim,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Rose.OnPrimary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Spacer(Modifier.width(GgSpacing.MdPlus))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = if (selected) Rose.Primary else Rose.Text,
                fontSize = fsp(12.8f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Rose.TextDim,
                fontSize = fsp(10.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canDelete) {
            GgIconActionButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "删除字体",
                tint = Rose.Red,
                iconSize = 17.dp,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun SafFontImportButton() {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Session.importCustomFontFromUri(uri)
        }
    }
    RoseButton(
        label = "导入 TTF",
        modifier = Modifier.height(32.dp),
        filled = true
    ) {
        launcher.launch(
            arrayOf(
                "font/ttf",
                "font/otf",
                "application/x-font-ttf",
                "application/x-font-opentype",
                "application/octet-stream",
                "*/*"
            )
        )
    }
}
