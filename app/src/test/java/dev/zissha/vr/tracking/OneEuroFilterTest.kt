package dev.zissha.vr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneEuroFilterTest {

    @Test
    fun `constant signal passes through`() {
        val filter = OneEuroFilter()
        var t = 0f
        repeat(50) {
            val out = filter.filter(0.5f, t)
            assertEquals(0.5f, out, 0.01f)
            t += 1f / 30f
        }
    }

    @Test
    fun `jitter is reduced at rest`() {
        val filter = OneEuroFilter(minCutoff = 1.5f, beta = 0.01f)
        var t = 0f
        var maxDeviation = 0f
        // constant value with +-0.05 noise
        val inputs = floatArrayOf(0.55f, 0.45f, 0.55f, 0.45f, 0.5f, 0.5f, 0.55f, 0.45f)
        inputs.forEachIndexed { i, v ->
            val out = filter.filter(v, t)
            if (i > 1) maxDeviation = maxOf(maxDeviation, kotlin.math.abs(out - 0.5f))
            t += 1f / 30f
        }
        assertTrue("filter did not smooth jitter: $maxDeviation", maxDeviation < 0.05f)
    }

    @Test
    fun `fast ramp is followed with small lag`() {
        val filter = OneEuroFilter(minCutoff = 1.0f, beta = 0.02f)
        var t = 0f
        var last = 0f
        // ramp from 0 to 1 over 1 second (30fps)
        repeat(30) { i ->
            val input = i / 29f
            last = filter.filter(input, t)
            t += 1f / 30f
        }
        assertTrue("filter too slow, last=$last", last > 0.7f)
    }

    @Test
    fun `reset clears state`() {
        val filter = OneEuroFilter()
        filter.filter(0.9f, 0f)
        filter.filter(0.9f, 1f)
        filter.reset()
        val out = filter.filter(0.1f, 2f)
        assertEquals(0.1f, out, 1e-6f)
    }
}
