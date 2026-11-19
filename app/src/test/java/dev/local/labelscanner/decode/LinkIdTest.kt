package dev.local.labelscanner.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkIdTest {
    @Test
    fun `encode and decode round trip`() {
        val value = LinkId.decode("1H234567")
        assertEquals("1H234567", LinkId.encode(value))
    }

    @Test
    fun `class A id gets dash after two characters`() {
        assertEquals("1H-234567", LinkId.format(LinkId.decode("1H234567")))
    }

    @Test
    fun `known gen3 prefix sets dash position`() {
        assertEquals("0G-123456", LinkId.format(LinkId.decode("0G123456")))
    }

    @Test
    fun `legacy id uses legacy alphabet and prefix`() {
        val value = LinkId.decode("w322CFGH")
        assertTrue(LinkId.isLegacy(LinkId.toLinkId(value)))
        assertEquals("w322-CFGH", LinkId.format(value))
    }

    @Test
    fun `dash is ignored when decoding`() {
        assertEquals(LinkId.decode("1H234567"), LinkId.decode("1H-234567"))
        assertFalse(LinkId.isLegacy("1H234567"))
    }
}
