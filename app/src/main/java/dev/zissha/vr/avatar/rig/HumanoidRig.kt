package dev.zissha.vr.avatar.rig

import dev.zissha.vr.avatar.gltf.GltfDocument
import dev.zissha.vr.avatar.gltf.VrmData
import dev.zissha.vr.math.Mat4
import dev.zissha.vr.math.Quat
import dev.zissha.vr.math.Vec3

/**
 * Runtime humanoid rig: evaluates node world matrices from a mix of rest pose and per-frame
 * overrides (world rotations / root translation) coming from [PoseRigger] and spring bones.
 */
class HumanoidRig(val doc: GltfDocument, val vrm: VrmData) {

    companion object {
        /** Camera looks from +Z toward -Z; screen right = +X. */
        val VIEW_FLIP: Quat = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), Math.PI.toFloat())
    }

    val nodes = doc.nodes
    val humanoidBones = vrm.humanoidBones

    /** Hierarchy order (parents before children) restricted to nodes that matter for evaluation. */
    private val evalOrder: IntArray
    private val localRotations: Array<Quat>
    private val localTranslations: Array<Vec3>
    private val localScales: Array<Vec3>
    private val worldMatrices: Array<Mat4>

    /** Per-node override for world rotation (from tracking / spring bones). */
    val worldRotationOverride = HashMap<Int, Quat>()

    /** Root translation (hips position in world space). */
    var rootTranslation: Vec3 = Vec3.ZERO
    var rootScale: Float = 1f

    /** Detected avatar facing: true when the model faces +Z in glTF space (toward the viewer). */
    val facesViewer: Boolean
    /** Multiplier applied to view-space directions to align the avatar with the tracked person. */
    val viewToAvatar: Quat

    init {
        val order = ArrayList<Int>()
        val visited = BooleanArray(nodes.size)
        fun visit(index: Int) {
            if (index !in nodes.indices || visited[index]) return
            visited[index] = true
            order.add(index)
            for (child in nodes[index].children) visit(child)
        }
        for (root in doc.sceneRoots) visit(root)
        for (node in nodes) visit(node.index) // orphan chains (spring bones)
        evalOrder = order.toIntArray()

        localRotations = Array(nodes.size) { Quat.IDENTITY }
        localTranslations = Array(nodes.size) { Vec3.ZERO }
        localScales = Array(nodes.size) { Vec3(1f, 1f, 1f) }
        worldMatrices = Array(nodes.size) { Mat4.identity() }

        // Detect facing direction from the T-pose arm positions (left arm on +X => model faces -Z).
        val lUpper = humanoidBones["leftUpperArm"]
        val rUpper = humanoidBones["rightUpperArm"]
        facesViewer = if (lUpper != null && rUpper != null) {
            nodes[lUpper].restWorld.extractTranslation().x < nodes[rUpper].restWorld.extractTranslation().x
        } else {
            true
        }
        viewToAvatar = if (facesViewer) Quat.IDENTITY else VIEW_FLIP
    }

    fun hasBone(name: String): Boolean = humanoidBones.containsKey(name)
    fun boneNode(name: String): Int? = humanoidBones[name]

    fun worldTranslation(nodeIndex: Int): Vec3 = worldMatrices[nodeIndex].extractTranslation()
    fun worldRotation(nodeIndex: Int): Quat = worldMatrices[nodeIndex].extractRotation()
    fun worldMatrix(nodeIndex: Int): Mat4 = worldMatrices[nodeIndex]

    /** Rest direction (avatar space) of the segment from [fromNode] toward [toNode]. */
    fun restDirection(fromNode: Int, toNode: Int): Vec3 =
        (nodes[toNode].restWorld.extractTranslation() - nodes[fromNode].restWorld.extractTranslation()).normalized()

    /** Length (avatar units) of the segment between two nodes. */
    fun restLength(fromNode: Int, toNode: Int): Float =
        (nodes[toNode].restWorld.extractTranslation() - nodes[fromNode].restWorld.extractTranslation()).length()

    fun resetOverrides() {
        worldRotationOverride.clear()
        rootTranslation = Vec3.ZERO
        rootScale = 1f
    }

    /**
     * Evaluate all node world matrices. Overrides are absolute world rotations; parent chains
     * are respected because evaluation runs in hierarchy order.
     */
    fun evaluate() {
        for (index in evalOrder) {
            val node = nodes[index]
            var localRot = node.rotation
            var localPos = node.translation
            var localScale = node.scale
            val override = worldRotationOverride[index]
            if (override != null) {
                val parentIndex = findParent(index)
                if (parentIndex >= 0) {
                    val parentWorldRot = worldMatrices[parentIndex].extractRotation()
                    localRot = parentWorldRot.conjugate() * override
                    // keep local translation relative but re-derive to preserve attachment
                    localPos = node.translation
                } else {
                    localRot = override
                }
            }
            localRotations[index] = localRot.normalized()
            localTranslations[index] = localPos
            localScales[index] = localScale
            val local = Mat4().setFromTrs(localPos, localRotations[index], localScale)
            val parentIndex = findParent(index)
            if (parentIndex >= 0 && visitedParent(index)) {
                worldMatrices[index].mul(worldMatrices[parentIndex], local)
            } else {
                worldMatrices[index].set(local)
            }
        }
        // root translation / scale applied through the hips chain: recompute with offset
        applyRootMotion()
    }

    private val parentCache = HashMap<Int, Int>()

    private fun findParent(index: Int): Int {
        parentCache[index]?.let { return it }
        var found = -1
        for (node in nodes) {
            if (node.children.contains(index)) {
                found = node.index
                break
            }
        }
        parentCache[index] = found
        return found
    }

    private fun visitedParent(index: Int): Boolean = findParent(index) >= 0

    /**
     * Apply root translation/scale on the humanoid root (hips): its world matrix is shifted
     * and scaled, and all descendants inherit by recomputation.
     */
    private fun applyRootMotion() {
        val hips = humanoidBones["hips"] ?: return
        val hipsNode = nodes[hips]
        val world = worldMatrices[hips]
        val scale = Mat4().setIdentity()
        scale.m[0] = rootScale; scale.m[5] = rootScale; scale.m[10] = rootScale
        val translated = Mat4().setIdentity()
        translated.m[12] = rootTranslation.x
        translated.m[13] = rootTranslation.y
        translated.m[14] = rootTranslation.z
        world.mul(translated, Mat4().mul(scale, Mat4().set(world)))
        // descendants
        val visited = BooleanArray(nodes.size)
        fun descend(idx: Int) {
            if (visited[idx]) return
            visited[idx] = true
            for (child in nodes[idx].children) {
                if (child !in nodes.indices) continue
                if (worldRotationOverride.containsKey(child)) {
                    // children with their own world overrides: re-derive local from new parent
                    val parentWorldRot = worldMatrices[idx].extractRotation()
                    val localRot = parentWorldRot.conjugate() * worldRotationOverride[child]!!
                    val local = Mat4().setFromTrs(nodes[child].translation, localRot, nodes[child].scale)
                    worldMatrices[child].mul(worldMatrices[idx], local)
                } else {
                    val local = Mat4().setFromTrs(nodes[child].translation, nodes[child].rotation, nodes[child].scale)
                    worldMatrices[child].mul(worldMatrices[idx], local)
                }
                descend(child)
            }
        }
        descend(hips)
    }

    /** Fill the bone matrix texture buffer (column-major mat4 per joint) for a skin. */
    fun fillSkinMatrices(skinIndex: Int, out: FloatArray): Int {
        if (skinIndex !in doc.skins.indices) return 0
        val skin = doc.skins[skinIndex]
        val count = minOf(skin.joints.size, out.size / 16)
        for (j in 0 until count) {
            val nodeIdx = skin.joints[j]
            val world = worldMatrices[nodeIdx]
            val ibm = skin.inverseBindMatrices[j]
            // out = world * ibm
            for (c in 0..3) {
                val b0 = ibm.m[c * 4]; val b1 = ibm.m[c * 4 + 1]; val b2 = ibm.m[c * 4 + 2]; val b3 = ibm.m[c * 4 + 3]
                out[j * 16 + c * 4] = world.m[0] * b0 + world.m[4] * b1 + world.m[8] * b2 + world.m[12] * b3
                out[j * 16 + c * 4 + 1] = world.m[1] * b0 + world.m[5] * b1 + world.m[9] * b2 + world.m[13] * b3
                out[j * 16 + c * 4 + 2] = world.m[2] * b0 + world.m[6] * b1 + world.m[10] * b2 + world.m[14] * b3
                out[j * 16 + c * 4 + 3] = world.m[3] * b0 + world.m[7] * b1 + world.m[11] * b2 + world.m[15] * b3
            }
        }
        return count
    }
}
