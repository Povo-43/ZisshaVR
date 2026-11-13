package dev.zissha.vr.avatar.render

import android.util.Log
import dev.zissha.vr.avatar.gltf.GltfDocument
import dev.zissha.vr.avatar.rig.ExpressionMapper
import dev.zissha.vr.avatar.rig.HumanoidRig
import dev.zissha.vr.avatar.rig.PoseRigger
import dev.zissha.vr.avatar.rig.SpringBoneSim
import dev.zissha.vr.math.Mat4
import dev.zissha.vr.math.Vec3
import dev.zissha.vr.tracking.TrackingFrame

/**
 * Per-frame avatar orchestration: pose mapping, expressions, spring bones, skin matrices.
 * Lives on the GL thread.
 */
class AvatarController(val renderer: AvatarRenderer) {

    companion object {
        private const val TAG = "AvatarController"
        private const val CAM_DIST = 3.0f
        private const val CAM_HEIGHT = 1.05f
        private const val FOV_Y = 40f * (Math.PI.toFloat() / 180f)
        /** world-space height visible at the root plane */
        val VIEW_HEIGHT = 2f * kotlin.math.tan(FOV_Y / 2f) * CAM_DIST
        private const val SPRING_DT = 1f / 60f
        private const val MAX_SPRING_SUBSTEPS = 2
    }

    var rig: HumanoidRig? = null
        private set

    /** User scale adjustment (spec 12). */
    @Volatile var scaleMultiplier: Float = 1f

    /** True when the most recent update applied a tracking pose (diagnostics). */
    @Volatile var poseApplied: Boolean = false
        private set

    /** Number of mapped humanoid bones (diagnostics; 0 = bone mapping failed). */
    val boneCount: Int get() = rig?.humanoidBones?.size ?: 0

    private var rigger: PoseRigger? = null
    private var spring: SpringBoneSim? = null
    private val exprWeights = ExpressionMapper.ExpressionWeights()

    /** Scene camera view-projection; aspect follows the composite size. */
    private var viewProj = Mat4.identity()
    private var viewWidth = VIEW_HEIGHT * 0.5625f

    val cameraHeight: Float get() = CAM_HEIGHT

    fun load(doc: GltfDocument) {
        renderer.load(doc)
        val vrm = doc.vrm
        rig = null
        rigger = null
        spring = null
        if (vrm != null && renderer.loaded) {
            // PoseRigger owns the rig it poses. The renderer must skin from that exact instance:
            // creating a second HumanoidRig here left the renderer reading an unposed rig, so the
            // avatar stayed in its rest (T) pose and never followed tracking.
            val rg = PoseRigger(doc, vrm)
            val r = rg.humanoidRig
            rigger = rg
            rig = r
            spring = SpringBoneSim(r, doc, vrm.springBoneGroups)
            r.evaluate()
            Log.i(
                TAG,
                "rig ready: bones=${vrm.humanoidBones.size} expressions=${vrm.expressions.size} " +
                    "springJoints=${spring?.jointCount ?: 0} " +
                    "restYawed=${rig!!.restFacingFlipped}",
            )
        }
        renderer.skinRig = rig
        updateProjection(720, 1280)
    }

    fun updateProjection(compositeWidth: Int, compositeHeight: Int) {
        val aspect = compositeWidth.toFloat() / compositeHeight.toFloat()
        viewWidth = VIEW_HEIGHT * aspect
        val proj = Mat4.perspective(FOV_Y, aspect, 0.05f, 30f)
        val view = Mat4().setIdentity()
        view.m[12] = 0f
        view.m[13] = -CAM_HEIGHT
        view.m[14] = -CAM_DIST
        viewProj = Mat4().mul(proj, view)
    }

    fun hasAvatar(): Boolean = rig != null && renderer.loaded

    /** Drive the avatar from a tracking frame. dt in seconds. */
    fun update(tracking: TrackingFrame?, dt: Float, springEnabled: Boolean) {
        val rig = this.rig ?: return
        val rigger = this.rigger ?: return
        val pose = tracking?.pose
        poseApplied = pose != null
        if (pose != null) {
            rigger.scaleMultiplier = scaleMultiplier
            rigger.applyPose(pose, viewWidth, VIEW_HEIGHT, CAM_HEIGHT, dt)
            tracking.hands?.let { hands ->
                for (hand in hands.hands) rigger.applyHand(hand)
            }
        }
        rig.evaluate()

        if (springEnabled) {
            val spring = this.spring
            if (spring != null) {
                var remaining = dt
                var steps = 0
                while (remaining > 0f && steps < MAX_SPRING_SUBSTEPS) {
                    val step = minOf(SPRING_DT, remaining)
                    spring.update(step)
                    remaining -= step
                    steps++
                }
                if (steps > 0) rig.evaluate()
            }
        }

        tracking?.face?.let { face ->
            ExpressionMapper.map(face, exprWeights)
            renderer.setExpressionWeights(exprWeights)
        }
    }

    /**
     * Draw with the tracking-derived model transform (scale about the avatar hips + translation)
     * so the avatar lands on the tracked person in the composite.
     */
    fun draw() {
        val rg = rigger
        renderer.draw(
            viewProj,
            rg?.modelOffset ?: Vec3.ZERO,
            rg?.modelScale ?: 1f,
            Vec3(-0.4f, 0.8f, 0.6f).normalized(),
        )
    }
}
