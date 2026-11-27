package com.littlecodesauce.ggnext

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.service.OverlayManager
import com.littlecodesauce.ggnext.service.OverlayState
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.littlecodesauce.ggnext.data.AppIconCache
import com.littlecodesauce.ggnext.ui.PanelScreen
import com.littlecodesauce.ggnext.ui.ToastBubble
import com.littlecodesauce.ggnext.ui.components.clickableLongPress
import com.littlecodesauce.ggnext.ui.components.ggGlassSurface
import com.littlecodesauce.ggnext.ui.home.DisclaimerDialog
import com.littlecodesauce.ggnext.ui.home.HomeScreen
import com.littlecodesauce.ggnext.ui.sheet.SheetHost
import com.littlecodesauce.ggnext.ui.theme.DarkMode
import com.littlecodesauce.ggnext.ui.theme.GgNextTheme
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * 主 Activity：
 * - 默认进入软件自身主页（包含启动悬浮球按钮与软件自身设置页：语言、主题、关于等）
 * - 点击悬浮球展开修改器界面 (PanelScreen) 时隐藏悬浮球；最小化收起面板后恢复显示悬浮球
 * - 长按悬浮球可展开原版 GG 风格的左右加减速变速条（`<<` `[G]` `>>` + 倍率显示）
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        Session.init(application)

        if (intent?.getBooleanExtra(EXTRA_PANEL, false) == true && Session.ui.value.disclaimerAccepted) {
            Session.setFloatingBallVisible(true)
            Session.setPanelVisible(true)
        }

        setContent {
            GgNextTheme {
                val ui by Session.ui.collectAsStateWithLifecycle()
                val toast by Session.toastState.collectAsStateWithLifecycle()
                val systemDark = isSystemInDarkTheme()
                val isDark = when (ui.darkMode) {
                    DarkMode.DARK -> true
                    DarkMode.LIGHT -> false
                    DarkMode.FOLLOW -> systemDark
                }

                LaunchedEffect(isDark) {
                    val barStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                    enableEdgeToEdge(
                        statusBarStyle = barStyle,
                        navigationBarStyle = barStyle
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                        window.isStatusBarContrastEnforced = false
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    HomeScreen(
                        ui = ui,
                        onStartOverlay = {
                            if (ui.disclaimerAccepted) {
                                OverlayManager.start(applicationContext)
                            }
                        },
                        onStopOverlay = {
                            OverlayManager.stop(applicationContext)
                        }
                    )

                    val showInActivityPanel = ui.panelVisible && !OverlayState.panelAttachedToWindow && ui.disclaimerAccepted
                    AnimatedVisibility(
                        visible = showInActivityPanel,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.94f),
                        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.96f),
                        modifier = Modifier.fillMaxSize().zIndex(10f)
                    ) {
                        PanelScreen(
                            ui = ui,
                            onNav = Session::selectTab,
                            onMenu = { Session.openSheet(Sheet.TOOLBAR_ORDER) },
                            onMinimize = {
                                Session.minimize()
                            }
                        )
                    }

                    if (!showInActivityPanel) {
                        SheetHost(ui)
                        toast?.let { msg ->
                            ToastBubble(msg) { Session.consumeToast() }
                        }
                    }

                    val showFallbackBall = ui.disclaimerAccepted &&
                        ui.floatingBallVisible &&
                        !ui.panelVisible &&
                        !OverlayState.ballAttachedToWindow &&
                        !OverlayState.running
                    if (showFallbackBall) {
                        InAppFloatingBall(
                            ui = ui,
                            onClick = { Session.setPanelVisible(true) },
                            onLongPress = { Session.toggleSpeedBar() }
                        )
                    }

                    if (ui.settingsLoaded && !ui.disclaimerAccepted) {
                        DisclaimerDialog(
                            onAccept = { Session.acceptDisclaimer() },
                            onDecline = {
                                OverlayManager.stop(applicationContext)
                                finishAndRemoveTask()
                                exitProcess(0)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val st = Session.ui.value
        if (st.disclaimerAccepted && st.floatingBallVisible && OverlayManager.canDraw(applicationContext) && !OverlayState.running) {
            OverlayManager.start(applicationContext)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PANEL, false) && Session.ui.value.disclaimerAccepted) {
            Session.setFloatingBallVisible(true)
            Session.setPanelVisible(true)
        }
    }

    companion object {
        const val EXTRA_PANEL = "open_panel"
    }
}

/**
 * 应用内悬浮球（同时支持短按打开面板、长按呼出原版 GG 变速调节栏 `<< [G] >>`）
 */
@Composable
private fun InAppFloatingBall(
    ui: Ui,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(36f) }
    var offsetY by remember { mutableFloatStateOf(420f) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, Motion.Fast, label = "ballScale")
    val sizeDp = (ui.settings["icons_size"]?.toIntOrNull() ?: 48).coerceIn(32, 80).dp

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(50f)
    ) {
        Column(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .wrapContentSize(unbounded = true)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceAtLeast(0f)
                        offsetY = (offsetY + dragAmount.y).coerceAtLeast(48f)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (ui.speedBarExpanded) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Rose.CardHigh.copy(alpha = 0.96f))
                            .border(1.dp, Rose.Primary.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                            .clickableLongPress(
                                onClick = { Session.stepSpeed(false) },
                                onLongPress = { Session.resetSpeed() }
                            )
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "<<",
                            color = Rose.Text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                val ctx = LocalContext.current
                val targetProc = ui.target
                val targetBmp = remember(targetProc?.packageName, targetProc?.apkPath, targetProc?.uid, ui.metrics.showProcessIconOnBall) {
                    if (targetProc != null && ui.metrics.showProcessIconOnBall) {
                        AppIconCache.get(ctx, targetProc.packageName, targetProc.apkPath, targetProc.uid)
                    } else null
                }
                val ballBg = if (Rose.IsDark) Color(0xEE1E191D) else Color(0xEEFFFFFF)
                val ballTextColor = if (Rose.IsDark) Rose.Primary else Color(0xFF161014)
                Box(
                    Modifier
                        .size(sizeDp)
                        .scale(scale)
                        .ggGlassSurface(
                            shape = CircleShape,
                            baseColor = ballBg,
                            borderColor = Rose.Primary,
                            borderWidth = 2.dp,
                            respectBgOpacity = true,
                            bgOpacityScale = 0.94f,
                            accentColor = Rose.Primary,
                            highlightStrength = 1.25f
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    pressed = true
                                    tryAwaitRelease()
                                    pressed = false
                                },
                                onTap = { onClick() },
                                onLongPress = { onLongPress() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (targetBmp != null) {
                        Image(
                            bitmap = targetBmp,
                            contentDescription = targetProc?.label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.5.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Text(
                            text = "G",
                            color = ballTextColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (ui.speedBarExpanded) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Rose.CardHigh.copy(alpha = 0.96f))
                            .border(1.dp, Rose.Primary.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                            .clickableLongPress(
                                onClick = { Session.stepSpeed(true) },
                                onLongPress = { Session.setSpeedBarExpanded(false) }
                            )
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = ">>",
                            color = Rose.Text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            if (ui.speedBarExpanded || ui.speedMultiplier != 1.0f) {
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Rose.CardHigh.copy(alpha = 0.96f))
                        .border(1.dp, Rose.Primary.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .clickableLongPress(
                            onClick = { Session.resetSpeed() },
                            onLongPress = { Session.setSpeedBarExpanded(false) }
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ui.speedText,
                        color = if (ui.speedMultiplier != 1.0f) Rose.Primary else Rose.Text,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
