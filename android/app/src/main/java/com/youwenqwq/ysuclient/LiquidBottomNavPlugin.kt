package com.youwenqwq.ysuclient

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.PixelCopy
import android.view.MotionEvent
import android.view.WindowManager
import android.view.Window
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import me.weishu.kernelsu.ui.component.FloatingBottomBar
import me.weishu.kernelsu.ui.component.FloatingBottomBarItem
import me.weishu.kernelsu.ui.theme.LocalHostDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Host adapter only. Rendering, shaders and gestures are the unmodified KernelSU files. */
@CapacitorPlugin(name = "LiquidBottomNav")
class LiquidBottomNavPlugin : Plugin() {
    private companion object {
        const val NAV_SCALE = 0.9f
    }

    private data class NavState(
        val selected: Int = 0,
        val labels: List<String> = listOf("总览", "日程", "成绩", "我的"),
        val dark: Boolean = false,
        val primary: Color = Color(0xFF222222),
        val foreground: Color = Color(0xFF111111),
        val surface: Color = Color(0xFFEEEEEE),
    )

    private var state by mutableStateOf(NavState())
    private var renderVisible = false
    private var backdropImage by mutableStateOf<ImageBitmap?>(null)
    private var overlay: ComposeView? = null
    private var panel: NavigationPanel? = null
    private var wanted = false
    private var obscured = false
    private var paused = false
    private var copying = false
    private var copyPending = false
    private var presentPending = false
    private var presentationCommit: Runnable? = null
    private var presentationGeneration = 0
    private var ready = false
    private var appearance = 0
    private var capturedAppearance = -1
    private var currentBitmap: Bitmap? = null
    private val spareBitmaps = ArrayDeque<Bitmap>()
    private val retiredBitmaps = ArrayDeque<Bitmap>()
    private var destroyed = false
    private var observingFrames = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastHeight = 0f
    private val density get() = context.resources.displayMetrics.density
    private val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private inner class NavigationPanel : FrameLayout(activity) {
        val barBounds = RectF()
        private var forwarding = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                forwarding = !barBounds.contains(event.x, event.y)
            }
            if (!forwarding) return super.dispatchTouchEvent(event)
            // Transparent source/shadow padding is not an input blocker. Keep an entire
            // gesture with the page if it began outside the actual KSU bar.
            val position = IntArray(2).also(::getLocationOnScreen)
            val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
            val forwarded = MotionEvent.obtain(event)
            try {
                forwarded.offsetLocation((position[0] - origin[0]).toFloat(),
                    (position[1] - origin[1]).toFloat())
                activity.dispatchTouchEvent(forwarded)
            } finally {
                forwarded.recycle()
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) forwarding = false
            }
            return true
        }
    }

    private val afterLayout = ViewTreeObserver.OnGlobalLayoutListener {
        updateSize()
        updateVisibility()
    }

    // Insets are a view lifecycle concern. WebView's compositor can submit frames
    // without a Java view traversal, so capture is driven by actual window frames.
    private val pageDraw = ViewTreeObserver.OnPreDrawListener {
        updateVisibility()
        true
    }

    // The activity window excludes the glass panel. Its render-thread frames include
    // Chromium-only updates, without creating a second page invalidation/RAF loop.
    private val pageFrame = Window.OnFrameMetricsAvailableListener { _, _, _ ->
        requestBackdrop()
    }

    @PluginMethod
    fun available(call: PluginCall) {
        call.resolve(JSObject().put("supported", supported))
    }

    @PluginMethod
    fun configure(call: PluginCall) {
        if (!supported) {
            call.resolve(JSObject().put("visible", false).put("height", 0))
            return
        }
        activity.runOnUiThread {
            wanted = call.getBoolean("visible", false) == true
            if (!wanted) {
                updateVisibility()
                call.resolve(JSObject().put("visible", false).put("height", 0))
                return@runOnUiThread
            }
            try {
                val labels = call.getArray("labels")
                val colors = call.getObject("colors")
                fun color(key: String, fallback: Color): Color = try {
                    Color(android.graphics.Color.parseColor(colors?.getString(key)))
                } catch (_: Exception) { fallback }
                state = NavState(
                    selected = (call.getInt("selectedIndex", 0) ?: 0).coerceIn(0, 3),
                    labels = List(4) { index -> labels?.optString(index) ?: state.labels[index] },
                    dark = call.getBoolean("dark", false) == true,
                    primary = color("primary", state.primary),
                    foreground = color("foreground", state.foreground),
                    surface = color("surface", state.surface),
                )
                ensureOverlay()
                updateSize()
                updateVisibility()
                call.resolve(JSObject().put("visible", true).put("height", lastHeight))
            } catch (error: Exception) {
                wanted = false
                updateVisibility()
                call.reject("Could not attach the native navigation bar", error)
            }
        }
    }

    @PluginMethod
    fun hide(call: PluginCall) {
        activity.runOnUiThread {
            wanted = false
            updateVisibility()
            call.resolve()
        }
    }

    @PluginMethod
    fun setObscured(call: PluginCall) {
        activity.runOnUiThread {
            obscured = call.getBoolean("obscured", false) == true
            updateVisibility()
            call.resolve()
        }
    }

    // Older cached web bundles may still send this event on scroll. It must not
    // force a second page draw/copy; committed activity frames are the only source.
    @PluginMethod
    fun refreshBackdrop(call: PluginCall) {
        call.resolve()
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = ComposeView(activity).apply {
            // This overlay must never consume insets belonging to the WebView sibling.
            consumeWindowInsets = false
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { NavigationContent() }
            // The pressed pill and KSU's 40 dp backdrop padding may extend above the bar.
            clipChildren = false
            clipToPadding = false
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
        }
        overlay = view
        val host = NavigationPanel().apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            clipChildren = false
            clipToPadding = false
            addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        panel = host
        // A sibling inside the activity's view tree shares layout/insets and the captured
        // surface. Use a non-focusable panel: PixelCopy reads only the activity surface,
        // never the glass, and the panel cannot resize the WebView or take its focus.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            token = activity.window.decorView.windowToken
            setFitInsetsTypes(0)
            title = "YSU Liquid Navigation"
            windowAnimations = 0
            alpha = 0f
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        view.visibility = View.INVISIBLE
        host.visibility = View.INVISIBLE
        activity.windowManager.addView(host, params)
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateSize()
            requestBackdrop()
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(afterLayout)
        root.viewTreeObserver.addOnPreDrawListener(pageDraw)
        activity.window.addOnFrameMetricsAvailableListener(pageFrame, mainHandler)
        observingFrames = true
    }

    private fun updateSize() {
        val view = panel ?: return
        val bottomPx = ViewCompat.getRootWindowInsets(bridge.webView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        // Scale the KSU metrics together; the physical system safe area stays intact.
        val gap = if (bottomPx != 0) bottomPx / density + 8f * NAV_SCALE else 28f * NAV_SCALE
        val height = 64f * NAV_SCALE + gap
        val pixels = ((height + 40f * NAV_SCALE) * density).toInt()
        if (view.layoutParams.height != pixels) {
            val params = view.layoutParams as WindowManager.LayoutParams
            params.height = pixels
            activity.windowManager.updateViewLayout(view, params)
        }
        if (height != lastHeight) {
            lastHeight = height
            notifyListeners("height", JSObject().put("height", height))
        }
    }

    private fun updateVisibility() {
        val ime = ViewCompat.getRootWindowInsets(bridge.webView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val visible = wanted && !obscured && !paused && !ime
        val appeared = visible && !renderVisible
        if (!visible && renderVisible) {
            presentationCommit?.let { callback ->
                overlay?.viewTreeObserver?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback)
            }
            presentationCommit = null
            presentationGeneration++
        }
        if (appeared) {
            appearance++
            ready = false
            // If hiding interrupted submission, re-record that source on return.
            if (presentPending) currentBitmap?.let { backdropImage = it.asImageBitmap() }
        }
        renderVisible = visible
        // Warm and render the original KSU shaders in a transparent surface. Reveal
        // only after a frame containing a valid page backdrop has been submitted.
        panel?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            val alpha = if (visible && ready) 1f else 0f
            val flags = if (visible && ready) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (params.alpha != alpha || params.flags != flags) {
                params.alpha = alpha
                params.flags = flags
                activity.windowManager.updateViewLayout(view, params)
            }
        }
        overlay?.let { view ->
            val next = if (visible) View.VISIBLE else View.INVISIBLE
            if (view.visibility != next) view.visibility = next
        }
        panel?.let { view ->
            val next = if (visible) View.VISIBLE else View.INVISIBLE
            if (view.visibility != next) view.visibility = next
        }
        if (appeared) requestBackdrop()
    }

    private fun requestBackdrop() {
        val view = overlay ?: return
        if (destroyed || !renderVisible || view.width <= 0 || view.height <= 0) return
        if (copying || presentPending) { copyPending = true; return }
        val decor = activity.window.decorView
        val origin = IntArray(2).also(decor::getLocationOnScreen)
        val position = IntArray(2).also(view::getLocationOnScreen)
        val rect = Rect(position[0] - origin[0], position[1] - origin[1],
            position[0] - origin[0] + view.width, position[1] - origin[1] + view.height)
        if (!rect.intersect(0, 0, decor.width, decor.height)) return
        var reusable: Bitmap? = null
        while (spareBitmaps.isNotEmpty() && reusable == null) {
            val candidate = spareBitmaps.removeFirst()
            if (candidate.width == rect.width() && candidate.height == rect.height()) reusable = candidate
            else candidate.recycle()
        }
        val bitmap = reusable ?: Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val epoch = appearance
        copyPending = false
        copying = true
        try {
            PixelCopy.request(activity.window, rect, bitmap, { result ->
                copying = false
                if (destroyed) {
                    bitmap.recycle()
                    return@request
                }
                if (result == PixelCopy.SUCCESS && renderVisible) {
                    if (ready && epoch == appearance && currentBitmap?.sameAs(bitmap) == true) {
                        // A spinner/card above the bar can repaint the activity without
                        // changing the glass source. Don't upload an identical texture.
                        spareBitmaps.addLast(bitmap)
                        if (copyPending) bridge.webView.postOnAnimation { requestBackdrop() }
                    } else {
                        currentBitmap?.let(retiredBitmaps::addLast)
                        currentBitmap = bitmap
                        capturedAppearance = epoch
                        presentPending = true
                        backdropImage = bitmap.asImageBitmap()
                    }
                } else {
                    spareBitmaps.addLast(bitmap)
                    if (copyPending) bridge.webView.postOnAnimation { requestBackdrop() }
                }
            }, mainHandler)
        } catch (_: IllegalArgumentException) {
            // A stopped/destroyed activity may have no surface. Resume's next draw retries.
            copying = false
            copyPending = false
            spareBitmaps.addLast(bitmap)
        }
    }

    private fun backdropComposed(image: ImageBitmap) {
        if (image !== backdropImage || !presentPending || presentationCommit != null) return
        val view = overlay ?: return
        val epoch = capturedAppearance
        val generation = presentationGeneration
        val callback = Runnable {
            mainHandler.post {
                if (destroyed || generation != presentationGeneration) return@post
                presentationCommit = null
                presentPending = false
                // The render thread has consumed the old source before it is reused.
                while (retiredBitmaps.isNotEmpty()) spareBitmaps.addLast(retiredBitmaps.removeFirst())
                if (renderVisible && epoch == appearance) ready = true
                updateVisibility()
                if (copyPending || (renderVisible && !ready)) {
                    bridge.webView.postOnAnimation { requestBackdrop() }
                }
            }
        }
        presentationCommit = callback
        // Register before traversal, not from OnDraw (too late for that frame's
        // callback collection). Compose's SideEffect runs after applying the image.
        view.viewTreeObserver.registerFrameCommitCallback(callback)
        view.postInvalidateOnAnimation()
    }

    @Composable
    private fun NavigationContent() {
        // Keep the composition and shader/gesture state through keyboard, modal and
        // activity visibility changes. INVISIBLE pauses drawing without cold rebuilding.
        val colors = (if (state.dark) darkColorScheme() else lightColorScheme()).copy(
            primary = state.primary,
            onSurface = state.foreground,
            surfaceContainer = state.surface,
        )
        val hostDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalHostDarkTheme provides state.dark,
            // Uniformly scale layout, icons, text and the original shader metrics.
            // No bitmap/post-render scaling: backdrop coordinates and hit tests stay exact.
            LocalDensity provides Density(hostDensity.density * NAV_SCALE, hostDensity.fontScale),
        ) {
            MiuixTheme(colors = colors) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    backdropImage?.let { image ->
                        SideEffect { backdropComposed(image) }
                        Image(bitmap = image, contentDescription = null,
                            modifier = Modifier.fillMaxSize().alpha(0f).layerBackdrop(backdrop),
                            contentScale = ContentScale.FillBounds)
                    }
                    // This is BottomBarMiuix.kt's floating branch, with only app destinations
                    // and theme roles supplied by YSU. No custom animation/effect parameters.
                    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val bottomPadding = if (inset != 0.dp) 8.dp + inset else 28.dp
                    FloatingBottomBar(
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding)
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                panel?.barBounds?.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
                            },
                        selectedIndex = state.selected,
                        onSelected = { select(it) },
                        backdrop = backdrop,
                        tabsCount = 4,
                        isBlurEnabled = true,
                    ) { activateTab ->
                        val icons = listOf(R.drawable.nav_overview, R.drawable.nav_schedule,
                            R.drawable.nav_grades, R.drawable.nav_me)
                        state.labels.forEachIndexed { index, label ->
                            FloatingBottomBarItem(
                                selected = state.selected == index,
                                onClick = { activateTab(index) },
                                modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                            ) {
                                Icon(imageVector = ImageVector.vectorResource(icons[index]), contentDescription = label)
                                Text(text = label, fontSize = 11.sp, lineHeight = 14.sp,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun select(index: Int) {
        state = state.copy(selected = index)
        notifyListeners("select", JSObject().put("index", index))
    }

    override fun handleOnPause() {
        activity.runOnUiThread { paused = true; updateVisibility() }
    }

    override fun handleOnResume() {
        activity.runOnUiThread { paused = false; updateSize(); updateVisibility() }
    }

    override fun handleOnDestroy() {
        activity.runOnUiThread {
            destroyed = true
            wanted = false
            updateVisibility()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(afterLayout)
                root.viewTreeObserver.removeOnPreDrawListener(pageDraw)
            }
            if (observingFrames) activity.window.removeOnFrameMetricsAvailableListener(pageFrame)
            observingFrames = false
            panel?.takeIf { it.isAttachedToWindow }?.let { activity.windowManager.removeViewImmediate(it) }
            overlay?.disposeComposition()
            overlay = null
            panel = null
            backdropImage = null
            currentBitmap = null
            while (spareBitmaps.isNotEmpty()) spareBitmaps.removeFirst().recycle()
            retiredBitmaps.clear()
        }
    }
}
