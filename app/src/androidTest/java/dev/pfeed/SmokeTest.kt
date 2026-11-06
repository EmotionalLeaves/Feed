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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end smoke test on a real emulator: app starts (Hilt, SQLCipher, DataStore, WorkManager),
 * PIN gate works, every top-level screen opens without crashing, and the panic gesture locks the app.
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
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private fun typePin() = pin.forEach { rule.onNodeWithText(it).performClick() }

    @Test
    fun launchUnlockNavigateAndPanic() {
        // 1. Lock screen appears on launch.
        waitFor(what = "lock screen") { exists("Create a PIN") || exists("Enter PIN") }

        // 2. Create (fresh install) or enter the PIN.
        if (exists("Create a PIN")) {
            typePin()
            rule.onNodeWithText("OK").performClick()
            waitFor(what = "confirm stage") { exists("Confirm PIN") }
            typePin()
            rule.onNodeWithText("OK").performClick()
        } else {
            typePin()
            rule.onNodeWithText("Unlock").performClick()
        }
        waitFor(what = "unlock") { !exists("Confirm PIN") && !exists("Enter PIN") && !exists("Create a PIN") }

        // 3. Feed loads — either content appears (pre-seeded sources) or the tab bar is visible.
        //    We no longer expect an empty-state: sources are pre-seeded at install and start loading.
        waitFor(what = "feed visible") { exists("For You") || exists("Latest") || exists("Following") }

        // 4. Every tab opens.
        rule.onNodeWithText("Search").performClick()
        waitFor(what = "search screen") { exists("Search all sources") }

        rule.onNodeWithText("Library").performClick()
        waitFor(what = "library screen") { exists("Saved") && exists("Blocked") }

        rule.onNodeWithText("Settings").performClick()
        waitFor(what = "settings screen") { exists("Manage sources") }

        // 5. Sources screen lists adapters and the quick-add bar.
        rule.onNodeWithText("Manage sources").performClick()
        waitFor(what = "sources screen") { exists("RSS / Atom") && exists("Local files") && exists("Quick add", substring = true) }
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor(what = "back to settings") { exists("Manage sources") }

        rule.onNodeWithText("Feed").performClick()
        waitFor(what = "back on feed") { exists("For You") }

        // 6. Panic gesture: three fingers down at once locks the app. Sent as real MotionEvents through
        //    the Activity, the same path a physical touch takes.
        val lockManager = rule.activity.lockManager
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
