package dev.pfeed

import dev.pfeed.source.net.EncryptedDns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class EncryptedDnsTest {
    @Test
    fun `sinkhole answers count as blocked, real ones don't`() {
        assertTrue(EncryptedDns.looksBlocked(InetAddress.getByName("0.0.0.0")))
        assertTrue(EncryptedDns.looksBlocked(InetAddress.getByName("127.0.0.1")))
        assertTrue(EncryptedDns.looksBlocked(InetAddress.getByName("10.10.34.35")))
        assertFalse(EncryptedDns.looksBlocked(InetAddress.getByName("66.254.114.41")))
    }

    @Test
    fun `parses a dns-json answer`() {
        val body = """{"Status":0,"Answer":[{"name":"x.com","type":5,"TTL":60,"data":"y.com."},{"name":"y.com","type":1,"TTL":120,"data":"93.184.216.34"},{"name":"y.com","type":1,"TTL":90,"data":"93.184.216.35"}]}"""
        val (ips, ttl) = EncryptedDns.parse("x.com", body, 1)
        assertEquals(listOf("93.184.216.34", "93.184.216.35"), ips.map { it.hostAddress })
        assertEquals(90, ttl)
    }
}
