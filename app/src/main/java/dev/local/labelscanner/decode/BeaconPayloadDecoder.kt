package dev.local.labelscanner.decode

import dev.local.labelscanner.util.toHex
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES key material for encrypted label frames. Supplied by the user; nothing is built in. */
class AesKey(val key: ByteArray, val iv: ByteArray) {
    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key must be 16, 24 or 32 bytes" }
        require(iv.size == 16) { "IV must be 16 bytes" }
    }
}

enum class FrameFormat { LEGACY_BEACON, PLAIN, DECRYPTED }

sealed interface DecodeResult {
    data class Decoded(val linkId: String, val format: FrameFormat) : DecodeResult

    /** A well-formed encrypted frame, but no key is configured. */
    data class Encrypted(val payloadHex: String) : DecodeResult

    data class Invalid(val reason: String) : DecodeResult
}

/**
 * Decodes a raw BLE advertisement (ScanRecord.getBytes()) from an electronic shelf label.
 *
 * Frame layout inside the 16-bit Service Data AD structure (type 0x16):
 *   [0..1]  service UUID, little-endian (0xFC8C -> 8C FC)
 *   [2]     frame version, must be 0x01
 *   [3]     flags; bits 1-2 = encryption type (0 = AES-CBC, 1 = unsupported)
 *   [4..19] 16-byte payload (plain or AES-CBC encrypted)
 *
 * Payload (after decryption, if needed):
 *   [5..9]  40-bit Link ID (if the first byte is 0x00, the remaining 4 bytes are shown as hex)
 *   [13]    marker byte 0xCA
 *   [14..15] CRC-16 over bytes 0..13, so the CRC over all 16 bytes is 0
 */
class BeaconPayloadDecoder(private val key: AesKey? = null) {

    fun decode(record: ByteArray): DecodeResult {
        if (record.size > 13 && record[5] == LEGACY_BEACON_TYPE) {
            return DecodeResult.Decoded(record.copyOfRange(10, 14).toHex().uppercase(), FrameFormat.LEGACY_BEACON)
        }

        val serviceData = findServiceData(record)
            ?: return DecodeResult.Invalid("no service data")
        if (serviceData.size < FRAME_LENGTH) {
            return DecodeResult.Invalid("service data too short (${serviceData.size} bytes)")
        }
        if (serviceData[2] != FRAME_VERSION) {
            return DecodeResult.Invalid("unsupported frame version 0x%02x".format(serviceData[2]))
        }
        val encryptionType = ((serviceData[3].toInt() and 0xFF) ushr 1) and 0x03
        val payload = serviceData.copyOfRange(4, FRAME_LENGTH)

        parseLinkId(payload)?.let { return DecodeResult.Decoded(it, FrameFormat.PLAIN) }

        return when (encryptionType) {
            0 -> {
                val k = key ?: return DecodeResult.Encrypted(payload.toHex())
                val plain = try {
                    decrypt(payload, k)
                } catch (e: Exception) {
                    return DecodeResult.Invalid("decryption failed: ${e.message}")
                }
                parseLinkId(plain)?.let { DecodeResult.Decoded(it, FrameFormat.DECRYPTED) }
                    ?: DecodeResult.Invalid("checksum failed after decryption (wrong key or IV?)")
            }
            1 -> DecodeResult.Invalid("encryption type 1 is not supported")
            else -> DecodeResult.Invalid("unknown encryption type $encryptionType")
        }
    }

    internal fun parseLinkId(plain: ByteArray): String? {
        if (plain.size != PAYLOAD_LENGTH) return null
        if (Crc16.compute(plain) != 0) return null
        if (plain[13] != MARKER) return null
        val idBytes = plain.copyOfRange(5, 10)
        return if (idBytes[0] == 0.toByte()) {
            idBytes.copyOfRange(1, 5).toHex().uppercase()
        } else {
            LinkId.format(BigInteger(1, idBytes).toLong())
        }
    }

    private fun decrypt(data: ByteArray, k: AesKey): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k.key, "AES"), IvParameterSpec(k.iv))
        return cipher.doFinal(data)
    }

    companion object {
        private const val LEGACY_BEACON_TYPE = 0xC7.toByte()
        private const val FRAME_VERSION = 0x01.toByte()
        private const val MARKER = 0xCA.toByte()
        private const val PAYLOAD_LENGTH = 16
        private const val FRAME_LENGTH = 4 + PAYLOAD_LENGTH
        private const val SERVICE_DATA_16 = 0x16

        /**
         * Walks the AD structures and returns the Service Data contents (UUID onwards).
         * Prefers the label service UUID 0xFC8C; falls back to the first Service Data found.
         */
        internal fun findServiceData(record: ByteArray): ByteArray? {
            var first: ByteArray? = null
            var i = 0
            while (i < record.size) {
                val len = record[i].toInt() and 0xFF
                if (len == 0 || i + len >= record.size) break
                val type = record[i + 1].toInt() and 0xFF
                if (type == SERVICE_DATA_16 && len >= 3) {
                    val data = record.copyOfRange(i + 2, i + 1 + len)
                    if (data[0] == 0x8C.toByte() && data[1] == 0xFC.toByte()) return data
                    if (first == null) first = data
                }
                i += len + 1
            }
            return first
        }
    }
}
