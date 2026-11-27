package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.core.WorkMode
import com.littlecodesauce.ggnext.model.BpMode
import com.littlecodesauce.ggnext.ui.components.GgConfirmDialog
import com.littlecodesauce.ggnext.ui.components.GgDivider
import com.littlecodesauce.ggnext.ui.components.GgSectionTitle
import com.littlecodesauce.ggnext.ui.components.GgSettingNavRow
import com.littlecodesauce.ggnext.ui.components.GgSettingSliderRow
import com.littlecodesauce.ggnext.ui.components.GgSettingSwitchRow
import com.littlecodesauce.ggnext.ui.components.GgSurfaceCard
import com.littlecodesauce.ggnext.ui.components.TargetProcessIcon
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.ui.components.rememberPersistentLazyListState
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import kotlin.math.roundToInt

/**
 * 悬浮窗内部的设置页：
 * 1. 通用设置（背景透明度、工作模式、页面屏幕占比、工具栏顺序与增删、外观与字体缩放、语言）
 * 2. 内存设置（进程选择、内存区域、自动暂停游戏、冻结间隔、搜索结果过滤器、数值显示格式、变速倍率设置）
 * 3. 操作与诊断（清除输入历史、运行日志、帮助文档、关于、终止此进程、退出）
 */
@Composable
fun SettingsScreen(ui: Ui, embedded: Boolean = false) {
    val autopause = (ui.settings["autopause"] ?: "0") == "1"
    val rawFreeze = ui.settings["freeze_interval"]?.toLongOrNull() ?: 33L
    val freezeIntervalMs = (if (rawFreeze > 1000L) rawFreeze / 1000L else rawFreeze).coerceIn(5L, 500L)

    val bgOpacityPct = (ui.metrics.bgOpacity * 100f).roundToInt().coerceIn(25, 100)
    val panelScalePct = (ui.metrics.panelScale * 100f).roundToInt().coerceIn(40, 100)

    var confirmKillProcess by remember { mutableStateOf(false) }
    var confirmExitApp by remember { mutableStateOf(false) }
    val listState = rememberPersistentLazyListState(if (embedded) "home_settings_list" else "settings_list")

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = GgSpacing.MdPlus, vertical = GgSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(GgSpacing.Md)
        ) {
            // 1. 通用设置
            item("general_section") { GgSectionTitle("通用设置") }
            item("general_card") {
                GgSurfaceCard(respectBgOpacity = true) {
                    GgSettingSliderRow(
                        title = "背景透明度",
                        value = bgOpacityPct.toFloat(),
                        range = 25f..100f,
                        display = "$bgOpacityPct%"
                    ) { pct ->
                        val ratio = (pct.roundToInt().coerceIn(25, 100)) / 100f
                        Session.setSetting("bg_opacity", String.format(java.util.Locale.US, "%.2f", ratio))
                    }

                    GgDivider()

                    GgSettingNavRow(
                        icon = Icons.Rounded.Security,
                        title = "工作模式",
                        subtitle = when (ui.workMode) {
                            WorkMode.ROOT -> if (ui.hasRootPermission) "Root 模式 · 已授权" else "Root 模式 · 未授权，点击切换"
                            WorkMode.NO_ROOT -> "免 Root 模式 · 共享 UID"
                        }
                    ) {
                        val nextMode = when (ui.workMode) {
                            WorkMode.ROOT -> WorkMode.NO_ROOT
                            WorkMode.NO_ROOT -> WorkMode.ROOT
                        }
                        Session.setWorkMode(nextMode)
                    }

                    GgDivider()

                    GgSettingSliderRow(
                        title = "页面屏幕占比",
                        value = panelScalePct.toFloat(),
                        range = 40f..100f,
                        display = "$panelScalePct%"
                    ) { pct ->
                        val ratio = (pct.roundToInt().coerceIn(40, 100)) / 100f
                        Session.setSetting("panel_scale", String.format(java.util.Locale.US, "%.2f", ratio))
                    }

                    GgDivider()

                    GgSettingNavRow(
                        icon = Icons.Rounded.Layers,
                        title = "工具栏顺序与增删",
                        subtitle = "调整搜索 / 保存 / 内存 / 调试页工具栏图标"
                    ) {
                        Session.openSheet(Sheet.TOOLBAR_ORDER)
                    }

                    GgDivider()

                    GgSettingSwitchRow(
                        title = "毛玻璃效果",
                        subtitle = if (ui.metrics.glassEnabled) "已开启 · 模糊强度 ${ui.metrics.glassBlur}dp" else "已关闭",
                        checked = ui.metrics.glassEnabled,
                        onCheckedChange = { next ->
                            Session.setSetting("glass_enabled", if (next) "1" else "0")
                        }
                    )

                    GgDivider()

                    val fontSub = ui.customFontName.ifBlank { tr("系统默认") }
                    GgSettingNavRow(
                        icon = Icons.Rounded.Palette,
                        title = "外观与主题",
                        subtitle = "当前主题：${tr(ui.preset.label)} · ${tr(ui.darkMode.label)} · $fontSub"
                    ) {
                        Session.openSheet(Sheet.APPEARANCE)
                    }

                    GgDivider()

                    GgSettingNavRow(
                        icon = Icons.Rounded.Language,
                        title = "语言",
                        subtitle = when (ui.settings["language"]) {
                            "1", "2" -> "English"
                            else -> "简体中文"
                        }
                    ) {
                        Session.openSheet(Sheet.LANGUAGE)
                    }
                }
            }

            // 2. 内存设置
            item("memory_section") { GgSectionTitle("内存设置") }
            item("memory_card") {
                GgSurfaceCard(respectBgOpacity = true) {
                    GgSettingNavRow(
                        icon = Icons.Rounded.Memory,
                        title = "进程选择",
                        subtitle = ui.target?.let { "[${it.pid}] ${it.label} · ${if (it.x64) "x64" else "x86"}" } ?: "未选择进程，点击选择",
                        leadingContent = if (ui.target != null) {
                            {
                                TargetProcessIcon(
                                    process = ui.target,
                                    size = 38.dp,
                                    shape = GgShapes.Md,
                                    showActiveBorder = true
                                )
                            }
                        } else null
                    ) {
                        Session.openSheet(Sheet.PROCESS)
                    }

                    GgDivider()

                    GgSettingNavRow(
                        icon = Icons.Rounded.Layers,
                        title = "内存区域",
                        subtitle = ui.selectedRegions.joinToString(", ").ifEmpty { "未选择" }
                    ) {
                        Session.openSheet(Sheet.REGION)
                    }

                    GgDivider()

                    GgSettingSwitchRow(
                        title = "自动暂停游戏",
                        checked = autopause,
                        onCheckedChange = { checked ->
                            val next = if (checked) "1" else "0"
                            Session.setSetting("autopause", next)
                            Session.toast(if (next == "1") "已开启自动暂停游戏" else "已关闭自动暂停游戏")
                        }
                    )

                    GgDivider()

                    GgSettingSliderRow(
                        title = "冻结间隔",
                        value = freezeIntervalMs.toFloat(),
                        range = 5f..500f,
                        display = "$freezeIntervalMs ms"
                    ) {
                        Session.setSetting("freeze_interval", it.roundToInt().coerceIn(5, 500).toString())
                    }

                    GgDivider()

                    GgSettingNavRow(
                        icon = Icons.Rounded.FilterAlt,
                        title = "搜索结果过滤器",
                        subtitle = "最大显示 ${ui.filter.maxShow} 条 · 地址/数值/类型筛选"
                    ) {
                        Session.openSheet(Sheet.FILTER)
                    }

                    GgDivider()

                    GgSettingNavRow(
                        icon = Icons.Rounded.Speed,
                        title = "变速倍率设置",
                        subtitle = "当前：${ui.speedText}"
                    ) {
                        Session.openSheet(Sheet.SPEED)
                    }

                    GgDivider()

                    val bpBackend = Session.breakpointBackendMode(ui)
                    GgSettingNavRow(
                        icon = Icons.Rounded.BugReport,
                        title = "断点调试后端",
                        subtitle = if (bpBackend == BpMode.PERF) {
                            "Perf 硬件断点 · 点击切换为 Ptrace"
                        } else {
                            "Ptrace 断点 · 点击切换为 Perf"
                        }
                    ) {
                        val next = if (bpBackend == BpMode.PERF) BpMode.PTRACE else BpMode.PERF
                        Session.setBreakpointBackendMode(next)
                    }
                }
            }

            // 3. 操作与诊断
            item("op_section") { GgSectionTitle("操作与诊断") }
            item("op_card") {
                GgSurfaceCard(respectBgOpacity = true) {
                    GgSettingNavRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "清除输入历史",
                        subtitle = "当前共有 ${ui.history.size} 条输入记录"
                    ) {
                        Session.clearHistory()
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.BugReport,
                        title = "运行日志",
                        subtitle = "查看引擎与附加记录"
                    ) {
                        Session.openSheet(Sheet.LOG)
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.AutoMirrored.Rounded.HelpOutline,
                        title = "帮助文档",
                        subtitle = "查看搜索语法与功能说明"
                    ) {
                        Session.openSheet(Sheet.HELP)
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.Info,
                        title = "关于",
                        subtitle = "GGNext v0.10.0"
                    ) {
                        Session.openSheet(Sheet.ABOUT)
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.Close,
                        title = "终止此进程",
                        subtitle = ui.target?.let { "终止当前选中的进程 [${it.pid}] ${it.label}" } ?: "当前未附加目标进程"
                    ) {
                        if (ui.target == null) {
                            Session.toast("当前未附加目标进程")
                        } else {
                            confirmKillProcess = true
                        }
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.Close,
                        title = "退出",
                        subtitle = "关闭悬浮窗并完全退出修改器"
                    ) {
                        confirmExitApp = true
                    }
                }
            }
        }

        // 二次确认弹窗：终止此进程 / 退出软件
        val showConfirm = confirmKillProcess || confirmExitApp
        val isKill = confirmKillProcess
        val target = ui.target
        val titleText = if (isKill) "确认终止进程" else "确认退出"
        val bodyText = if (isKill) {
            if (target != null) {
                "确定要强制终止目标进程 [${target.pid}] ${target.label} 吗？进程未保存的数据可能会丢失。"
            } else {
                "当前未选择目标进程。"
            }
        } else {
            "确定要关闭悬浮窗并完全退出 GGNext 吗？"
        }
        val confirmBtnLabel = if (isKill) "确认终止" else "确认退出"

        GgConfirmDialog(
            visible = showConfirm,
            title = titleText,
            body = bodyText,
            confirmLabel = confirmBtnLabel,
            onDismiss = {
                confirmKillProcess = false
                confirmExitApp = false
            },
            onConfirm = {
                val doKill = confirmKillProcess
                confirmKillProcess = false
                confirmExitApp = false
                if (doKill) {
                    Session.terminateTargetProcess()
                } else {
                    Session.exitApp()
                }
            }
        )
    }
}
