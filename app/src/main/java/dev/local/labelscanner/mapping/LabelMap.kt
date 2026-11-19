package dev.local.labelscanner.mapping

data class LabelLocation(val aisle: String, val modular: String?)

/**
 * Your own mapping from label to aisle/modular, loaded from a CSV file.
 *
 * Each row's id is either a Link ID (dash optional) or a MAC address / MAC prefix
 * such as "AA:BB:CC". Link IDs are matched exactly (legacy IDs are case-sensitive);
 * MACs are matched case-insensitively by prefix.
 */
class LabelMap private constructor(
    private val byLinkId: Map<String, LabelLocation>,
    private val byMacPrefix: List<Pair<String, LabelLocation>>,
) {
    val size: Int get() = byLinkId.size + byMacPrefix.size

    fun lookup(linkId: String?, mac: String?): LabelLocation? {
        if (linkId != null) byLinkId[normalizeLinkId(linkId)]?.let { return it }
        if (mac != null) {
            val upper = mac.uppercase()
            byMacPrefix.firstOrNull { upper.startsWith(it.first) }?.let { return it.second }
        }
        return null
    }

    data class ParseResult(val map: LabelMap, val skippedLines: List<Int>)

    companion object {
        val EMPTY = LabelMap(emptyMap(), emptyList())

        private val MAC_LIKE = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){1,5}:?$")
        private val HEADER_NAMES = setOf("id", "linkid", "link_id", "mac")

        fun normalizeLinkId(id: String) = id.trim().replace("-", "")

        /**
         * Parses `id,aisle[,modular]` rows. Blank lines and lines starting with '#' are
         * ignored, as is a header row whose first column is id/linkId/mac.
         * Commas inside values are not supported.
         */
        fun parseCsv(text: String): ParseResult {
            val byLinkId = LinkedHashMap<String, LabelLocation>()
            val byMac = ArrayList<Pair<String, LabelLocation>>()
            val skipped = ArrayList<Int>()

            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val cols = line.split(',').map { it.trim() }
                if (index == 0 && cols[0].lowercase() in HEADER_NAMES) return@forEachIndexed
                if (cols.size < 2 || cols[0].isEmpty() || cols[1].isEmpty()) {
                    skipped += index + 1
                    return@forEachIndexed
                }
                val location = LabelLocation(cols[1], cols.getOrNull(2)?.takeIf { it.isNotEmpty() })
                if (MAC_LIKE.matches(cols[0])) {
                    byMac += cols[0].uppercase().trimEnd(':') to location
                } else {
                    byLinkId[normalizeLinkId(cols[0])] = location
                }
            }
            byMac.sortByDescending { it.first.length } // most specific prefix wins
            return ParseResult(LabelMap(byLinkId, byMac), skipped)
        }
    }
}
