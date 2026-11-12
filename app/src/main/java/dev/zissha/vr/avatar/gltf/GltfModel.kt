package dev.zissha.vr.avatar.gltf

import dev.zissha.vr.math.Mat4
import dev.zissha.vr.math.Quat
import dev.zissha.vr.math.Vec3

/**
 * In-memory glTF 2.0 / VRM model (spec 3). Produced by [GlbParser], consumed by the avatar
 * renderer and the rigging code.
 */
class GltfNode(
    val index: Int,
    val name: String?,
    val children: MutableList<Int> = ArrayList(),
    var translation: Vec3 = Vec3.ZERO,
    var rotation: Quat = Quat.IDENTITY,
    var scale: Vec3 = Vec3(1f, 1f, 1f),
) {
    /** Rest local matrix (never modified; runtime bones are evaluated separately). */
    var restLocal: Mat4 = Mat4.identity()
    var restWorld: Mat4 = Mat4.identity()
    var meshIndex: Int = -1
    var skinIndex: Int = -1

    /** Humanoid bone name (normalized, e.g. "leftUpperArm") when this node is a humanoid bone. */
    var humanoidBone: String? = null
}

class MorphTarget(
    val positionDelta: FloatArray,
    val normalDelta: FloatArray?,
)

class GltfPrimitive(
    val positions: FloatArray,
    val normals: FloatArray?,
    val uvs: FloatArray,
    val indices: ShortArray,
    /** 4 joint indices (into the skin's joint list) per vertex */
    val joints: FloatArray,
    /** 4 weights per vertex */
    val weights: FloatArray,
    val materialIndex: Int,
    val morphTargets: List<MorphTarget>,
)

class GltfMaterial(
    val name: String?,
    val baseColorFactor: FloatArray,
    val baseColorTextureIndex: Int,
    val alphaMode: Int, // 0 opaque, 1 mask, 2 blend
    val alphaCutoff: Float,
    val doubleSided: Boolean,
)

class GltfImage(val name: String?, val data: ByteArray, val mimeType: String)

class GltfTexture(val imageIndex: Int)

class GltfSkin(
    val name: String?,
    /** node indices, ordered as referenced by joint attributes */
    val joints: IntArray,
    val inverseBindMatrices: Array<Mat4>,
)

class GltfMesh(val name: String?, val primitives: List<GltfPrimitive>)

/** VRM expression (blendShape) bind to a mesh morph target. */
class ExpressionBind(val nodeIndex: Int, val morphIndex: Int, val weight: Float)

class VrmExpression(
    val name: String,
    val presetName: String?,
    val binds: List<ExpressionBind>,
)

class SpringBoneJoint(
    val nodeIndex: Int,
    val hitRadius: Float,
    val stiffness: Float,
    val gravityPower: Float,
    val gravityDir: Vec3,
    val dragForce: Float,
)

class SpringCollider(val nodeIndex: Int, val radius: Float)

class SpringBoneGroup(
    val name: String?,
    /** ordered joints forming one or more chains */
    val joints: List<SpringBoneJoint>,
    val colliders: List<Int>, // indices into colliders list
)

class VrmData(
    val humanoidBones: Map<String, Int>,
    val expressions: List<VrmExpression>,
    val springBoneGroups: List<SpringBoneGroup>,
    val springColliders: List<SpringCollider>,
    /** VRM 0.x legacy firstPerson / meta etc. reserved for future use */
    val specVersion: Int, // 0 or 1
)

class GltfDocument(
    val nodes: List<GltfNode>,
    val meshes: List<GltfMesh>,
    val materials: List<GltfMaterial>,
    val images: List<GltfImage>,
    val textures: List<GltfTexture>,
    val skins: List<GltfSkin>,
    val sceneRoots: List<Int>,
    val vrm: VrmData?,
) {
    /** Total morph target count across all meshes (for HUD). */
    fun morphCount(): Int = meshes.sumOf { mesh -> mesh.primitives.maxOfOrNull { it.morphTargets.size } ?: 0 }
}
