package dev.local.labelscanner.decode

import org.junit.Assert.assertEquals
import org.junit.Test

class Crc16Test {
    @Test
    fun `matches CRC-16 CMS check value`() {
        assertEquals(0xAEE7, Crc16.compute("123456789".toByteArray()))
    }

    @Test
    fun `frame with appended big-endian CRC checks to zero`() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 0x7F, -1, 0x40)
        val crc = Crc16.compute(data)
        val frame = data + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
        assertEquals(0, Crc16.compute(frame))
    }
}
