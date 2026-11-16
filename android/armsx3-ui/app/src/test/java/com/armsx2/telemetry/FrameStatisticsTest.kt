package com.armsx2.telemetry

import org.junit.Assert.*
import org.junit.Test

class FrameStatisticsTest {
    @Test fun emptyWindowDoesNotInventFrameTimes() {
        val stats = FrameStatistics()
        assertNull(stats.percentile(95.0))
        assertNull(stats.fps(0))
        assertEquals(0.0, stats.fps(1_000_000_000L)!!, 0.0001)
    }

    @Test fun firstFrameAndPauseBoundaryHaveNoInterval() {
        val stats = FrameStatistics()
        stats.add(0)
        stats.add(33_000_000)
        stats.add(0)
        assertEquals(3, stats.frames)
        assertEquals(33.0, stats.percentile(50.0)!!, 0.0001)
        assertEquals(3.0, stats.fps(1_000_000_000)!!, 0.0001)
    }

    @Test fun percentilesRetainStuttersRatherThanAveragingThemAway() {
        val stats = FrameStatistics()
        repeat(99) { stats.add(33_000_000) }
        stats.add(800_000_000)
        assertEquals(33.0, stats.percentile(95.0)!!, 0.0001)
        assertEquals(800.0, stats.percentile(100.0)!!, 0.0001)
        assertEquals(20.0, stats.fps(5_000_000_000)!!, 0.0001)
    }
}
