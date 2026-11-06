package dev.pfeed

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

        // 6. Panic gesture: three fingers down at once locks the app.
        val lockManager = rule.activity.lockManager
        rule.onRoot().performTouchInput {
            down(0, center)
            down(1, center + Offset(80f, 0f))
            down(2, center + Offset(160f, 0f))
            up(0); up(1); up(2)
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!lockManager.locked.value && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("panic gesture should lock the app", lockManager.locked.value)
    }
}
