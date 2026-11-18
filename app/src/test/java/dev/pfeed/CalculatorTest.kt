package dev.pfeed

import dev.pfeed.ui.lock.Calc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {
    private fun run(vararg keys: String) = keys.fold(Calc()) { c, k -> c.press(k) }

    @Test
    fun calculates() {
        assertEquals("5", run("2", "+", "3", "=").display)
        assertEquals("6", run("2", "+", "3", "−", "1", "+", "2", "=").display)
        assertEquals("2.5", run("5", "÷", "2", "=").display)
        assertEquals("Error", run("5", "÷", "0", "=").display)
        assertEquals("0.12", run("1", "2", "%").display)
        assertEquals("-7", run("7", "±").display)
    }

    @Test
    fun knowsAPinFromASum() {
        assertTrue(run("1", "2", "3", "4").isPlainNumber())
        assertTrue(!run("1", "+", "2").isPlainNumber())
    }
}
