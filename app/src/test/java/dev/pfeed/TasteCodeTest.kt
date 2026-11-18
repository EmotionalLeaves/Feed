package dev.pfeed

import dev.pfeed.domain.reco.TasteCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TasteCodeTest {
    @Test
    fun `a taste code round-trips and is found inside a message`() {
        val aff = mapOf("redhead" to 2.0, "pov" to 1.0, "cosplay" to -1.0, "type:video" to 3.0, "w:title" to 2.0)
        val code = TasteCode.encode(aff)!!
        val back = TasteCode.decode("Here's mine: $code  ❤")!!
        assertEquals(listOf("redhead" to 9, "pov" to 5), back)
        assertNull(TasteCode.decode("no code here"))
        assertEquals(2, TasteCode.blend(back).size)
    }
}
