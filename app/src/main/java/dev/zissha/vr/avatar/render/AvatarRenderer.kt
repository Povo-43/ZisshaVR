package dev.zissha.vr.avatar.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.util.Log
import dev.zissha.vr.avatar.gltf.GltfDocument
import dev.zissha.vr.avatar.gltf.GltfPrimitive
import dev.zissha.vr.avatar.rig.ExpressionMapper
import dev.zissha.vr.gl.GlUtil
import dev.zissha.vr.gl.ShaderLib
import dev.zissha.vr.math.Mat4
import dev.zissha.vr.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES renderer for a loaded VRM/glTF avatar (spec 6.2).
 * GPU skinning via a bone-matrix float texture; morph targets applied on the CPU and re-uploaded
 * only when their weights change.
 */
class AvatarRenderer {

    companion object {
        private const val TAG = "AvatarRenderer"
        private const val LOC_POS = 0
        private const val LOC_NORMAL = 1
        private const val LOC_UV = 2
        private const val LOC_JOINTS = 3
        private const val LOC_WEIGHTS = 4
    }

    private var program = 0
    private var quadVao = 0
    private val uniformCache = HashMap<String, Int>()

    class PrimitiveGpu(
        val primitive: GltfPrimitive,
        val vao: Int,
        val positionVbo: Int,
        val indexCount: Int,
        val indexType: Int,
        val vertexCount: Int,
        val morphedPositions: FloatArray,
        val basePositions: FloatArray,
    )

    class MeshGpu(
        val meshIndex: Int,
        val nodeIndex: Int,
        val skinIndex: Int,
        val primitives: List<PrimitiveGpu>,
    )

    private val meshes = ArrayList<MeshGpu>()
    private val textures = HashMap<Int, Int>() // imageIndex -> GL texture

    private var boneTexture = 0
    private var boneMatrixBuffer: FloatArray = FloatArray(0)
    private var boneTexHeight = 0
    private var boneUploadBuffer: FloatBuffer? = null

    var loaded = false
        private set

    var doc: GltfDocument? = null
        private set

    /** active morph weights: (meshIndex, morphIndex) -> weight */
    private val morphWeights = HashMap<Long, Float>()

    fun load(doc: GltfDocument) {
        release()
        this.doc = doc
        program = GlUtil.createProgram(ShaderLib.AVATAR_VS, ShaderLib.AVATAR_FS)
        GlUtil.checkGlError("avatar program")

        // full screen quad for potential internal use + we keep a dummy VAO binding pattern
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        quadVao = vaos[0]

        val maxBones = (doc.skins.maxOfOrNull { it.joints.size } ?: 0).coerceAtLeast(1)
        boneTexHeight = maxBones * 4
        if (boneTexHeight > 0) {
            boneTexture = GlUtil.createTextureBoneMatrices(maxBones)
            boneMatrixBuffer = FloatArray(maxBones * 16)
            boneUploadBuffer = ByteBuffer.allocateDirect(boneMatrixBuffer.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        }

        // textures
        for ((imageIndex, image) in doc.images.withIndex()) {
            if (image.data.isEmpty()) continue
            val bmp = BitmapFactory.decodeByteArray(image.data, 0, image.data.size)
            if (bmp == null) {
                Log.w(TAG, "failed to decode image $imageIndex")
                continue
            }
            textures[imageIndex] = uploadTexture(bmp)
            bmp.recycle()
        }

        // meshes: find nodes that reference a mesh with a skin
        for (node in doc.nodes) {
            if (node.meshIndex < 0 || node.meshIndex >= doc.meshes.size) continue
            val mesh = doc.meshes[node.meshIndex]
            val primitives = ArrayList<PrimitiveGpu>()
            for (prim in mesh.primitives) {
                try {
                    primitives.add(createPrimitiveGpu(prim))
                } catch (e: Exception) {
                    Log.w(TAG, "primitive skipped: ${e.message}")
                }
            }
            if (primitives.isNotEmpty()) {
                meshes.add(MeshGpu(node.meshIndex, node.index, node.skinIndex, primitives))
            }
        }
        loaded = meshes.isNotEmpty()
        Log.i(TAG, "avatar loaded: ${meshes.size} mesh nodes, maxBones=$maxBones, images=${textures.size}")
    }

    private fun createPrimitiveGpu(prim: GltfPrimitive): PrimitiveGpu {
        val vertexCount = prim.positions.size / 3
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])

        val posBuf = floatBuffer(prim.positions)
        val posVbo = IntArray(1)
        GLES30.glGenBuffers(1, posVbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, posVbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, prim.positions.size * 4, posBuf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(LOC_POS)
        GLES30.glVertexAttribPointer(LOC_POS, 3, GLES30.GL_FLOAT, false, 0, 0)

        if (prim.normals != null) {
            val nbo = IntArray(1)
            GLES30.glGenBuffers(1, nbo, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, nbo[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, prim.normals.size * 4, floatBuffer(prim.normals), GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(LOC_NORMAL)
            GLES30.glVertexAttribPointer(LOC_NORMAL, 3, GLES30.GL_FLOAT, false, 0, 0)
        } else {
            // flat fallback: attribute disabled -> constant (0,0,1) via vertexAttrib default
            GLES30.glDisableVertexAttribArray(LOC_NORMAL)
            GLES30.glVertexAttrib3f(LOC_NORMAL, 0f, 0f, 1f)
        }

        val ubo = IntArray(1)
        GLES30.glGenBuffers(1, ubo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ubo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, prim.uvs.size * 4, floatBuffer(prim.uvs), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(LOC_UV)
        GLES30.glVertexAttribPointer(LOC_UV, 2, GLES30.GL_FLOAT, false, 0, 0)

        val jbo = IntArray(1)
        GLES30.glGenBuffers(1, jbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, jbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, prim.joints.size * 4, floatBuffer(prim.joints), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(LOC_JOINTS)
        GLES30.glVertexAttribPointer(LOC_JOINTS, 4, GLES30.GL_FLOAT, false, 0, 0)

        val wbo = IntArray(1)
        GLES30.glGenBuffers(1, wbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, wbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, prim.weights.size * 4, floatBuffer(prim.weights), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(LOC_WEIGHTS)
        GLES30.glVertexAttribPointer(LOC_WEIGHTS, 4, GLES30.GL_FLOAT, false, 0, 0)

        val ibo = IntArray(1)
        GLES30.glGenBuffers(1, ibo, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, prim.indices.size * 2, shortBuffer(prim.indices), GLES30.GL_STATIC_DRAW)

        GLES30.glBindVertexArray(0)
        return PrimitiveGpu(
            primitive = prim,
            vao = vao[0],
            positionVbo = posVbo[0],
            indexCount = prim.indices.size,
            indexType = GLES30.GL_UNSIGNED_SHORT,
            vertexCount = vertexCount,
            morphedPositions = prim.positions.copyOf(),
            basePositions = prim.positions,
        )
    }

    private fun uploadTexture(bmp: Bitmap): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun floatBuffer(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(arr); position(0)
        }

    private fun shortBuffer(arr: ShortArray): java.nio.ShortBuffer =
        ByteBuffer.allocateDirect(arr.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(arr); position(0)
        }

    /** Update morph weights (called per frame before draw). */
    fun setExpressionWeights(weights: ExpressionMapper.ExpressionWeights) {
        val doc = this.doc ?: return
        val vrm = doc.vrm ?: return
        morphWeights.clear()
        for (expr in vrm.expressions) {
            val w = weights.values[expr.name] ?: continue
            if (w < 0.01f) continue
            for (bind in expr.binds) {
                val meshIndex = bind.nodeIndex // VRM0: mesh index; VRM1: node index resolved below
                val resolvedMesh = if (vrm.specVersion == 1) {
                    doc.nodes.getOrNull(bind.nodeIndex)?.meshIndex ?: continue
                } else meshIndex
                if (resolvedMesh < 0) continue
                val key = morphKey(resolvedMesh, bind.morphIndex)
                morphWeights[key] = ((morphWeights[key] ?: 0f) + w * bind.weight).coerceIn(0f, 1f)
            }
        }
        applyMorphs()
    }

    private fun morphKey(mesh: Int, morph: Int): Long = (mesh.toLong() shl 32) or morph.toLong()

    private var lastMorphKey = -1L

    private fun applyMorphs() {
        val doc = this.doc ?: return
        // cheap change detection: hash of active weights
        var hash = 1125899906842597L
        for ((k, v) in morphWeights) {
            hash = hash * 31 + k
            hash = hash * 31 + (v * 1000).toInt()
        }
        if (hash == lastMorphKey) return
        lastMorphKey = hash

        for (meshGpu in meshes) {
            val mesh = doc.meshes[meshGpu.meshIndex]
            for (p in meshGpu.primitives.indices) {
                val prim = mesh.primitives[p]
                val gpu = meshGpu.primitives[p]
                if (prim.morphTargets.isEmpty()) continue
                val out = gpu.morphedPositions
                System.arraycopy(gpu.basePositions, 0, out, 0, out.size)
                for ((morphIdx, target) in prim.morphTargets.withIndex()) {
                    val w = morphWeights[morphKey(meshGpu.meshIndex, morphIdx)] ?: continue
                    if (w < 0.005f) continue
                    val delta = target.positionDelta
                    val n = minOf(out.size, delta.size)
                    for (i in 0 until n) out[i] += delta[i] * w
                }
                // re-upload positions
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gpu.positionVbo)
                val fb = floatBuffer(out)
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, out.size * 4, fb)
            }
        }
    }

    /** Draw the avatar. [viewProj] column-major. */
    fun draw(viewProj: Mat4, modelOffset: Vec3, modelScale: Float, lightDir: Vec3) {
        val doc = this.doc ?: return
        if (!loaded) return
        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        // straight-alpha color blending; alpha accumulates as coverage (not squared)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )

        uniform("uViewProj")?.let { GLES30.glUniformMatrix4fv(it, 1, false, viewProj.m, 0) }
        uniform("uModelOffset")?.let { GLES30.glUniform3f(it, modelOffset.x, modelOffset.y, modelOffset.z) }
        uniform("uModelScale")?.let { GLES30.glUniform1f(it, modelScale) }
        uniform("uLightDir")?.let { GLES30.glUniform3f(it, lightDir.x, lightDir.y, lightDir.z) }
        uniform("uBoneTex")?.let { GLES30.glUniform1i(it, 0) }

        // fill skin matrices from the rig *before* uploading: uploading first sent stale/zero
        // matrices and collapsed every skinned vertex onto the origin.
        val rig = skinRig
        if (rig != null) {
            for (meshGpu in meshes) {
                if (meshGpu.skinIndex >= 0) {
                    rig.fillSkinMatrices(meshGpu.skinIndex, boneMatrixBuffer)
                    break
                }
            }
        }

        // upload bone matrices
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, boneTexture)
        if (boneTexHeight > 0) {
            val fb = boneUploadBuffer!!
            fb.clear()
            fb.put(boneMatrixBuffer)
            fb.position(0)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, 1, boneTexHeight,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, fb,
            )
        }

        // draw opaque, then mask, then blend
        for (pass in 0..2) {
            for (meshGpu in meshes) {
                val mesh = doc.meshes[meshGpu.meshIndex]
                for ((pi, gpu) in meshGpu.primitives.withIndex()) {
                    val material = doc.materials.getOrNull(mesh.primitives[pi].materialIndex) ?: continue
                    val mode = material.alphaMode
                    if ((pass == 0 && mode != 0) || (pass == 1 && mode != 1) || (pass == 2 && mode != 2)) continue
                    uniform("uSkinned")?.let {
                        GLES30.glUniform1i(it, if (meshGpu.skinIndex >= 0 && rig != null) 1 else 0)
                    }
                    drawPrimitive(gpu, material)
                }
            }
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindVertexArray(0)
    }

    private val identity4 = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
    )

    var skinRig: dev.zissha.vr.avatar.rig.HumanoidRig? = null

    private fun drawPrimitive(gpu: PrimitiveGpu, material: dev.zissha.vr.avatar.gltf.GltfMaterial) {
        uniform("uBaseColorFactor")?.let {
            GLES30.glUniform4f(
                it, material.baseColorFactor[0], material.baseColorFactor[1],
                material.baseColorFactor[2], material.baseColorFactor[3],
            )
        }
        uniform("uAlphaMode")?.let { GLES30.glUniform1i(it, material.alphaMode) }
        uniform("uAlphaCutoff")?.let { GLES30.glUniform1f(it, material.alphaCutoff) }
        uniform("uAlphaOverride")?.let { GLES30.glUniform1f(it, -1f) }
        uniform("uHasTexture")?.let { GLES30.glUniform1i(it, if (material.baseColorTextureIndex >= 0) 1 else 0) }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        val tex = textures[material.baseColorTextureIndex]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex ?: 0)
        uniform("uBaseTex")?.let { GLES30.glUniform1i(it, 1) }
        if (material.doubleSided) {
            GLES30.glDisable(GLES30.GL_CULL_FACE)
        } else {
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glCullFace(GLES30.GL_BACK)
        }
        GLES30.glBindVertexArray(gpu.vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, gpu.indexCount, gpu.indexType, 0)
    }

    private fun uniform(name: String): Int? {
        val cached = uniformCache[name]
        if (cached != null) return if (cached < 0) null else cached
        val loc = GLES30.glGetUniformLocation(program, name)
        uniformCache[name] = loc
        return if (loc < 0) null else loc
    }

    fun release() {
        for (meshGpu in meshes) {
            for (p in meshGpu.primitives) {
                GLES30.glDeleteVertexArrays(1, intArrayOf(p.vao), 0)
                GLES30.glDeleteBuffers(1, intArrayOf(p.positionVbo), 0)
            }
        }
        meshes.clear()
        for (tex in textures.values) GlUtil.deleteTexture(tex)
        textures.clear()
        if (boneTexture != 0) GlUtil.deleteTexture(boneTexture)
        boneTexture = 0
        if (program != 0) GLES30.glDeleteProgram(program)
        program = 0
        // Uniform locations belong to the program that was just deleted; the cache must be dropped
        // or a reload (new program) would reuse stale locations and set the wrong uniforms.
        uniformCache.clear()
        loaded = false
        morphWeights.clear()
        lastMorphKey = -1
    }
}
