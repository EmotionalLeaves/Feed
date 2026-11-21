package dev.pfeed

import dev.pfeed.domain.filter.MinorsFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestFilterTest {
    @Test
    fun `search suggestions that could mean a minor are dropped`() {
        assertTrue(MinorsFilter.allowsText("redhead anal"))
        assertTrue(MinorsFilter.allowsText("teen 18 redhead"))
        assertFalse(MinorsFilter.allowsText("young redhead"))
        assertFalse(MinorsFilter.allowsText("16yo redhead"))
        assertFalse(MinorsFilter.allowsText("high school"))
    }
}
