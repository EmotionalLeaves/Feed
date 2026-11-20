package dev.pfeed

import dev.pfeed.domain.reco.CommentMoments
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentMomentsTest {
    @Test
    fun `the time most people point to comes first`() {
        val comments = listOf(
            "a" to "12:31 omg",
            "b" to "best part at 12:40!!",
            "c" to "4:05 is good too",
            "d" to "12:35 holy",
            "e" to "nothing here, 100% real",
        )
        assertEquals(listOf(751_000L, 245_000L), CommentMoments.of(comments, durationSec = 20 * 60))
    }

    @Test
    fun `hours, impossible times and times past the end`() {
        val comments = listOf("a" to "1:02:15 finally", "b" to "at 9:75 lol", "c" to "45:00 nope")
        // One mention each: earliest first; 9:75 isn't a time.
        assertEquals(listOf(2_700_000L, 3_735_000L), CommentMoments.of(comments, durationSec = 70 * 60))
        assertEquals(emptyList<Long>(), CommentMoments.of(listOf("x" to "45:00"), durationSec = 30 * 60))
    }
}
