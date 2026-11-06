package dev.pfeed

import dev.pfeed.source.parse.JsonPath
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonPathTest {
    private val root = Json.parseToJsonElement(
        """{"a":{"b":[{"c":"hit"},{"c":"miss"}]},"n":null,"num":5}""",
    )

    @Test fun `navigates objects and array indices`() {
        assertEquals("hit", JsonPath.string(root, "a.b.0.c"))
        assertEquals("miss", JsonPath.string(root, "a.b.1.c"))
    }

    @Test fun `blank path returns root`() {
        assertEquals(root, JsonPath.select(root, ""))
    }

    @Test fun `missing or wrong-type paths return null`() {
        assertNull(JsonPath.string(root, "a.b.9.c"))
        assertNull(JsonPath.string(root, "a.x"))
        assertNull(JsonPath.string(root, "n"))
    }

    @Test fun `array returns elements, empty on miss`() {
        assertEquals(2, JsonPath.array(root, "a.b").size)
        assertEquals(0, JsonPath.array(root, "a.c").size)
    }

    @Test fun `primitive content as string`() {
        assertEquals("5", JsonPath.string(root, "num"))
    }
}
