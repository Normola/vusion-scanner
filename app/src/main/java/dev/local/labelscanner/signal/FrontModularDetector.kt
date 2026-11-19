package dev.local.labelscanner.signal

import kotlin.math.sqrt

data class FrontModularConfig(
    val evaluationIntervalMs: Long = 1_200,
    val windowMs: Long = 5_000,
    val minSamples: Int = 2,
    val minMeanRssi: Int = -80,
    /** Required lead (dB) of the best label over the runner-up. */
    val marginToSecondDb: Int = 2,
    /** Required lead when the best label is strong (mean > -60 dBm). */
    val strongSignalMarginDb: Int = 3,
    /** Score = mean - stdevPenalty * stdev, so jumpy signals rank lower. */
    val stdevPenalty: Double = 0.7,
    /** Consecutive winning evaluations before a new front modular is reported. */
    val dwellWindows: Int = 2,
    /** Consecutive failed evaluations before the current front modular is reported lost. */
    val lostWindows: Int = 3,
    /** Only consider labels in the current nearest aisle. */
    val restrictToLastKnownAisle: Boolean = true,
)

sealed interface FrontEvent {
    data class Updated(
        val deviceId: String,
        val aisle: String,
        val modular: String,
        val meanRssi: Int,
        val confidence: Float,
    ) : FrontEvent

    data class Lost(val deviceId: String, val aisle: String?, val modular: String?, val reason: String) : FrontEvent
}

/**
 * Decides which shelf unit ("modular") the phone is facing.
 *
 * Every evaluation ranks the labels by penalised mean RSSI, then requires the leader to be
 * strong enough and far enough ahead of the runner-up. A new winner must hold for
 * [FrontModularConfig.dwellWindows] evaluations before it is reported; repeated failures
 * for [FrontModularConfig.lostWindows] evaluations report it lost.
 */
class FrontModularDetector(private val config: FrontModularConfig = FrontModularConfig()) {

    private data class Candidate(
        val deviceId: String,
        val aisle: String,
        val modular: String,
        val mean: Double,
        val stdev: Double,
        val score: Double,
        val samples: Int,
    )

    private var lastFrontId: String? = null
    private var lastEmittedId: String? = null
    private var winCount = 0
    private var missCount = 0

    fun evaluate(
        snapshot: Map<String, List<Sample>>,
        aisleHint: String?,
        aisleOf: (String) -> String?,
        modularOf: (String) -> String?,
    ): FrontEvent? {
        val candidates = snapshot.mapNotNull { (id, samples) ->
            if (samples.size < config.minSamples) return@mapNotNull null
            val aisle = aisleOf(id) ?: return@mapNotNull null
            if (aisleHint != null && aisleHint != aisle) return@mapNotNull null
            val values = samples.map { it.rssi.toDouble() }
            val mean = values.average()
            val stdev = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
            Candidate(id, aisle, modularOf(id) ?: "unknown", mean, stdev, mean - config.stdevPenalty * stdev, samples.size)
        }.sortedByDescending { it.score }

        if (candidates.isEmpty()) {
            return miss(aisleOf, modularOf, if (snapshot.isEmpty()) "no samples" else "no candidates")
        }

        val best = candidates[0]
        val second = candidates.getOrNull(1)
        val margin = if (second != null) best.mean - second.mean else 99.0
        val requiredMargin = if (best.mean > -60.0) config.strongSignalMarginDb else config.marginToSecondDb
        if (best.mean < config.minMeanRssi || margin < requiredMargin) {
            return miss(aisleOf, modularOf, "gate failed")
        }

        if (lastFrontId == best.deviceId) {
            winCount++
        } else {
            lastFrontId = best.deviceId
            winCount = 1
        }
        missCount = 0

        if (winCount < config.dwellWindows || lastEmittedId == best.deviceId) return null
        lastEmittedId = best.deviceId
        return FrontEvent.Updated(best.deviceId, best.aisle, best.modular, best.mean.toInt(), confidence(best, margin))
    }

    fun reset() {
        lastFrontId = null
        lastEmittedId = null
        winCount = 0
        missCount = 0
    }

    private fun miss(aisleOf: (String) -> String?, modularOf: (String) -> String?, reason: String): FrontEvent? {
        missCount++
        if (missCount < config.lostWindows) return null
        val id = lastFrontId ?: run {
            missCount = 0
            return null
        }
        reset()
        return FrontEvent.Lost(id, aisleOf(id), modularOf(id), reason)
    }

    /** 45% signal strength, 45% lead over the runner-up, 10% sample count. */
    private fun confidence(best: Candidate, margin: Double): Float {
        fun clamp01(v: Double) = if (v.isNaN()) 0.0 else v.coerceIn(0.0, 1.0)
        val signal = clamp01((best.mean - config.minMeanRssi) / 20.0)
        val separation = clamp01(margin / config.marginToSecondDb)
        val samples = clamp01((best.samples - config.minSamples) / 3.0)
        return (0.45 * signal + 0.45 * separation + 0.1 * samples).toFloat()
    }
}
