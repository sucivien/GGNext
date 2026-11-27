package com.littlecodesauce.ggnext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.littlecodesauce.ggnext.MainActivity
import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.data.AppIconCache
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.lua.LuaScriptEngine
import com.littlecodesauce.ggnext.ui.theme.AppFontCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮球 + 覆盖面板前台服务：
 * - 支持目标进程图标实时联动显示（选中进程后悬浮球展示目标进程图标，并覆以毛玻璃高光环）
 * - 支持毛玻璃（Android 12+ 系统级窗口背景模糊 + 表面毛玻璃折射）与动态光照效果实时同步
 */
class FloatingIconService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var ballRoot: LinearLayout? = null
    private var ball: HotIconView? = null
    private var speedDecBtn: TextView? = null
    private var speedIncBtn: TextView? = null
    private var speedLabel: TextView? = null
    private var panelRoot: FrameLayout? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelShown = false
    private var panelCompose: ComposePanel? = null
    private var luaSxView: View? = null
    private var luaSxParams: WindowManager.LayoutParams? = null
    private var luaStopView: View? = null
    private var luaStopParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        Session.init(application)
        OverlayState.running = true
        startForegroundNotification()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildBall()
        observeSessionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OverlayState.running = true
        if (ballRoot == null) {
            buildBall()
        }
        when (intent?.action) {
            ACTION_TOGGLE_PANEL -> togglePanel()
            ACTION_SHOW_PANEL -> showPanel()
            ACTION_HIDE_PANEL -> hidePanel()
        }
        return START_STICKY
    }

    private fun refreshBallWindowLayout() {
        val root = ballRoot ?: return
        val lp = ballParams ?: return
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        root.requestLayout()
        root.invalidate()
        if (OverlayState.ballAttachedToWindow) {
            runCatching { wm.updateViewLayout(root, lp) }
            root.post {
                root.requestLayout()
                root.invalidate()
                runCatching { wm.updateViewLayout(root, lp) }
            }
        }
    }

    private fun applyPanelWindowBlur(
        lp: WindowManager.LayoutParams,
        glassEnabled: Boolean,
        glassBlurDp: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (glassEnabled && glassBlurDp > 0) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = dp(glassBlurDp.coerceIn(1, 36))
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.blurBehindRadius = 0
            }
        }
    }

    private fun observeSessionState() {
        serviceScope.launch {
            Session.ui
                .map { it.panelVisible }
                .distinctUntilChanged()
                .collect { visible ->
                    if (visible && !panelShown) {
                        showPanel()
                    } else if (!visible && panelShown) {
                        hidePanel()
                    }
                    ballRoot?.visibility = if (visible) View.GONE else View.VISIBLE
                    if (!visible) {
                        refreshBallWindowLayout()
                    }
                    syncScriptMiniOverlays()
                }
        }
        serviceScope.launch {
            LuaScriptEngine.state
                .map { Triple(it.running, it.uiButtonVisible, it.scriptName) }
                .distinctUntilChanged()
                .collect {
                    syncScriptMiniOverlays()
                }
        }
        serviceScope.launch {
            Session.ui
                .map { it.settings["icons_size"]?.toIntOrNull() ?: 48 }
                .distinctUntilChanged()
                .collect { sizeDp ->
                    val px = dp(sizeDp.coerceIn(28, 84))
                    val b = ball ?: return@collect
                    b.layoutParams = LinearLayout.LayoutParams(px, px)
                    refreshBallWindowLayout()
                }
        }
        serviceScope.launch {
            Session.ui
                .map {
                    listOf(
                        it.speedBarExpanded,
                        it.speedText,
                        it.preset,
                        it.darkMode,
                        it.dynamicColor,
                        it.speedMultiplier,
                        it.customFontPath,
                        it.target?.pid ?: -1,
                        it.target?.packageName.orEmpty(),
                        it.target?.apkPath.orEmpty(),
                        it.metrics.glassEnabled,
                        it.metrics.glassBlur,
                        it.metrics.bgOpacity,
                        it.metrics.showProcessIconOnBall
                    )
                }
                .distinctUntilChanged()
                .collect {
                    val cur = Session.ui.value
                    val expanded = cur.speedBarExpanded
                    val speedTxt = cur.speedText
                    val preset = cur.preset
                    val darkMode = cur.darkMode
                    val metrics = cur.metrics
                    val customTf = AppFontCache.typefaceFor(cur.customFontPath)
                    val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val isDark = when (darkMode) {
                        com.littlecodesauce.ggnext.ui.theme.DarkMode.LIGHT -> false
                        com.littlecodesauce.ggnext.ui.theme.DarkMode.DARK -> true
                        com.littlecodesauce.ggnext.ui.theme.DarkMode.FOLLOW -> isNight
                    }
                    val accentColorInt = if (cur.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            val resId = if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
                            getColor(resId)
                        }.getOrElse {
                            val c = preset.seedFor(isDark)
                            (c.value shr 32).toInt()
                        }
                    } else {
                        val c = preset.seedFor(isDark)
                        (c.value shr 32).toInt()
                    }

                    val targetProc = cur.target
                    val targetBmp = if (targetProc != null && metrics.showProcessIconOnBall) {
                        AppIconCache.getBitmap(
                            this@FloatingIconService,
                            targetProc.packageName,
                            targetProc.apkPath,
                            targetProc.uid
                        )
                    } else null

                    ball?.setThemeAccent(accentColorInt, isDark)
                    ball?.setCustomTypeface(customTf)
                    ball?.setTargetIcon(targetBmp)
                    ball?.setVisualEffects(
                        glassEnabled = metrics.glassEnabled,
                        glassBlur = metrics.glassBlur,
                        bgOpacity = metrics.bgOpacity
                    )

                    val panelLp = panelParams
                    val pRoot = panelRoot
                    if (panelShown && panelLp != null && pRoot != null && OverlayState.panelAttachedToWindow) {
                        applyPanelWindowBlur(panelLp, metrics.glassEnabled, metrics.glassBlur)
                        runCatching { wm.updateViewLayout(pRoot, panelLp) }
                    }

                    val glassAlpha = if (metrics.glassEnabled) {
                        ((metrics.bgOpacity.coerceIn(0.25f, 1.0f) * 0.88f) * 255).toInt().coerceIn(85, 242)
                    } else {
                        ((metrics.bgOpacity.coerceIn(0.25f, 1.0f) * 0.96f) * 255).toInt().coerceIn(110, 250)
                    }
                    val baseBtnRgb = if (isDark) 0x221C20 else 0xFFFFFF
                    val btnBgColor = (glassAlpha shl 24) or baseBtnRgb
                    val borderAlpha = if (isDark) 0x88 else 0xBB
                    val btnBorderColor = (accentColorInt and 0x00FFFFFF) or (borderAlpha shl 24)
                    val btnTextColor = if (isDark) Color.parseColor("#F2E8EC") else Color.parseColor("#121217")

                    val boldTf = customTf?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
                    val monoTf = customTf ?: Typeface.MONOSPACE

                    speedDecBtn?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(btnBgColor)
                        setStroke(dp(1), btnBorderColor)
                    }
                    speedDecBtn?.setTextColor(btnTextColor)
                    speedDecBtn?.typeface = boldTf

                    speedIncBtn?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(btnBgColor)
                        setStroke(dp(1), btnBorderColor)
                    }
                    speedIncBtn?.setTextColor(btnTextColor)
                    speedIncBtn?.typeface = boldTf

                    speedLabel?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(btnBgColor)
                        setStroke(dp(1), btnBorderColor)
                    }
                    speedLabel?.setTextColor(btnTextColor)
                    speedLabel?.typeface = monoTf

                    speedDecBtn?.visibility = if (expanded) View.VISIBLE else View.GONE
                    speedIncBtn?.visibility = if (expanded) View.VISIBLE else View.GONE
                    val showBadge = expanded || cur.speedMultiplier != 1.0f
                    speedLabel?.visibility = if (showBadge) View.VISIBLE else View.GONE
                    speedLabel?.text = speedTxt
                    refreshBallWindowLayout()
                }
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "GGNext 悬浮窗", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val n: Notification = builder
            .setContentTitle("GGNext 正在运行")
            .setContentText("短按悬浮球打开修改器，长按悬浮球调节变速")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        runCatching { startForeground(NOTIFY_ID, n) }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun makeStepButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            isSingleLine = true
            includeFontPadding = false
            minWidth = dp(40)
            minimumHeight = dp(32)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#EE221C20"))
                setStroke(dp(1), Color.parseColor("#88E7A8B8"))
            }
            visibility = View.GONE
            setOnClickListener { onClick() }
        }
    }

    private fun buildBall() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val decBtn = makeStepButton("<<") { Session.stepSpeed(false) }.apply {
            setOnLongClickListener {
                Session.resetSpeed()
                true
            }
        }
        val incBtn = makeStepButton(">>") { Session.stepSpeed(true) }.apply {
            setOnLongClickListener {
                Session.setSpeedBarExpanded(false)
                true
            }
        }

        val iconView = HotIconView(this)
        val sizeDp = Session.setting("icons_size").toIntOrNull()?.coerceIn(28, 84) ?: 48

        topRow.addView(
            decBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(5) }
        )
        topRow.addView(iconView, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        topRow.addView(
            incBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(5) }
        )

        val badge = TextView(this).apply {
            text = "1.0x"
            isSingleLine = true
            includeFontPadding = false
            minWidth = dp(44)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#EE221C20"))
                setStroke(dp(1), Color.parseColor("#88E7A8B8"))
            }
            visibility = View.GONE
            setOnClickListener {
                Session.resetSpeed()
            }
            setOnLongClickListener {
                Session.setSpeedBarExpanded(false)
                true
            }
        }

        container.addView(
            topRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            badge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        )

        var longPressTriggered = false
        val longPressRunnable = Runnable {
            if (!dragging) {
                longPressTriggered = true
                iconView.isPressedState = false
                iconView.invalidate()
                Session.toggleSpeedBar()
                refreshBallWindowLayout()
            }
        }

        val touchListener = View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = ballParams?.x ?: 16
                    startY = ballParams?.y ?: 320
                    dragging = false
                    longPressTriggered = false
                    iconView.isPressedState = true
                    iconView.invalidate()
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, 420L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        ballParams?.let { lp ->
                            lp.x = (startX + dx.toInt()).coerceAtLeast(0)
                            lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                            runCatching { wm.updateViewLayout(container, lp) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    iconView.isPressedState = false
                    iconView.invalidate()
                    if (!dragging && !longPressTriggered) {
                        v.performClick()
                        showPanel()
                    }
                    dragging = false
                    longPressTriggered = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    iconView.isPressedState = false
                    iconView.invalidate()
                    dragging = false
                    longPressTriggered = false
                    true
                }
                else -> false
            }
        }
        iconView.setOnTouchListener(touchListener)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }
        ballRoot = container
        ball = iconView
        speedDecBtn = decBtn
        speedIncBtn = incBtn
        speedLabel = badge
        ballParams = lp
        val added = runCatching { wm.addView(container, lp) }
            .onFailure { AppLog.add("overlay", "ball addView failed: ${it.message}") }
            .isSuccess
        OverlayState.ballAttachedToWindow = added
        if (Session.ui.value.panelVisible) {
            container.visibility = View.GONE
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    private fun togglePanel() {
        if (panelShown) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        if (panelShown) {
            ballRoot?.visibility = View.GONE
            return
        }
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (!Session.popSheet()) {
                        hidePanel()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val compose = ComposePanel(this) { hidePanel() }
        compose.attach(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val requiredTop = maxOf(sysBars.top, cutout.top)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val windowTopY = loc[1].coerceAtLeast(0)
            val extraTop = (requiredTop - windowTopY).coerceAtLeast(0)
            if (v.paddingTop != extraTop) {
                v.setPadding(0, extraTop, 0, 0)
            }
            insets
        }

        val curMetrics = Session.ui.value.metrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            dimAmount = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.displayCutout()
            }
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            applyPanelWindowBlur(this, curMetrics.glassEnabled, curMetrics.glassBlur)
        }

        val added = runCatching { wm.addView(root, lp) }
            .onFailure { e ->
                AppLog.add("overlay", "panel addView error: ${e.javaClass.simpleName}: ${e.message}")
                compose.detach()
            }
            .isSuccess

        ballRoot?.visibility = View.GONE

        if (added) {
            panelRoot = root
            panelCompose = compose
            panelParams = lp
            panelShown = true
            OverlayState.panelAttachedToWindow = true
            Session.setPanelVisible(true)
            syncScriptMiniOverlays()
            root.post { ViewCompat.requestApplyInsets(root) }
        } else {
            OverlayState.panelAttachedToWindow = false
            Session.setPanelVisible(true)
            syncScriptMiniOverlays()
        }
    }

    private fun hidePanel() {
        ballRoot?.visibility = View.VISIBLE
        refreshBallWindowLayout()
        if (!panelShown) {
            Session.setPanelVisible(false)
            syncScriptMiniOverlays()
            return
        }
        panelShown = false
        OverlayState.panelAttachedToWindow = false
        Session.setPanelVisible(false)
        syncScriptMiniOverlays()
        ball?.showProgress = false
        ball?.invalidate()
        runCatching {
            panelCompose?.detach()
            panelRoot?.let { wm.removeView(it) }
        }
        panelCompose = null
        panelRoot = null
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        OverlayState.running = false
        OverlayState.ballAttachedToWindow = false
        OverlayState.panelAttachedToWindow = false
        runCatching {
            hidePanel()
            removeScriptMiniOverlays()
            ballRoot?.let { wm.removeView(it) }
        }
        ballRoot = null
    }

    private fun syncScriptMiniOverlays() {
        val luaSt = LuaScriptEngine.state.value
        val showSx = !panelShown && luaSt.uiButtonVisible
        val showStop = !panelShown && luaSt.running
        if (showSx) {
            ensureLuaSxOverlay()
            luaSxView?.visibility = View.VISIBLE
        } else {
            luaSxView?.visibility = View.GONE
        }
        if (showStop) {
            ensureLuaStopOverlay()
            luaStopView?.visibility = View.VISIBLE
        } else {
            luaStopView?.visibility = View.GONE
        }
    }

    private fun removeScriptMiniOverlays() {
        luaSxView?.let { runCatching { wm.removeView(it) } }
        luaSxView = null
        luaSxParams = null
        luaStopView?.let { runCatching { wm.removeView(it) } }
        luaStopView = null
        luaStopParams = null
    }

    private fun attachDraggableMiniTouch(
        view: View,
        lp: WindowManager.LayoutParams,
        sizePx: Int,
        onClick: () -> Unit,
        onLongPress: () -> Unit
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.coerceAtLeast(dp(6)).toFloat()
        var dX = 0f
        var dY = 0f
        var sX = 0
        var sY = 0
        var isDragging = false
        var longPressed = false
        val longRunnable = Runnable {
            if (!isDragging) {
                longPressed = true
                onLongPress()
            }
        }
        view.setOnTouchListener { v, e ->
            val dm = resources.displayMetrics
            val maxX = (dm.widthPixels - sizePx - dp(6)).coerceAtLeast(dp(6))
            val maxY = (dm.heightPixels - sizePx - dp(6)).coerceAtLeast(dp(6))
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = e.rawX
                    dY = e.rawY
                    sX = lp.x
                    sY = lp.y
                    isDragging = false
                    longPressed = false
                    mainHandler.removeCallbacks(longRunnable)
                    mainHandler.postDelayed(longRunnable, 460L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dX
                    val dy = e.rawY - dY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        mainHandler.removeCallbacks(longRunnable)
                    }
                    if (isDragging) {
                        lp.x = (sX + dx.toInt()).coerceIn(dp(6), maxX)
                        lp.y = (sY + dy.toInt()).coerceIn(dp(6), maxY)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longRunnable)
                    if (!isDragging && !longPressed) {
                        v.performClick()
                        onClick()
                    }
                    isDragging = false
                    longPressed = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longRunnable)
                    isDragging = false
                    longPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun ensureLuaSxOverlay() {
        if (luaSxView != null) return
        val sizePx = dp(32)
        val dm = resources.displayMetrics
        val v = TextView(this).apply {
            text = "Sx"
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E7A8B8"))
            contentDescription = AppStrings.tr("脚本按钮 Sx")
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#E8261C22"))
                setStroke(dp(1), Color.parseColor("#CCE7A8B8"))
            }
        }
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - sizePx * 2 - dp(20)).coerceAtLeast(dp(8))
            y = dp(76)
        }
        attachDraggableMiniTouch(
            view = v,
            lp = lp,
            sizePx = sizePx,
            onClick = { LuaScriptEngine.onUiButtonClicked() },
            onLongPress = { Session.toast("脚本按钮 Sx") }
        )
        if (runCatching { wm.addView(v, lp) }.isSuccess) {
            luaSxView = v
            luaSxParams = lp
        }
    }

    private fun ensureLuaStopOverlay() {
        if (luaStopView != null) return
        val sizePx = dp(32)
        val dm = resources.displayMetrics
        val v = object : View(this) {
            private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#EF5350")
                style = Paint.Style.FILL
            }
            private val rect = RectF()
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val inset = width * 0.30f
                val r = width * 0.08f
                rect.set(inset, inset, width - inset, height - inset)
                canvas.drawRoundRect(rect, r, r, iconPaint)
            }
        }.apply {
            contentDescription = AppStrings.tr("停止脚本")
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#E8261C22"))
                setStroke(dp(1), Color.parseColor("#B8EF5350"))
            }
        }
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - sizePx - dp(10)).coerceAtLeast(dp(8))
            y = dp(76)
        }
        attachDraggableMiniTouch(
            view = v,
            lp = lp,
            sizePx = sizePx,
            onClick = { LuaScriptEngine.stopScript() },
            onLongPress = { Session.toast("停止脚本") }
        )
        if (runCatching { wm.addView(v, lp) }.isSuccess) {
            luaStopView = v
            luaStopParams = lp
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class HotIconView(context: Context) : View(context) {
        private var accentColorInt: Int = Color.parseColor("#E7A8B8")
        private var isDarkTheme: Boolean = true
        private var targetBitmap: Bitmap? = null
        private var glassEnabled: Boolean = true
        private var glassBlur: Int = 16
        private var bgOpacity: Float = 0.96f

        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val innerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.8f
            color = Color.parseColor("#99E7A8B8")
        }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EE1F191D")
        }
        private val bgPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5382730")
        }
        private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clipPath = Path()
        private val dstRect = RectF()
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F2E8EC")
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        var showProgress = false
        var isPressedState = false

        fun setCustomTypeface(tf: Typeface?) {
            fg.typeface = tf?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
            invalidate()
        }

        fun setTargetIcon(bmp: Bitmap?) {
            if (targetBitmap !== bmp) {
                targetBitmap = bmp
                invalidate()
            }
        }

        fun setVisualEffects(
            glassEnabled: Boolean,
            glassBlur: Int,
            bgOpacity: Float
        ) {
            this.glassEnabled = glassEnabled
            this.glassBlur = glassBlur.coerceIn(0, 36)
            this.bgOpacity = bgOpacity.coerceIn(0.25f, 1.0f)
            updateThemePaints()
            invalidate()
        }

        fun setThemeAccent(accentColor: Int, isDark: Boolean = true) {
            this.accentColorInt = accentColor
            this.isDarkTheme = isDark
            updateThemePaints()
            invalidate()
        }

        private fun updateThemePaints() {
            val glassRatio = if (glassEnabled) (glassBlur / 36f) else 0f
            val baseAlpha = ((bgOpacity * (1f - glassRatio * 0.16f)).coerceIn(0.30f, 0.96f) * 255).toInt()
            val rgb = accentColorInt and 0x00FFFFFF
            innerBorder.color = rgb or (if (isDarkTheme) (0xB8 shl 24) else (0xD8 shl 24))
            if (isDarkTheme) {
                bg.color = (baseAlpha shl 24) or 0x1F191D
                bgPressed.color = (minOf(255, baseAlpha + 18) shl 24) or 0x382730
                fg.color = Color.parseColor("#F2E8EC")
            } else {
                bg.color = (baseAlpha shl 24) or 0xFFFFFF
                bgPressed.color = (minOf(255, baseAlpha + 15) shl 24) or 0xE6E9F0
                fg.color = Color.parseColor("#1A1216")
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val maxR = minOf(width, height) / 2f
            val r = (maxR - 4f).coerceAtLeast(8f)
            val rgb = accentColorInt and 0x00FFFFFF

            // 2. 毛玻璃球体底色
            canvas.drawCircle(cx, cy, r, if (isPressedState) bgPressed else bg)

            if (glassEnabled) {
                val glassRatio = (glassBlur / 36f).coerceIn(0.15f, 1.0f)
                val sheenTop = if (isDarkTheme) {
                    ((0.20f * glassRatio * 255).toInt() shl 24) or 0xFFFFFF
                } else {
                    ((0.55f * glassRatio * 255).toInt() shl 24) or 0xFFFFFF
                }
                val sheenMid = ((0.10f * glassRatio * 255).toInt() shl 24) or rgb
                frostPaint.shader = LinearGradient(
                    cx - r,
                    cy - r,
                    cx + r,
                    cy + r,
                    intArrayOf(sheenTop, sheenMid, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r, frostPaint)
            }

            // 3. 目标进程图标或默认 G 字样
            val bmp = targetBitmap
            if (bmp != null && !bmp.isRecycled) {
                val iconR = (r - 2.6f).coerceAtLeast(4f)
                dstRect.set(cx - iconR, cy - iconR, cx + iconR, cy + iconR)
                val saveCount = canvas.save()
                clipPath.reset()
                clipPath.addCircle(cx, cy, iconR, Path.Direction.CW)
                canvas.clipPath(clipPath)
                canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
                canvas.restoreToCount(saveCount)
            } else {
                fg.textSize = r * 0.92f
                val baseline = cy - (fg.descent() + fg.ascent()) / 2f
                canvas.drawText("G", cx, baseline, fg)
            }

            innerBorder.shader = null
            canvas.drawCircle(cx, cy, r, innerBorder)
        }
    }

    companion object {
        private const val CHANNEL = "ggnext_overlay"
        private const val NOTIFY_ID = 96
        const val ACTION_TOGGLE_PANEL = "com.littlecodesauce.ggnext.ACTION_TOGGLE_PANEL"
        const val ACTION_SHOW_PANEL = "com.littlecodesauce.ggnext.ACTION_SHOW_PANEL"
        const val ACTION_HIDE_PANEL = "com.littlecodesauce.ggnext.ACTION_HIDE_PANEL"

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, FloatingIconService::class.java).apply {
                if (action != null) this.action = action
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(i)
                else
                    context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FloatingIconService::class.java))
            }
        }
    }
}
