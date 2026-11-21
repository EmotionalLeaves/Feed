package dev.pfeed

import dev.pfeed.domain.reco.FollowedSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowedSearchTest {
    @Test
    fun `a followed search keeps posts with every one of its words`() {
        val tag = FollowedSearch.PREFIX + "redhead pov"
        assertTrue(FollowedSearch.isOne(tag))
        assertEquals("redhead pov", FollowedSearch.query(tag))
        assertTrue(FollowedSearch.matches(item("1", title = "POV: the redhead next door"), tag))
        assertTrue(FollowedSearch.matches(item("2", title = "Next door", tags = listOf("Redhead", "pov")), tag))
        assertFalse(FollowedSearch.matches(item("3", title = "Redhead in the kitchen"), tag))
        // Whole words only: "povray" is not "pov".
        assertFalse(FollowedSearch.matches(item("4", title = "Redhead povray render"), tag))
        assertFalse(FollowedSearch.isOne("redhead"))
    }
}
