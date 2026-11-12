package dev.zissha.vr.math

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTypesTest {

    private fun assertVecNear(a: Vec3, b: Vec3, eps: Float = 1e-4f) {
        assertTrue("vectors differ: $a vs $b", (a - b).length() < eps)
    }

    private fun assertQuatNear(a: Quat, b: Quat, eps: Float = 1e-4f) {
        // quaternions q and -q represent the same rotation
        val d1 = abs(a.x - b.x) + abs(a.y - b.y) + abs(a.z - b.z) + abs(a.w - b.w)
        val d2 = abs(a.x + b.x) + abs(a.y + b.y) + abs(a.z + b.z) + abs(a.w + b.w)
        assertTrue("quats differ: $a vs $b", minOf(d1, d2) < eps)
    }

    @Test
    fun `quaternion identity rotates nothing`() {
        val q = Quat.IDENTITY
        val v = Vec3(1f, 2f, 3f)
        assertVecNear(v, q.rotate(v))
    }

    @Test
    fun `quaternion 90 degree z rotation`() {
        val q = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), (Math.PI / 2).toFloat())
        val rotated = q.rotate(Vec3(1f, 0f, 0f))
        assertVecNear(Vec3(0f, 1f, 0f), rotated)
    }

    @Test
    fun `fromToRotation maps x to y`() {
        val q = Quat.fromToRotation(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f))
        assertVecNear(Vec3(0f, 1f, 0f), q.rotate(Vec3(1f, 0f, 0f)))
    }

    @Test
    fun `fromToRotation handles opposite vectors`() {
        val q = Quat.fromToRotation(Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f))
        val rotated = q.rotate(Vec3(0f, 0f, 1f))
        assertTrue(abs(rotated.z + 1f) < 1e-4f)
    }

    @Test
    fun `slerp midpoint is halfway rotation`() {
        val a = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 0f)
        val b = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), (Math.PI / 2).toFloat())
        val mid = a.slerp(b, 0.5f)
        val angle = 2f * kotlin.math.acos(mid.w.coerceIn(-1f, 1f))
        assertTrue(abs(angle - (Math.PI / 4).toFloat()) < 1e-3f)
    }

    @Test
    fun `mat4 trs transform point`() {
        val mat = Mat4().setFromTrs(Vec3(1f, 2f, 3f), Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 0f), Vec3(2f, 2f, 2f))
        val p = mat.transformPoint(Vec3(1f, 1f, 1f))
        assertVecNear(Vec3(3f, 4f, 5f), p)
    }

    @Test
    fun `mat4 mul composes translations`() {
        val a = Mat4().setIdentity().also { it.m[12] = 1f } // translate x+1
        val b = Mat4().setIdentity().also { it.m[13] = 2f } // translate y+2
        val c = Mat4().mul(a, b)
        val p = c.transformPoint(Vec3(0f, 0f, 0f))
        assertVecNear(Vec3(1f, 2f, 0f), p)
    }

    @Test
    fun `quat to mat4 rotation round trip`() {
        val q = Quat.fromAxisAngle(Vec3(1f, 1f, 0f).normalized(), 0.7f)
        val m = Mat4()
        q.toMat4(m.m)
        val q2 = m.extractRotation()
        assertQuatNear(q, q2)
        // rotate a vector through both paths
        val v = Vec3(0.3f, -0.5f, 0.8f)
        assertVecNear(q.rotate(v), m.transformVector(v), 1e-3f)
    }

    @Test
    fun `perspective matrix maps near plane`() {
        val p = Mat4.perspective((Math.PI / 3).toFloat(), 16f / 9f, 0.1f, 100f)
        // point at center of near plane: x = tan(fov/2)*near*aspect
        val fovHalf = (Math.PI / 6).toFloat()
        val x = kotlin.math.tan(fovHalf) * 0.1f * (16f / 9f)
        val clip = p.transformPoint(Vec3(x, 0f, -0.1f))
        assertTrue(abs(abs(clip.x) - 1f) < 1e-3f)
    }

    @Test
    fun `vec3 operations`() {
        val a = Vec3(1f, 2f, 3f)
        val b = Vec3(4f, 5f, 6f)
        assertEquals(32f, a.dot(b), 1e-5f)
        assertEquals(3f, Vec3(2f, 0f, 0f).distanceTo(Vec3(-1f, 0f, 0f)), 1e-5f)
        assertVecNear(Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f).cross(Vec3(0f, 1f, 0f)))
        assertVecNear(Vec3(1f, 1f, 0f).normalized(), Vec3(sqrt(0.5f), sqrt(0.5f), 0f))
    }
}
