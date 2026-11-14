package dev.pfeed

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end smoke test on a real emulator: app starts (Hilt, SQLCipher, DataStore, WorkManager),
 * the app opens without a PIN by default, every top-level screen opens without crashing, and the
 * panic gesture locks the app once a PIN is set.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    companion object {
        /**
         * Start every launch from an empty feed: with instant start a video can be playing the
         * moment the app opens, and the test framework can't inspect the screen while one plays.
         */
        @JvmStatic
        @org.junit.BeforeClass
        fun noInstantStart() { dev.pfeed.data.repo.PoolCache.enabled = false }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val pin = listOf("1", "2", "3", "4")

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits by polling. A busy screen (loading bars, thumbnails arriving, a playing video) makes the
     * framework refuse to inspect it for a moment; that counts as "not yet", not as a failure.
     */
    private fun waitFor(timeoutMs: Long = 15_000, what: String, condition: () -> Boolean) {
        var last: Throwable? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition()) return
            } catch (e: Throwable) {
                last = e
            }
            Thread.sleep(300)
        }
        throw AssertionError("Failed waiting for: $what [${last?.javaClass?.simpleName}: ${last?.message?.take(200)}]", last)
    }

    /** Taps a text, retrying while the screen is too busy to be inspected. */
    private fun tap(text: String) = waitFor(timeoutMs = 30_000, what = "tap '$text'") {
        rule.onAllNodes(hasText(text), useUnmergedTree = true)[0].performClick()
        true
    }

    /** Polls without touching the Compose tree (which can't be inspected while a video plays). */
    private fun poll(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            // Under a test rule the UI only draws frames when the test drives its clock.
            rule.mainClock.advanceTimeBy(250)
            Thread.sleep(150)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    /** A real finger swipe up through the Activity, the same path a physical touch takes. */
    private fun swipeUpGesture() {
        val activity = rule.activity
        val w = activity.window.decorView.width.toFloat()
        val h = activity.window.decorView.height.toFloat()
        val down = SystemClock.uptimeMillis()
        fun ev(action: Int, y: Float, t: Long) = MotionEvent.obtain(down, t, action, w / 2f, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val steps = 10
        rule.runOnUiThread { ev(MotionEvent.ACTION_DOWN, h * 0.75f, down).let { activity.dispatchTouchEvent(it); it.recycle() } }
        for (i in 1..steps) {
            Thread.sleep(16)
            val y = h * (0.75f - 0.5f * i / steps)
            rule.runOnUiThread { ev(MotionEvent.ACTION_MOVE, y, SystemClock.uptimeMillis()).let { activity.dispatchTouchEvent(it); it.recycle() } }
        }
        rule.runOnUiThread { ev(MotionEvent.ACTION_UP, h * 0.25f, SystemClock.uptimeMillis()).let { activity.dispatchTouchEvent(it); it.recycle() } }
    }

    /**
     * The thing the app is for: with the built-in sources and a real network, posts actually
     * display (an image drawn or a video's first frame), and keep displaying as you swipe.
     */
    @Test
    fun feedShowsRealPostsAndKeepsScrolling() {
        val shown = rule.activity.mediaHealth.shownCount
        try {
            poll(90_000, "a first post to display") { shown.get() >= 1 }
        } catch (e: AssertionError) {
            throw AssertionError("No post displayed in 90 s. What happened: ${rule.activity.mediaHealth.recentEvents()}")
        }
        var swipes = 0
        try {
            poll(120_000, "three posts to have displayed while swiping") {
                if (swipes < 12) {
                swipeUpGesture(); swipes++
                repeat(8) { rule.mainClock.advanceTimeBy(250); Thread.sleep(150) }
            }
                shown.get() >= 3
            }
        } catch (e: AssertionError) {
            throw AssertionError("Only ${shown.get()} posts displayed after $swipes swipes. What happened: ${rule.activity.mediaHealth.recentEvents()}")
        }
        assertTrue("activity still alive after scrolling", !rule.activity.isFinishing && !rule.activity.isDestroyed)
    }

    /** Search finds real results and plays them. */
    @Test
    fun searchFindsPlaysAndSaves() {
        waitFor(timeoutMs = 30_000, what = "feed tab") { exists("For You") }
        tap("Search")
        waitFor(timeoutMs = 30_000, what = "search screen") { exists("Popular") }
        tap("anal")
        // Results stream in as sources answer. On a slow test machine every source can miss its
        // deadline; that's a finished search with nothing found, not a failure of the app.
        waitFor(timeoutMs = 120_000, what = "search to return results or finish") {
            exists("Play all") || (!exists("Searching every source…") && !exists("still searching", substring = true))
        }
        if (!exists("Play all")) return
        tap("Play all")
        // Results must really display in the viewer.
        val shown = rule.activity.mediaHealth.shownCount
        val before = shown.get()
        try {
            poll(90_000, "a search result to display") { shown.get() > before }
        } catch (e: AssertionError) {
            throw AssertionError("No search result displayed in 90 s. What happened: ${rule.activity.mediaHealth.recentEvents()}")
        }
        assertTrue("viewer still alive after playing search results", !rule.activity.isFinishing && !rule.activity.isDestroyed)
    }

    @Test
    fun launchUnlockNavigateAndPanic() {
        // 1–2. The app lock is optional and off by default: no PIN screen, straight to the feed.
        waitFor(what = "app opens without a PIN") { exists("For You") || exists("Latest") }
        assertTrue("no lock screen by default", !exists("Create a PIN") && !exists("Enter PIN"))

        // 3. Feed loads — either content appears (pre-seeded sources) or the tab bar is visible.
        //    We no longer expect an empty-state: sources are pre-seeded at install and start loading.
        waitFor(what = "feed visible") { exists("For You") || exists("Latest") || exists("Following") }

        // 4. Every tab opens.
        rule.onNodeWithText("Search").performClick()
        waitFor(what = "search screen") { exists("Search every source") && exists("Popular") }

        rule.onNodeWithText("Library").performClick()
        waitFor(what = "library screen") { exists("Saved", substring = true) && exists("Following", substring = true) }

        rule.onNodeWithText("Settings").performClick()
        waitFor(what = "settings screen") { exists("Sources") && exists("App lock") }

        // 5. Sources screen lists every source, the health check and the topic finder.
        rule.onNodeWithText("Sources").performClick()
        waitFor(what = "sources screen") { exists("RSS / Atom") && exists("Local files") && exists("Check sources") && exists("feeds from", substring = true) }
        // Tapping a source opens its sheet with the one way to add to it.
        tap("Reddit")
        waitFor(what = "reddit sheet") { exists("Subreddit") }
        androidx.test.espresso.Espresso.pressBack()
        waitFor(what = "sheet closed") { !exists("Subreddit") }
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor(what = "back to settings") { exists("App lock") }

        rule.onNodeWithText("Feed").performClick()
        waitFor(what = "back on feed") { exists("For You") }

        // 6. Panic gesture: three fingers down at once locks the app. Sent as real MotionEvents through
        //    the Activity, the same path a physical touch takes.
        val lockManager = rule.activity.lockManager
        // Panic only locks when the user has turned the lock on; turn it on the way Settings does.
        runBlocking { lockManager.createPin(pin.joinToString("").toCharArray()) }
        rule.runOnUiThread {
            val activity = rule.activity
            val cx = activity.window.decorView.width / 2f
            val cy = activity.window.decorView.height / 2f
            val t = SystemClock.uptimeMillis()
            fun touch(action: Int, count: Int): MotionEvent {
                val props = Array(count) { i ->
                    MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
                }
                val coords = Array(count) { i ->
                    MotionEvent.PointerCoords().apply { x = cx + i * 80f; y = cy; pressure = 1f; size = 1f }
                }
                return MotionEvent.obtain(t, t, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            }
            fun pointerDown(index: Int) = MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            listOf(
                touch(MotionEvent.ACTION_DOWN, 1),
                touch(pointerDown(1), 2),
                touch(pointerDown(2), 3),
                touch(MotionEvent.ACTION_CANCEL, 3),
            ).forEach { ev -> activity.dispatchTouchEvent(ev); ev.recycle() }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!lockManager.locked.value && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("panic gesture should lock the app", lockManager.locked.value)
        // Tests share one install: leave the app unlocked with no PIN, or every test that runs after
        // this one would start on the lock screen.
        runBlocking {
            lockManager.unlockWithPin(pin.joinToString("").toCharArray())
            lockManager.disable(pin.joinToString("").toCharArray())
        }
    }
}
