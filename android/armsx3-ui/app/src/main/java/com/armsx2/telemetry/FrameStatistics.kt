package com.armsx2.telemetry

/** One drain interval, measured from native guest flips, never Android UI frames. */
internal class FrameStatistics {
    private val intervals = ArrayList<Double>()
    var frames = 0; private set
    fun add(durationNs: Long) {
        frames++
        if (durationNs > 0) intervals.add(durationNs / 1_000_000.0)
    }
    fun percentile(percent: Double): Double? {
        if (intervals.isEmpty()) return null
        val sorted = intervals.sorted()
        val index = kotlin.math.ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }
    fun fps(elapsedNs: Long): Double? = if (elapsedNs > 0) frames * 1e9 / elapsedNs else null
}
