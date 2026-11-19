package dev.local.labelscanner.signal

/** Scalar Kalman filter for smoothing RSSI. [q] = process noise, [r] = measurement noise. */
class Kalman1D(
    var q: Double = 0.1,
    var r: Double = 16.0,
    initialEstimate: Double? = null,
    initialError: Double = 9.0,
) {
    var estimate: Double? = initialEstimate
        private set
    private var p = initialError

    fun update(measurement: Double): Double {
        val prior = estimate ?: measurement
        val predictedError = p + q
        val gain = predictedError / (predictedError + r)
        val next = prior + gain * (measurement - prior)
        estimate = next
        p = (1.0 - gain) * predictedError
        return next
    }
}
