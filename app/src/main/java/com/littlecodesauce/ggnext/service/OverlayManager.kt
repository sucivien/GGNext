package com.littlecodesauce.ggnext.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.littlecodesauce.ggnext.core.Session

/** 悬浮窗运行与挂载状态 */
object OverlayState {
    @Volatile
    var running: Boolean = false

    @Volatile
    var ballAttachedToWindow: Boolean = false

    @Volatile
    var panelAttachedToWindow: Boolean = false
}

object OverlayManager {

    var isRunning: Boolean
        get() = OverlayState.running
        set(value) { OverlayState.running = value }

    fun canDraw(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    fun requestPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || canDraw(context)) return
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * 点击主页“启动”按钮时调用：
     * 1. 立即激活 Session 中的悬浮球显示状态（如果系统悬浮窗已授权则启动前台悬浮窗服务；
     *    如果尚未授权，则同时在应用内展示可拖拽悬浮球并引导开启系统悬浮窗权限）。
     * 2. 点击悬浮球即可随时呼出修改器主面板。
     */
    fun start(context: Context) {
        Session.setFloatingBallVisible(true)
        if (!canDraw(context)) {
            Session.toast("已显示悬浮球（如需在其他应用上层悬浮，请授予悬浮窗权限）")
            requestPermission(context)
            return
        }
        FloatingIconService.start(context)
        isRunning = true
        Session.toast("悬浮球已启动，点击悬浮球打开修改器")
    }

    fun togglePanel(context: Context) {
        if (canDraw(context) && OverlayState.running) {
            FloatingIconService.start(context, FloatingIconService.ACTION_TOGGLE_PANEL)
        } else {
            Session.togglePanelVisible()
        }
    }

    fun stop(context: Context) {
        FloatingIconService.stop(context)
        isRunning = false
        OverlayState.ballAttachedToWindow = false
        OverlayState.panelAttachedToWindow = false
        Session.setFloatingBallVisible(false)
        Session.setPanelVisible(false)
    }
}
