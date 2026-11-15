package dev.pfeed

import dev.pfeed.data.repo.OfflineStore
import dev.pfeed.domain.model.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineStoreTest {
    @Test
    fun `file type comes from the URL, else from the content type`() {
        assertEquals("mp4", OfflineStore.extFor("https://cdn.example/v/abc.mp4?token=1", null))
        assertEquals("webm", OfflineStore.extFor("https://x/y", "video/webm"))
        assertEquals("jpg", OfflineStore.extFor("https://x/get_image", "image/jpeg"))
        assertEquals("mp4", OfflineStore.extFor("https://x/stream", "video/x-whatever"))
        assertEquals("bin", OfflineStore.extFor("https://x/stream", null))
        assertEquals("video/mp4", OfflineStore.mimeOf("mp4"))
        assertEquals(null, OfflineStore.mimeOf("bin"))
    }

    @Test
    fun `HLS streams are not kept`() {
        assertTrue(OfflineStore.isHls(StreamInfo.Direct("https://x/master.m3u8?t=1")))
        assertTrue(OfflineStore.isHls(StreamInfo.Direct("https://x/s", "application/x-mpegURL")))
        assertFalse(OfflineStore.isHls(StreamInfo.Direct("https://x/v.mp4")))
    }

    @Test
    fun `each post and comic page has its own stable file name`() {
        val a = OfflineStore.hash("tube:pornhub.com/abc")
        assertEquals(a, OfflineStore.hash("tube:pornhub.com/abc"))
        assertTrue(a != OfflineStore.hash("tube:pornhub.com/abc#0"))
        assertEquals(40, a.length)
    }
}
