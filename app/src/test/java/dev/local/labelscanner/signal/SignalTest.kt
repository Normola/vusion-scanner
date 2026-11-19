package dev.local.labelscanner.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTest {

    private fun samples(vararg rssi: Int) = rssi.mapIndexed { i, r -> Sample(i.toLong(), r) }

    @Test
    fun `kalman moves toward measurements`() {
        val k = Kalman1D(q = 0.5, r = 16.0, initialEstimate = -70.0)
        var v = 0.0
        repeat(50) { v = k.update(-50.0) }
        assertTrue(v > -52.0)
    }

    @Test
    fun `pipeline ignores invalid rssi and expires old samples`() {
        var now = 0L
        val p = RssiPipeline(retentionMs = 10_000, clock = { now })
        assertNull(p.ingest("a", 127))
        assertNull(p.ingest("a", 0))
        p.ingest("a", -60)
        assertEquals(setOf("a"), p.snapshot(0).keys)
        now = 20_000
        assertTrue(p.snapshot(0).isEmpty())
    }

    @Test
    fun `nearest keeps previous winner within hysteresis`() {
        val d = NearestDeviceDetector(hysteresisDb = 3.0)
        assertEquals("a", d.evaluate(mapOf("a" to samples(-50), "b" to samples(-60)))?.deviceId)
        assertEquals("a", d.evaluate(mapOf("a" to samples(-50), "b" to samples(-48)))?.deviceId)
        assertEquals("b", d.evaluate(mapOf("a" to samples(-50), "b" to samples(-45)))?.deviceId)
    }

    @Test
    fun `front modular needs dwell then reports lost`() {
        val d = FrontModularDetector(FrontModularConfig())
        val aisles = mapOf("a" to "1", "b" to "1")
        val modulars = mapOf("a" to "M1", "b" to "M2")
        val snap = mapOf("a" to samples(-50, -51, -50), "b" to samples(-62, -63, -61))

        assertNull(d.evaluate(snap, null, aisles::get, modulars::get)) // dwell 1 of 2
        val updated = d.evaluate(snap, null, aisles::get, modulars::get)
        assertTrue(updated is FrontEvent.Updated)
        updated as FrontEvent.Updated
        assertEquals("M1", updated.modular)
        assertTrue(updated.confidence in 0f..1f)

        assertNull(d.evaluate(snap, null, aisles::get, modulars::get)) // already emitted

        assertNull(d.evaluate(emptyMap(), null, aisles::get, modulars::get))
        assertNull(d.evaluate(emptyMap(), null, aisles::get, modulars::get))
        assertTrue(d.evaluate(emptyMap(), null, aisles::get, modulars::get) is FrontEvent.Lost)
    }

    @Test
    fun `front modular rejects close race`() {
        val d = FrontModularDetector(FrontModularConfig(dwellWindows = 1, lostWindows = 99))
        val aisles = mapOf("a" to "1", "b" to "1")
        // Strong signals need a 3 dB lead; these are 1 dB apart.
        val snap = mapOf("a" to samples(-50, -50), "b" to samples(-51, -51))
        assertNull(d.evaluate(snap, null, aisles::get) { null })
    }

    @Test
    fun `front modular respects aisle hint`() {
        val d = FrontModularDetector(FrontModularConfig(dwellWindows = 1))
        val aisles = mapOf("a" to "1", "b" to "2")
        val snap = mapOf("a" to samples(-50, -50), "b" to samples(-70, -70))
        val event = d.evaluate(snap, "2", aisles::get) { null }
        assertTrue(event is FrontEvent.Updated)
        assertEquals("b", (event as FrontEvent.Updated).deviceId)
    }
}
