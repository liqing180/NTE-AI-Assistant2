package com.nte.auction.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nte.auction.ui.AuctionOverlayContent
import com.nte.auction.ui.AuctionStateStore

/**
 * 真正显示在游戏上方的悬浮窗。
 * 只负责窗口生命周期和位置，业务状态全部来自 AuctionStateStore。
 */
class AuctionOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WindowManager::class.java)
        showOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onDestroy() {
        composeView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        composeView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun showOverlay() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(90)
        }
        params = layoutParams

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AuctionOverlayService)
            setViewTreeSavedStateRegistryOwner(this@AuctionOverlayService)
            setViewTreeViewModelStoreOwner(this@AuctionOverlayService)
            setContent {
                val state by AuctionStateStore.state.collectAsState()
                AuctionOverlayContent(
                    state = state,
                    collapsed = collapsed,
                    onToggleCollapsed = {
                        collapsed = !collapsed
                        // Compose 读取的是 Service 字段，强制重建 Composition 让 WRAP_CONTENT 同步刷新。
                        setContent {
                            val refreshed by AuctionStateStore.state.collectAsState()
                            AuctionOverlayContent(
                                state = refreshed,
                                collapsed = collapsed,
                                onToggleCollapsed = ::toggleCollapsed,
                                onClose = ::stopSelf,
                                onDrag = ::moveBy,
                            )
                        }
                        updateWindowSize()
                    },
                    onClose = ::stopSelf,
                    onDrag = ::moveBy,
                )
            }
        }
        composeView = view
        windowManager.addView(view, layoutParams)
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        composeView?.setContent {
            val state by AuctionStateStore.state.collectAsState()
            AuctionOverlayContent(
                state = state,
                collapsed = collapsed,
                onToggleCollapsed = ::toggleCollapsed,
                onClose = ::stopSelf,
                onDrag = ::moveBy,
            )
        }
        updateWindowSize()
    }

    private fun moveBy(dx: Int, dy: Int) {
        val p = params ?: return
        val view = composeView ?: return
        p.x += dx
        p.y += dy
        windowManager.updateViewLayout(view, p)
    }

    private fun updateWindowSize() {
        val p = params ?: return
        val view = composeView ?: return
        p.width = WindowManager.LayoutParams.WRAP_CONTENT
        p.height = WindowManager.LayoutParams.WRAP_CONTENT
        windowManager.updateViewLayout(view, p)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startService(Intent(context, AuctionOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AuctionOverlayService::class.java))
        }
    }
}
