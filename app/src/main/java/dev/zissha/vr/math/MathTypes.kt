package dev.zissha.vr.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Minimal float 3-vector used by rigging and spring bone math. */
data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-8f) Vec3(0f, 0f, 0f) else Vec3(x / l, y / l, z / l)
    }

    fun lerp(o: Vec3, t: Float) = Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun distanceTo(o: Vec3) = (this - o).length()

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        val FORWARD = Vec3(0f, 0f, -1f)
        val RIGHT = Vec3(1f, 0f, 0f)
    }
}

/**
 * Unit quaternion. All math is in the same convention as the VRM/glTF node hierarchy
 * (right-handed, Y-up, -Z forward).
 */
data class Quat(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f) {

    val isIdentity: Boolean get() = abs(x) < 1e-7f && abs(y) < 1e-7f && abs(z) < 1e-7f && w > 0f

    operator fun times(o: Quat): Quat = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-10f) IDENTITY else Quat(x / l, y / l, z / l, w / l)
    }

    fun conjugate() = Quat(-x, -y, -z, w)

    /** Rotate a vector by this quaternion. */
    fun rotate(v: Vec3): Vec3 {
        val qv = Vec3(x, y, z)
        val t = qv.cross(v) * 2f
        return v + (t * w) + qv.cross(t)
    }

    fun slerp(o: Quat, t: Float): Quat {
        var b = o
        var cosOmega = x * b.x + y * b.y + z * b.z + w * b.w
        if (cosOmega < 0f) {
            b = Quat(-b.x, -b.y, -b.z, -b.w)
            cosOmega = -cosOmega
        }
        if (cosOmega > 0.9995f) {
            return Quat(
                x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t, w + (b.w - w) * t
            ).normalized()
        }
        val omega = acos(cosOmega.coerceIn(-1f, 1f))
        val sinOmega = sqrt(1f - cosOmega * cosOmega)
        val wa = kotlin.math.sin((1f - t) * omega) / sinOmega
        val wb = kotlin.math.sin(t * omega) / sinOmega
        return Quat(
            x * wa + b.x * wb, y * wa + b.y * wb, z * wa + b.z * wb, w * wa + b.w * wb
        )
    }

    /** Column-major 4x4 rotation matrix (as expected by OpenGL uniformMatrix4fv). */
    fun toMat4(out: FloatArray) {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        out[0] = 1f - 2f * (yy + zz); out[4] = 2f * (xy - wz); out[8] = 2f * (xz + wy); out[12] = 0f
        out[1] = 2f * (xy + wz); out[5] = 1f - 2f * (xx + zz); out[9] = 2f * (yz - wx); out[13] = 0f
        out[2] = 2f * (xz - wy); out[6] = 2f * (yz + wx); out[10] = 1f - 2f * (xx + yy); out[14] = 0f
        out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
    }

    companion object {
        val IDENTITY = Quat()

        fun fromAxisAngle(axis: Vec3, radians: Float): Quat {
            val a = axis.normalized()
            val s = kotlin.math.sin(radians / 2f)
            return Quat(a.x * s, a.y * s, a.z * s, kotlin.math.cos(radians / 2f)).normalized()
        }

        /**
         * Shortest-arc rotation from vector [from] to vector [to].
         * Equivalent to Unity's Quaternion.FromToRotation for non-opposite vectors.
         */
        fun fromToRotation(from: Vec3, to: Vec3): Quat {
            val f = from.normalized()
            val t = to.normalized()
            val d = f.dot(t)
            if (d > 1f - 1e-6f) return IDENTITY
            if (d < -1f + 1e-6f) {
                // Opposite vectors: rotate 180 degrees around any perpendicular axis.
                val axis = Vec3(1f, 0f, 0f).cross(f)
                val axis2 = if (axis.length() < 1e-5f) Vec3(0f, 1f, 0f).cross(f) else axis
                return fromAxisAngle(axis2.normalized(), Math.PI.toFloat())
            }
            val axis = f.cross(t)
            val w = sqrt((1f + d) * 2f)
            return Quat(axis.x / w, axis.y / w, axis.z / w, w / 2f).normalized()
        }
    }
}

/**
 * 4x4 matrix, column-major storage (m[col * 4 + row]) matching OpenGL conventions.
 */
class Mat4 {

    val m = FloatArray(16)

    fun setIdentity(): Mat4 {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return this
    }

    fun set(other: Mat4): Mat4 {
        System.arraycopy(other.m, 0, m, 0, 16)
        return this
    }

    fun setFromTrs(t: Vec3, r: Quat, s: Vec3): Mat4 {
        r.toMat4(m)
        m[0] *= s.x; m[1] *= s.x; m[2] *= s.x
        m[4] *= s.y; m[5] *= s.y; m[6] *= s.y
        m[8] *= s.z; m[9] *= s.z; m[10] *= s.z
        m[12] = t.x; m[13] = t.y; m[14] = t.z
        m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        return this
    }

    /** this = a * b (column-major). this must not be a or b. */
    fun mul(a: Mat4, b: Mat4): Mat4 {
        for (col in 0..3) {
            val b0 = b.m[col * 4]; val b1 = b.m[col * 4 + 1]; val b2 = b.m[col * 4 + 2]; val b3 = b.m[col * 4 + 3]
            m[col * 4] = a.m[0] * b0 + a.m[4] * b1 + a.m[8] * b2 + a.m[12] * b3
            m[col * 4 + 1] = a.m[1] * b0 + a.m[5] * b1 + a.m[9] * b2 + a.m[13] * b3
            m[col * 4 + 2] = a.m[2] * b0 + a.m[6] * b1 + a.m[10] * b2 + a.m[14] * b3
            m[col * 4 + 3] = a.m[3] * b0 + a.m[7] * b1 + a.m[11] * b2 + a.m[15] * b3
        }
        return this
    }

    fun transformPoint(v: Vec3): Vec3 {
        val w = m[3] * v.x + m[7] * v.y + m[11] * v.z + m[15]
        val invW = if (abs(w) < 1e-9f) 1f else 1f / w
        return Vec3(
            (m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12]) * invW,
            (m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13]) * invW,
            (m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14]) * invW,
        )
    }

    fun transformVector(v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z,
    )

    fun extractTranslation(): Vec3 = Vec3(m[12], m[13], m[14])

    /** Extract rotation assuming orthonormal scale (uniform scale safe). */
    fun extractRotation(): Quat {
        val trace = m[0] + m[5] + m[10]
        return if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            Quat(
                (m[6] - m[9]) / s,
                (m[8] - m[2]) / s,
                (m[1] - m[4]) / s,
                0.25f * s,
            ).normalized()
        } else if (m[0] > m[5] && m[0] > m[10]) {
            val s = sqrt(1f + m[0] - m[5] - m[10]) * 2f
            Quat(0.25f * s, (m[4] + m[1]) / s, (m[8] + m[2]) / s, (m[6] - m[9]) / s).normalized()
        } else if (m[5] > m[10]) {
            val s = sqrt(1f + m[5] - m[0] - m[10]) * 2f
            Quat((m[4] + m[1]) / s, 0.25f * s, (m[9] + m[6]) / s, (m[8] - m[2]) / s).normalized()
        } else {
            val s = sqrt(1f + m[10] - m[0] - m[5]) * 2f
            Quat((m[8] + m[2]) / s, (m[9] + m[6]) / s, 0.25f * s, (m[1] - m[4]) / s).normalized()
        }
    }

    companion object {
        fun identity(): Mat4 = Mat4().setIdentity()

        /**
         * Perspective projection matrix. [fovYRadians] vertical fov, [aspect] w/h.
         * Column-major, suitable for uniformMatrix4fv.
         */
        fun perspective(fovYRadians: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val mat = Mat4().setIdentity()
            val f = 1f / kotlin.math.tan(fovYRadians / 2f)
            val nf = 1f / (near - far)
            mat.m[0] = f / aspect
            mat.m[5] = f
            mat.m[10] = (far + near) * nf
            mat.m[11] = -1f
            mat.m[14] = 2f * far * near * nf
            mat.m[15] = 0f
            return mat
        }
    }
}
