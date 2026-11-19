package dev.local.labelscanner.decode

/**
 * Link IDs are 40-bit label identifiers shown as 8 base-32 characters with a dash,
 * e.g. "1H-234567" or "w322-CFGH". Two alphabets exist: the current one and a legacy one.
 * The dash position depends on the label model prefix.
 */
object LinkId {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val ALPHABET_LEGACY = "23456789CFGHJMPQRVWXcfghjmpqrvwx"
    private const val LENGTH = 8

    /** Model codes that indicate a legacy-alphabet ID. */
    private val LEGACY_MODELS = listOf(
        "ED9", "Y100", "Y200", "Y201", "Y202", "Y203", "Y204", "Y300", "Y400", "Y401",
        "Y402", "Y600", "Y700", "Z000", "XZM", "Y20M", "XZN", "Y500", "Y205",
    )

    /** Legacy-alphabet prefixes; also where the dash goes for legacy IDs. */
    private val LEGACY_PREFIXES = listOf(
        "PMF", "w322", "w422", "w423", "w424", "w425", "w426", "w522", "w622", "w623",
        "w624", "w822", "w922", "x222", "vxc", "w42c", "vxf", "w722", "w427",
    )

    /** Current-generation model prefixes; the dash goes after the prefix. */
    private val GEN3_PREFIXES = listOf(
        "YCA9", "4Z1", "4Z0", "Z003", "Z001", "Z002", "Z005", "Y101", "Y102", "4H1",
        "4H4", "Y0Z1", "4H0", "4H2", "4H3", "4H5", "Y0Z0", "Y0ZG", "Y209", "Y201",
        "0G", "4J3", "4J5", "Y210", "4J0", "4J1", "4J2", "4J4", "Y206", "Y207",
        "Y208", "Y20D", "Y20E", "4K0", "Y20N", "Y414", "Y409", "Y501", "Y305", "Y306",
        "Y301", "Y302", "Y511", "Y303", "Y420", "Y40F", "Y40G", "Y40K", "4Q3", "Y41B",
        "Y40A", "Y415", "4Q0", "Y512", "Y408", "Y304", "Y307", "Y407", "Y40D", "Y40C",
        "Y40J", "Y406", "Y40B", "Y40H", "Y411", "Y410", "Y40E", "4R0", "Y41C", "Y41D",
        "Y502", "Y503", "Y601", "Y701", "Y611", "Y603", "Y604", "Y703", "Y602", "Y811",
        "Y810", "Y706", "Y800", "Y710", "Y704", "Y705", "Y900", "Y702", "Z004", "EDA",
        "4G0",
    )

    private val GEN3_CLASS_A = Regex("^[0-3][GHJKMNPQRSTVWXYZ][0-9ABCDEFGHJKMNPQRSTVWXYZ]{6}$")
    private val GEN3_CLASS_B = Regex(
        "^([4-9ABCDEF][GHJKMNPQRSTVWXYZ][0-9ABCDEFGHJKMNPQRSTVWXYZ]|[GHJKMNPQRSTVWX][0-9ABCDEFGHJKMNPQRSTVWXYZ]{2})[0-9ABCDEFGHJKMNPQRSTVWXYZ]{5}$"
    )

    fun isLegacy(id: String): Boolean =
        LEGACY_MODELS.any { id.startsWith(it) } || LEGACY_PREFIXES.any { id.startsWith(it) }

    /** Encodes the low 40 bits of [value] as 8 characters of [alphabet]. */
    fun encode(value: Long, alphabet: String = ALPHABET): String {
        var v = value
        val out = CharArray(LENGTH)
        for (i in LENGTH - 1 downTo 0) {
            out[i] = alphabet[Math.floorMod(v, 32L).toInt()]
            v = Math.floorDiv(v, 32L)
        }
        return String(out)
    }

    /** Parses a Link ID (with or without dash) back to its numeric value. */
    fun decode(id: String): Long {
        val clean = id.trim().replace("-", "")
        val alphabet = if (isLegacy(clean)) ALPHABET_LEGACY else ALPHABET
        var v = 0L
        for (c in clean) {
            val idx = alphabet.indexOf(c)
            require(idx >= 0) { "Invalid Link ID character '$c' in \"$id\"" }
            v = v * 32 + idx
        }
        return v
    }

    /** The undashed ID: legacy alphabet if that produces a legacy ID, otherwise the current alphabet. */
    fun toLinkId(value: Long): String {
        val legacy = encode(value, ALPHABET_LEGACY)
        return if (isLegacy(legacy)) legacy else encode(value, ALPHABET)
    }

    /** The display form with the dash in the model-dependent position. */
    fun format(value: Long): String {
        val id = toLinkId(value)
        return if (isLegacy(id)) addDash(encode(value, ALPHABET_LEGACY), LEGACY_PREFIXES)
        else addDash(id, GEN3_PREFIXES)
    }

    internal fun addDash(id: String, prefixes: List<String>): String {
        prefixes.firstOrNull { id.startsWith(it) }?.let {
            return id.substring(0, it.length) + "-" + id.substring(it.length)
        }
        val split = when {
            GEN3_CLASS_A.matches(id) -> 2
            GEN3_CLASS_B.matches(id) -> 3
            else -> 4
        }
        return id.substring(0, split) + "-" + id.substring(split)
    }
}
