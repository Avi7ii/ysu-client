package com.youwenqwq.ysuclient

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.State
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs only on the disposable emulator; fixtures never touch a signed-in device. */
@RunWith(AndroidJUnit4::class)
class LiquidBottomNavTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val navScale = 0.9f

    private class Call(data: JSObject = JSObject()) : PluginCall(null, "LiquidBottomNav", "-1", "test", data) {
        val done = CountDownLatch(1)
        var result: JSObject? = null
        override fun resolve(data: JSObject?) { result = data; done.countDown() }
        override fun resolve() { done.countDown() }
        override fun reject(message: String?, error: Exception?) { throw AssertionError(message, error) }
    }

    private fun frames(count: Int = 12) {
        val complete = CountDownLatch(1)
        instrumentation.runOnMainSync {
            var remaining = count
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    if (--remaining == 0) complete.countDown()
                    else Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(callback)
        }
        assertTrue("render frames", complete.await(5, TimeUnit.SECONDS))
    }

    private fun selected(plugin: LiquidBottomNavPlugin): Int {
        val field = plugin.javaClass.getDeclaredField("state\$delegate").apply { isAccessible = true }
        val state = (field.get(plugin) as State<*>).value!!
        return state.javaClass.getDeclaredField("selected").apply { isAccessible = true }.getInt(state)
    }

    @Test
    fun originalNativeRendererSupportsSelectionOcclusionAndRepeatedSwitching() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var plugin: LiquidBottomNavPlugin
            lateinit var root: ViewGroup
            var density = 1f
            val loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                plugin = activity.bridge.getPlugin("LiquidBottomNav").instance as LiquidBottomNavPlugin
                root = activity.findViewById(android.R.id.content)
                density = activity.resources.displayMetrics.density
                activity.bridge.webView.apply {
                    stopLoading()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
                    }
                    loadDataWithBaseURL("http://localhost", """
                        <html><meta name="viewport" content="width=device-width, initial-scale=1">
                        <body onpointerdown="window.pageTouches=(window.pageTouches||0)+1" style="margin:0;height:300vh;background:repeating-linear-gradient(90deg,#161616 0 16px,#767676 16px 32px)">
                        <input id="keyboard-test" style="position:fixed;top:80px;left:20px" aria-label="Keyboard test">
                        </body></html>
                    """.trimIndent(), "text/html", "UTF-8", null)
                }
            }
            assertTrue("WebView fixture", loaded.await(10, TimeUnit.SECONDS))
            frames()
            val config = JSObject("""{"visible":true,"selectedIndex":0,"dark":true,"labels":["总览","日程","成绩","我的"],"colors":{"primary":"#ebebeb","foreground":"#fafafa","surface":"#262626"}}""")
            val call = Call(config)
            lateinit var overlay: ComposeView
            val backdropField = plugin.javaClass.getDeclaredField("backdropImage\$delegate").apply {
                isAccessible = true
            }
            val currentBitmapField = plugin.javaClass.getDeclaredField("currentBitmap").apply {
                isAccessible = true
            }
            val firstVisible = CountDownLatch(1)
            val configuredAt = SystemClock.uptimeMillis()
            scenario.onActivity {
                plugin.configure(call)
                overlay = plugin.javaClass.getDeclaredField("overlay").apply { isAccessible = true }
                    .get(plugin) as ComposeView
                val host = overlay.parent as View
                assertEquals("unprepared panel must be transparent", 0f,
                    (host.layoutParams as android.view.WindowManager.LayoutParams).alpha)
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        val alpha = (host.layoutParams as android.view.WindowManager.LayoutParams).alpha
                        if (alpha > 0f) {
                            assertNotNull("first visible frame has a page backdrop", (backdropField.get(plugin) as State<*>).value)
                            assertNotNull(currentBitmapField.get(plugin))
                            Log.i("LiquidNavTest", "first prepared glass frame: ${SystemClock.uptimeMillis() - configuredAt}ms")
                            firstVisible.countDown()
                        } else Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                Choreographer.getInstance().postFrameCallback(callback)
            }
            assertTrue(call.done.await(5, TimeUnit.SECONDS))
            assertEquals(true, call.result?.getBoolean("visible"))
            val prepared = firstVisible.await(10, TimeUnit.SECONDS)
            if (!prepared) scenario.onActivity {
                val fields = listOf("copying", "copyPending", "presentPending", "presentationCommit", "ready", "capturedAppearance", "appearance")
                    .joinToString { name -> "$name=${plugin.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(plugin)}" }
                Log.i("LiquidNavTest", "unprepared: ${overlay.width}x${overlay.height}, shown=${overlay.isShown}, image=${(backdropField.get(plugin) as State<*>).value}, $fields")
            }
            assertTrue("prepared first frame", prepared)
            frames(30)
            scenario.onActivity {
                val views = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<ComposeView>()
                assertEquals("glass is outside the page layout/window", 0, views.size)
                overlay = plugin.javaClass.getDeclaredField("overlay").apply { isAccessible = true }
                    .get(plugin) as ComposeView
                assertTrue(overlay.isAttachedToWindow)
                assertEquals(View.VISIBLE, overlay.visibility)
                assertTrue(overlay.width > 0 && overlay.height > 0)
                val host = overlay.parent as View
                val bounds = host.javaClass.getDeclaredField("barBounds").apply { isAccessible = true }
                    .get(host) as android.graphics.RectF
                assertEquals("bar width is 90 percent", 312f * navScale * density, bounds.width(), 2f)
                assertEquals("bar height is 90 percent", 64f * navScale * density, bounds.height(), 2f)
            }

            // Native-only animation must not force the static WebView to paint again.
            lateinit var webView: WebView
            scenario.onActivity { webView = it.bridge.webView }
            val images = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            var pageDraws = 0
            val pageDrawObserver = android.view.ViewTreeObserver.OnDrawListener { pageDraws++ }
            val beforeRedraws = instrumentation.uiAutomation.takeScreenshot()
            val stable = CountDownLatch(1)
            scenario.onActivity {
                root.viewTreeObserver.addOnDrawListener(pageDrawObserver)
                var remaining = 90
                val initialWebBounds = android.graphics.Rect().also { root.getChildAt(0).getHitRect(it) }
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        (backdropField.get(plugin) as State<*>).value?.let { images.add(it) }
                        val bounds = android.graphics.Rect().also { root.getChildAt(0).getHitRect(it) }
                        assertEquals("native repaint must not resize the WebView", initialWebBounds, bounds)
                        overlay.invalidate()
                        if (--remaining == 0) stable.countDown()
                        else Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                Choreographer.getInstance().postFrameCallback(callback)
            }
            assertTrue("idle redraw observation", stable.await(10, TimeUnit.SECONDS))
            scenario.onActivity { root.viewTreeObserver.removeOnDrawListener(pageDrawObserver) }
            Log.i("LiquidNavTest", "90 glass redraws: page draws=$pageDraws, backdrop images=${images.size}")
            assertEquals("native redraw cannot repaint the page", 0, pageDraws)
            assertEquals("native redraw cannot recapture static WebView", 1, images.size)
            val afterRedraws = instrumentation.uiAutomation.takeScreenshot()
            val sampleY = afterRedraws.height / 2
            for (sampleX in 20 until afterRedraws.width - 20 step 40) {
                assertEquals("page pixels stay stable during native redraws", beforeRedraws.getPixel(sampleX, sampleY),
                    afterRedraws.getPixel(sampleX, sampleY))
            }

            // A DOM content update must reach the glass without a software WebView draw.
            val oldImage = (backdropField.get(plugin) as State<*>).value
            scenario.onActivity {
                webView.evaluateJavascript("document.body.style.background='repeating-linear-gradient(90deg,#163616 0 16px,#76b676 16px 32px)'", null)
            }
            frames(20)
            scenario.onActivity {
                assertNotSame("updated page updates the backdrop", oldImage, (backdropField.get(plugin) as State<*>).value)
            }

            // Animated page content must reuse source storage, not allocate a full
            // screen-width bitmap every frame. Track identities, not just idle draws.
            val bitmaps = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
            val updatedImages = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            var animatedDraws = 0
            val animatedObserver = android.view.ViewTreeObserver.OnDrawListener { animatedDraws++ }
            scenario.onActivity { root.viewTreeObserver.addOnDrawListener(animatedObserver) }
            repeat(60) { step ->
                scenario.onActivity {
                    currentBitmapField.get(plugin)?.let { bitmap -> bitmaps.add(bitmap as Bitmap) }
                    (backdropField.get(plugin) as State<*>).value?.let(updatedImages::add)
                    webView.evaluateJavascript("document.body.style.backgroundColor='rgb(${step * 3},30,50)';document.body.style.backgroundImage='none'", null)
                }
                frames(2)
            }
            frames(10)
            Log.i("LiquidNavTest", "animated page: ${updatedImages.size} copies, ${bitmaps.size} bitmap allocations")
            scenario.onActivity {
                root.viewTreeObserver.removeOnDrawListener(animatedObserver)
                val fields = listOf("copying", "copyPending", "presentPending", "presentationCommit", "ready")
                    .joinToString { name -> "$name=${plugin.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(plugin)}" }
                val bitmap = currentBitmapField.get(plugin) as Bitmap
                Log.i("LiquidNavTest", "animated draws=$animatedDraws, source=${Integer.toHexString(bitmap.getPixel(bitmap.width/2, bitmap.height/2))}, $fields")
            }
            assertTrue("page animation reaches the glass", updatedImages.size >= 10)
            assertTrue("backdrop storage is double buffered", bitmaps.size <= 2)
            scenario.onActivity {
                webView.evaluateJavascript("document.body.style.background='repeating-linear-gradient(90deg,#163616 0 16px,#76b676 16px 32px)'", null)
            }
            frames(10)

            // Capture the actual original shader output for visual inspection.
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val output = File(instrumentation.targetContext.externalCacheDir, "liquid-native-test.png")
            output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }

            // KSU's intrinsic content width is four 76 dp tabs plus 8 dp padding.
            val location = IntArray(2)
            scenario.onActivity { overlay.getLocationOnScreen(location) }
            val outsideTime = SystemClock.uptimeMillis()
            val outsideX = overlay.width / 2f
            val outsideY = location[1] + 8 * density
            instrumentation.sendPointerSync(MotionEvent.obtain(outsideTime, outsideTime, MotionEvent.ACTION_DOWN, outsideX, outsideY, 0))
            instrumentation.sendPointerSync(MotionEvent.obtain(outsideTime, outsideTime + 40, MotionEvent.ACTION_UP, outsideX, outsideY, 0))
            frames(10)
            val touchRead = CountDownLatch(1)
            var pageTouches = 0
            scenario.onActivity {
                webView.evaluateJavascript("window.pageTouches||0") { value ->
                    pageTouches = value.toInt()
                    touchRead.countDown()
                }
            }
            assertTrue(touchRead.await(5, TimeUnit.SECONDS))
            assertTrue("transparent panel padding passes touch to the page", pageTouches > 0)
            val gap = (call.result!!.getDouble("height") - 64 * navScale).toFloat()
            val y = location[1] + overlay.height - (gap + 32 * navScale) * density
            val x = location[0] + overlay.width / 2f + 0.5f * 76 * navScale * density
            val down = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
            instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0))
            frames(30)
            scenario.onActivity { assertEquals("tap selects grades", 2, selected(plugin)) }

            val startX = location[0] + overlay.width / 2f - 1.5f * 76 * navScale * density
            val endX = location[0] + overlay.width / 2f + 1.5f * 76 * navScale * density
            val dragTime = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(dragTime, dragTime, MotionEvent.ACTION_DOWN, startX, y, 0))
            for (step in 1..8) {
                instrumentation.sendPointerSync(MotionEvent.obtain(dragTime, dragTime + step * 16L, MotionEvent.ACTION_MOVE,
                    startX + (endX - startX) * step / 8, y, 0))
                frames(1)
            }
            instrumentation.sendPointerSync(MotionEvent.obtain(dragTime, dragTime + 160, MotionEvent.ACTION_UP, endX, y, 0))
            frames(30)
            scenario.onActivity { assertEquals("drag commits the final tab", 3, selected(plugin)) }

            // Scrolling and repeated style switches must keep page geometry and scroll offset.
            for (step in 1..12) {
                scenario.onActivity { webView.evaluateJavascript("window.scrollTo(0,${step * 30})", null) }
                frames(2)
            }
            frames(20)
            var scroll = 0
            val bounds = android.graphics.Rect()
            scenario.onActivity {
                scroll = webView.scrollY
                assertTrue("page scrolled", scroll > 0)
                webView.getHitRect(bounds)
                plugin.hide(Call())
            }
            frames(10)
            val classicPage = instrumentation.uiAutomation.takeScreenshot()
            scenario.onActivity { plugin.configure(Call(config)) }
            frames(20)
            val glassPage = instrumentation.uiAutomation.takeScreenshot()
            scenario.onActivity {
                assertEquals("style switch cannot jump the page scroll", scroll, webView.scrollY)
                val current = android.graphics.Rect().also(webView::getHitRect)
                assertEquals("style switch cannot change page bounds", bounds, current)
            }
            for (sampleX in 20 until glassPage.width - 20 step 40) {
                assertEquals("style switch cannot alter page pixels", classicPage.getPixel(sampleX, sampleY),
                    glassPage.getPixel(sampleX, sampleY))
            }

            val keyboardShown = CountDownLatch(1)
            val keyboardHidden = CountDownLatch(1)
            var sawKeyboard = false
            val keyboardObserver = android.view.ViewTreeObserver.OnPreDrawListener {
                val visible = androidx.core.view.ViewCompat.getRootWindowInsets(webView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                if (visible) { sawKeyboard = true; keyboardShown.countDown() }
                else if (sawKeyboard) keyboardHidden.countDown()
                true
            }
            scenario.onActivity { activity ->
                root.viewTreeObserver.addOnPreDrawListener(keyboardObserver)
                webView.requestFocus()
                webView.evaluateJavascript("document.getElementById('keyboard-test').focus()", null)
                val ime = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                ime.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            assertTrue("IME shown", keyboardShown.await(15, TimeUnit.SECONDS))
            frames(5)
            scenario.onActivity { assertEquals("keyboard hides glass", View.INVISIBLE, overlay.visibility) }
            scenario.onActivity { activity ->
                val ime = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                ime.hideSoftInputFromWindow(webView.windowToken, 0)
                webView.evaluateJavascript("document.activeElement.blur()", null)
            }
            assertTrue("IME hidden", keyboardHidden.await(10, TimeUnit.SECONDS))
            frames(5)
            scenario.onActivity { root.viewTreeObserver.removeOnPreDrawListener(keyboardObserver) }
            scenario.onActivity { assertEquals("glass returns after keyboard", View.VISIBLE, overlay.visibility) }

            scenario.onActivity {
                plugin.setObscured(Call(JSObject().put("obscured", true)))
                assertEquals(View.INVISIBLE, overlay.visibility)
                plugin.setObscured(Call(JSObject().put("obscured", false)))
                assertEquals(View.VISIBLE, overlay.visibility)
                repeat(3) {
                    plugin.hide(Call())
                    assertEquals(View.INVISIBLE, overlay.visibility)
                    plugin.configure(Call(config))
                    assertEquals(View.VISIBLE, overlay.visibility)
                }
                assertEquals(0, (0 until root.childCount).count { root.getChildAt(it) is ComposeView })
                assertSame(overlay, plugin.javaClass.getDeclaredField("overlay").apply { isAccessible = true }.get(plugin))
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.onActivity { assertEquals("background hides native panel", View.INVISIBLE, overlay.visibility) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            frames(15)
            scenario.onActivity {
                assertEquals("resume restores native panel", View.VISIBLE, overlay.visibility)
                assertTrue(overlay.isAttachedToWindow)
                assertEquals("resume reuses the original composition host", 1, overlay.childCount)
                val host = overlay.parent as View
                assertEquals("resume presents a prepared frame", 1f,
                    (host.layoutParams as android.view.WindowManager.LayoutParams).alpha)
            }
        }
    }
}
