package com.zaslon.browser.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class AndroidDohResolverTest {

    @Before
    fun resetCache() {
        AndroidDohResolver.clearCache()
    }

    @Test
    fun parsesLiteralIpv4AndIpv6WithoutDns() {
        val v4 = AndroidDohResolver.parseLiteralIpAddress("9.9.9.9")
        assertNotNull(v4)
        assertEquals("9.9.9.9", v4?.hostAddress)

        val v6 = AndroidDohResolver.parseLiteralIpAddress("[2620:fe::fe]")
        assertNotNull(v6)
        assertTrue(v6!!.address.size == 16)

        assertNull(AndroidDohResolver.parseLiteralIpAddress("999.1.2.3"))
        assertNull(AndroidDohResolver.parseLiteralIpAddress("example.com"))
    }

    @Test
    fun buildsRfc8484WireQueryAndParsesCompressedResponse() {
        val query = AndroidDohResolver.buildDnsQueryPacket("example.com", qtype = 1)
        assertEquals(0x00, query[0].toInt() and 0xFF)
        assertEquals(0x00, query[1].toInt() and 0xFF)
        assertEquals(0x01, query[2].toInt() and 0xFF) // RD = 1
        assertEquals(0x01, query[5].toInt() and 0xFF) // QDCOUNT = 1

        // Sentetik RFC 1035 yanıtı: soru + 0xC0 0x0C işaretçili A kaydı (93.184.216.34)
        val resp = ByteArrayOutputStream().apply {
            write(query.copyOfRange(0, 12))
            val hdr = toByteArray()
            hdr[2] = 0x81.toByte() // QR=1, RD=1
            hdr[3] = 0x80.toByte() // RA=1, RCODE=0
            hdr[7] = 0x01 // ANCOUNT=1
            reset()
            write(hdr)
            write(query.copyOfRange(12, query.size)) // Question section
            write(byteArrayOf(0xC0.toByte(), 0x0C)) // Name pointer to offset 12
            write(byteArrayOf(0x00, 0x01)) // Type A
            write(byteArrayOf(0x00, 0x01)) // Class IN
            write(byteArrayOf(0x00, 0x00, 0x00, 0x78)) // TTL = 120s
            write(byteArrayOf(0x00, 0x04)) // RDLENGTH = 4
            write(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        }.toByteArray()

        var calls = 0
        val addr = AndroidDohResolver.resolveHostStrict(
            host = "example.com",
            dohUrl = AndroidDohResolver.DEFAULT_DOH_URL
        ) { _, _ ->
            calls++
            resp
        }
        assertEquals("93.184.216.34", addr.hostAddress)
        assertEquals(1, calls)

        // İkinci çağrı önbellekten gelmeli (transport çağrılmamalı)
        val cached = AndroidDohResolver.resolveHostStrict("example.com") { _, _ ->
            calls++
            resp
        }
        assertEquals("93.184.216.34", cached.hostAddress)
        assertEquals(1, calls)
    }

    @Test(expected = IOException::class)
    fun rejectsOnionDomainOutsideTor() {
        AndroidDohResolver.resolveHostStrict("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion")
    }

    @Test(expected = IOException::class)
    fun failsClosedWhenDohTransportFails() {
        AndroidDohResolver.resolveHostStrict("strict-fail-closed.test") { _, _ ->
            throw IOException("DoH endpoint unreachable")
        }
    }
}
