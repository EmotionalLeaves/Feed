package dev.pfeed

import dev.pfeed.source.LinkOpener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkOpenerTest {
    private fun id(u: String) = LinkOpener.videoId(u.toHttpUrl())

    @Test
    fun readsVideoIdsFromPageLinks() {
        assertEquals("ph5f3a", id("https://www.pornhub.com/view_video.php?viewkey=ph5f3a"))
        assertEquals("AbC123xY", id("https://www.eporner.com/video-AbC123xY/some-title/"))
        assertEquals("123456", id("https://www.pornhat.com/videos/123456/some-title/"))
        assertEquals("xhQ1w2E", id("https://xhamster.com/videos/some-long-title-xhQ1w2E"))
        assertEquals("98765", id("https://www.redtube.com/98765"))
    }
}
