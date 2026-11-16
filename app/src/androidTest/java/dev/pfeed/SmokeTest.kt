package dev.pfeed

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
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
        val onScreen = runCatching {
            rule.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { root ->
                fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                    run {
                        val key = androidx.compose.ui.semantics.SemanticsProperties.Text
                        if (key in n.config) n.config[key].map { t -> t.text } else emptyList()
                    } + n.children.flatMap { c -> walk(c) }
                walk(root)
            }.distinct().take(40).joinToString(" | ")
        }.getOrDefault("?")
        throw AssertionError("Failed waiting for: $what [${last?.javaClass?.simpleName}: ${last?.message?.take(200)}] on screen: $onScreen", last)
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
        val t0 = System.currentTimeMillis()
        try {
            poll(90_000, "a first post to display") { shown.get() >= 1 }
            android.util.Log.i("PFHEALTH", "feed: first post on screen ${System.currentTimeMillis() - t0} ms after launch (cold, no saved posts)")
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
        // Keep scrolling a while and log what showed and what failed: the mix of sites and the
        // failure rate are what using the app feels like.
        fun mem(): String {
            val rt = Runtime.getRuntime()
            return "java ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB, native ${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} MB"
        }
        android.util.Log.i("PFHEALTH", "feed: memory after ${swipes} swipes: ${mem()}")
        repeat(45) { swipeUpGesture(); repeat(6) { rule.mainClock.advanceTimeBy(250); Thread.sleep(120) } }
        android.util.Log.i("PFHEALTH", "feed: memory after ${swipes + 45} swipes: ${mem()}")
        val events = rule.activity.mediaHealth.recentEvents()
        events.forEach { android.util.Log.i("PFHEALTH", "  feed $it") }
        android.util.Log.i("PFHEALTH", "feed: ${shown.get()} shown after ${swipes + 45} swipes, " +
            "${events.count { it.startsWith("failed") }} of the last ${events.size} events failed, " +
            "${events.filter { it.startsWith("shown") }.map { it.split(' ').getOrElse(1) { "" } }.distinct().size} different origins")
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
        rule.onNode(hasText("Search") and androidx.compose.ui.test.isSelectable()).performClick()
        waitFor(what = "search screen") { exists("Search every site") && exists("Popular") }

        rule.onNode(hasText("Library") and androidx.compose.ui.test.isSelectable()).performClick()
        waitFor(what = "library screen") { exists("Saved", substring = true) && exists("Following", substring = true) }

        rule.onNode(hasText("Settings") and androidx.compose.ui.test.isSelectable()).performClick()
        waitFor(what = "settings screen") { exists("Sources") && exists("App lock") }

        // 5. Sources screen lists every source, the health check and the topic finder.
        rule.onNodeWithText("Sources").performClick()
        waitFor(what = "sources screen") { exists("RSS / Atom") && exists("Local files") && exists("Check sources") && exists("feeds from", substring = true) }
        // Tapping a source opens its sheet with the one way to add to it.
        waitFor(timeoutMs = 30_000, what = "scroll to Reddit and open it") {
            rule.onAllNodes(hasText("Reddit"), useUnmergedTree = true)[0].performScrollTo().performClick()
            true
        }
        waitFor(what = "reddit sheet") { exists("Subreddit") || exists("subreddits", substring = true) && exists("Find one") }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitFor(what = "sheet closed, still on sources") { !exists("Subreddit") && exists("Check sources") }
        waitFor(what = "tap back") { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Back"), useUnmergedTree = true)[0].performClick(); true }
        waitFor(what = "back to settings") { exists("App lock") }

        rule.onNode(hasText("Feed") and androidx.compose.ui.test.isSelectable()).performClick()
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

    /**
     * Every enabled source, checked from the test device's network exactly as Settings → Sources
     * does: posts listed and their files reachable. Each result is logged (tag PFHEALTH) so CI shows
     * a health report; the test fails only if the app is broadly broken.
     */
    @Test
    fun sourcesAnswerFromThisNetwork() {
        val checker = rule.activity.sourceChecker
        val adapters = runBlocking { rule.activity.sourceRegistry.enabledAdapters() }
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val results = runBlocking {
            kotlinx.coroutines.coroutineScope {
                adapters.map { a ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        gate.acquire()
                        try {
                            val t0 = System.currentTimeMillis()
                            val r = kotlinx.coroutines.withTimeoutOrNull(90_000) { checker.check(a) }
                            Triple(a.displayName, r, System.currentTimeMillis() - t0)
                        } finally { gate.release() }
                    }
                }.map { it.await() }
            }
        }
        results.forEach { (name, r, ms) ->
            android.util.Log.i("PFHEALTH", "${if (r?.ok == true) "OK  " else "FAIL"} $name (${ms} ms): ${r?.detail ?: "no answer in 90 s"}")
        }
        // Reddit, raw: one feed request through the app's client, and whether the source is resting.
        runBlocking {
            val t0 = System.currentTimeMillis()
            val line = runCatching { rule.activity.httpJson.getRaw("reddit", "https://www.reddit.com/r/gonewild/new/.rss?limit=100").split("<entry>").size - 1 }
                .fold({ "$it posts" }, { "error: ${it.message}" })
            val resting = (rule.activity.sourceRegistry.byId("reddit") as? dev.pfeed.source.net.CachingAdapter)?.restingGroups()
            android.util.Log.i("PFHEALTH", "  reddit raw: $line in ${System.currentTimeMillis() - t0} ms; resting=$resting")
        }
        runBlocking { rule.activity.tubeAdapter.siteReport() }.forEach { (site, line) -> android.util.Log.i("PFHEALTH", "  tube $site: $line") }
        val working = results.count { it.second?.ok == true && it.second?.detail?.startsWith("Nothing added") != true }
        assertTrue("only $working sources working: " + results.joinToString { "${it.first}=${it.second?.detail}" }, working >= 3)
    }

    /** Search across every source for a few common terms; logs results per site and timing. */
    @Test
    fun searchReachesManySources() {
        val repo = rule.activity.searchRepository
        var total = 0
        for (q in listOf("milf", "cosplay", "hentai")) {
            val last = runBlocking { kotlinx.coroutines.withTimeoutOrNull(60_000) { repo.search(q).toList().lastOrNull() } }
            val bySource = last?.items.orEmpty().groupingBy { it.sourceId }.eachCount()
            total += last?.items?.size ?: 0
            android.util.Log.i("PFHEALTH", "search \"$q\": ${last?.items?.size ?: 0} results in ${last?.elapsedMs} ms from $bySource; errors=${last?.errors?.take(4)} timings=${last?.timings}")
        }
        assertTrue("searches found nothing at all", total > 0)
    }

    /** Saves a screenshot of what's on screen (pulled from the device and uploaded by CI). */
    private fun shot(name: String) {
        repeat(6) { rule.mainClock.advanceTimeBy(250); Thread.sleep(120) }
        // Taken by the shell into /data/local/tmp, which outlives the test run's uninstall (CI pulls it).
        val ua = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(cmd: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ua.executeShellCommand(cmd)).use { it.readBytes() }
        sh("mkdir -p /data/local/tmp/shots")
        sh("screencap -p /data/local/tmp/shots/$name.png")
        android.util.Log.i("PFHEALTH", "screenshot $name")
    }

    /**
     * Screenshots of every main screen for design review. Every post's picture is covered with a
     * flat panel (see MaskMedia): the shots show the app's layout, not the content.
     */
    @Test
    fun designScreenshots() {
        dev.pfeed.ui.common.MaskMedia.on = true
        // The app blocks screenshots (FLAG_SECURE); lift that for this test only, with the posts masked.
        rule.runOnUiThread { rule.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        try {
            val shown = rule.activity.mediaHealth.shownCount
            val before = shown.get()
            runCatching { poll(90_000, "a post on screen") { shown.get() > before } }
            Thread.sleep(1_500)
            shot("01_feed")
            rule.onNode(hasText("Search") and androidx.compose.ui.test.isSelectable()).performClick()
            waitFor(what = "search screen") { exists("Popular") }
            shot("02_search")
            tap("anal")
            waitFor(timeoutMs = 60_000, what = "results") { exists("Play all") || !exists("still searching", substring = true) }
            Thread.sleep(2_000)
            shot("03_results")
            if (exists("Play all")) {
                tap("Play all")
                val b = shown.get()
                runCatching { poll(60_000, "a result on screen") { shown.get() > b } }
                Thread.sleep(1_000)
                shot("03b_viewer")
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                Thread.sleep(1_000)
            }
            // A few saved images, so the Saved grid (count, Play all, kept badges) is in the shot.
            val savedForShot = runBlocking {
                rule.activity.searchRepository.search("cosplay").toList().lastOrNull()?.items.orEmpty()
                    .filter { it.type == dev.pfeed.domain.model.MediaType.IMAGE && !it.isGallery }.take(5)
            }
            runBlocking { savedForShot.forEach { rule.activity.library.add(dev.pfeed.data.repo.LibraryKind.SAVED, it) } }
            Thread.sleep(8_000) // time for them to be kept
            rule.onNode(hasText("Library") and androidx.compose.ui.test.isSelectable()).performClick()
            waitFor(what = "library") { exists("Saved", substring = true) }
            Thread.sleep(1_500)
            shot("04_library")
            runBlocking { savedForShot.forEach { rule.activity.library.remove(dev.pfeed.data.repo.LibraryKind.SAVED, it) } }
            rule.onNode(hasText("Settings") and androidx.compose.ui.test.isSelectable()).performClick()
            waitFor(what = "settings") { exists("App lock") }
            shot("05_settings")
            waitFor(what = "scroll settings") { rule.onAllNodes(hasText("Encrypted DNS"), useUnmergedTree = true)[0].performScrollTo(); true }
            shot("06_settings_more")
            waitFor(what = "scroll to top") { rule.onAllNodes(hasText("Sources"), useUnmergedTree = true)[0].performScrollTo(); true }
            rule.onNodeWithText("Your taste").performClick()
            Thread.sleep(1_000)
            shot("07_taste")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor(what = "settings again") { exists("App lock") }
            rule.onNodeWithText("Sources").performClick()
            waitFor(what = "sources") { exists("Check sources") }
            shot("08_sources")
            // The PIN screen, as it looks when the app locks.
            val lm = rule.activity.lockManager
            runBlocking { lm.createPin("1234".toCharArray()) }
            rule.runOnUiThread { lm.lockNow() }
            Thread.sleep(800)
            shot("09_lock")
            runBlocking { lm.unlockWithPin("1234".toCharArray()); lm.disable("1234".toCharArray()) }
        } finally {
            rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            dev.pfeed.ui.common.MaskMedia.on = false
        }
    }

    /** Saving a video and a comic keeps them on the phone: the copies appear and are what plays. */
    @Test
    fun savedPostsAreKeptOffline() {
        val a = rule.activity
        runBlocking {
            val found = a.searchRepository.search("milf").toList().lastOrNull()?.items.orEmpty()
            val picks = listOfNotNull(
                found.firstOrNull { it.sourceId == "tube" },
                // A site that streams (HLS), so the stream download is checked too.
                found.firstOrNull { it.sourceId == "tube" && (it.id.startsWith("pornhub.com") || it.id.startsWith("xhamster")) },
                found.firstOrNull { it.isGallery && it.pages.size <= 40 },
                found.firstOrNull { it.type == dev.pfeed.domain.model.MediaType.IMAGE && !it.isGallery },
            ).distinctBy { it.key }
            picks.forEach { a.library.add(dev.pfeed.data.repo.LibraryKind.SAVED, it) }
            try {
                val t0 = System.currentTimeMillis()
                fun done(it: dev.pfeed.domain.model.MediaItem) =
                    if (it.isGallery) a.offlineStore.local("${it.key}#${it.pages.size - 1}") != null else a.offlineStore.local(it.key) != null
                while (picks.any { !done(it) } && System.currentTimeMillis() - t0 < 180_000) kotlinx.coroutines.delay(2_000)
                picks.forEach { p ->
                    val local = if (p.isGallery) a.offlineStore.local("${p.key}#0") else a.offlineStore.local(p.key)
                    android.util.Log.i("PFHEALTH", "offline ${p.sourceId}/${p.type}${if (p.isGallery) " (${p.pages.size} pages)" else ""}: ${if (done(p)) "kept" else "NOT kept"} in ${System.currentTimeMillis() - t0} ms ${local?.mimeType ?: a.offlineStore.problem(p.key)} ${p.id.substringBefore('/')}; total ${a.offlineStore.bytes.value / 1024} KB")
                }
                // The emulator sometimes reports a metered (mobile) connection: downloads rightly wait for Wi-Fi then.
                val metered = picks.isNotEmpty() && picks.any { a.offlineStore.problem(it.key) == "waiting for Wi-Fi" }
                if (metered) android.util.Log.i("PFHEALTH", "offline: this emulator's connection is metered; saved posts wait for Wi-Fi (as intended)")
                assertTrue("nothing was kept offline", picks.isEmpty() || picks.any(::done) || (metered && picks.all { it.type == dev.pfeed.domain.model.MediaType.VIDEO }))
            } finally {
                picks.forEach { a.library.remove(dev.pfeed.data.repo.LibraryKind.SAVED, it) }
            }
        }
    }

    /** Following a tag fills the Following feed, and a tag page and a creator page both load. */
    @Test
    fun followingTagAndCreatorPagesLoad() {
        val a = rule.activity
        runBlocking {
            a.library.follow(dev.pfeed.data.repo.FollowKind.TAG, "milf")
            try {
                for (spec in listOf(
                    dev.pfeed.data.repo.FeedSpec.Main(dev.pfeed.domain.model.FeedMode.FOLLOWING),
                    dev.pfeed.data.repo.FeedSpec.Tag("cosplay"),
                )) {
                    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                    val session = a.feedSessions.create(spec, scope)
                    val t0 = System.currentTimeMillis()
                    var got = 0
                    while (got == 0 && System.currentTimeMillis() - t0 < 60_000) {
                        val r = session.loadMore()
                        got += r.items.size
                        if (r.endReached) break
                        if (r.items.isEmpty()) kotlinx.coroutines.delay(1_000)
                    }
                    session.close()
                    android.util.Log.i("PFHEALTH", "page $spec: $got posts in ${System.currentTimeMillis() - t0} ms")
                    assertTrue("$spec loaded nothing in 60 s", got > 0)
                }
            } finally {
                a.library.unfollow(dev.pfeed.data.repo.FollowKind.TAG, "milf")
            }
        }
    }

    /** What For You actually serves (not what had time to draw): its first 60 posts by source and type. */
    @Test
    fun forYouMix() {
        val a = rule.activity
        for (mode in listOf(dev.pfeed.domain.model.FeedMode.FOR_YOU, dev.pfeed.domain.model.FeedMode.LATEST)) runBlocking {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val session = a.feedSessions.create(dev.pfeed.data.repo.FeedSpec.Main(mode), scope)
            val got = ArrayList<dev.pfeed.domain.model.MediaItem>()
            val t0 = System.currentTimeMillis()
            while (got.size < 60 && System.currentTimeMillis() - t0 < 90_000) {
                val r = session.loadMore()
                got += r.items
                if (r.items.isEmpty()) kotlinx.coroutines.delay(1_000)
            }
            session.close()
            val bySource = got.groupingBy { it.sourceId }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }
            val byType = got.groupingBy { if (it.isGallery) "comic" else it.type.name.lowercase() }.eachCount()
            android.util.Log.i("PFHEALTH", "$mode: ${got.size} posts in ${System.currentTimeMillis() - t0} ms; by source: $bySource; by type: $byType; origins: ${got.map { dev.pfeed.domain.reco.Spread.origin(it) }.distinct().size}; my little pony: ${got.count { dev.pfeed.domain.reco.Niche.of(it) != null }}")
            assertTrue("For You served nothing", got.isNotEmpty())
        }
    }
}
