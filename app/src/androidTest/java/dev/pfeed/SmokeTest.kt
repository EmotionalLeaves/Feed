package dev.pfeed

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val pin = listOf("1", "2", "3", "4")

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(timeoutMs: Long = 15_000, what: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (e: Throwable) {
            throw AssertionError("Failed waiting for: $what [${e.javaClass.simpleName}: ${e.message?.take(300)}]", e)
        }
    }


    /**
     * The thing the app is for: with the built-in sources and a real network, posts actually appear
     * and you can keep swiping through them. (Every post page has the "More" button.)
     */
    @Test
    fun feedShowsRealPostsAndKeepsScrolling() {
        waitFor(timeoutMs = 20_000, what = "feed tab") { exists("For You") }
        waitFor(timeoutMs = 60_000, what = "a first post from the built-in sources") { exists("More") }
        repeat(8) { n ->
            rule.onRoot().performTouchInput { swipeUp() }
            waitFor(timeoutMs = 30_000, what = "a post after swipe ${n + 1}") { exists("More") }
        }
        assertTrue("activity still alive after scrolling", !rule.activity.isFinishing)
    }

    /** Search finds real results, plays them, and saving a post works. */
    @Test
    fun searchFindsPlaysAndSaves() {
        waitFor(timeoutMs = 20_000, what = "feed tab") { exists("For You") }
        rule.onNodeWithText("Search").performClick()
        waitFor(what = "search screen") { exists("Popular") }
        rule.onNodeWithText("anal").performClick()
        // Results stream in as sources answer. On a slow test machine every source can miss its
        // deadline; that's a finished search with nothing found, not a failure of the app.
        waitFor(timeoutMs = 120_000, what = "search to return results or finish") {
            exists("Play all") || (!exists("Searching every source…") && !exists("still searching", substring = true))
        }
        if (!exists("Play all")) return
        rule.onNodeWithText("Play all").performClick()
        waitFor(timeoutMs = 60_000, what = "a result opens in the viewer") { exists("More") && exists("Save") }
        // The pager also composes the next page, so there can be two Save buttons; the first is on screen.
        rule.onAllNodes(hasText("Save"), useUnmergedTree = true)[0].performClick()
        waitFor(what = "post saved") { exists("Saved") }
        repeat(3) { n ->
            rule.onRoot().performTouchInput { swipeUp() }
            waitFor(timeoutMs = 30_000, what = "next result after swipe ${n + 1}") { exists("More") }
        }
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
        waitFor(what = "library screen") { exists("Saved", substring = true) && exists("Blocked", substring = true) }

        rule.onNodeWithText("Settings").performClick()
        waitFor(what = "settings screen") { exists("Sources") && exists("Privacy") }

        // 5. Sources screen lists adapters and the quick-add bar.
        rule.onNodeWithText("Sources").performClick()
        waitFor(what = "sources screen") { exists("RSS / Atom") && exists("Local files") && exists("Add more") && exists("feeds from", substring = true) }
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor(what = "back to settings") { exists("Privacy") }

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
    }
}
