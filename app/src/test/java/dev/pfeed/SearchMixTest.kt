package dev.pfeed

import dev.pfeed.data.repo.SearchRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMixTest {
    @Test
    fun `best first, never more than two in a row from one source while others remain`() {
        val sorted = (1..6).map { item("a$it", source = "redgifs") } + (1..3).map { item("b$it", source = "tube") }
        val out = SearchRepository.mixForTest(sorted)
        assertEquals(sorted.size, out.size)
        assertEquals("a1", out[0].id) // the best stays first
        for (i in 0 until out.size - 2) {
            val run = out.subList(i, i + 3).map { it.sourceId }.distinct().size == 1
            // A run of three is only allowed once no other source is left to break it up.
            if (run) assertTrue("run of three at $i: ${out.map { it.id }}", out.drop(i + 3).all { it.sourceId == out[i].sourceId })
        }
        // Within a source the order is kept.
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5", "a6"), out.filter { it.sourceId == "redgifs" }.map { it.id })
    }
}
