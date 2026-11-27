package com.littlecodesauce.ggnext.service

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.littlecodesauce.ggnext.R
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.ui.PanelScreen
import com.littlecodesauce.ggnext.ui.theme.GgNextTheme

/**
 * 在 Service 的 WindowManager 悬浮窗口里承载 Jetpack Compose：
 * 必须同时实现 LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner / OnBackPressedDispatcherOwner，
 * 并且必须将四个 ViewTreeOwner 同时挂载到窗口根视图 (parent) 与 ComposeView 上，
 * 否则 WindowRecomposer 查找 rootView.findViewTreeLifecycleOwner() 会返回 null 并抛异常导致点击悬浮球无界面显示。
 */
class ComposePanel(
    baseContext: Context,
    private val onClose: () -> Unit
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {

    private val themedContext = ContextThemeWrapper(baseContext, R.style.Theme_GGNext)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val backDispatcher = OnBackPressedDispatcher {
        if (!Session.popSheet()) {
            onClose()
        }
    }
    private var view: ComposeView? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher

    fun attach(parent: ViewGroup) {
        runCatching { savedStateController.performRestore(null) }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        // 将四个 ViewTreeOwner 挂载在窗口根容器 parent 上，确保 WindowRecomposer 在 rootView 上能找到 LifecycleOwner
        parent.setViewTreeLifecycleOwner(this@ComposePanel)
        parent.setViewTreeViewModelStoreOwner(this@ComposePanel)
        parent.setViewTreeSavedStateRegistryOwner(this@ComposePanel)
        parent.setViewTreeOnBackPressedDispatcherOwner(this@ComposePanel)

        val cv = ComposeView(themedContext).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewTreeLifecycleOwner(this@ComposePanel)
            setViewTreeViewModelStoreOwner(this@ComposePanel)
            setViewTreeSavedStateRegistryOwner(this@ComposePanel)
            setViewTreeOnBackPressedDispatcherOwner(this@ComposePanel)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides this@ComposePanel) {
                    PanelContent()
                }
            }
        }
        view = cv
        parent.addView(
            cv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    @Composable
    private fun PanelContent() {
        GgNextTheme {
            val ui by Session.ui.collectAsStateWithLifecycle()
            PanelScreen(
                ui = ui,
                onNav = Session::selectTab,
                onMenu = { Session.openSheet(Sheet.TOOLBAR_ORDER) },
                onMinimize = {
                    Session.minimize()
                    onClose()
                },
                compact = true
            )
        }
    }

    fun detach() {
        runCatching {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
            (view?.parent as? ViewGroup)?.removeView(view)
        }
        view = null
    }
}
