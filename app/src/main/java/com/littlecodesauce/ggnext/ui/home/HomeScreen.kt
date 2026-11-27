package com.littlecodesauce.ggnext.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.core.FileBrowserTarget
import com.littlecodesauce.ggnext.core.HomeTab
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.model.BpMode
import com.littlecodesauce.ggnext.core.WorkMode
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.mcp.McpHttpServer
import com.littlecodesauce.ggnext.service.OverlayManager
import com.littlecodesauce.ggnext.ui.components.GgBadge
import com.littlecodesauce.ggnext.ui.components.GgDivider
import com.littlecodesauce.ggnext.ui.components.GgIconActionButton
import com.littlecodesauce.ggnext.ui.components.GgSectionTitle
import com.littlecodesauce.ggnext.ui.components.GgSegmentTab
import com.littlecodesauce.ggnext.ui.components.GgSettingInfoRow
import com.littlecodesauce.ggnext.ui.components.GgSettingNavRow
import com.littlecodesauce.ggnext.ui.components.GgSettingSliderRow
import com.littlecodesauce.ggnext.ui.components.GgSettingSwitchRow
import com.littlecodesauce.ggnext.ui.components.GgSurfaceCard
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.TargetProcessIcon
import com.littlecodesauce.ggnext.ui.components.ggGlassSurface
import com.littlecodesauce.ggnext.ui.components.ggPageBackdrop
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.AppFontCache
import com.littlecodesauce.ggnext.ui.theme.DarkMode
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.ThemePreset
import kotlin.math.roundToInt

private enum class HomeSubPage {
    NONE,
    GENERAL,
    APPEARANCE,
    MCP,
    HELP,
    LOG,
    THANKS,
    ABOUT
}

/**
 * 重构后的软件主页（Material 3 分组卡片 + 独立子页面导航 + 底部三页签：主页 / 扩展 / 设置）
 */
@Composable
fun HomeScreen(
    ui: Ui,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    var subPage by remember { mutableStateOf(HomeSubPage.NONE) }

    BackHandler(enabled = subPage != HomeSubPage.NONE && ui.sheet == Sheet.NONE && !ui.panelVisible) {
        subPage = HomeSubPage.NONE
    }

    BackHandler(enabled = subPage == HomeSubPage.NONE && ui.homeTab != HomeTab.HOME && ui.sheet == Sheet.NONE && !ui.panelVisible) {
        Session.selectHomeTab(HomeTab.HOME)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .ggPageBackdrop()
    ) {
        AnimatedContent(
            targetState = subPage,
            transitionSpec = {
                if (targetState != HomeSubPage.NONE) {
                    (slideInHorizontally(tween(210)) { it / 5 } + fadeIn(tween(180)))
                        .togetherWith(fadeOut(tween(120)))
                } else {
                    fadeIn(tween(170))
                        .togetherWith(slideOutHorizontally(tween(180)) { it / 5 } + fadeOut(tween(130)))
                }
            },
            label = "homeSubPageTransition",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (page) {
                HomeSubPage.NONE -> {
                    AnimatedContent(
                        targetState = ui.homeTab,
                        transitionSpec = {
                            fadeIn(tween(160)).togetherWith(fadeOut(tween(110)))
                        },
                        label = "homeTabContent",
                        modifier = Modifier.fillMaxSize()
                    ) { tab ->
                        when (tab) {
                            HomeTab.HOME -> HomeMainTab(
                                ui = ui,
                                onStartOverlay = onStartOverlay,
                                onStopOverlay = onStopOverlay
                            )
                            HomeTab.EXTENSIONS -> HomeExtensionsTab()
                            HomeTab.DOCS -> HomeDocsTab()
                            HomeTab.SETTINGS -> HomeSettingsTab(
                                ui = ui,
                                onOpenSubPage = { subPage = it }
                            )
                        }
                    }
                }
                HomeSubPage.GENERAL -> HomeGeneralSubPage(
                    ui = ui,
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.APPEARANCE -> HomeAppearanceSubPage(
                    ui = ui,
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.MCP -> HomeMcpSubPage(
                    ui = ui,
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.HELP -> HomeHelpSubPage(
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.LOG -> HomeLogSubPage(
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.THANKS -> HomeThanksSubPage(
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.ABOUT -> HomeAboutSubPage(
                    ui = ui,
                    onBack = { subPage = HomeSubPage.NONE }
                )
            }
        }

        if (subPage == HomeSubPage.NONE) {
            HomeBottomNavBar(
                selectedTab = ui.homeTab,
                onSelect = { Session.selectHomeTab(it) }
            )
        }
    }
}

@Composable
private fun HomeMainTab(
    ui: Ui,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    val ctx = LocalContext.current
    val hasOverlayPerm = remember(ui.floatingBallVisible, ui.panelVisible) {
        OverlayManager.canDraw(ctx)
    }
    val hasAllFilesPerm = remember(ui.floatingBallVisible, ui.panelVisible) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }
    val isRunning = ui.floatingBallVisible

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("top_bar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GGNext",
                    color = Rose.Text,
                    fontSize = fsp(24f),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 融合式主状态卡片（上方运行状态横幅 + 启停控制 + 下方工作模式选择器）
        item("hero_card") {
            HeroStatusCard(
                ui = ui,
                isRunning = isRunning,
                onStatusClick = {
                    if (isRunning) {
                        Session.setPanelVisible(true)
                    } else {
                        onStartOverlay()
                    }
                },
                onToggleService = {
                    if (isRunning) onStopOverlay() else onStartOverlay()
                }
            )
        }

        item("perm_section_label") {
            GgSectionTitle("权限与环境", fontSize = 13.5f, startPadding = GgSpacing.MdPlus)
        }

        item("perm_card") {
            GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                GgSettingNavRow(
                    icon = Icons.Rounded.Security,
                    title = "悬浮窗权限",
                    subtitle = if (hasOverlayPerm) "已允许在其他应用上层显示" else "尚未授予系统悬浮窗权限",
                    iconInBox = false,
                    badgeText = if (hasOverlayPerm) "已授权" else "去授权",
                    badgeColor = if (hasOverlayPerm) Rose.Green else Rose.Yellow,
                    respectBgOpacity = false
                ) {
                    if (hasOverlayPerm) {
                        Session.toast("系统悬浮窗权限已授予")
                    } else {
                        OverlayManager.requestPermission(ctx)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.FolderOpen,
                        title = "所有文件访问权限",
                        subtitle = if (hasAllFilesPerm) "文件浏览器与脚本路径可访问完整外部存储" else "授予后可在文件浏览器中完整查看所有文件",
                        iconInBox = false,
                        badgeText = if (hasAllFilesPerm) "已授权" else "去授权",
                        badgeColor = if (hasAllFilesPerm) Rose.Green else Rose.Yellow,
                        respectBgOpacity = false
                    ) {
                        if (hasAllFilesPerm) {
                            Session.toast("已授予所有文件访问权限")
                        } else {
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
                    }
                }
                if (ui.workMode == WorkMode.ROOT) {
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.Verified,
                        title = "Root 权限检测",
                        subtitle = if (ui.hasRootPermission) "已获取超级用户权限，可直接读写进程内存" else "未检测到 Root 授权，点击重新检测",
                        iconInBox = false,
                        badgeText = if (ui.hasRootPermission) "已授权" else "检测",
                        badgeColor = if (ui.hasRootPermission) Rose.Green else Rose.Red,
                        respectBgOpacity = false
                    ) {
                        Session.refreshRootStatus(promptIfMissing = true)
                    }
                }
            }
        }

        item("asm_engine_section_label") {
            GgSectionTitle("汇编引擎扩展", fontSize = 13.5f, startPadding = GgSpacing.MdPlus)
        }

        item("asm_engine_card") {
            HomeAsmEnginePackCard(ui = ui)
        }

        item("status_section_label") {
            GgSectionTitle("运行状态", fontSize = 13.5f, startPadding = GgSpacing.MdPlus)
        }

        item("status_summary_card") {
            GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                GgSettingNavRow(
                    icon = Icons.Rounded.Memory,
                    title = "当前目标进程",
                    subtitle = ui.target?.let { "[PID ${it.pid}] ${it.label} · ${if (it.x64) "x64" else "x86"}" } ?: tr("未附加，点击选择目标进程"),
                    iconInBox = false,
                    badgeText = if (ui.target != null) "切换" else "选择",
                    badgeColor = if (ui.target != null) Rose.Green else Rose.Primary,
                    leadingContent = {
                        TargetProcessIcon(
                            process = ui.target,
                            size = 32.dp,
                            shape = GgShapes.Sm,
                            showActiveBorder = ui.target != null
                        )
                    }
                ) {
                    Session.openSheet(Sheet.PROCESS)
                }
                GgDivider()
                GgSettingInfoRow(
                    icon = Icons.Rounded.Layers,
                    title = "已启用内存区域",
                    value = "${ui.selectedRegions.size} / ${ui.regions.size}"
                )
            }
        }
    }
}

@Composable
private fun HomeAsmEnginePackCard(ui: Ui) {
    val pack = ui.asmPack
    val hasActivityResultOwner = LocalActivityResultRegistryOwner.current != null
    val statusBadge = when {
        pack.fullyReady -> "已就绪"
        pack.keystoneReady || pack.capstoneReady -> "部分加载"
        else -> "未导入"
    }
    val statusColor = when {
        pack.fullyReady -> Rose.Green
        pack.keystoneReady || pack.capstoneReady -> Rose.Yellow
        else -> Rose.TextDim
    }
    val abiText = pack.abi.ifBlank { com.littlecodesauce.ggnext.engine.AsmEngineManager.primaryAbi() }
    val detailText = when {
        pack.fullyReady -> "${pack.versionInfo.ifBlank { "Keystone + Capstone" }} · ABI: $abiText"
        pack.error.isNotBlank() -> pack.error
        else -> "独立外置压缩包（arm / arm64 / x86 / x86_64），导入后启用完整汇编与反汇编"
    }

    GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Code,
                contentDescription = null,
                tint = if (pack.fullyReady) Rose.Green else Rose.Primary,
                modifier = Modifier.size(GgIconSize.Xl)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr("Keystone / Capstone 引擎包"),
                        color = Rose.Text,
                        fontSize = fsp(15f),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(GgSpacing.Md))
                    GgBadge(
                        text = statusBadge,
                        textColor = statusColor,
                        shape = GgShapes.Xs,
                        fontSize = 10.5f,
                        fontWeight = FontWeight.SemiBold,
                        horizontalPadding = 7.dp,
                        verticalPadding = GgSpacing.Xxs
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tr(detailText),
                    color = if (pack.error.isNotBlank() && !pack.fullyReady) Rose.Red else Rose.TextSecond,
                    fontSize = fsp(12f),
                    lineHeight = fsp(16.5f)
                )
            }
        }

        GgDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GgSpacing.Xl, vertical = GgSpacing.MdPlus),
            horizontalArrangement = Arrangement.spacedBy(GgSpacing.Md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasActivityResultOwner) {
                HomeSafAsmPackPickerButton(modifier = Modifier.weight(1f), installed = pack.installed)
            } else {
                RoseButton(
                    label = if (pack.installed) "重新导入压缩包" else "导入压缩包 (.zip)",
                    filled = true,
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Session.openFileBrowser(
                        initialPath = "/sdcard/Download",
                        selectDirectory = false,
                        returnSheet = Sheet.NONE,
                        target = FileBrowserTarget.ASM_PACK
                    )
                }
            }
            if (pack.installed) {
                RoseButton(
                    label = "移除",
                    modifier = Modifier.width(74.dp).height(36.dp)
                ) {
                    Session.clearAsmPack()
                }
            }
        }
    }
}

@Composable
private fun HomeSafAsmPackPickerButton(
    modifier: Modifier = Modifier,
    installed: Boolean
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Session.importAsmPackFromUri(uri)
        }
    }
    RoseButton(
        label = if (installed) "重新导入压缩包" else "导入压缩包 (.zip)",
        filled = true,
        modifier = modifier.height(36.dp)
    ) {
        runCatching {
            launcher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
        }.onFailure {
            Session.openFileBrowser(
                initialPath = "/sdcard/Download",
                selectDirectory = false,
                returnSheet = Sheet.NONE,
                target = FileBrowserTarget.ASM_PACK
            )
        }
    }
}

@Composable
private fun HeroStatusCard(
    ui: Ui,
    isRunning: Boolean,
    onStatusClick: () -> Unit,
    onToggleService: () -> Unit
) {
    val isDark = Rose.IsDark
    val statusBannerBg by animateColorAsState(
        targetValue = when {
            isRunning -> Rose.HeroRunningBg
            isDark -> Rose.CardHigh
            else -> Rose.ChipSelected.copy(alpha = 0.55f)
        },
        animationSpec = Motion.ColorNormal,
        label = "heroBannerBg"
    )
    val statusAccent = if (isRunning) Rose.HeroRunningAccent else Rose.Primary
    val statusSubColor = if (isRunning) Rose.HeroRunningSub else Rose.TextSecond

    var modeDropdownOpen by remember { mutableStateOf(false) }

    GgSurfaceCard(
        shape = GgShapes.Hero,
        borderColor = if (isRunning) statusAccent.copy(alpha = 0.25f) else Rose.Divider,
        borderWidth = GgBorder.Thin,
        contentPadding = PaddingValues(0.dp)
    ) {
        // 上半区：运行状态横幅
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(statusBannerBg)
                .clickableWithTooltip(onClick = onStatusClick)
                .padding(horizontal = GgSpacing.Xxl, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Rounded.Verified else Icons.Rounded.Layers,
                contentDescription = null,
                tint = statusAccent,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(GgSpacing.Xl))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr(if (isRunning) "悬浮窗运行中" else "悬浮窗未启动"),
                    color = statusAccent,
                    fontSize = fsp(16.5f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tr(
                        if (isRunning) "GGNext v0.10.0 · 点击展开修改器面板"
                        else "GGNext v0.10.0 · 点击启动悬浮球"
                    ),
                    color = statusSubColor,
                    fontSize = fsp(12.5f)
                )
            }
            Spacer(Modifier.width(GgSpacing.MdPlus))
            GgBadge(
                text = if (isRunning) "停止" else "启动",
                textColor = statusAccent,
                backgroundColor = statusAccent.copy(alpha = if (isDark) 0.20f else 0.16f),
                borderColor = statusAccent.copy(alpha = 0.45f),
                shape = GgShapes.Pill,
                fontSize = 12.5f,
                fontWeight = FontWeight.SemiBold,
                horizontalPadding = 13.dp,
                verticalPadding = GgSpacing.Sm,
                onClick = onToggleService
            )
        }

        // 下半区：工作模式行 + 右侧圆角下拉胶囊
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithTooltip {
                    when (ui.workMode) {
                        WorkMode.ROOT -> {
                            if (!ui.hasRootPermission) {
                                Session.refreshRootStatus(promptIfMissing = true)
                            } else {
                                Session.toast("当前运行于 Root 直读模式")
                            }
                        }
                        WorkMode.NO_ROOT -> Session.toast("当前运行于免 Root 共享 UID 模式")
                    }
                }
                .padding(horizontal = GgSpacing.Xxl, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Build,
                contentDescription = null,
                tint = Rose.TextSecond,
                modifier = Modifier.size(GgIconSize.Xl)
            )
            Spacer(Modifier.width(GgSpacing.Xl))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr("工作模式"),
                    color = Rose.Text,
                    fontSize = fsp(15.5f),
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(GgSpacing.Xxs))
                val modeSubtitle = when (ui.workMode) {
                    WorkMode.ROOT -> if (ui.hasRootPermission) "Root · 已授权" else "Root · 未授权，点击检测"
                    WorkMode.NO_ROOT -> "免 Root · 共享 UID"
                }
                Text(
                    text = tr(modeSubtitle),
                    color = if (ui.workMode == WorkMode.ROOT && !ui.hasRootPermission) Rose.Red else Rose.TextSecond,
                    fontSize = fsp(12.5f)
                )
            }

            Spacer(Modifier.width(GgSpacing.MdPlus))

            Box {
                Row(
                    modifier = Modifier
                        .clip(GgShapes.Pill)
                        .background(Rose.Surface.copy(alpha = if (isDark) 0.72f else 0.9f))
                        .border(GgBorder.Thin, Rose.Divider, GgShapes.Pill)
                        .clickableWithTooltip { modeDropdownOpen = true }
                        .padding(start = 14.dp, end = GgSpacing.MdPlus, top = GgSpacing.Md, bottom = GgSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr(if (ui.workMode == WorkMode.ROOT) "Root" else "免 Root"),
                        color = Rose.Text,
                        fontSize = fsp(13.5f),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Rose.TextSecond,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = modeDropdownOpen,
                    onDismissRequest = { modeDropdownOpen = false },
                    modifier = Modifier.background(Rose.CardHigh)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = tr("Root"),
                                color = if (ui.workMode == WorkMode.ROOT) Rose.Primary else Rose.Text,
                                fontSize = fsp(14f),
                                fontWeight = if (ui.workMode == WorkMode.ROOT) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        trailingIcon = if (ui.workMode == WorkMode.ROOT) {
                            { Icon(Icons.Rounded.Check, null, tint = Rose.Primary, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            modeDropdownOpen = false
                            Session.setWorkMode(WorkMode.ROOT)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = tr("免 Root"),
                                color = if (ui.workMode == WorkMode.NO_ROOT) Rose.Primary else Rose.Text,
                                fontSize = fsp(14f),
                                fontWeight = if (ui.workMode == WorkMode.NO_ROOT) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        trailingIcon = if (ui.workMode == WorkMode.NO_ROOT) {
                            { Icon(Icons.Rounded.Check, null, tint = Rose.Primary, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            modeDropdownOpen = false
                            Session.setWorkMode(WorkMode.NO_ROOT)
                        }
                    )
                }
            }
        }
    }
}



@Composable
private fun HomeExtensionsTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = tr("扩展"),
            color = Rose.Text,
            fontSize = fsp(24f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            GgSurfaceCard(
                shape = GgShapes.Hero,
                borderWidth = GgBorder.Thin,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 36.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(GgShapes.Xl)
                            .background(Rose.Primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Extension,
                            contentDescription = tr("扩展"),
                            tint = Rose.Primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = tr("扩展插件"),
                        color = Rose.Text,
                        fontSize = fsp(17f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(GgSpacing.Sm))
                    Text(
                        text = tr("扩展插件系统将在后续版本中提供"),
                        color = Rose.TextSecond,
                        fontSize = fsp(13f)
                    )
                }
            }
        }
    }
}

/**
 * 设置页（清晰分层入口，不再直接堆叠外观与主题控件；所有子功能统一归位至此）
 */
@Composable
private fun HomeSettingsTab(
    ui: Ui,
    onOpenSubPage: (HomeSubPage) -> Unit
) {
    val currentLangCode = ui.settings["language"] ?: "0"
    val fontSummary = ui.customFontName.ifBlank { tr("系统默认") }
    val mcpState by McpHttpServer.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("settings_title") {
            Text(
                text = tr("设置"),
                color = Rose.Text,
                fontSize = fsp(24f),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item("general_section") { GgSectionTitle("常规", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
        item("general_card") {
            GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                GgSettingNavRow(
                    icon = Icons.Rounded.Tune,
                    title = "常规配置",
                    subtitle = "悬浮窗图标、自动暂停、冻结间隔与断点后端",
                    iconInBox = false,
                    respectBgOpacity = false
                ) {
                    onOpenSubPage(HomeSubPage.GENERAL)
                }
                GgDivider()
                GgSettingNavRow(
                    icon = Icons.Rounded.Palette,
                    title = "外观与主题",
                    subtitle = "${tr(ui.preset.label)} · ${tr(ui.darkMode.label)} · $fontSummary",
                    iconInBox = false,
                    respectBgOpacity = false
                ) {
                    onOpenSubPage(HomeSubPage.APPEARANCE)
                }
                GgDivider()
                GgSettingNavRow(
                    icon = Icons.Rounded.Language,
                    title = "界面语言",
                    subtitle = if (currentLangCode == "1" || currentLangCode == "2") "English" else "简体中文",
                    iconInBox = false,
                    badgeText = "切换",
                    badgeColor = Rose.Primary,
                    respectBgOpacity = false
                ) {
                    Session.openSheet(Sheet.LANGUAGE)
                }
                GgDivider()
                GgSettingNavRow(
                    icon = Icons.Rounded.Dns,
                    title = "MCP 服务",
                    subtitle = if (mcpState.running) mcpState.mcpEndpointUrl else "本地 MCP 接口与后台保活配置",
                    iconInBox = false,
                    badgeText = if (mcpState.running) "运行中" else "未开启",
                    badgeColor = if (mcpState.running) Rose.Green else Rose.TextSecond,
                    respectBgOpacity = false
                ) {
                    onOpenSubPage(HomeSubPage.MCP)
                }
            }
        }

        item("support_section") { GgSectionTitle("支持与诊断", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
        item("support_card") {
            GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                GgSettingNavRow(
                    icon = Icons.AutoMirrored.Rounded.HelpOutline,
                    title = "使用帮助",
                    subtitle = "搜索语法、联合搜索与功能说明",
                    iconInBox = false,
                    respectBgOpacity = false
                ) {
                    onOpenSubPage(HomeSubPage.HELP)
                }
                GgDivider()
                GgSettingNavRow(
                    icon = Icons.Rounded.BugReport,
                    title = "运行日志",
                    subtitle = "查看引擎与附加记录",
                    iconInBox = false,
                    respectBgOpacity = false
                ) {
                    onOpenSubPage(HomeSubPage.LOG)
                }
                GgDivider()
                GgSettingNavRow(
                    icon = Icons.Rounded.RestartAlt,
                    title = "恢复默认设置",
                    subtitle = "重置主题、字体、透明度、屏幕占比与语言设置",
                    iconInBox = false,
                    respectBgOpacity = false
                ) {
                    Session.resetAppSettings()
                }
            }
        }

        item("about_section") { GgSectionTitle("关于", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
        item("about_card") {
            GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                GgSettingNavRow(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = "特别鸣谢",
                    subtitle = "致谢启发本项目设计的优秀工具",
                    iconInBox = false,
                    respectBgOpacity = false
                ) {
                    onOpenSubPage(HomeSubPage.THANKS)
                }
                GgDivider()
                GgSettingNavRow(
                    icon = Icons.Rounded.Info,
                    title = "关于 GGNext",
                    subtitle = "版本 v0.10.0、官方群组与开源许可",
                    iconInBox = false,
                    respectBgOpacity = false
                ) {
                    onOpenSubPage(HomeSubPage.ABOUT)
                }
            }
        }
    }
}

// ============================================================
// 独立子页面顶部栏
// ============================================================
@Composable
private fun HomeSubPageTopBar(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = tr("返回"),
                tint = Rose.Text,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = tr(title),
            color = Rose.Text,
            fontSize = fsp(20f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.invoke()
    }
}

// ============================================================
// 子页面 0：常规配置（仅位于软件主页）
// ============================================================
@Composable
private fun HomeGeneralSubPage(
    ui: Ui,
    onBack: () -> Unit
) {
    val m = ui.metrics
    val autoPause = ui.settings["autopause"] == "1"
    val freezeInterval = (ui.settings["freeze_interval"]?.toIntOrNull() ?: 33).coerceIn(5, 500)
    val historyLimit = (ui.settings["history_limit"]?.toIntOrNull() ?: 500).coerceIn(0, 2000)
    val bpMode = Session.breakpointBackendMode(ui)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .ggPageBackdrop()
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(title = "常规配置", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("gen_ball_sec") { GgSectionTitle("悬浮窗与进程联动", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("gen_ball_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingSwitchRow(
                        title = "悬浮窗图标显示当前进程",
                        subtitle = "开启后，悬浮球图标将自动显示当前附加目标进程的应用图标",
                        checked = m.showProcessIconOnBall,
                        onCheckedChange = { next ->
                            Session.setSetting("show_process_icon", if (next) "1" else "0")
                            Session.toast(if (next) "悬浮窗图标将显示当前进程" else "已恢复默认悬浮窗图标")
                        }
                    )
                    GgDivider()
                    GgSettingSwitchRow(
                        title = "打开面板时自动暂停游戏",
                        subtitle = "展开修改器面板时自动发送 SIGSTOP 暂停目标进程，关闭面板后自动恢复",
                        checked = autoPause,
                        onCheckedChange = { next ->
                            Session.setSetting("autopause", if (next) "1" else "0")
                            Session.toast(if (next) "已开启自动暂停目标进程" else "已关闭自动暂停目标进程")
                        }
                    )
                    GgDivider()
                    GgSettingSwitchRow(
                        title = "列表入场与骨架屏动画",
                        subtitle = "关闭可进一步降低低配设备界面渲染开销",
                        checked = m.skeletonLoading,
                        onCheckedChange = { next ->
                            Session.setSetting("skeleton", if (next) "1" else "0")
                        }
                    )
                }
            }

            item("gen_engine_sec") { GgSectionTitle("引擎与调试偏好", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("gen_engine_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingSliderRow(
                        title = "冻结刷新间隔",
                        value = freezeInterval.toFloat(),
                        range = 5f..500f,
                        display = "${freezeInterval}ms"
                    ) { v ->
                        Session.setSetting("freeze_interval", v.roundToInt().coerceIn(5, 500).toString())
                    }
                    GgSettingSliderRow(
                        title = "历史记录条数限制",
                        value = historyLimit.toFloat(),
                        range = 0f..2000f,
                        display = if (historyLimit == 0) tr("关闭") else "$historyLimit"
                    ) { v ->
                        val step = (v.roundToInt() / 50) * 50
                        Session.setSetting("history_limit", step.coerceIn(0, 2000).toString())
                    }
                    GgDivider()
                    Column(Modifier.padding(horizontal = GgSpacing.Xl, vertical = GgSpacing.Lg)) {
                        Text(tr("硬件断点默认后端"), color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(tr("Perf 低侵入无停顿（需 Root），Ptrace 支持完整浮点寄存器与免 Root"), color = Rose.TextDim, fontSize = fsp(11.5f))
                        Spacer(Modifier.height(GgSpacing.Md))
                        Row(horizontalArrangement = Arrangement.spacedBy(GgSpacing.Md)) {
                            BpMode.entries.forEach { mode ->
                                GgSegmentTab(
                                    label = mode.label,
                                    selected = bpMode == mode,
                                    modifier = Modifier.weight(1f),
                                    height = 36.dp,
                                    shape = GgShapes.Md,
                                    accent = Rose.Primary,
                                    fontSize = 12.5f,
                                    horizontalPadding = GgSpacing.Xs
                                ) {
                                    Session.setBreakpointBackendMode(mode)
                                }
                            }
                        }
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.RestartAlt,
                        title = "清除输入历史",
                        subtitle = "清空搜索历史与转到地址历史记录",
                        iconInBox = false,
                        respectBgOpacity = false
                    ) {
                        Session.clearHistory()
                    }
                }
            }
        }
    }
}

// ============================================================
// 子页面 1：外观与主题（含完整多字体管理系统）
// ============================================================
@Composable
private fun HomeAppearanceSubPage(
    ui: Ui,
    onBack: () -> Unit
) {
    val m = ui.metrics
    val ballSize = (ui.settings["icons_size"]?.toIntOrNull() ?: 48).coerceIn(28, 84)
    val bgOpacityPct = (m.bgOpacity * 100f).roundToInt().coerceIn(25, 100)
    val panelScalePct = (m.panelScale * 100f).roundToInt().coerceIn(40, 100)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .ggPageBackdrop()
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(title = "外观与主题", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("theme_section") { GgSectionTitle("色彩与深浅模式", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("theme_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    Column(Modifier.padding(horizontal = GgSpacing.Xl, vertical = GgSpacing.Lg)) {
                        Text(tr("主题配色"), color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(GgSpacing.MdPlus))
                        Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.MdPlus)) {
                            val isDark = Rose.IsDark
                            ThemePreset.entries.chunked(4).forEach { rowPresets ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(GgSpacing.Md)
                                ) {
                                    rowPresets.forEach { p ->
                                        val selected = ui.preset == p
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
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(swatch, CircleShape)
                                                    .then(
                                                        if (selected) Modifier.border(2.dp, Rose.Text, CircleShape)
                                                        else Modifier
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (selected) {
                                                    Icon(Icons.Rounded.Check, null, tint = checkTint, modifier = Modifier.size(GgIconSize.Sm))
                                                }
                                            }
                                            Spacer(Modifier.height(3.dp))
                                            Text(
                                                tr(p.label),
                                                color = if (selected) Rose.Text else Rose.TextSecond,
                                                fontSize = fsp(10.5f),
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    GgDivider()

                    Column(Modifier.padding(horizontal = GgSpacing.Xl, vertical = GgSpacing.Lg)) {
                        Text(tr("深色模式"), color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(GgSpacing.Md))
                        Row(horizontalArrangement = Arrangement.spacedBy(GgSpacing.Md)) {
                            DarkMode.entries.forEach { mode ->
                                GgSegmentTab(
                                    label = mode.label,
                                    selected = ui.darkMode == mode,
                                    modifier = Modifier.weight(1f),
                                    height = 36.dp,
                                    shape = GgShapes.Md,
                                    accent = Rose.Text,
                                    fontSize = 13f,
                                    horizontalPadding = GgSpacing.Xs
                                ) {
                                    Session.setDarkMode(mode)
                                    Session.toast("${tr("深色模式")}：${tr(mode.label)}")
                                }
                            }
                        }
                    }

                    GgDivider()

                    GgSettingSwitchRow(
                        title = "Android 12+ 动态壁纸取色",
                        checked = ui.dynamicColor,
                        onCheckedChange = { next ->
                            Session.setDynamicColor(next)
                            Session.toast(if (next) "已开启壁纸动态取色" else "已关闭壁纸动态取色")
                        }
                    )
                }
            }

            item("font_section") { GgSectionTitle("字体管理", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("font_manager_card") {
                HomeFontManagerCard(ui = ui)
            }

            item("visual_fx_section") { GgSectionTitle("毛玻璃效果", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("visual_fx_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingSwitchRow(
                        title = "毛玻璃效果",
                        subtitle = "为所有主页、工具页、弹窗与悬浮窗开启毛玻璃折射与系统级背景模糊",
                        checked = m.glassEnabled,
                        onCheckedChange = { next ->
                            Session.setSetting("glass_enabled", if (next) "1" else "0")
                            Session.toast(if (next) "已启用全局毛玻璃效果" else "已关闭全局毛玻璃效果")
                        }
                    )
                    if (m.glassEnabled) {
                        GgSettingSliderRow("毛玻璃强度", m.glassBlur.toFloat(), 0f..36f, "${m.glassBlur}dp") {
                            Session.setSetting("glass_blur", it.roundToInt().coerceIn(0, 36).toString())
                        }
                    }

                }
            }

            item("layout_section") { GgSectionTitle("悬浮窗与界面尺寸", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("layout_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingSliderRow("背景透明度", bgOpacityPct.toFloat(), 25f..100f, "$bgOpacityPct%") { pct ->
                        val ratio = (pct.roundToInt().coerceIn(25, 100)) / 100f
                        Session.setSetting("bg_opacity", String.format(java.util.Locale.US, "%.2f", ratio))
                    }
                    GgSettingSliderRow("页面屏幕占比", panelScalePct.toFloat(), 40f..100f, "$panelScalePct%") { pct ->
                        val ratio = (pct.roundToInt().coerceIn(40, 100)) / 100f
                        Session.setSetting("panel_scale", String.format(java.util.Locale.US, "%.2f", ratio))
                    }
                    GgSettingSliderRow("界面字体缩放", m.fontScale, 0.7f..1.3f, "%.2fx".format(m.fontScale)) {
                        Session.setSetting("font_scale", "%.2f".format(it))
                    }
                    GgSettingSliderRow("弹窗圆角半径", m.dialogCorner.toFloat(), 0f..28f, "${m.dialogCorner}dp") {
                        Session.setSetting("dialog_corner", it.toInt().toString())
                    }
                    GgSettingSliderRow("悬浮球尺寸", ballSize.toFloat(), 32f..76f, "${ballSize}dp") {
                        Session.setSetting("icons_size", it.toInt().toString())
                    }
                    GgDivider()
                    GgSettingSwitchRow(
                        title = "列表入场与骨架屏动画",
                        checked = m.skeletonLoading,
                        onCheckedChange = { next ->
                            Session.setSetting("skeleton", if (next) "1" else "0")
                            Session.toast(if (next) "已启用列表动画" else "已关闭列表动画")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeFontManagerCard(ui: Ui) {
    val activePath = ui.customFontPath
    val managedFonts = ui.managedFonts
    val hasActivityResultOwner = LocalActivityResultRegistryOwner.current != null

    GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GgSpacing.Xl, vertical = GgSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.FontDownload,
                contentDescription = null,
                tint = Rose.Primary,
                modifier = Modifier.size(GgIconSize.Xl)
            )
            Spacer(Modifier.width(GgSpacing.Lg))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr("已管理字体"),
                    color = Rose.Text,
                    fontSize = fsp(15f),
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(GgSpacing.Xxs))
                Text(
                    text = tr("导入本地 .ttf / .otf 字体后可随时切换或删除，同步生效于主页与悬浮窗"),
                    color = Rose.TextDim,
                    fontSize = fsp(11.5f)
                )
            }
            Spacer(Modifier.width(GgSpacing.MdPlus))
            if (hasActivityResultOwner) {
                HomeSafFontPickerButton()
            } else {
                RoseButton(
                    label = "导入字体",
                    modifier = Modifier.height(GgControlHeight.Compact),
                    filled = true
                ) {
                    Session.openFileBrowser(
                        initialPath = "/sdcard/Download",
                        selectDirectory = false,
                        returnSheet = Sheet.NONE,
                        target = FileBrowserTarget.FONT
                    )
                }
            }
        }

        GgDivider()

        // 1. 固定内置项：系统默认
        val isSystemSelected = activePath.isBlank()
        FontManagerRow(
            name = tr("系统默认"),
            subtitle = tr("不额外加载外部字体文件"),
            selected = isSystemSelected,
            canDelete = false,
            onSelect = { Session.selectFont("", "") },
            onDelete = {}
        )

        // 2. 用户导入的字体列表
        managedFonts.forEach { font ->
            GgDivider()
            val selected = activePath == font.path
            val sizeText = if (font.sizeBytes > 0L) AppFontCache.formatFontSize(font.sizeBytes) else ""
            val sub = if (sizeText.isNotEmpty()) "${tr("自定义字体")} · $sizeText" else tr("自定义字体")
            FontManagerRow(
                name = font.displayName,
                subtitle = sub,
                selected = selected,
                canDelete = true,
                onSelect = { Session.selectFont(font.path, font.displayName) },
                onDelete = { Session.deleteManagedFont(font.path) }
            )
        }
    }
}

@Composable
private fun FontManagerRow(
    name: String,
    subtitle: String,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = Motion.Fast,
        label = "fontRowScale"
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.74f else 1f,
        animationSpec = Motion.Fast,
        label = "fontRowAlpha"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressScale)
            .graphicsLayer { alpha = pressAlpha }
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) Rose.Primary else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (selected) Rose.Primary else Rose.TextDim,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = if (selected) Rose.Primary else Rose.Text,
                fontSize = fsp(14.5f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = Rose.TextSecond,
                fontSize = fsp(11.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canDelete) {
            Spacer(Modifier.width(GgSpacing.Md))
            GgIconActionButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "删除字体",
                tint = Rose.Red.copy(alpha = 0.85f),
                boxSize = GgControlHeight.Compact,
                iconSize = GgIconSize.Lg,
                shape = CircleShape,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun HomeSafFontPickerButton() {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Session.importCustomFontFromUri(uri)
        }
    }
    Row(
        modifier = Modifier
            .height(GgControlHeight.Compact)
            .clip(GgShapes.Md)
            .background(Rose.Primary)
            .clickable {
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
            .padding(horizontal = GgSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GgSpacing.Xs)
    ) {
        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(GgIconSize.Sm))
        Text(
            text = tr("导入字体"),
            color = Color.White,
            fontSize = fsp(12.5f),
            fontWeight = FontWeight.Medium
        )
    }
}

// ============================================================
// 子页面 2：使用帮助（独立界面）
// ============================================================
@Composable
private fun HomeHelpSubPage(onBack: () -> Unit) {
    val helpContent = remember(AppStrings.currentLang) {
        AppStrings.helpText(AppStrings.currentLang)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .ggPageBackdrop()
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(title = "使用帮助", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            GgSurfaceCard(
                shape = GgShapes.Xxl,
                borderWidth = GgBorder.Thin,
                contentPadding = PaddingValues(18.dp)
            ) {
                Text(
                    text = helpContent,
                    color = Rose.TextSecond,
                    fontSize = fsp(13.5f),
                    lineHeight = fsp(22f)
                )
            }
            Spacer(Modifier.height(GgSpacing.Xxl))
        }
    }
}

// ============================================================
// 子页面 3：运行日志（独立界面）
// ============================================================
@Composable
private fun HomeLogSubPage(onBack: () -> Unit) {
    val clip = LocalClipboardManager.current
    var lines by remember { mutableStateOf(AppLog.snapshot()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .ggPageBackdrop()
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(
            title = "运行日志",
            onBack = onBack,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(GgSpacing.Xs)) {
                    GgIconActionButton(
                        icon = Icons.Rounded.ContentCopy,
                        contentDescription = "复制",
                        tint = Rose.TextSecond,
                        boxSize = GgControlHeight.Chip,
                        iconSize = 19.dp,
                        shape = CircleShape,
                        onClick = {
                            if (lines.isEmpty()) {
                                Session.toast("暂无日志可复制")
                            } else {
                                clip.setText(AnnotatedString(lines.joinToString("\n")))
                                Session.toast("已复制运行日志")
                            }
                        }
                    )
                    GgIconActionButton(
                        icon = Icons.Rounded.DeleteOutline,
                        contentDescription = "清空",
                        tint = Rose.Red.copy(alpha = 0.85f),
                        boxSize = GgControlHeight.Chip,
                        iconSize = GgIconSize.Lg,
                        shape = CircleShape,
                        onClick = {
                            AppLog.clear()
                            lines = emptyList()
                            Session.toast("日志已清空")
                        }
                    )
                }
            }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = GgSpacing.Xl, vertical = GgSpacing.Md)
                .clip(GgShapes.Dialog)
                .background(Rose.Card)
                .border(GgBorder.Thin, Rose.Divider, GgShapes.Dialog)
                .verticalScroll(rememberScrollState())
                .padding(GgSpacing.Xl)
        ) {
            Text(
                text = if (lines.isEmpty()) tr("暂无日志") else lines.joinToString("\n"),
                style = monoStyle(11, Rose.TextSecond),
                lineHeight = fsp(17.5f)
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ============================================================
// 子页面 4：特别鸣谢（独立界面，界面不展示任何 URL，仅点击跳转）
// ============================================================
@Composable
private fun HomeThanksSubPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current

    fun openExternalUrl(url: String) {
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.isSuccess
        if (!opened) {
            clip.setText(AnnotatedString(url))
            Session.toast("无法打开浏览器，已复制链接")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .ggPageBackdrop()
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(title = "特别鸣谢", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("thanks_intro") {
                Text(
                    text = tr("GGNext 的设计与成长离不开以下优秀项目与先驱工具的启发，谨致以诚挚谢意。"),
                    color = Rose.TextSecond,
                    fontSize = fsp(13f),
                    lineHeight = fsp(19.5f),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            item("thanks_gg") {
                ThanksCreditCard(
                    title = "GameGuardian",
                    tag = "经典内存分析工具先驱",
                    description = "致敬 Android 平台经典的内存分析与修改工具 GameGuardian。其开创性的搜索语法、联合搜索范式与内存浏览体系，为本项目奠定了重要参考与灵感。",
                    onClick = { openExternalUrl("https://gameguardian.net") }
                )
            }

            item("thanks_agg") {
                ThanksCreditCard(
                    title = "AGameGuardian",
                    tag = "界面与交互灵感",
                    description = "GGNext 的整体视觉层次、布局排布与多项交互细节，参考并致敬了 AGameGuardian 优秀的界面设计理念。",
                    onClick = { openExternalUrl("https://github.com/AGameGuardian/agameguardian.github.io") }
                )
            }

            item("thanks_shadowhook") {
                ThanksCreditCard(
                    title = "ShadowHook",
                    tag = "Android Inline Hook 框架",
                    description = "感谢字节跳动开源的 Android inline hook 库 ShadowHook，为本项目底层函数拦截能力提供了稳定可靠的实现基础。",
                    onClick = { openExternalUrl("https://github.com/bytedance/android-shadowhook") }
                )
            }

            item("thanks_xdl") {
                ThanksCreditCard(
                    title = "xDL",
                    tag = "增强型 Android DL 库",
                    description = "感谢开源项目 xDL，为本项目在 Android 高版本环境下解析动态库与符号地址提供了高效可靠的底层支持。",
                    onClick = { openExternalUrl("https://github.com/hexhacking/xDL") }
                )
            }

            item("thanks_hint") {
                Text(
                    text = tr("点击卡片即可前往对应项目主页"),
                    color = Rose.TextDim,
                    fontSize = fsp(12f),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ThanksCreditCard(
    title: String,
    tag: String,
    description: String,
    onClick: () -> Unit
) {
    GgSurfaceCard(
        shape = GgShapes.Xxl,
        borderWidth = GgBorder.Thin,
        contentPadding = PaddingValues(18.dp),
        onClick = onClick
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(GgSpacing.Md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(GgShapes.Lg)
                        .background(Rose.Primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = Rose.Primary,
                        modifier = Modifier.size(GgIconSize.Lg)
                    )
                }
                Spacer(Modifier.width(GgSpacing.Lg))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Rose.Text,
                        fontSize = fsp(16f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = tr(tag),
                        color = Rose.Primary,
                        fontSize = fsp(12f),
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Rose.TextDim,
                    modifier = Modifier.size(GgIconSize.Lg)
                )
            }
            Text(
                text = tr(description),
                color = Rose.TextSecond,
                fontSize = fsp(13f),
                lineHeight = fsp(19.5f)
            )
        }
    }
}

// ============================================================
// 子页面 5：关于 GGNext（独立界面，非弹窗，含官方 QQ 群 1131034753）
// ============================================================
@Composable
private fun HomeAboutSubPage(
    ui: Ui,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val projectUrl = "https://github.com/2304974523/GGNext"
    val qqGroupNumber = "1131034753"

    fun openExternalUrl(url: String) {
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.isSuccess
        if (!opened) {
            clip.setText(AnnotatedString(url))
            Session.toast("无法打开浏览器，已复制链接")
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .ggPageBackdrop()
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(title = "关于 GGNext", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("about_header") {
                GgSurfaceCard(
                    shape = GgShapes.Hero,
                    borderWidth = GgBorder.Thin,
                    contentPadding = PaddingValues(GgSpacing.Xxl)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(GgShapes.Xl)
                                .background(Rose.Primary.copy(alpha = 0.16f))
                                .border(GgBorder.Default, Rose.Primary.copy(alpha = 0.35f), GgShapes.Xl),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "G",
                                color = Rose.Primary,
                                fontSize = fsp(26f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(GgSpacing.Lg))
                        Text(
                            text = "GGNext",
                            color = Rose.Text,
                            fontSize = fsp(20f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(GgSpacing.Xs))
                        GgBadge(
                            text = "v0.10.0",
                            textColor = Rose.Primary,
                            backgroundColor = Rose.Primary.copy(alpha = 0.14f),
                            shape = GgShapes.Pill,
                            fontSize = 12f,
                            monospace = true,
                            fontWeight = FontWeight.SemiBold,
                            horizontalPadding = GgSpacing.MdPlus,
                            verticalPadding = 3.dp,
                            translate = false
                        )
                        Spacer(Modifier.height(GgSpacing.MdPlus))
                        Text(
                            text = tr("开源 Android 内存分析与调试工具，采用 Jetpack Compose 与 C++20 原生引擎构建，遵循 GNU General Public License v3.0 协议发布。"),
                            color = Rose.TextSecond,
                            fontSize = fsp(12.5f),
                            lineHeight = fsp(19f)
                        )
                    }
                }
            }

            item("community_label") { GgSectionTitle("社区与项目", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("community_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingNavRow(
                        icon = Icons.Rounded.Groups,
                        title = "官方群组",
                        subtitle = "${tr("QQ 群")}：$qqGroupNumber · ${tr("交流反馈与更新动态")}",
                        iconInBox = false,
                        badgeText = "加入",
                        badgeColor = Rose.Primary,
                        respectBgOpacity = false
                    ) {
                        openOrCopyQqGroup()
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.Code,
                        title = "开源仓库",
                        subtitle = "GGNext GitHub · 查看源码、提交 Issue 与获取更新",
                        iconInBox = false,
                        respectBgOpacity = false
                    ) {
                        openExternalUrl(projectUrl)
                    }
                    GgDivider()
                    GgSettingInfoRow(
                        icon = Icons.Rounded.Info,
                        title = "开源协议",
                        value = "GNU GPL v3.0"
                    )
                }
            }

            item("runtime_label") { GgSectionTitle("当前环境", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("runtime_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingInfoRow(
                        icon = Icons.Rounded.Build,
                        title = "工作模式",
                        value = tr(ui.workMode.title)
                    )
                    GgDivider()
                    GgSettingInfoRow(
                        icon = Icons.Rounded.Memory,
                        title = "当前目标",
                        value = ui.target?.label ?: tr("未选择")
                    )
                    GgDivider()
                    GgSettingInfoRow(
                        icon = Icons.Rounded.FontDownload,
                        title = "当前字体",
                        value = ui.customFontName.ifBlank { tr("系统默认") }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeBottomNavBar(
    selectedTab: HomeTab,
    onSelect: (HomeTab) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val items = listOf(
        Triple(HomeTab.HOME, Icons.Rounded.Home, "主页"),
        Triple(HomeTab.EXTENSIONS, Icons.Rounded.Extension, "扩展"),
        Triple(HomeTab.DOCS, Icons.Rounded.MenuBook, "文档"),
        Triple(HomeTab.SETTINGS, Icons.Rounded.Settings, "设置")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .ggGlassSurface(
                shape = GgShapes.Xxs,
                baseColor = Rose.Surface,
                borderWidth = 0.dp,
                respectBgOpacity = true,
                bgOpacityScale = 0.94f,
                highlightStrength = 0.85f
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(Rose.Divider.copy(alpha = 0.65f))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(68.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (tab, icon, label) ->
                val selected = selectedTab == tab
                var pressed by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(
                    if (pressed) 0.92f else 1f,
                    Motion.Fast,
                    label = "homeNavScale_${tab.name}"
                )
                val pillWidth by animateDpAsState(
                    targetValue = if (selected) 64.dp else 0.dp,
                    animationSpec = tween(220),
                    label = "homeNavPill_${tab.name}"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (selected) primary else Rose.TextSecond,
                    animationSpec = Motion.ColorNormal,
                    label = "homeNavTint_${tab.name}"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .scale(scale)
                        .clickableWithTooltip(
                            tooltip = label,
                            onPressChange = { pressed = it },
                            onClick = { onSelect(tab) }
                        )
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .width(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (pillWidth > 1.dp) {
                            Box(
                                modifier = Modifier
                                    .width(pillWidth)
                                    .height(32.dp)
                                    .clip(GgShapes.Xl)
                                    .background(primary.copy(alpha = if (Rose.IsDark) 0.22f else 0.16f))
                            )
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = tr(label),
                            tint = iconTint,
                            modifier = Modifier.size(GgIconSize.Xl)
                        )
                    }
                    AnimatedVisibility(visible = selected) {
                        Text(
                            text = tr(label),
                            color = Rose.Text,
                            fontSize = fsp(11.5f),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    }
}
