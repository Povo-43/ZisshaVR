package dev.zissha.vr.tracking

/**
 * One Euro Filter (Casiez et al., CHI 2012) for adaptive low-pass filtering of
 * landmark coordinates. Cuts jitter at rest while keeping fast motion responsive.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.0f,
    private val beta: Float = 0.007f,
    private val dCutoff: Float = 1.0f,
) {
    private var xPrev = Float.NaN
    private var dxPrev = 0f
    private var tPrev = Float.NaN

    /** @param value new sample @param timestampSeconds monotonic sample time @return filtered value */
    fun filter(value: Float, timestampSeconds: Float): Float {
        if (xPrev.isNaN()) {
            xPrev = value
            dxPrev = 0f
            tPrev = timestampSeconds
            return value
        }
        var dt = timestampSeconds - tPrev
        if (dt <= 0f) dt = 1f / 30f
        tPrev = timestampSeconds
        val dx = (value - xPrev) / dt
        val edx = dxPrev + alpha(dt, dCutoff) * (dx - dxPrev)
        dxPrev = edx
        val cutoff = minCutoff + beta * kotlin.math.abs(edx)
        val result = xPrev + alpha(dt, cutoff) * (value - xPrev)
        xPrev = result
        return result
    }

    fun reset() {
        xPrev = Float.NaN
        dxPrev = 0f
        tPrev = Float.NaN
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

/** 3-channel (x, y, z) One Euro filter set. */
class OneEuroFilter3(
    minCutoff: Float = 1.0f,
    beta: Float = 0.007f,
) {
    private val fx = OneEuroFilter(minCutoff, beta)
    private val fy = OneEuroFilter(minCutoff, beta)
    private val fz = OneEuroFilter(minCutoff, beta)

    fun filter(x: Float, y: Float, z: Float, t: Float): FloatArray {
        out[0] = fx.filter(x, t)
        out[1] = fy.filter(y, t)
        out[2] = fz.filter(z, t)
        return out
    }

    fun reset() {
        fx.reset(); fy.reset(); fz.reset()
    }

    private val out = FloatArray(3)
}
