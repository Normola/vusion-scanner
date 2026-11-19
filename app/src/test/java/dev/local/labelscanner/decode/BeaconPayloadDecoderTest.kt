package dev.local.labelscanner.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BeaconPayloadDecoderTest {

    // Arbitrary test key. Not a real deployment key.
    private val testKey = AesKey(ByteArray(16) { it.toByte() }, ByteArray(16) { (0x10 + it).toByte() })

    /** Builds a valid 16-byte plaintext payload carrying the given 5 Link ID bytes. */
    private fun payload(idBytes: ByteArray): ByteArray {
        require(idBytes.size == 5)
        val p = ByteArray(16)
        p[0] = 0x11; p[1] = 0x22; p[2] = 0x33; p[3] = 0x44; p[4] = 0x55
        idBytes.copyInto(p, 5)
        p[10] = 0x01; p[11] = 0x02; p[12] = 0x03
        p[13] = 0xCA.toByte()
        val crc = Crc16.compute(p, 14)
        p[14] = (crc ushr 8).toByte()
        p[15] = crc.toByte()
        return p
    }

    /** Wraps a payload in a raw advertisement: Flags AD + Service Data AD for 0xFC8C. */
    private fun record(payload: ByteArray, encryptionType: Int): ByteArray {
        val serviceData = byteArrayOf(0x8C.toByte(), 0xFC.toByte(), 0x01, (encryptionType shl 1).toByte()) + payload
        return byteArrayOf(0x02, 0x01, 0x06) +
            byteArrayOf((serviceData.size + 1).toByte(), 0x16) + serviceData +
            ByteArray(8) // trailing zero padding, as Android returns it
    }

    private fun encrypt(data: ByteArray, k: AesKey): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.key, "AES"), IvParameterSpec(k.iv))
            doFinal(data)
        }

    private val hexId = byteArrayOf(0x00, 0x12, 0x34, 0xAB.toByte(), 0xCD.toByte())

    @Test
    fun `plain frame decodes without a key`() {
        val result = BeaconPayloadDecoder().decode(record(payload(hexId), 0))
        assertEquals(DecodeResult.Decoded("1234ABCD", FrameFormat.PLAIN), result)
    }

    @Test
    fun `base32 link id is formatted with dash`() {
        val value = LinkId.decode("1H234567")
        val bytes = BigInteger.valueOf(value).toByteArray().let { b ->
            ByteArray(5 - b.size.coerceAtMost(5)) + b.takeLast(5).toByteArray()
        }
        val result = BeaconPayloadDecoder().decode(record(payload(bytes), 0))
        assertEquals(DecodeResult.Decoded("1H-234567", FrameFormat.PLAIN), result)
    }

    @Test
    fun `encrypted frame without key is reported as encrypted`() {
        val result = BeaconPayloadDecoder().decode(record(encrypt(payload(hexId), testKey), 0))
        assertTrue(result is DecodeResult.Encrypted)
    }

    @Test
    fun `encrypted frame decodes with the right key`() {
        val result = BeaconPayloadDecoder(testKey).decode(record(encrypt(payload(hexId), testKey), 0))
        assertEquals(DecodeResult.Decoded("1234ABCD", FrameFormat.DECRYPTED), result)
    }

    @Test
    fun `wrong key fails the checksum`() {
        val wrong = AesKey(ByteArray(16) { 0x55 }, testKey.iv)
        val result = BeaconPayloadDecoder(wrong).decode(record(encrypt(payload(hexId), testKey), 0))
        assertTrue(result is DecodeResult.Invalid)
    }

    @Test
    fun `encryption type 1 is unsupported`() {
        val result = BeaconPayloadDecoder(testKey).decode(record(encrypt(payload(hexId), testKey), 1))
        assertTrue(result is DecodeResult.Invalid)
    }

    @Test
    fun `legacy beacon id is read from fixed offset`() {
        val rec = ByteArray(20)
        rec[5] = 0xC7.toByte()
        rec[10] = 0x0A; rec[11] = 0x0B; rec[12] = 0x0C; rec[13] = 0x0D
        assertEquals(DecodeResult.Decoded("0A0B0C0D", FrameFormat.LEGACY_BEACON), BeaconPayloadDecoder().decode(rec))
    }

    @Test
    fun `advert without service data is invalid`() {
        val result = BeaconPayloadDecoder().decode(byteArrayOf(0x02, 0x01, 0x06, 0, 0, 0, 0))
        assertTrue(result is DecodeResult.Invalid)
    }
}
