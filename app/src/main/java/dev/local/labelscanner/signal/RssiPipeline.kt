package dev.local.labelscanner.signal

data class Sample(val timeMs: Long, val rssi: Int)

/**
 * Per-device RSSI history with adaptive Kalman smoothing.
 *
 * Each device gets its own filter. Once a device has 5+ raw samples in the retention
 * window, the measurement noise R is re-tuned to the variance of its last 10 readings
 * (clamped to 4..50), so noisy labels are smoothed harder than steady ones.
 */
class RssiPipeline(
    private val retentionMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val raw = HashMap<String, MutableList<Sample>>()
    private val filtered = HashMap<String, MutableList<Sample>>()
    private val filters = HashMap<String, Kalman1D>()

    /** Adds a reading and returns the filtered RSSI, or null if the reading is invalid. */
    fun ingest(deviceId: String, rssi: Int): Int? {
        if (rssi >= 0 || rssi == 127) return null
        val now = clock()
        synchronized(lock) {
            val filter = filters.getOrPut(deviceId) {
                Kalman1D(q = 0.5, r = 16.0, initialEstimate = rssi.toDouble(), initialError = 9.0)
            }

            val history = raw.getOrPut(deviceId) { ArrayList() }
            history.add(Sample(now, rssi))
            history.removeAll { it.timeMs < now - retentionMs }
            if (history.size >= 5) {
                val recent = history.takeLast(10).map { it.rssi.toDouble() }
                val mean = recent.average()
                val variance = recent.sumOf { (it - mean) * (it - mean) } / recent.size
                filter.r = variance.coerceIn(4.0, 50.0)
                filter.q = 0.5
            }

            val value = filter.update(rssi.toDouble()).toInt()
            val out = filtered.getOrPut(deviceId) { ArrayList() }
            if (out.isNotEmpty() && out.last().timeMs == now) out[out.lastIndex] = Sample(now, value)
            else out.add(Sample(now, value))
            out.removeAll { it.timeMs < now - retentionMs }
            return value
        }
    }

    /** Filtered samples newer than [cutoffMs], per device. Devices with no recent samples are omitted. */
    fun snapshot(cutoffMs: Long): Map<String, List<Sample>> = synchronized(lock) {
        pruneStale()
        buildMap {
            for ((id, samples) in filtered) {
                val recent = samples.filter { it.timeMs >= cutoffMs }
                if (recent.isNotEmpty()) put(id, recent)
            }
        }
    }

    fun clear() = synchronized(lock) {
        raw.clear()
        filtered.clear()
        filters.clear()
    }

    private fun pruneStale() {
        val limit = clock() - retentionMs
        val stale = filtered.filterValues { list -> list.isEmpty() || list.last().timeMs < limit }.keys
        for (id in stale) {
            filtered.remove(id)
            raw.remove(id)
            filters.remove(id)
        }
    }
}
