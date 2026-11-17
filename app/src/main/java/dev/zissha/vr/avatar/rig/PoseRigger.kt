package dev.zissha.vr.avatar.rig

import dev.zissha.vr.avatar.gltf.GltfDocument
import dev.zissha.vr.avatar.gltf.VrmData
import dev.zissha.vr.math.Mat4
import dev.zissha.vr.math.Quat
import dev.zissha.vr.math.Vec3
import dev.zissha.vr.tracking.HandSkeleton
import dev.zissha.vr.tracking.OneEuroFilter
import dev.zissha.vr.tracking.OneEuroFilter3
import dev.zissha.vr.tracking.PoseResult

/**
 * Maps MediaPipe tracking results onto VRM humanoid bones (spec 4 / 3.1 Humanoid Bone Mapping).
 *
 * Clean rewrite: every bone is driven independently and its last valid world rotation is held
 * through dropouts, so a single low-confidence frame no longer snaps the avatar back to its rest
 * pose and forward again (the "flicker").
 *
 * View space: x right, y up, z toward the viewer. The tracking input is the *displayed*
 * (mirrored, selfie) image, so landmark y is "down on screen" exactly like the composite.
 */
class PoseRigger(doc: GltfDocument, vrm: VrmData) {

    private val rig = HumanoidRig(doc, vrm)

    /** User scale multiplier applied on top of the tracking-derived scale. */
    @Volatile var scaleMultiplier: Float = 1f

    // Rest directions in avatar/world space, computed once at load.
    private var restUpperArmL: Vec3? = null
    private var restLowerArmL: Vec3? = null
    private var restUpperArmR: Vec3? = null
    private var restLowerArmR: Vec3? = null
    private var restUpperLegL: Vec3? = null
    private var restLowerLegL: Vec3? = null
    private var restUpperLegR: Vec3? = null
    private var restLowerLegR: Vec3? = null
    private var restTorsoUp: Vec3? = null
    private var avatarShoulderWidth: Float = 0.3f
    private var avatarHipsToFeet: Float = 0.9f
    /** Rest midpoint of the shoulder joints (anchor for root position). */
    private var restShoulderMid: Vec3? = null
    /** Rest length from hips midpoint to shoulders midpoint (like-for-like scale reference). */
    private var restTorsoLength: Float = 0.3f
    /** Avatar rest hips position (pivot of the model transform). */
    private var restHip: Vec3 = Vec3(0f, 0.9f, 0f)

    // smoothed root state
    private var rootPos = Vec3(0f, 0f, 0f)
    private var rootScale = 1f
    private var rootPosFilter = OneEuroFilter3(minCutoff = 0.5f, beta = 0.02f)
    private var rootScaleFilter = OneEuroFilter(minCutoff = 0.4f, beta = 0.01f)
    private var filterT = 0f

    /** Last valid world rotation per node, held through visibility dropouts. */
    private val boneRotation = HashMap<Int, Quat>()

    /** Last valid face head rotation (view space), held through face dropouts. */
    private var lastHeadRotation: Quat? = null
    private var framesSinceFace = Int.MAX_VALUE

    /**
     * World-space model transform derived from tracking. The renderer applies it after skinning
     * (`world = skinned * modelScale + modelOffset`), scaling the avatar about its hips and
     * placing those hips on the tracked person's hips.
     */
    var modelScale: Float = 1f
        private set
    var modelOffset: Vec3 = Vec3.ZERO
        private set

    init {
        fun node(name: String) = rig.boneNode(name)
        fun restDir(from: String, to: String): Vec3? =
            rig.boneNode(from)?.let { l ->
                rig.boneNode(to)?.let { r -> rig.restDirection(l, r) }
            }

        restUpperArmL = restDir("leftUpperArm", "leftLowerArm")
        restLowerArmL = restDir("leftLowerArm", "leftHand")
        restUpperArmR = restDir("rightUpperArm", "rightLowerArm")
        restLowerArmR = restDir("rightLowerArm", "rightHand")
        restUpperLegL = restDir("leftUpperLeg", "leftLowerLeg")
        restLowerLegL = restDir("leftLowerLeg", "leftFoot")
        restUpperLegR = restDir("rightUpperLeg", "rightLowerLeg")
        restLowerLegR = restDir("rightLowerLeg", "rightFoot")
        restTorsoUp = restDir("hips", "spine")
            ?: restDir("hips", "chest")

        fun restTranslation(name: String): Vec3? =
            rig.boneNode(name)?.let { rig.nodes[it].restWorld.extractTranslation() }
        val ls = restTranslation("leftShoulder")
        val rs = restTranslation("rightShoulder")
        if (ls != null && rs != null) avatarShoulderWidth = (ls - rs).length().coerceAtLeast(0.05f)
        restShoulderMid = if (ls != null && rs != null) {
            (ls + rs) * 0.5f
        } else {
            val lu = restTranslation("leftUpperArm")
            val ru = restTranslation("rightUpperArm")
            if (lu != null && ru != null) (lu + ru) * 0.5f else null
        }
        val hips = restTranslation("hips")
        val footL = restTranslation("leftFoot")
        val footR = restTranslation("rightFoot")
        if (hips != null && footL != null && footR != null) {
            avatarHipsToFeet = hips.y - ((footL.y + footR.y) / 2f).coerceAtLeast(0.1f)
        }
        restHip = restTranslation("hips") ?: Vec3(0f, 0.9f, 0f)
        restShoulderMid?.let { mid ->
            restTorsoLength = (mid - restHip).length().coerceAtLeast(0.05f)
        }
    }

    /** The underlying rig; the renderer calls [rig.evaluate] after [applyPose]. */
    val humanoidRig: HumanoidRig get() = rig

    /**
     * Drive the rig from a pose frame.
     * @param viewWidth  world-space width of the visible frustum at the root plane
     * @param viewHeight world-space height of the visible frustum at the root plane
     * @param cameraHeight world-space Y of the scene camera
     */
    fun applyPose(pose: PoseResult, viewWidth: Float, viewHeight: Float, cameraHeight: Float, dt: Float) {
        rig.resetOverrides()
        val lm = pose.landmarks
        val w = pose.worldLandmarks
        val conv = rig.viewToAvatar

        fun visible(i: Int, thr: Float = 0.3f): Boolean =
            i in lm.indices && lm[i].visibility >= thr

        fun wZ(i: Int): Float = if (i in w.indices) w[i].z else 0f

        /**
         * View-space direction from one landmark to another.
         * x right, y down on screen, z toward viewer (from world landmark z).
         */
        fun viewDir(from: Int, to: Int): Vec3? {
            if (!visible(from) || !visible(to)) return null
            val dx = (lm[to].x - lm[from].x) * viewWidth
            val dy = (lm[to].y - lm[from].y) * viewHeight
            val dz = (wZ(to) - wZ(from))
            val v = Vec3(dx, -dy, dz * Z_SIGN)
            return if (v.length() > 1e-5f) v.normalized() else null
        }

        fun midWorld(a: Int, b: Int): Vec3? {
            if (!visible(a) || !visible(b)) return null
            return Vec3(
                x = ((lm[a].x + lm[b].x) * 0.5f - 0.5f) * viewWidth,
                y = cameraHeight - ((lm[a].y + lm[b].y) * 0.5f - 0.5f) * viewHeight,
                z = 0f,
            )
        }

        // ================= root: position from shoulders/hips, scale from torso length =================
        val shouldersOk = visible(11) && visible(12)
        val hipsOk = visible(23) && visible(24)
        val shoulderMid = if (shouldersOk) midWorld(11, 12) else null

        // scale: torso length (like-for-like joint centers) first, shoulder width fallback
        var targetScale: Float? = null
        if (shouldersOk && hipsOk && restTorsoLength > 1e-4f) {
            val personTorso = (shoulderMid!! - midWorld(23, 24)!!).length()
            if (personTorso > 1e-4f) {
                targetScale = (personTorso / restTorsoLength).coerceIn(0.25f, 3.5f) * scaleMultiplier
            }
        }
        if (targetScale == null && shouldersOk && avatarShoulderWidth > 1e-4f) {
            val sdx = (lm[11].x - lm[12].x) * viewWidth
            val sdy = (lm[11].y - lm[12].y) * viewHeight
            val personShoulderWorld = kotlin.math.hypot(sdx.toDouble(), sdy.toDouble()).toFloat()
            targetScale = (personShoulderWorld / avatarShoulderWidth).coerceIn(0.25f, 3.5f) * scaleMultiplier
        }
        filterT += dt.coerceIn(0f, 0.1f)
        if (targetScale != null) {
            rootScale = rootScaleFilter.filter(targetScale, filterT)
        }
        modelScale = rootScale

        // position: prefer observed hips; otherwise project hips out of the shoulders.
        val targetHip: Vec3? = when {
            hipsOk -> midWorld(23, 24)
            shoulderMid != null && restShoulderMid != null ->
                shoulderMid - (restShoulderMid!! - restHip) * rootScale
            else -> null
        }
        if (targetHip != null) {
            val f = rootPosFilter.filter(targetHip.x, targetHip.y, targetHip.z, filterT)
            rootPos = Vec3(f[0], f[1], 0f)
        }
        modelOffset = Vec3(
            rootPos.x - restHip.x * rootScale,
            rootPos.y - restHip.y * rootScale,
            rootPos.z - restHip.z * rootScale,
        )

        // ================= torso: absolute up direction =================
        if (shouldersOk && hipsOk) {
            val hipMid = midWorld(23, 24)!!
            val shoulderMidV = shoulderMid!!
            val torsoUp = Vec3(
                shoulderMidV.x - hipMid.x,
                shoulderMidV.y - hipMid.y,
                ((wZ(11) + wZ(12)) / 2f - (wZ(23) + wZ(24)) / 2f),
            ).normalized()
            if (torsoUp.length() > 1e-5f) {
                val up = torsoUp.normalized()
                val restUp = restTorsoUp ?: Vec3(0f, 1f, 0f)
                rig.boneNode("hips")?.let { hipsNode ->
                    val restWorldRot = rig.nodes[hipsNode].restWorld.extractRotation()
                    rig.worldRotationOverride[hipsNode] = Quat.fromToRotation(restUp, up) * restWorldRot
                }
                rig.boneNode("spine")?.let { spineNode ->
                    val restWorldRot = rig.nodes[spineNode].restWorld.extractRotation()
                    rig.worldRotationOverride[spineNode] =
                        Quat.fromToRotation(Vec3(0f, 1f, 0f), up) * restWorldRot
                }
            }
        }

        // ================= limbs =================
        driveBone("leftUpperArm", viewDir(11, 13), restUpperArmL)
        driveBone("leftLowerArm", viewDir(13, 15), restLowerArmL)
        driveBone("rightUpperArm", viewDir(12, 14), restUpperArmR)
        driveBone("rightLowerArm", viewDir(14, 16), restLowerArmR)
        driveBone("leftUpperLeg", viewDir(23, 25), restUpperLegL)
        driveBone("leftLowerLeg", viewDir(25, 27), restLowerLegL)
        driveBone("rightUpperLeg", viewDir(24, 26), restUpperLegR)
        driveBone("rightLowerLeg", viewDir(26, 28), restLowerLegR)
        driveBone("leftFoot", viewDir(27, 31), Vec3(0f, -1f, 0f))
        driveBone("rightFoot", viewDir(28, 32), Vec3(0f, -1f, 0f))
    }

    /**
     * Drive one bone to [desiredDir] (view space), holding the last valid rotation through
     * dropouts. [restDir] is the bone's rest direction (avatar space).
     */
    private fun driveBone(name: String, desiredDir: Vec3?, restDir: Vec3?) {
        if (desiredDir == null || desiredDir.length() < 1e-5f) return
        val node = rig.boneNode(name) ?: return
        val rest = restDir ?: return
        val restWorldRot = rig.nodes[node].restWorld.extractRotation()
        val target = Quat.fromToRotation(rest, desiredDir) * restWorldRot
        val smoothed = boneRotation[node]?.let { last ->
            last.slerp(target, 0.6f).normalized()
        } ?: target
        boneRotation[node] = smoothed
        rig.worldRotationOverride[node] = smoothed
    }

    /** Drive finger bones from a tracked hand. */
    fun applyHand(hand: HandSkeleton) {
        val side = if (hand.isLeftHand) "left" else "right"
        val wl = hand.worldLandmarks
        val conv = rig.viewToAvatar
        fun lmVec(i: Int): Vec3 =
            conv.rotate(Vec3(wl[i].x, -wl[i].y, wl[i].z * Z_SIGN))

        rig.boneNode("${side}Hand")?.let { handNode ->
            val midProximal = rig.boneNode("${side}MiddleProximal")
            if (midProximal != null) {
                val desired = (lmVec(9) - lmVec(0)).normalized()
                if (desired.length() > 1e-5f) {
                    val rest = rig.restDirection(handNode, midProximal)
                    val restWorldRot = rig.nodes[handNode].restWorld.extractRotation()
                    val target = Quat.fromToRotation(rest, desired) * restWorldRot
                    val smoothed = boneRotation[handNode]?.let { last ->
                        last.slerp(target, 0.6f).normalized()
                    } ?: target
                    boneRotation[handNode] = smoothed
                    rig.worldRotationOverride[handNode] = smoothed
                }
            }
        }
        driveFinger(side, "Thumb", intArrayOf(1, 2, 3, 4)) { lmVec(it) }
        driveFinger(side, "Index", intArrayOf(5, 6, 7, 8)) { lmVec(it) }
        driveFinger(side, "Middle", intArrayOf(9, 10, 11, 12)) { lmVec(it) }
        driveFinger(side, "Ring", intArrayOf(13, 14, 15, 16)) { lmVec(it) }
        driveFinger(side, "Little", intArrayOf(17, 18, 19, 20)) { lmVec(it) }
    }

    private fun driveFinger(
        side: String,
        finger: String,
        lm: IntArray,
        lmVec: (Int) -> Vec3,
    ) {
        val segments = arrayOf("Proximal", "Intermediate", "Distal")
        for (s in 0 until 3) {
            val bone = rig.boneNode("${side}${finger}${segments[s]}") ?: continue
            val desired = (lmVec(lm[s + 1]) - lmVec(lm[s])).normalized()
            if (desired.length() < 1e-5f) continue
            val nextBone = rig.boneNode("${side}${finger}${segments.getOrNull(s + 1) ?: ""}")
            val rest = when {
                nextBone != null && nextBone != bone -> rig.restDirection(bone, nextBone)
                else -> Vec3(0f, 1f, 0f)
            }
            val restWorldRot = rig.nodes[bone].restWorld.extractRotation()
            val target = Quat.fromToRotation(rest, desired) * restWorldRot
            val smoothed = boneRotation[bone]?.let { last ->
                last.slerp(target, 0.6f).normalized()
            } ?: target
            boneRotation[bone] = smoothed
            rig.worldRotationOverride[bone] = smoothed
        }
    }

    /**
     * Drive neck/head from the face landmarker's head pose (view space). The neck follows with a
     * fraction of it. Held through face dropouts via [lastHeadRotation].
     */
    fun applyFace(headRotation: Quat?) {
        if (headRotation != null) {
            lastHeadRotation = headRotation
            framesSinceFace = 0
        } else {
            framesSinceFace++
        }
        val q = lastHeadRotation ?: return
        rig.boneNode("head")?.let {
            rig.worldRotationOverride[it] = q
        }
        rig.boneNode("neck")?.let { neck ->
            rig.worldRotationOverride[neck] = Quat.IDENTITY.slerp(q, 0.35f).normalized()
        }
    }

    companion object {
        /**
         * MediaPipe world-landmark z axis sign relative to "toward the viewer".
         * Tunable: flip if limb depth appears inverted on a device.
         */
        const val Z_SIGN = -1f
    }
}
