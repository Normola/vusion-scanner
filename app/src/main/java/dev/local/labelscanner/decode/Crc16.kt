package dev.local.labelscanner.decode

/**
 * CRC-16 with polynomial 0x8005, initial value 0xFFFF, no reflection and no final XOR
 * (the CRC-16/CMS variant).
 *
 * A frame whose last two bytes hold the big-endian CRC of the preceding bytes
 * produces 0 when the whole frame is run through this function.
 */
object Crc16 {
    private const val POLY = 0x8005
    private const val INIT = 0xFFFF

    fun compute(data: ByteArray, length: Int = data.size): Int {
        var crc = INIT
        for (k in 0 until length) {
            var b = data[k].toInt() and 0xFF
            repeat(8) {
                val topBit = (crc and 0x8000) ushr 8
                crc = if ((topBit xor (b and 0x80)) != 0) (crc shl 1) xor POLY else crc shl 1
                crc = crc and 0xFFFF
                b = b shl 1
            }
        }
        return crc
    }
}
