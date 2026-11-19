package dev.local.labelscanner

import android.content.Context
import dev.local.labelscanner.decode.AesKey
import dev.local.labelscanner.mapping.LabelMap
import dev.local.labelscanner.util.parseHexOrNull
import java.io.File

data class ScannerSettings(
    val keyHex: String = "",
    val ivHex: String = "",
    val labelsOnly: Boolean = true,
    val hysteresisDb: Double = 2.0,
)

/** Local-only persistence. The key is stored in app-private preferences on the device. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("scanner", Context.MODE_PRIVATE)
    private val mapFile = File(context.filesDir, "label-map.csv")

    fun load() = ScannerSettings(
        keyHex = prefs.getString(KEY_HEX, "") ?: "",
        ivHex = prefs.getString(IV_HEX, "") ?: "",
        labelsOnly = prefs.getBoolean(LABELS_ONLY, true),
        hysteresisDb = prefs.getFloat(HYSTERESIS, 2.0f).toDouble(),
    )

    fun save(s: ScannerSettings) {
        prefs.edit()
            .putString(KEY_HEX, s.keyHex)
            .putString(IV_HEX, s.ivHex)
            .putBoolean(LABELS_ONLY, s.labelsOnly)
            .putFloat(HYSTERESIS, s.hysteresisDb.toFloat())
            .apply()
    }

    fun loadLabelMap(): LabelMap =
        if (mapFile.exists()) LabelMap.parseCsv(mapFile.readText()).map else LabelMap.EMPTY

    fun saveLabelMapCsv(text: String) = mapFile.writeText(text)

    companion object {
        private const val KEY_HEX = "aes_key_hex"
        private const val IV_HEX = "aes_iv_hex"
        private const val LABELS_ONLY = "labels_only"
        private const val HYSTERESIS = "hysteresis_db"

        /**
         * Returns the key (null if both fields are blank), or throws IllegalArgumentException
         * with a user-facing message if the input is malformed.
         */
        fun parseKey(keyHex: String, ivHex: String): AesKey? {
            if (keyHex.isBlank() && ivHex.isBlank()) return null
            val key = parseHexOrNull(keyHex) ?: throw IllegalArgumentException("Key is not valid hex")
            val iv = parseHexOrNull(ivHex) ?: throw IllegalArgumentException("IV is not valid hex")
            return AesKey(key, iv)
        }
    }
}
