package com.littlecodesauce.ggnext.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.mcp.McpHttpServer
import com.littlecodesauce.ggnext.mcp.McpToolExecutor
import com.littlecodesauce.ggnext.mcp.McpToolSummary
import com.littlecodesauce.ggnext.service.BatteryOptimizationHelper
import com.littlecodesauce.ggnext.service.McpServerService
import com.littlecodesauce.ggnext.ui.components.GgBadge
import com.littlecodesauce.ggnext.ui.components.GgDivider
import com.littlecodesauce.ggnext.ui.components.GgInputBox
import com.littlecodesauce.ggnext.ui.components.GgSectionTitle
import com.littlecodesauce.ggnext.ui.components.GgSettingInfoRow
import com.littlecodesauce.ggnext.ui.components.GgSettingNavRow
import com.littlecodesauce.ggnext.ui.components.GgSettingSwitchRow
import com.littlecodesauce.ggnext.ui.components.GgSurfaceCard
import com.littlecodesauce.ggnext.ui.components.GgTextField
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.expandEnter
import com.littlecodesauce.ggnext.ui.components.expandExit
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.ggPageBackdrop
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

@Composable
internal fun HomeMcpSubPage(
    ui: Ui,
    onBack: () -> Unit
) {
    var showToolListPage by remember { mutableStateOf(false) }

    BackHandler(enabled = showToolListPage) {
        showToolListPage = false
    }

    AnimatedContent(
        targetState = showToolListPage,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(210)) { it / 5 } + fadeIn(tween(180)))
                    .togetherWith(fadeOut(tween(120)))
            } else {
                fadeIn(tween(170))
                    .togetherWith(slideOutHorizontally(tween(180)) { it / 5 } + fadeOut(tween(130)))
            }
        },
        label = "mcpSubPageNav",
        modifier = Modifier.fillMaxSize()
    ) { isToolsPage ->
        if (isToolsPage) {
            HomeMcpToolsListPage(onBack = { showToolListPage = false })
        } else {
            HomeMcpMainConfigPage(
                ui = ui,
                onBack = onBack,
                onOpenToolsList = { showToolListPage = true }
            )
        }
    }
}

@Composable
private fun HomeMcpMainConfigPage(
    ui: Ui,
    onBack: () -> Unit,
    onOpenToolsList: () -> Unit
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mcpState by McpHttpServer.state.collectAsState()

    val savedPort = ui.settings["mcp_port"]?.toIntOrNull()?.coerceIn(1024, 65535) ?: 27080
    val savedAllowLan = ui.settings["mcp_allow_lan"] == "1"
    val savedAuthEnabled = ui.settings["mcp_auth_enabled"] == "1"
    val savedToken = remember(ui.settings["mcp_auth_token"]) {
        ui.settings["mcp_auth_token"].orEmpty().ifBlank {
            val generated = McpHttpServer.generateToken()
            Session.setSetting("mcp_auth_token", generated)
            generated
        }
    }

    var portTf by remember(savedPort) {
        val text = savedPort.toString()
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var batteryIgnored by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(ctx))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun resolvePort(): Int = portTf.text.trim().toIntOrNull()?.coerceIn(1024, 65535) ?: savedPort

    fun restartIfRunning(port: Int, allowLan: Boolean, authEnabled: Boolean, token: String) {
        if (mcpState.running) {
            McpServerService.start(ctx, port, allowLan, authEnabled, token)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface)
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
                    .clickableWithTooltip(tooltip = tr("返回")) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = tr("返回"),
                    tint = Rose.Text,
                    modifier = Modifier.size(GgIconSize.Xl)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = tr("MCP 服务"),
                color = Rose.Text,
                fontSize = fsp(19f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            GgBadge(
                text = tr(if (mcpState.running) "运行中" else "已停止"),
                textColor = if (mcpState.running) Rose.HeroRunningAccent else Rose.TextSecond,
                backgroundColor = if (mcpState.running) Rose.HeroRunningBg else Rose.Chip,
                shape = GgShapes.Pill,
                fontSize = 11.5f,
                horizontalPadding = GgSpacing.Md,
                verticalPadding = GgSpacing.Xs,
                translate = false
            )
            Spacer(Modifier.width(8.dp))
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. 服务开关与访问地址
            item("mcp_ctrl_title") { GgSectionTitle("服务控制", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("mcp_ctrl_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingSwitchRow(
                        title = "启用 MCP 服务",
                        subtitle = if (mcpState.running) {
                            "${mcpState.mcpEndpointUrl} · ${tr("请求")} ${mcpState.totalRequests}"
                        } else {
                            "开启本地 MCP 接口，供外部智能体调用内存与调试能力"
                        },
                        checked = mcpState.running,
                        onCheckedChange = { enable ->
                            if (enable) {
                                val p = resolvePort()
                                Session.setSetting("mcp_port", p.toString())
                                McpServerService.start(ctx, p, savedAllowLan, savedAuthEnabled, savedToken)
                                Session.toast(tr("MCP 服务已启动"))
                            } else {
                                McpServerService.stop(ctx)
                                Session.toast(tr("MCP 服务已停止"))
                            }
                        }
                    )

                    if (mcpState.lastError.isNotBlank()) {
                        GgDivider()
                        Text(
                            text = "${tr("启动失败")}：${mcpState.lastError}",
                            color = Rose.Red,
                            fontSize = fsp(12.5f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }

                    GgDivider()
                    val previewHost = if (savedAllowLan) {
                        if (mcpState.running) mcpState.displayHost else McpHttpServer.resolveLanIpv4()
                    } else {
                        "127.0.0.1"
                    }
                    val previewPort = if (mcpState.running) mcpState.port else resolvePort()
                    val httpUrl = "http://$previewHost:$previewPort/mcp"
                    val sseUrl = "http://$previewHost:$previewPort/sse"

                    GgSettingNavRow(
                        icon = Icons.Rounded.ContentCopy,
                        title = "Streamable HTTP 地址",
                        subtitle = httpUrl,
                        iconInBox = false,
                        badgeText = "复制",
                        badgeColor = Rose.Primary,
                        respectBgOpacity = false
                    ) {
                        clipboard.setText(AnnotatedString(httpUrl))
                        Session.toast("${tr("已复制")} $httpUrl")
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.ContentCopy,
                        title = "SSE 兼容地址",
                        subtitle = sseUrl,
                        iconInBox = false,
                        badgeText = "复制",
                        badgeColor = Rose.Primary,
                        respectBgOpacity = false
                    ) {
                        clipboard.setText(AnnotatedString(sseUrl))
                        Session.toast("${tr("已复制")} $sseUrl")
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.Terminal,
                        title = "复制客户端配置 JSON",
                        subtitle = "一键复制 mcpServers 标准接入配置",
                        iconInBox = false,
                        badgeText = "复制",
                        badgeColor = Rose.Primary,
                        respectBgOpacity = false
                    ) {
                        val json = McpHttpServer.buildClientConfigJson()
                        clipboard.setText(AnnotatedString(json))
                        Session.toast(tr("已复制 MCP 客户端配置"))
                    }
                    GgDivider()
                    GgSettingNavRow(
                        icon = Icons.Rounded.Build,
                        title = "工具列表",
                        subtitle = "查看全部已注册 MCP 工具与参数说明",
                        iconInBox = false,
                        badgeText = McpToolExecutor.toolSummaries.size.toString(),
                        badgeColor = Rose.Primary,
                        respectBgOpacity = false
                    ) {
                        onOpenToolsList()
                    }
                }
            }

            // 2. 后台保活与忽略电池优化权限
            item("mcp_battery_title") { GgSectionTitle("后台保活与权限", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("mcp_battery_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingNavRow(
                        icon = if (batteryIgnored) Icons.Rounded.CheckCircle else Icons.Rounded.BatteryChargingFull,
                        title = "忽略电池优化",
                        subtitle = if (batteryIgnored) {
                            "已允许后台无限制运行，切换应用或锁屏时连接更稳定"
                        } else {
                            "建议授予此权限，防止切后台后 MCP 服务被系统休眠中断"
                        },
                        iconInBox = false,
                        badgeText = if (batteryIgnored) "已开启" else "去申请",
                        badgeColor = if (batteryIgnored) Rose.Green else Rose.Primary,
                        respectBgOpacity = false
                    ) {
                        val launched = BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(ctx)
                        if (!launched) {
                            Session.toast(tr("无法打开系统电池优化页面"))
                        }
                    }
                }
            }

            // 3. 网络与鉴权配置
            item("mcp_net_title") { GgSectionTitle("网络与鉴权", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
            item("mcp_net_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(tr("监听端口 (1024–65535)"), color = Rose.TextSecond, fontSize = fsp(12.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GgInputBox(
                                modifier = Modifier.weight(1f),
                                height = 40.dp,
                                borderColor = Rose.Divider
                            ) {
                                GgTextField(
                                    value = portTf,
                                    onValueChange = { next ->
                                        val digits = next.text.filter { it.isDigit() }.take(5)
                                        portTf = next.copy(
                                            text = digits,
                                            selection = TextRange(
                                                next.selection.start.coerceIn(0, digits.length),
                                                next.selection.end.coerceIn(0, digits.length)
                                            )
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    textStyle = monoStyle(15, Rose.Text),
                                    cursorColor = Rose.Primary,
                                    suppressSystemKeyboard = false,
                                    placeholder = "27080"
                                )
                            }
                            RoseButton(
                                label = "应用",
                                modifier = Modifier.height(40.dp),
                                filled = true
                            ) {
                                val p = resolvePort()
                                portTf = TextFieldValue(p.toString(), TextRange(p.toString().length))
                                Session.setSetting("mcp_port", p.toString())
                                restartIfRunning(p, savedAllowLan, savedAuthEnabled, savedToken)
                                Session.toast("${tr("已保存端口")} $p")
                            }
                        }
                    }

                    GgDivider()
                    GgSettingSwitchRow(
                        title = "允许局域网访问",
                        subtitle = if (savedAllowLan) {
                            "监听 0.0.0.0（同一 Wi-Fi 下电脑可直接连接）"
                        } else {
                            "仅限本机 127.0.0.1 访问（推荐配合 ADB 端口转发）"
                        },
                        checked = savedAllowLan,
                        onCheckedChange = { enabled ->
                            Session.setSetting("mcp_allow_lan", if (enabled) "1" else "0")
                            restartIfRunning(resolvePort(), enabled, savedAuthEnabled, savedToken)
                        }
                    )

                    GgDivider()
                    GgSettingSwitchRow(
                        title = "启用令牌鉴权",
                        subtitle = "开启后请求需携带 Bearer Token，局域网访问建议开启",
                        checked = savedAuthEnabled,
                        onCheckedChange = { enabled ->
                            Session.setSetting("mcp_auth_enabled", if (enabled) "1" else "0")
                            restartIfRunning(resolvePort(), savedAllowLan, enabled, savedToken)
                        }
                    )

                    if (savedAuthEnabled) {
                        GgDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Key,
                                contentDescription = null,
                                tint = Rose.Primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = savedToken,
                                style = monoStyle(12, Rose.Text),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(GgShapes.Xs)
                                    .background(Rose.Chip)
                                    .clickableWithTooltip(tooltip = tr("复制")) {
                                        clipboard.setText(AnnotatedString(savedToken))
                                        Session.toast(tr("已复制访问令牌"))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.ContentCopy, tr("复制"), tint = Rose.TextSecond, modifier = Modifier.size(16.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(GgShapes.Xs)
                                    .background(Rose.Chip)
                                    .clickableWithTooltip(tooltip = tr("重置令牌")) {
                                        val nextTok = McpHttpServer.generateToken()
                                        Session.setSetting("mcp_auth_token", nextTok)
                                        restartIfRunning(resolvePort(), savedAllowLan, savedAuthEnabled, nextTok)
                                        Session.toast(tr("已重置访问令牌"))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Refresh, tr("重置令牌"), tint = Rose.TextSecond, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // 4. 实时调用记录
            if (mcpState.recentLogs.isNotEmpty()) {
                item("mcp_logs_title") { GgSectionTitle("最近调用记录", fontSize = 13.5f, startPadding = GgSpacing.MdPlus) }
                item("mcp_logs_card") {
                    GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                        mcpState.recentLogs.take(10).forEachIndexed { idx, log ->
                            if (idx > 0) GgDivider()
                            val label = if (log.toolName.isNotBlank()) "${log.method} · ${log.toolName}" else log.method
                            GgSettingInfoRow(
                                icon = Icons.Rounded.Terminal,
                                title = label,
                                value = "${if (log.ok) "OK" else "ERR"} · ${log.time} · ${log.durationMs}ms"
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * MCP 工具列表二级页面：简洁圆角单项列表，点击展开英文提示词与参数详情。
 */
@Composable
private fun HomeMcpToolsListPage(
    onBack: () -> Unit
) {
    val tools = remember { McpToolExecutor.toolSummaries }
    var expandedToolName by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface)
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
                    .clickableWithTooltip(tooltip = tr("返回")) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = tr("返回"),
                    tint = Rose.Text,
                    modifier = Modifier.size(GgIconSize.Xl)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = tr("MCP 工具列表"),
                color = Rose.Text,
                fontSize = fsp(19f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            GgBadge(
                text = tools.size.toString(),
                textColor = Rose.Primary,
                backgroundColor = Rose.Primary.copy(alpha = 0.14f),
                shape = GgShapes.Pill,
                fontSize = 11.5f,
                monospace = true,
                horizontalPadding = GgSpacing.Md,
                verticalPadding = GgSpacing.Xs,
                translate = false
            )
            Spacer(Modifier.width(8.dp))
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("tools_hint") {
                Text(
                    text = tr("点击任意工具展开英文提示词与参数详情"),
                    color = Rose.TextSecond,
                    fontSize = fsp(12.5f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            items(tools, key = { it.name }) { tool ->
                val expanded = expandedToolName == tool.name
                McpToolItemCard(
                    tool = tool,
                    expanded = expanded,
                    onToggle = {
                        expandedToolName = if (expanded) null else tool.name
                    }
                )
            }
        }
    }
}

@Composable
private fun McpToolItemCard(
    tool: McpToolSummary,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = Motion.Fast,
        label = "mcpToolScale"
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = Motion.Fast,
        label = "mcpToolAlpha"
    )
    val arrowRot by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.Fast,
        label = "mcpToolArrow"
    )

    GgSurfaceCard(
        modifier = Modifier
            .scale(pressScale)
            .alpha(pressAlpha),
        shape = GgShapes.Lg,
        borderWidth = GgBorder.Thin,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tool.name,
                style = monoStyle(14, Rose.Text, weight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            GgBadge(
                text = tool.category,
                textColor = Rose.TextSecond,
                backgroundColor = Rose.Chip,
                shape = GgShapes.Xs,
                fontSize = 10.5f,
                horizontalPadding = GgSpacing.Sm,
                verticalPadding = 2.dp,
                translate = false
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = Rose.TextSecond,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(arrowRot)
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandEnter(), exit = expandExit()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GgDivider()
                Text(
                    text = tool.description,
                    color = Rose.TextSecond,
                    fontSize = fsp(12.5f),
                    lineHeight = fsp(18f)
                )
                if (tool.params.isEmpty()) {
                    Text(
                        text = tr("无需参数"),
                        color = Rose.TextDim,
                        fontSize = fsp(11.5f)
                    )
                } else {
                    Text(
                        text = tr("参数列表"),
                        color = Rose.Text,
                        fontSize = fsp(12f),
                        fontWeight = FontWeight.SemiBold
                    )
                    tool.params.forEach { p ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = p.name,
                                    style = monoStyle(12, Rose.Primary, weight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = p.type,
                                    style = monoStyle(11, Rose.TextDim)
                                )
                                GgBadge(
                                    text = tr(if (p.required) "必填" else "可选"),
                                    textColor = if (p.required) Rose.Orange else Rose.TextSecond,
                                    backgroundColor = Rose.Chip,
                                    shape = GgShapes.Xs,
                                    fontSize = 10f,
                                    horizontalPadding = 6.dp,
                                    verticalPadding = 1.dp,
                                    translate = false
                                )
                            }
                            if (p.description.isNotBlank()) {
                                Text(
                                    text = p.description,
                                    color = Rose.TextSecond,
                                    fontSize = fsp(11.5f),
                                    lineHeight = fsp(16.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
