package dev.pfeed

import dev.pfeed.update.Updater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test
    fun comparesVersions() {
        assertTrue(Updater.isNewer("4.81.0", "4.80.0"))
        assertTrue(Updater.isNewer("v4.80.1", "4.80.0"))
        assertTrue(Updater.isNewer("5.0", "4.99.9"))
        assertTrue(Updater.isNewer("4.100.0", "4.99.0"))
        assertFalse(Updater.isNewer("4.80.0", "4.80.0"))
        assertFalse(Updater.isNewer("4.79.9", "4.80.0"))
        assertFalse(Updater.isNewer("4.80", "4.80.0"))
    }
}
