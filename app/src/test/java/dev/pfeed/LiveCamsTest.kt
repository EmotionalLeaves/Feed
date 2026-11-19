package dev.pfeed

import dev.pfeed.source.live.LiveCamsAdapter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CamSoda's online listing (a real answer, November 2026, cut to four rooms). */
class LiveCamsTest {
    @Test
    fun `reads the online rooms, both shapes, and leaves out private shows`() {
        val rooms = LiveCamsAdapter.parseOnline(Json.parseToJsonElement(ONLINE))
        assertEquals(3, rooms.size)
        assertTrue(rooms.all { it.extras[LiveCamsAdapter.LIVE] == "1" && it.thumbnailUrl!!.startsWith("https://") && it.id.startsWith("camsoda/") })
        // Busiest first.
        val viewers = rooms.map { it.extras["viewers"]!!.toInt() }
        assertEquals(viewers.sortedDescending(), viewers)
    }

    private companion object {
        val ONLINE = """{"template": ["user_id", "username", "display_name", "status", "connections", "sort_value", "subject_html", "stream_name", "gender", "edge_servers", "thumb", "pvt_rating", "bitrate", "control_her", "standby", "offline_picture"], "results": [{"width": 1920, "height": 1080, "last_on": "2026-11-19 00:57:56", "tpl": {"0": 8288322, "1": "gabyferrer", "2": "Gaby Ferrer", "4": 156, "5": 106.97, "6": "NEW PUBLIC SQUIRT AND CUM VIDEO! / ALL MY MEDIA 300 TKS/ SNAP 150 TKS...", "7": "cam_obs/gabyferrer-flu", "8": "f", "9": ["edge2-cod.livemediahost.com", "edge6-ild.livemediahost.com"], "10": "https://media.livemediahost.com/thumbs/828/gabyferrer.jpg?cb=1795050210", "11": 95.82, "12": 5473, "13": 1, "15": "https://media.livemediahost.com/media/offline_picture/828/gabyferrer.20240402203329.jpg"}}, {"width": 1280, "height": 720, "last_on": "2026-11-18 21:25:27", "top_pvt": 1, "tpl": {"0": 18008278, "1": "sweetreya", "2": "SweetReya", "4": 186, "5": 105.8, "6": "Happy day kings!Ultra high333 |\u200b 555 | 1234 | 3333 |PVT/C2C Open@ [821...", "7": "cam_obs/sweetreya-flu", "8": "f", "9": ["edge9-ild.livemediahost.com", "edge5-cod.livemediahost.com"], "10": "https://media.livemediahost.com/thumbs/1800/sweetreya.jpg?cb=1795050210", "11": 98, "12": 6171, "13": 1, "15": "https://media.livemediahost.com/media/offline_picture/1800/sweetreya.20260225025930.jpg"}}, {"width": 1280, "height": 720, "last_on": "2026-11-19 00:32:15", "tpl": [47156558, "cocowantsyouu", "cocowantsyouu", "connected", 13, 81.95, "HELLO LOVES LETS HAVE HARDCORE STRAP ON FUCK WITH THE 2 WILD CHOCOS...", "", "f", ["edge1-fld.livemediahost.com", "edge2-fld.livemediahost.com"], "https://media.livemediahost.com/thumbs/4715/cocowantsyouu.jpg?cb=1795050210", 88.79, 2844, 1]}, {"width": 1280, "height": 720, "last_on": "2026-11-19 00:39:06", "spy_allowed": 1, "top_pvt": 1, "tpl": {"0": 35559390, "1": "thelittlenative", "2": "Little Native", "3": "private", "4": 149, "5": 104.38, "6": "\u2b50 Your Good girl - Private Open! \u2b50 Squirt at Goal! \u2b50 X...", "7": "", "8": "f", "9": ["edge3-cod.livemediahost.com", "edge7-ild.livemediahost.com"], "10": "https://media.livemediahost.com/thumbs/3555/thelittlenative.jpg?cb=1795050210", "11": 99.45, "12": 2867, "13": 1, "15": "https://media.livemediahost.com/media/offline_picture/3555/thelittlenative.20250928003320.jpg"}}], "status": true}"""
    }
}
