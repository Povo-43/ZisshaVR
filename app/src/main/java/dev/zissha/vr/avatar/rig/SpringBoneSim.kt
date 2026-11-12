package dev.zissha.vr.avatar.rig

import dev.zissha.vr.avatar.gltf.GltfDocument
import dev.zissha.vr.avatar.gltf.SpringBoneGroup
import dev.zissha.vr.avatar.gltf.SpringCollider
import dev.zissha.vr.math.Quat
import dev.zissha.vr.math.Vec3

/**
 * VRM SpringBone simulation (spec 3.1). Verlet integration with stiffness, gravity, drag and
 * sphere colliders, matching the UniVRM algorithm closely enough for hair/skirt/cloth motion.
 */
class SpringBoneSim(
    private val rig: HumanoidRig,
    private val doc: GltfDocument,
    private val groups: List<SpringBoneGroup>,
) {

    private class JointState {
        var tail = Vec3()
        var prevTail = Vec3()
        var initialized = false
        var restDirLocal = Vec3(0f, -1f, 0f)
        var boneLength = 0.07f
        var hasLastHead = false
        var lastHead = Vec3()
    }

    private val states = HashMap<Int, JointState>() // nodeIndex -> state

    init {
        // Derive each joint's rest direction + length from the rest pose.
        for (group in groups) {
            for (i in group.joints.indices) {
                val joint = group.joints[i]
                val nodeIdx = joint.nodeIndex
                if (nodeIdx !in rig.nodes.indices) continue
                val state = states.getOrPut(nodeIdx) { JointState() }
                val next = group.joints.getOrNull(i + 1)
                if (next != null && next.nodeIndex in rig.nodes.indices) {
                    val from = rig.nodes[nodeIdx].restWorld.extractTranslation()
                    val to = rig.nodes[next.nodeIndex].restWorld.extractTranslation()
                    val d = to - from
                    if (d.length() > 1e-6f) {
                        state.boneLength = d.length().coerceAtLeast(0.01f)
                        state.restDirLocal = d.normalized()
                    }
                }
            }
        }
    }

    val jointCount: Int get() = states.size

    fun reset() {
        for (state in states.values) state.initialized = false
    }

    /**
     * Advance the simulation. Must be called after [HumanoidRig.evaluate] so world matrices
     * reflect the tracked pose. New overrides are written into the rig; the caller re-runs
     * [HumanoidRig.evaluate] to propagate them into skin matrices.
     */
    fun update(dt: Float) {
        if (dt <= 0f || dt > 0.1f) return
        val dt2 = dt * dt
        for (group in groups) {
            val colliders = groupColliders(group)
            for (joint in group.joints) {
                val nodeIdx = joint.nodeIndex
                if (nodeIdx !in rig.nodes.indices) continue
                val state = states[nodeIdx] ?: continue
                val worldNow = rig.worldMatrix(nodeIdx)
                val headPos = worldNow.extractTranslation()
                val restDirWorld = worldNow.transformVector(state.restDirLocal)
                if (restDirWorld.length() < 1e-6f) continue
                val restDirWorldN = restDirWorld.normalized()
                // Re-seed the tail when the bone head jumps (large pose/root change) or on first
                // use; otherwise the Verlet tail lags far behind and hair/cloth tears apart.
                val jumped = state.hasLastHead &&
                    (headPos - state.lastHead).length() > maxOf(0.15f, state.boneLength * 4f)
                if (!state.initialized || jumped) {
                    state.tail = headPos + restDirWorldN * state.boneLength
                    state.prevTail = state.tail
                    state.initialized = true
                }
                state.hasLastHead = true
                state.lastHead = headPos
                // verlet integration (velocity clamped so the tail can never be flung away)
                val drag = joint.dragForce.coerceIn(0f, 1f)
                var velocity = (state.tail - state.prevTail) * (1f - drag)
                val vMax = state.boneLength * 0.5f
                if (velocity.length() > vMax) velocity = velocity.normalized() * vMax
                state.prevTail = state.tail
                val gravity = joint.gravityDir.normalized() * joint.gravityPower
                val idealTail = headPos + restDirWorldN * state.boneLength
                val stiffnessForce = (idealTail - state.tail) * joint.stiffness
                var newTail = state.tail + velocity + (gravity + stiffnessForce) * dt2
                // length constraint
                val diff = (newTail - headPos).normalized()
                newTail = headPos + diff * state.boneLength
                // sphere colliders push-out
                for (c in colliders) {
                    val center = rig.worldMatrix(c.nodeIndex).extractTranslation()
                    val r = c.radius + joint.hitRadius
                    val toTail = newTail - center
                    val d = toTail.length()
                    if (d > 1e-6f && d < r) {
                        newTail = center + toTail.normalized() * r
                        newTail = headPos + (newTail - headPos).normalized() * state.boneLength
                    }
                }
                state.tail = newTail
                // rotate the node so its bone points at the tail
                val desiredDir = (state.tail - headPos).normalized()
                if (desiredDir.length() > 1e-5f) {
                    val restWorldRot = worldNow.extractRotation()
                    rig.worldRotationOverride[nodeIdx] =
                        Quat.fromToRotation(restDirWorldN, desiredDir) * restWorldRot
                }
            }
        }
    }

    private val colliderScratch = ArrayList<SpringCollider>()

    private fun groupColliders(group: SpringBoneGroup): List<SpringCollider> {
        colliderScratch.clear()
        for (idx in group.colliders) {
            doc.vrm?.springColliders?.getOrNull(idx)?.let { colliderScratch.add(it) }
        }
        return colliderScratch
    }
}
