package dev.zissha.vr.tracking

/**
 * Immutable tracking snapshot types flowing from the tracking thread to the render thread.
 * All normalized coordinates are in composite/view space: x right, y down, [0..1].
 */

data class Landmark3(val x: Float, val y: Float, val z: Float, val visibility: Float)

/** MediaPipe Pose (33 landmarks). */
class PoseResult(
    val tsMs: Long,
    /** size 33, may contain entries with visibility = 0 when missing */
    val landmarks: List<Landmark3>,
    /** size 33, world coordinates in meters, hip-centered */
    val worldLandmarks: List<Landmark3>,
) {
    fun bboxHeight(): Float {
        var minY = 1f; var maxY = 0f
        for (l in landmarks) {
            if (l.visibility < 0.3f) continue
            if (l.y < minY) minY = l.y
            if (l.y > maxY) maxY = l.y
        }
        return (maxY - minY).coerceAtLeast(0f)
    }
}

/** MediaPipe Face blendshapes + head pose. */
class FaceResult(
    val tsMs: Long,
    /** category name -> score */
    val blendshapes: Map<String, Float>,
    /** 4x4 head transformation matrix (row-major as delivered by MediaPipe), null when unavailable */
    val headMatrix: FloatArray?,
) {
    fun score(name: String): Float = blendshapes[name] ?: 0f
}

data class HandSkeleton(
    val isLeftHand: Boolean,
    /** size 21, normalized view space */
    val landmarks: List<Landmark3>,
    /** size 21, world coords in meters relative to hand center */
    val worldLandmarks: List<Landmark3>,
)

class HandResult(
    val tsMs: Long,
    val hands: List<HandSkeleton>,
)

class SegmentationResult(
    val tsMs: Long,
    /** person-confidence floats in [0..1], length = width*height */
    val mask: FloatArray,
    val width: Int,
    val height: Int,
)

/** Aggregated snapshot for one camera frame. Individual parts may be null when not enabled/available. */
class TrackingFrame(
    val tsMs: Long,
    val pose: PoseResult?,
    val face: FaceResult?,
    val hands: HandResult?,
    val segmentation: SegmentationResult?,
) {
    /** Average pose landmark visibility; 0 when pose missing. */
    fun confidence(): Float {
        val p = pose ?: return 0f
        var sum = 0f
        var n = 0
        for (l in p.landmarks) {
            if (l.visibility > 0f) { sum += l.visibility; n++ }
        }
        return if (n == 0) 0f else sum / n
    }
}

/** Per-frame health verdict consumed by the safety gate. */
data class FrameHealth(
    val tsMs: Long,
    val healthy: Boolean,
    val reason: String? = null,
)
