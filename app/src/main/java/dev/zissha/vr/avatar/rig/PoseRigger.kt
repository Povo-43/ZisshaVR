package dev.zissha.vr.avatar.rig

import dev.zissha.vr.avatar.gltf.GltfDocument
import dev.zissha.vr.avatar.gltf.VrmData
import dev.zissha.vr.math.Mat4
import dev.zissha.vr.math.Quat
import dev.zissha.vr.math.Vec3
import dev.zissha.vr.tracking.HandSkeleton
import dev.zissha.vr.tracking.PoseResult

/**
 * Maps MediaPipe tracking results onto VRM humanoid bones (spec 4 / 3.1 Humanoid Bone Mapping).
 *
 * Strategy:
 * - Limb bones are driven with direction-based FK: each segment's world rotation is the
 *   shortest-arc rotation from its rest direction to the landmark-derived direction.
 * - The head is driven by an orthonormal basis built from eye / mouth / ear landmarks.
 * - The root follows the person's hip position scaled by the shoulder-width ratio, with the
 *   feet clamped to the ground plane.
 *
 * View space: x right, y up, z toward the viewer, and the tracking input is the *displayed*
 * (mirrored, selfie) image, so landmark y is "down on screen" exactly like the composite. The
 * humanoid side naming lines up with MediaPipe's own anatomical labels because both are expressed
 * on the mirrored image; [HumanoidRig] yaws VRM 0.x models so the avatar faces the viewer.
 */
class PoseRigger(doc: GltfDocument, vrm: VrmData) {

    private val rig = HumanoidRig(doc, vrm)

    /** User scale multiplier applied on top of the tracking-derived scale. */
    @Volatile var scaleMultiplier: Float = 1f

    // Rest directions in avatar/world space
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

    // smoothed root state
    private var rootPos = Vec3(0f, 0f, 0f)
    private var rootScale = 1f
    private var initialized = false

    /**
     * World-space model transform derived from tracking. The renderer applies it after skinning
     * (`world = skinned * modelScale + modelOffset`), which scales the avatar about its hips and
     * places those hips on the tracked person's hips.
     */
    var modelScale: Float = 1f
        private set
    var modelOffset: Vec3 = Vec3.ZERO
        private set

    /** Avatar rest hips position (pivot of the model transform). */
    private var restHip: Vec3 = Vec3(0f, 0.9f, 0f)

    init {
        fun node(name: String) = rig.boneNode(name)
        node("leftUpperArm")?.let { l ->
            node("leftLowerArm")?.let { r -> restUpperArmL = rig.restDirection(l, r) }
        }
        node("leftLowerArm")?.let { l ->
            node("leftHand")?.let { r -> restLowerArmL = rig.restDirection(l, r) }
        }
        node("rightUpperArm")?.let { l ->
            node("rightLowerArm")?.let { r -> restUpperArmR = rig.restDirection(l, r) }
        }
        node("rightLowerArm")?.let { l ->
            node("rightHand")?.let { r -> restLowerArmR = rig.restDirection(l, r) }
        }
        node("leftUpperLeg")?.let { l ->
            node("leftLowerLeg")?.let { r -> restUpperLegL = rig.restDirection(l, r) }
        }
        node("leftLowerLeg")?.let { l ->
            node("leftFoot")?.let { r -> restLowerLegL = rig.restDirection(l, r) }
        }
        node("rightUpperLeg")?.let { l ->
            node("rightLowerLeg")?.let { r -> restUpperLegR = rig.restDirection(l, r) }
        }
        node("rightLowerLeg")?.let { l ->
            node("rightFoot")?.let { r -> restLowerLegR = rig.restDirection(l, r) }
        }
        rig.boneNode("hips")?.let { hips ->
            rig.boneNode("spine")?.let { spine ->
                restTorsoUp = rig.restDirection(hips, spine)
            } ?: rig.boneNode("chest")?.let { chest ->
                restTorsoUp = rig.restDirection(hips, chest)
            }
        }
        // Read the rest pose, not the evaluated world matrices: this constructor runs before the
        // first rig.evaluate(), so worldTranslation() would still return identity (all zeros) and
        // the shoulder width would collapse to the 0.05 floor, pinning the tracking scale at the
        // 2.5x clamp and making the avatar permanently oversized.
        fun restTranslation(name: String): Vec3? =
            rig.boneNode(name)?.let { rig.nodes[it].restWorld.extractTranslation() }
        val ls = restTranslation("leftShoulder")
        val rs = restTranslation("rightShoulder")
        if (ls != null && rs != null) avatarShoulderWidth = (ls - rs).length().coerceAtLeast(0.05f)
        val hips = restTranslation("hips")
        val footL = restTranslation("leftFoot")
        val footR = restTranslation("rightFoot")
        if (hips != null && footL != null && footR != null) {
            avatarHipsToFeet = hips.y - ((footL.y + footR.y) / 2f).coerceAtLeast(0.1f)
        }
        rig.boneNode("hips")?.let { restHip = rig.nodes[it].restWorld.extractTranslation() }
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
         * View-space direction. The tracking image shares the composite UV mapping, so image-x
         * (normalized) spans viewWidth world units and image-y (normalized, y down) spans
         * viewHeight world units. Depth comes from the world landmark z (meters) with a tunable
         * sign. Using the normalized image landmarks for x/y guarantees the avatar follows the
         * silhouette that is actually displayed.
         */
        fun viewDir(dxN: Float, dyN: Float, dz: Float): Vec3 =
            conv.rotate(Vec3(dxN * viewWidth, -dyN * viewHeight, dz * Z_SIGN))

        fun segmentDir(from: Int, to: Int): Vec3? {
            if (!visible(from) || !visible(to)) return null
            val v = viewDir(lm[to].x - lm[from].x, lm[to].y - lm[from].y, wZ(to) - wZ(from))
            return if (v.length() > 1e-5f) v.normalized() else null
        }

        // ---------------- root placement: hips on the tracked hip, scale from shoulders --------
        val shouldersOk = visible(11) && visible(12)
        val hipsOk = visible(23) && visible(24)

        // Compute target hip position whenever hips are visible (more robust than requiring both)
        var targetHip: Vec3? = null
        if (hipsOk) {
            val hipCx = (lm[23].x + lm[24].x) / 2f
            val hipCy = (lm[23].y + lm[24].y) / 2f
            targetHip = Vec3(
                x = (hipCx - 0.5f) * viewWidth,
                y = cameraHeight - (hipCy - 0.5f) * viewHeight,
                z = 0f,
            )
        }

        // Compute target scale whenever shoulders are visible
        var targetScale: Float? = null
        if (shouldersOk) {
            val sdx = (lm[11].x - lm[12].x) * viewWidth
            val sdy = (lm[11].y - lm[12].y) * viewHeight
            val personShoulderWorld = kotlin.math.hypot(sdx.toDouble(), sdy.toDouble()).toFloat()
            targetScale = (personShoulderWorld / avatarShoulderWidth).coerceIn(0.3f, 2.5f) * scaleMultiplier
        }

        // Initialize on first valid data (hip position or scale), then smooth
        if (!initialized && (targetHip != null || targetScale != null)) {
            if (targetHip != null) rootPos = targetHip
            if (targetScale != null) rootScale = targetScale
            initialized = true
        } else if (initialized) {
            val k = (dt * 12f).coerceIn(0.05f, 1f)
            if (targetHip != null) {
                rootPos = Vec3(
                    rootPos.x + (targetHip.x - rootPos.x) * k,
                    rootPos.y + (targetHip.y - rootPos.y) * k,
                    0f,
                )
            }
            if (targetScale != null) {
                rootScale += (targetScale - rootScale) * k
            }
        }
        // Aggressive fallback: if still not initialized, try any visible landmark as proxy
        if (!initialized) {
            // Try nose (0) -> approximate hip center from nose position
            if (visible(0)) {
                val nose = lm[0]
                rootPos = Vec3(
                    x = (nose.x - 0.5f) * viewWidth,
                    y = cameraHeight - (nose.y - 0.3f) * viewHeight, // nose is above hips
                    z = 0f,
                )
                rootScale = (1.0f / avatarShoulderWidth).coerceIn(0.5f, 2.0f) * scaleMultiplier
                initialized = true
            }
            // Try neck (1) as secondary fallback
            else if (visible(1)) {
                val neck = lm[1]
                rootPos = Vec3(
                    x = (neck.x - 0.5f) * viewWidth,
                    y = cameraHeight - (neck.y - 0.15f) * viewHeight,
                    z = 0f,
                )
                rootScale = (1.0f / avatarShoulderWidth).coerceIn(0.5f, 2.0f) * scaleMultiplier
                initialized = true
            }
            // Last resort: any visible landmark
            else {
                for (i in 0 until 33) {
                    if (visible(i)) {
                        val landmark = lm[i]
                        rootPos = Vec3(
                            x = (landmark.x - 0.5f) * viewWidth,
                            y = cameraHeight - (landmark.y - 0.2f) * viewHeight,
                            z = 0f,
                        )
                        rootScale = (1.0f / avatarShoulderWidth).coerceIn(0.5f, 2.0f) * scaleMultiplier
                        initialized = true
                        break
                    }
                }
            }
        }

        // Scale about the rest hips, then translate so they land on the tracked hips.
        modelScale = rootScale
        modelOffset = Vec3(
            rootPos.x - restHip.x * rootScale,
            rootPos.y - restHip.y * rootScale,
            rootPos.z - restHip.z * rootScale,
        )

        // ---------------- torso ----------------
        if (shouldersOk && hipsOk) {
            val torsoUp = viewDir(
                ((lm[11].x + lm[12].x) / 2f) - ((lm[23].x + lm[24].x) / 2f),
                ((lm[11].y + lm[12].y) / 2f) - ((lm[23].y + lm[24].y) / 2f),
                ((wZ(11) + wZ(12)) / 2f) - ((wZ(23) + wZ(24)) / 2f),
            )
            if (torsoUp.length() > 1e-5f) {
                val up = torsoUp.normalized()
                rig.boneNode("hips")?.let { hipsNode ->
                    val restUp = restTorsoUp ?: Vec3(0f, 1f, 0f)
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

        // ---------------- arms ----------------
        driveBone("leftUpperArm", segmentDir(11, 13), restUpperArmL)
        driveBone("leftLowerArm", segmentDir(13, 15), restLowerArmL)
        driveBone("rightUpperArm", segmentDir(12, 14), restUpperArmR)
        driveBone("rightLowerArm", segmentDir(14, 16), restLowerArmR)

        // ---------------- legs ----------------
        driveBone("leftUpperLeg", segmentDir(23, 25), restUpperLegL)
        driveBone("leftLowerLeg", segmentDir(25, 27), restLowerLegL)
        driveBone("rightUpperLeg", segmentDir(24, 26), restUpperLegR)
        driveBone("rightLowerLeg", segmentDir(26, 28), restLowerLegR)

        // feet: ankle -> foot index
        driveBone("leftFoot", segmentDir(27, 31), Vec3(0f, -1f, 0f))
        driveBone("rightFoot", segmentDir(28, 32), Vec3(0f, -1f, 0f))

        // ---------------- head ----------------
        if (visible(2) && visible(5) && visible(9) && visible(10)) {
            // Use right_eye - left_eye for the avatar's right vector.
            // In the mirrored selfie view: left eye (idx 2) is on image right, right eye (idx 5) on image left.
            // right_eye - left_eye points to the person's anatomical right (image left = -X),
            // which corresponds to the avatar's right (+X) after the rest yaw aligns the model to face the viewer.
            var right = viewDir(lm[5].x - lm[2].x, lm[5].y - lm[2].y, wZ(5) - wZ(2))
            var up = viewDir(
                (lm[2].x + lm[5].x) / 2f - (lm[9].x + lm[10].x) / 2f,
                (lm[2].y + lm[5].y) / 2f - (lm[9].y + lm[10].y) / 2f,
                (wZ(2) + wZ(5)) / 2f - (wZ(9) + wZ(10)) / 2f,
            )
            right = right.normalized()
            up = up.normalized()
            if (right.length() > 1e-5f && up.length() > 1e-5f) {
                // orthonormalize
                up = (up - right * (right.dot(up))).normalized()
                val forward = right.cross(up).normalized()
                val basis = Mat4().setIdentity()
                basis.m[0] = right.x; basis.m[1] = right.y; basis.m[2] = right.z
                basis.m[4] = up.x; basis.m[5] = up.y; basis.m[6] = up.z
                basis.m[8] = forward.x; basis.m[9] = forward.y; basis.m[10] = forward.z
                val headQuat = basis.extractRotation()
                rig.boneNode("neck")?.let { rig.worldRotationOverride[it] = headQuat }
                rig.boneNode("head")?.let { rig.worldRotationOverride[it] = headQuat }
            }
        }
    }

    private fun driveBone(boneName: String, desired: Vec3?, restDir: Vec3?) {
        val node = rig.boneNode(boneName) ?: return
        val rest = restDir ?: return
        val d = desired ?: return
        if (d.length() < 1e-5f) return
        val restWorldRot = rig.nodes[node].restWorld.extractRotation()
        rig.worldRotationOverride[node] = Quat.fromToRotation(rest, d) * restWorldRot
    }

    /** Drive finger bones from a tracked hand. */
    fun applyHand(hand: HandSkeleton) {
        val side = if (hand.isLeftHand) "left" else "right"
        val wl = hand.worldLandmarks
        val conv = rig.viewToAvatar
        fun toWorldVec(l: dev.zissha.vr.tracking.Landmark3): Vec3 = conv.rotate(Vec3(l.x, -l.y, l.z * Z_SIGN))

        // hand bone orientation: wrist -> middle MCP direction blended with thumb direction
        rig.boneNode("${side}Hand")?.let { handNode ->
            val desired = (toWorldVec(wl[9]) - toWorldVec(wl[0])).normalized()
            val restWorldRot = rig.nodes[handNode].restWorld.extractRotation()
            // hands rest along +Y (fingers continue the arm); use lower-arm continuation as rest
            rig.worldRotationOverride[handNode] = Quat.fromToRotation(Vec3(0f, 1f, 0f), desired) * restWorldRot
        }
        driveFinger(side, "Thumb", intArrayOf(1, 2, 3, 4), wl, ::toWorldVec)
        driveFinger(side, "Index", intArrayOf(5, 6, 7, 8), wl, ::toWorldVec)
        driveFinger(side, "Middle", intArrayOf(9, 10, 11, 12), wl, ::toWorldVec)
        driveFinger(side, "Ring", intArrayOf(13, 14, 15, 16), wl, ::toWorldVec)
        driveFinger(side, "Little", intArrayOf(17, 18, 19, 20), wl, ::toWorldVec)
    }

    private fun driveFinger(
        side: String,
        finger: String,
        lm: IntArray,
        wl: List<dev.zissha.vr.tracking.Landmark3>,
        toWorldVec: (dev.zissha.vr.tracking.Landmark3) -> Vec3,
    ) {
        val segments = arrayOf("Proximal", "Intermediate", "Distal")
        for (s in 0 until 3) {
            val bone = rig.boneNode("${side}${finger}${segments[s]}") ?: continue
            val from = wl[lm[s]]
            val to = wl[lm[s + 1]]
            val desired = (toWorldVec(to) - toWorldVec(from)).normalized()
            if (desired.length() < 1e-5f) continue
            val restWorldRot = rig.nodes[bone].restWorld.extractRotation()
            rig.worldRotationOverride[bone] = Quat.fromToRotation(Vec3(0f, 1f, 0f), desired) * restWorldRot
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
