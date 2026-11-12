package dev.zissha.vr.avatar.gltf

import android.util.Base64
import android.util.Log
import dev.zissha.vr.math.Mat4
import dev.zissha.vr.math.Quat
import dev.zissha.vr.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal glTF 2.0 / GLB / VRM 0.x / VRM 1.0 parser (spec 3.1).
 *
 * Supported: meshes (POSITION/NORMAL/TEXCOORD_0/JOINTS_0/WEIGHTS_0), materials
 * (baseColorFactor/baseColorTexture/alphaMode), skins (inverseBindMatrices), morph targets
 * (POSITION deltas), VRM humanoid mapping, VRM 0.x blendShapeMaster, VRM 1.0 expressions,
 * VRM spring bones (0.x secondaryAnimation + 1.0 VRMC_springBone).
 *
 * Not supported (documented limitation): external buffer/texture files for .gltf, KHR texture
 * transforms, draco/meshopt compression, animations.
 */
object GlbParser {

    private const val TAG = "GlbParser"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(bytes: ByteArray): GltfDocument {
        var jsonText: String
        var bin: ByteArray? = null
        if (bytes.size >= 12 && bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte() &&
            bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            // GLB container
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.getInt() // magic
            val version = buf.getInt()
            require(version == 2) { "unsupported GLB version $version" }
            buf.getInt() // total length
            jsonText = ""
            while (buf.remaining() >= 8) {
                val chunkLen = buf.getInt()
                val chunkType = buf.getInt()
                val chunk = ByteArray(chunkLen)
                buf.get(chunk)
                when (chunkType) {
                    0x4E4F534A -> jsonText = String(chunk, Charsets.UTF_8)
                    0x004E4942 -> bin = chunk
                    else -> Log.w(TAG, "unknown GLB chunk 0x${Integer.toHexString(chunkType)}")
                }
            }
        } else {
            jsonText = String(bytes, Charsets.UTF_8)
        }
        val root = json.parseToJsonElement(jsonText).jsonObject
        return parseGltf(root, bin)
    }

    // ---------------------------------------------------------------------- JSON helpers

    private fun JsonObject.obj(name: String): JsonObject? = (this[name] as? JsonObject)

    private fun JsonObject.array(name: String): JsonArray? = (this[name] as? JsonArray)

    private fun JsonObject.int(name: String, def: Int = -1): Int =
        ((this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull })?.content?.toIntOrNull() ?: def

    private fun JsonObject.float(name: String, def: Float): Float =
        ((this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull })?.content?.toFloatOrNull() ?: def

    private fun JsonObject.str(name: String): String? =
        ((this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull })?.content

    private fun JsonArray.floatList(): List<Float> = map { it.jsonPrimitive.content.toFloat() }

    private fun JsonArray.intList(): List<Int> = map { it.jsonPrimitive.content.toInt() }

    // ------------------------------------------------------------------------ buffer data

    private class BufferContext(val root: JsonObject, val bin: ByteArray?) {
        val buffers: List<JsonObject> = root.array("buffers")?.map { it.jsonObject } ?: emptyList()
        val bufferViews: List<JsonObject> = root.array("bufferViews")?.map { it.jsonObject } ?: emptyList()

        fun bufferData(index: Int): ByteArray {
            val b = buffers[index]
            val uri = b.str("uri")
            return if (uri != null) {
                if (uri.startsWith("data:")) {
                    val base64 = uri.substringAfter("base64,")
                    Base64.decode(base64, Base64.DEFAULT)
                } else {
                    throw UnsupportedOperationException("external buffer URIs are not supported")
                }
            } else {
                bin ?: throw IllegalStateException("GLB binary chunk missing")
            }
        }
    }

    private fun readView(ctx: BufferContext, bufferViewIndex: Int, byteOffsetIn: Int): Pair<ByteBuffer, Int> {
        val view = ctx.bufferViews[bufferViewIndex]
        val data = ctx.bufferData(view.int("buffer", 0))
        val offset = view.int("byteOffset", 0) + byteOffsetIn
        val length = view.int("byteLength", data.size - offset)
        val stride = view.int("byteStride", 0)
        val bb = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        return bb to stride
    }

    private fun readAccessor(ctx: BufferContext, index: Int): Pair<ByteBuffer, Int> {
        val acc = ctx.root.array("accessors")!![index].jsonObject
        val componentSize = componentSize(acc.int("componentType"))
        val count = acc.int("count")
        val comps = components(acc.str("type") ?: "SCALAR")
        val byteOffset = acc.int("byteOffset", 0)
        val (bb, stride) = readView(ctx, acc.int("bufferView"), byteOffset)
        val effectiveStride = if (stride > 0) stride else componentSize * comps
        return bb to effectiveStride
    }

    private fun componentSize(type: Int): Int = when (type) {
        5120, 5121 -> 1 // byte, ubyte
        5122, 5123 -> 2 // short, ushort
        5125 -> 4 // uint
        5126 -> 4 // float
        else -> throw IllegalArgumentException("unsupported componentType $type")
    }

    private fun components(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        "MAT4" -> 16
        else -> throw IllegalArgumentException("unsupported type $type")
    }

    /** Read a float accessor as a flat array. */
    private fun readFloats(ctx: BufferContext, index: Int): FloatArray {
        val acc = ctx.root.array("accessors")!![index].jsonObject
        val compType = acc.int("componentType")
        val comps = components(acc.str("type") ?: "SCALAR")
        val count = acc.int("count")
        val (bb, stride) = readAccessor(ctx, index)
        val out = FloatArray(count * comps)
        for (i in 0 until count) {
            bb.mark()
            for (c in 0 until comps) {
                out[i * comps + c] = when (compType) {
                    5126 -> bb.float
                    5123 -> bb.short.toUShort().toInt().toFloat()
                    5121 -> bb.get().toUShort().toInt().toFloat()
                    5122 -> bb.short.toFloat()
                    5120 -> bb.get().toFloat()
                    5125 -> bb.int.toFloat()
                    else -> 0f
                }
            }
            if (stride > 0) {
                bb.reset()
                bb.position(bb.position() + stride)
            }
        }
        return out
    }

    /** Read an index accessor as shorts. */
    private fun readIndices(ctx: BufferContext, index: Int): ShortArray {
        val acc = ctx.root.array("accessors")!![index].jsonObject
        val compType = acc.int("componentType")
        val count = acc.int("count")
        val (bb, stride) = readAccessor(ctx, index)
        val out = ShortArray(count)
        for (i in 0 until count) {
            bb.mark()
            out[i] = when (compType) {
                5121 -> bb.get().toInt().toShort()
                5123 -> bb.short
                5125 -> bb.int.toShort()
                else -> throw IllegalArgumentException("unsupported index componentType $compType")
            }
            if (stride > 0) {
                bb.reset()
                bb.position(bb.position() + stride)
            }
        }
        return out
    }

    // --------------------------------------------------------------------------- document

    private fun parseGltf(root: JsonObject, bin: ByteArray?): GltfDocument {
        val ctx = BufferContext(root, bin)
        val meshesJson = root.array("meshes") ?: JsonArray(emptyList())
        val nodesJson = root.array("nodes") ?: JsonArray(emptyList())
        val materialsJson = root.array("materials") ?: JsonArray(emptyList())
        val imagesJson = root.array("images") ?: JsonArray(emptyList())
        val texturesJson = root.array("textures") ?: JsonArray(emptyList())
        val skinsJson = root.array("skins") ?: JsonArray(emptyList())
        val accessorsJson = root.array("accessors") ?: JsonArray(emptyList())

        // --- images
        val images = imagesJson.map { el ->
            val img = el.jsonObject
            val data: ByteArray
            val mime = img.str("mimeType") ?: "image/png"
            val bv = img.int("bufferView", -1)
            data = if (bv >= 0) {
                val (bb, _) = readView(ctx, bv, 0)
                ByteArray(bb.remaining()).also { bb.get(it) }
            } else {
                val uri = img.str("uri")
                if (uri != null && uri.startsWith("data:")) {
                    Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT)
                } else {
                    Log.w(TAG, "external image uri not supported: $uri")
                    ByteArray(0)
                }
            }
            GltfImage(img.str("name"), data, mime)
        }

        // --- textures
        val textures = texturesJson.map { el ->
            val t = el.jsonObject
            val src = t.int("source", -1)
            GltfTexture(src)
        }

        // --- materials
        val materials = materialsJson.map { el ->
            val m = el.jsonObject
            val pbr = m.obj("pbrMetallicRoughness")
            val baseColor = pbr?.array("baseColorFactor")?.floatList()?.toFloatArray()
                ?: floatArrayOf(1f, 1f, 1f, 1f)
            val texIndex = pbr?.obj("baseColorTexture")?.int("index", -1) ?: -1
            val alphaModeStr = m.str("alphaMode") ?: "OPAQUE"
            val alphaMode = when (alphaModeStr) {
                "MASK" -> 1
                "BLEND" -> 2
                else -> 0
            }
            GltfMaterial(
                name = m.str("name"),
                baseColorFactor = baseColor,
                baseColorTextureIndex = if (texIndex >= 0 && texIndex < textures.size) textures[texIndex].imageIndex else -1,
                alphaMode = alphaMode,
                alphaCutoff = m.float("alphaCutoff", 0.5f),
                doubleSided = (m["doubleSided"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            )
        }

        // --- meshes
        val meshes = meshesJson.map { el ->
            val m = el.jsonObject
            val prims = m.array("primitives")?.map { pel ->
                val p = pel.jsonObject
                val attrs = p.obj("attributes") ?: JsonObject(emptyMap())
                val posIdx = attrs.int("POSITION", -1)
                require(posIdx >= 0) { "primitive without POSITION" }
                val positions = readFloats(ctx, posIdx)
                val normals = attrs.int("NORMAL", -1).takeIf { it >= 0 }?.let { readFloats(ctx, it) }
                val uvIdx = attrs.int("TEXCOORD_0", -1)
                val uvs = if (uvIdx >= 0) readFloats(ctx, uvIdx) else FloatArray(positions.size / 3 * 2)
                val idxIdx = p.int("indices", -1)
                val indices = if (idxIdx >= 0) readIndices(ctx, idxIdx) else {
                    ShortArray(positions.size / 3) { it.toShort() }
                }
                val joints = attrs.int("JOINTS_0", -1).takeIf { it >= 0 }?.let { readFloats(ctx, it) }
                    ?: FloatArray(positions.size / 3 * 4)
                val weights = attrs.int("WEIGHTS_0", -1).takeIf { it >= 0 }?.let { readFloats(ctx, it) }
                    ?: FloatArray(positions.size / 3 * 4)
                val targets = p.array("targets")?.map { tel ->
                    val t = tel.jsonObject
                    val pd = t.int("POSITION", -1).takeIf { it >= 0 }?.let { readFloats(ctx, it) }
                        ?: FloatArray(positions.size)
                    val nd = t.int("NORMAL", -1).takeIf { it >= 0 }?.let { readFloats(ctx, it) }
                    MorphTarget(pd, nd)
                } ?: emptyList()
                GltfPrimitive(positions, normals, uvs, indices, joints, weights, p.int("material", 0), targets)
            } ?: emptyList()
            GltfMesh(m.str("name"), prims)
        }

        // --- nodes
        val nodes = nodesJson.mapIndexed { i, el ->
            val n = el.jsonObject
            val node = GltfNode(index = i, name = n.str("name"))
            node.meshIndex = n.int("mesh", -1)
            node.skinIndex = n.int("skin", -1)
            val t = n.array("translation")?.floatList()
            val r = n.array("rotation")?.floatList()
            val s = n.array("scale")?.floatList()
            if (t != null && t.size >= 3) node.translation = Vec3(t[0], t[1], t[2])
            if (r != null && r.size >= 4) node.rotation = Quat(r[0], r[1], r[2], r[3]).normalized()
            if (s != null && s.size >= 3) node.scale = Vec3(s[0], s[1], s[2])
            n.array("children")?.intList()?.let { node.children.addAll(it) }
            node
        }
        // node local "matrix" overrides TRS
        nodesJson.forEachIndexed { i, el ->
            val n = el.jsonObject
            val matrix = n.array("matrix")?.floatList()
            if (matrix != null && matrix.size == 16) {
                val mat = Mat4()
                System.arraycopy(matrix.toFloatArray(), 0, mat.m, 0, 16)
                // decompose (assumes no skew)
                val translation = mat.extractTranslation()
                val rotation = mat.extractRotation()
                val sx = dev.zissha.vr.math.Vec3(mat.m[0], mat.m[1], mat.m[2]).length()
                val sy = dev.zissha.vr.math.Vec3(mat.m[4], mat.m[5], mat.m[6]).length()
                val sz = dev.zissha.vr.math.Vec3(mat.m[8], mat.m[9], mat.m[10]).length()
                nodes[i].translation = translation
                nodes[i].rotation = rotation
                nodes[i].scale = Vec3(sx, sy, sz)
            }
        }

        // --- skins
        val skins = skinsJson.map { el ->
            val s = el.jsonObject
            val joints = s.array("joints")?.intList()?.toIntArray() ?: IntArray(0)
            val ibmIdx = s.int("inverseBindMatrices", -1)
            val ibm = if (ibmIdx >= 0) readFloats(ctx, ibmIdx) else FloatArray(0)
            val mats = Array(joints.size) { j ->
                val mat = Mat4()
                if (ibm.size >= (j + 1) * 16) {
                    System.arraycopy(ibm, j * 16, mat.m, 0, 16)
                } else {
                    mat.setIdentity()
                }
                mat
            }
            GltfSkin(s.str("name"), joints, mats)
        }

        // --- scene roots
        val sceneIdx = root.int("scene", 0)
        val scenes = root.array("scenes")
        val sceneObj = scenes?.getOrNull(sceneIdx)?.jsonObject
        val sceneRoots = sceneObj?.array("nodes")?.intList()
            ?: nodes.indices.filter { nodeIdx -> nodes.none { it.children.contains(nodeIdx) } }

        // --- compute rest matrices
        val visited = BooleanArray(nodes.size)
        for (rootIdx in sceneRoots) {
            computeWorld(nodes, rootIdx, Mat4.identity(), visited)
        }
        for (node in nodes) {
            if (!visited[node.index]) {
                // orphan nodes (spring bone chains etc.): compute with identity parent
                computeWorld(nodes, node.index, Mat4.identity(), visited)
            }
        }

        val vrm = parseVrm(root, nodes)
        return GltfDocument(nodes, meshes, materials, images, textures, skins, sceneRoots, vrm)
    }

    private fun computeWorld(nodes: List<GltfNode>, index: Int, parent: Mat4, visited: BooleanArray) {
        if (visited[index]) return
        visited[index] = true
        val node = nodes[index]
        node.restLocal = Mat4().setFromTrs(node.translation, node.rotation, node.scale)
        node.restWorld = Mat4().mul(parent, node.restLocal)
        for (child in node.children) {
            if (child in nodes.indices) computeWorld(nodes, child, node.restWorld, visited)
        }
    }

    // ------------------------------------------------------------------------------ VRM

    private fun parseVrm(root: JsonObject, nodes: List<GltfNode>): VrmData? {
        val vrm0 = root.obj("extensions")?.obj("VRM")
        val vrm1 = root.obj("extensions")?.obj("VRMC_vrm")
        val humanoid = HashMap<String, Int>()
        val expressions = ArrayList<VrmExpression>()
        val springGroups = ArrayList<SpringBoneGroup>()
        val colliders = ArrayList<SpringCollider>()

        if (vrm0 != null) {
            // ---- humanoid (VRM 0.x)
            vrm0.obj("humanoid")?.array("humanBones")?.forEach { el ->
                val b = el.jsonObject
                val bone = normalizeBoneName(b.str("bone") ?: return@forEach)
                val node = b.int("node", -1)
                if (bone != null && node >= 0) humanoid[bone] = node
            }
            // ---- blendShapeMaster (VRM 0.x)
            vrm0.obj("blendShapeMaster")?.array("blendShapeGroups")?.forEach { el ->
                val g = el.jsonObject
                val preset = normalizeExpressionName(g.str("presetName"))
                val name = g.str("name") ?: preset ?: return@forEach
                val isBinary = (g["isBinary"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                val binds = g.array("binds")?.map { bel ->
                    val b = bel.jsonObject
                    val mesh = b.int("mesh", -1)
                    val morphIndex = b.int("index", -1)
                    var weight = b.float("weight", 100f) / 100f
                    if (isBinary) weight = 1f
                    if (mesh >= 0 && morphIndex >= 0) ExpressionBind(mesh, morphIndex, weight) else null
                }?.filterNotNull() ?: emptyList()
                if (binds.isNotEmpty()) expressions.add(VrmExpression(name, preset, binds))
            }
            // ---- spring bones (VRM 0.x secondaryAnimation)
            val colliderNodes = ArrayList<SpringCollider>()
            vrm0.obj("secondaryAnimation")?.array("colliders")?.forEach { el ->
                val c = el.jsonObject
                val node = c.int("node", -1)
                val radius = c.float("hitRadius", 0.1f)
                if (node >= 0) colliderNodes.add(SpringCollider(node, radius))
            }
            colliders.addAll(colliderNodes)
            val groupList = vrm0.obj("secondaryAnimation")?.array("groups")
                ?: vrm0.obj("secondaryAnimation")?.array("boneGroups")
            groupList?.forEach { el ->
                val g = el.jsonObject
                val boneNodes = g.array("bones")?.intList() ?: emptyList()
                val stiffness = if (g["stiffiness"] != null) g.float("stiffiness", 1f) else g.float("stiffness", 1f)
                val joints = boneNodes.map { nodeIdx ->
                    SpringBoneJoint(
                        nodeIndex = nodeIdx,
                        hitRadius = g.float("hitRadius", 0.05f),
                        stiffness = stiffness, // note: 0.x spec misspells "stiffiness"
                        gravityPower = g.float("gravityPower", 0f),
                        gravityDir = g.array("gravityDir")?.floatList()?.let {
                            if (it.size >= 3) Vec3(it[0], it[1], it[2]) else Vec3(0f, -1f, 0f)
                        } ?: Vec3(0f, -1f, 0f),
                        dragForce = g.float("dragForce", 0.4f),
                    )
                }
                val colliderRefs = g.array("colliderGroups")?.intList() ?: emptyList()
                if (joints.isNotEmpty()) springGroups.add(SpringBoneGroup(g.str("comment"), joints, colliderRefs))
            }
        }

        if (vrm1 != null) {
            // ---- humanoid (VRM 1.0)
            vrm1.obj("humanoid")?.obj("humanBones")?.forEach { (bone, el) ->
                val b = el.jsonObject
                val node = b.int("node", -1)
                val normalized = normalizeBoneName(bone)
                if (node >= 0 && normalized != null) humanoid[normalized] = node
            }
            // ---- expressions (VRM 1.0)
            val exprRoot = vrm1.obj("expressions")
            fun parseExpressionEntry(name: String, el: JsonElement) {
                val e = el.jsonObject
                val binds = e.array("morphTargetBinds")?.map { bel ->
                    val b = bel.jsonObject
                    val node = b.int("node", -1)
                    val index = b.int("index", -1)
                    val weight = b.float("weight", 1f)
                    if (node >= 0 && index >= 0) ExpressionBind(node, index, weight) else null
                }?.filterNotNull() ?: emptyList()
                if (binds.isNotEmpty()) expressions.add(VrmExpression(name, normalizeExpressionName(name), binds))
            }
            exprRoot?.obj("preset")?.forEach { (name, el) -> parseExpressionEntry(name, el) }
            exprRoot?.obj("custom")?.forEach { (name, el) -> parseExpressionEntry(name, el) }
            // ---- spring bones (VRM 1.0)
            val sb = root.obj("extensions")?.obj("VRMC_springBone")
            if (sb != null) {
                sb.array("colliders")?.forEach { el ->
                    val c = el.jsonObject
                    val node = c.int("node", -1)
                    val radius = c.obj("shape")?.obj("sphere")?.float("radius", 0.1f) ?: 0.1f
                    if (node >= 0) colliders.add(SpringCollider(node, radius))
                }
                sb.array("springs")?.forEach { el ->
                    val s = el.jsonObject
                    val joints = s.array("joints")?.map { jel ->
                        val j = jel.jsonObject
                        SpringBoneJoint(
                            nodeIndex = j.int("node", -1),
                            hitRadius = j.float("hitRadius", 0.05f),
                            stiffness = j.float("stiffness", 1f),
                            gravityPower = j.float("gravityPower", 0f),
                            gravityDir = j.array("gravityDir")?.floatList()?.let {
                                if (it.size >= 3) Vec3(it[0], it[1], it[2]) else Vec3(0f, -1f, 0f)
                            } ?: Vec3(0f, -1f, 0f),
                            dragForce = j.float("dragForce", 0.4f),
                        )
                    }?.filter { it.nodeIndex >= 0 } ?: emptyList()
                    if (joints.isNotEmpty()) springGroups.add(SpringBoneGroup(s.str("name"), joints, emptyList()))
                }
            }
        }

        if (humanoid.isEmpty()) return null
        // mark nodes
        for ((bone, nodeIdx) in humanoid) {
            if (nodeIdx in nodes.indices) nodes[nodeIdx].humanoidBone = bone
        }
        val specVersion = if (vrm1 != null) 1 else 0
        return VrmData(humanoid, expressions, springGroups, colliders, specVersion)
    }

    /** Normalize VRM 0.x / VRM 1.0 bone names to a shared camelCase vocabulary. */
    fun normalizeBoneName(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        // VRM0 uses names like "J_Bip_C_Hips"? No - VRM0 enum: "hips". Some exporters use lowercase first.
        val camel = n.replaceFirstChar { it.lowercase() }
        val known = setOf(
            "hips", "spine", "chest", "upperChest", "neck", "head",
            "leftShoulder", "leftUpperArm", "leftLowerArm", "leftHand",
            "rightShoulder", "rightUpperArm", "rightLowerArm", "rightHand",
            "leftUpperLeg", "leftLowerLeg", "leftFoot", "leftToes",
            "rightUpperLeg", "rightLowerLeg", "rightFoot", "rightToes",
            "leftEye", "rightEye", "jaw",
            "leftThumbProximal", "leftThumbIntermediate", "leftThumbDistal",
            "leftIndexProximal", "leftIndexIntermediate", "leftIndexDistal",
            "leftMiddleProximal", "leftMiddleIntermediate", "leftMiddleDistal",
            "leftRingProximal", "leftRingIntermediate", "leftRingDistal",
            "leftLittleProximal", "leftLittleIntermediate", "leftLittleDistal",
            "rightThumbProximal", "rightThumbIntermediate", "rightThumbDistal",
            "rightIndexProximal", "rightIndexIntermediate", "rightIndexDistal",
            "rightMiddleProximal", "rightMiddleIntermediate", "rightMiddleDistal",
            "rightRingProximal", "rightRingIntermediate", "rightRingDistal",
            "rightLittleProximal", "rightLittleIntermediate", "rightLittleDistal",
        )
        return if (n in known) n else if (camel in known) camel else n
    }

    /** Normalize VRM 0.x preset names (a/i/u/e/o, joy/sorrow/fun) to VRM 1.0 preset names. */
    fun normalizeExpressionName(name: String?): String? {
        if (name == null) return null
        return when (name.lowercase()) {
            "a" -> "aa"
            "i" -> "ih"
            "u" -> "ou"
            "e" -> "ee"
            "o" -> "oh"
            "joy" -> "happy"
            "angry" -> "angry"
            "sorrow" -> "sad"
            "fun" -> "relaxed"
            "blink_l" -> "blinkLeft"
            "blink_r" -> "blinkRight"
            else -> name
        }
    }
}
