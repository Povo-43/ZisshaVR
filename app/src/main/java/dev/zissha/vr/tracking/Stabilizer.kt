package dev.zissha.vr.tracking

import dev.zissha.vr.math.Quat
import dev.zissha.vr.math.Vec3

/**
 * Tracking stabilization (spec 4.4):
 * - One Euro filtering per landmark
 * - confidence gate: low-visibility landmarks are replaced by predicted positions
 * - velocity gate: implausible jumps are clamped to the predicted trajectory
 * - landmark prediction: dead-reckoning with exponential decay when landmarks drop out
 */
class Stabilizer(
    private val minCutoff: Float = 1.2f,
    private val beta: Float = 0.02f,
    private val visibilityGate: Float = 0.35f,
    /** max plausible normalized displacement per second; larger jumps are clamped */
    private val maxSpeed: Float = 2.5f,
) {

    private class LandmarkState {
        val filter = OneEuroFilter3(minCutoff, beta)
        var lastValid = Vec3()
        var lastVelocity = Vec3()
        var lastValidTs = 0f
        var consecutiveMissing = 0
    }

    private val poseStates = Array(33) { LandmarkState() }

    /** Per-hand states, keyed 0/1 by slot. */
    private val handStates = Array(2) { Array(21) { LandmarkState() } }

    private val blendshapeEma = HashMap<String, Float>()
    private var headQuat: Quat? = null

    fun smoothPose(tsMs: Long, pose: PoseResult): PoseResult {
        val t = tsMs / 1000f
        val out = ArrayList<Landmark3>(33)
        val outWorld = ArrayList<Landmark3>(33)
        for (i in 0 until 33) {
            val st = poseStates[i]
            val lm = pose.landmarks[i]
            val visible = lm.visibility >= visibilityGate
            if (visible) {
                // velocity gate on raw input
                val raw = Vec3(lm.x, lm.y, lm.z)
                val dt = (t - st.lastValidTs).coerceAtLeast(1f / 120f)
                val speed = if (st.lastValidTs == 0f) 0f else (raw - st.lastValid).length() / dt
                val accepted = if (speed > maxSpeed * 4f) {
                    // implausible warp: clamp displacement
                    val dir = (raw - st.lastValid).normalized()
                    st.lastValid + dir * (maxSpeed * 4f * dt)
                } else raw
                val f = st.filter.filter(accepted.x, accepted.y, accepted.z, t)
                val newV = Vec3(f[0], f[1], f[2])
                if (st.lastValidTs > 0f) {
                    val dt2 = (t - st.lastValidTs).coerceAtLeast(1f / 120f)
                    st.lastVelocity = (newV - st.lastValid) * (1f / dt2)
                }
                st.lastValid = newV
                st.lastValidTs = t
                st.consecutiveMissing = 0
                out.add(Landmark3(newV.x, newV.y, newV.z, lm.visibility))
            } else {
                // missing: predict with decaying velocity
                st.consecutiveMissing++
                val decay = pow(0.85f, st.consecutiveMissing.coerceAtMost(30))
                val dt = (t - st.lastValidTs).coerceAtLeast(0f)
                val predicted = st.lastValid + st.lastVelocity * (dt * decay)
                out.add(Landmark3(predicted.x, predicted.y, predicted.z, 0f))
            }
            outWorld.add(pose.worldLandmarks[i])
        }
        return PoseResult(pose.tsMs, out, outWorld)
    }

    fun smoothHands(tsMs: Long, hands: HandResult): HandResult {
        val t = tsMs / 1000f
        val outHands = ArrayList<HandSkeleton>(2)
        hands.hands.forEachIndexed { slot, hand ->
            val states = handStates[slot % 2]
            val out = ArrayList<Landmark3>(21)
            val outWorld = ArrayList<Landmark3>(21)
            for (i in 0 until 21) {
                val st = states[i]
                val lm = hand.landmarks[i]
                val f = st.filter.filter(lm.x, lm.y, lm.z, t)
                val v = Vec3(f[0], f[1], f[2])
                st.lastValid = v
                st.lastValidTs = t
                out.add(Landmark3(v.x, v.y, v.z, lm.visibility))
                outWorld.add(hand.worldLandmarks[i])
            }
            outHands.add(hand.copy(landmarks = out, worldLandmarks = outWorld))
        }
        return HandResult(hands.tsMs, outHands)
    }

    fun smoothFace(tsMs: Long, face: FaceResult): FaceResult {
        val alpha = 0.4f
        val smoothed = HashMap<String, Float>(face.blendshapes.size)
        for ((name, value) in face.blendshapes) {
            val prev = blendshapeEma[name]
            val s = if (prev == null) value else prev + alpha * (value - prev)
            blendshapeEma[name] = s
            smoothed[name] = s
        }
        var headOut = face.headMatrix
        if (face.headMatrix != null) {
            val target = quatFromRowMajorMatrix(face.headMatrix)
            headQuat = if (headQuat == null) target else headQuat!!.slerp(target, 0.4f).normalized()
            val m = FloatArray(16)
            headQuat!!.toMat4(m)
            headOut = m
        }
        return FaceResult(face.tsMs, smoothed, headOut)
    }

    fun reset() {
        for (s in poseStates) s.filter.reset()
        for (arr in handStates) for (s in arr) s.filter.reset()
        blendshapeEma.clear()
        headQuat = null
    }

    /** Velocity of a pose landmark between the two most recent accepted samples (units: 1/s). */
    fun poseVelocity(index: Int): Vec3 = poseStates[index].lastVelocity

    companion object {
        private fun pow(base: Float, n: Int): Float {
            var result = 1f
            repeat(n) { result *= base }
            return result
        }

        /**
         * Build a quaternion from a 4x4 row-major matrix (MediaPipe facial transformation matrix
         * is documented as row-major).
         */
        fun quatFromRowMajorMatrix(m: FloatArray): Quat {
            // convert to column-major Mat4
            val col = FloatArray(16)
            for (r in 0..3) for (c in 0..3) col[c * 4 + r] = m[r * 4 + c]
            val mat = dev.zissha.vr.math.Mat4()
            System.arraycopy(col, 0, mat.m, 0, 16)
            return mat.extractRotation()
        }
    }
}
