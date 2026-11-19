package dev.local.labelscanner.signal

/**
 * Picks the device with the strongest average filtered RSSI.
 *
 * With [hysteresisDb] > 0 the previous winner is kept unless a challenger beats it by at
 * least that many dB, which stops the result flickering between two similar labels.
 */
class NearestDeviceDetector(var hysteresisDb: Double = 0.0) {
    data class Result(val deviceId: String, val meanRssi: Double)

    private var lastWinner: String? = null

    fun evaluate(snapshot: Map<String, List<Sample>>): Result? {
        val averages = snapshot.mapNotNull { (id, samples) ->
            if (samples.isEmpty()) null else Result(id, samples.map { it.rssi }.average())
        }
        val best = averages.maxByOrNull { it.meanRssi } ?: return null
        val previous = lastWinner?.let { w -> averages.firstOrNull { it.deviceId == w } }
        val winner =
            if (previous != null && previous.deviceId != best.deviceId &&
                best.meanRssi - previous.meanRssi < hysteresisDb.coerceAtLeast(0.0)
            ) previous else best
        lastWinner = winner.deviceId
        return winner
    }

    fun reset() {
        lastWinner = null
    }
}
