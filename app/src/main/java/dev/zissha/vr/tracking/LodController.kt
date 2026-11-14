package dev.zissha.vr.tracking

/**
 * Dynamic LOD controller (spec 4.2/4.3).
 *
 * LOD 0: far person  -> pose only, reduced inference rate
 * LOD 1: mid person  -> pose (+hands when clearly visible)
 * LOD 2: near person -> pose + face + hands, full rate
 *
 * Switching uses hysteresis thresholds plus a hold time to avoid flapping.
 */
class LodController(
    private val nearThreshold: Float = 0.72f,
    private val nearExitThreshold: Float = 0.62f,
    private val farThreshold: Float = 0.38f,
    private val farExitThreshold: Float = 0.48f,
    private val holdMs: Long = 1200,
) {
    var lod: Int = 1
        private set

    var handsEnabled: Boolean = false
        private set
    var faceEnabled: Boolean = true  // enabled by default
        private set
    /** run pose inference every Nth camera frame */
    var poseStride: Int = 1
        private set

    init { applyConfig() }

    private var lastChangeMs = 0L

    /**
     * @param nowMs current time
     * @param personHeightRatio person bbox height / frame height (0..1)
     * @param faceSizeRatio face bbox height / frame height (0..1), 0 when unknown
     */
    fun update(nowMs: Long, personHeightRatio: Float, faceSizeRatio: Float): Int {
        val distanceScore = (personHeightRatio * 0.8f + faceSizeRatio * 0.2f)
        if (lastChangeMs == 0L) lastChangeMs = nowMs
        val held = nowMs - lastChangeMs >= holdMs
        val newLod = when (lod) {
            0 -> when {
                distanceScore > farExitThreshold && held -> 1
                else -> 0
            }
            1 -> when {
                distanceScore > nearThreshold && held -> 2
                distanceScore < farThreshold && held -> 0
                else -> 1
            }
            else -> when {
                distanceScore < nearExitThreshold && held -> 1
                else -> 2
            }
        }
        if (newLod != lod) {
            lod = newLod
            lastChangeMs = nowMs
        }
        applyConfig()
        return lod
    }

    /** Force a level (thermal governor or manual settings). -1 to return to automatic. */
    fun forceOverride(level: Int) {
        overrideLevel = level
        if (level >= 0) {
            lod = level.coerceIn(0, 2)
            applyConfig()
        }
    }

    private var overrideLevel: Int = -1

    private fun applyConfig() {
        when (lod) {
            0 -> {
                faceEnabled = false
                handsEnabled = false
                poseStride = 2
            }
            1 -> {
                faceEnabled = true
                handsEnabled = false
                poseStride = 1
            }
            else -> {
                faceEnabled = true
                handsEnabled = true
                poseStride = 1
            }
        }
        if (overrideLevel in 0..2) {
            // forced levels still use their natural stride but never disable tasks below LOD1
            faceEnabled = true
            poseStride = 1
        }
    }

    /** Relative distance estimate from body metrics; also exported for HUD. */
    fun estimateDistance(personHeightRatio: Float, shoulderWidthRatio: Float, faceSizeRatio: Float): Float {
        // Monocular estimation is inherently approximate; combine body proportions.
        val byHeight = 1f - personHeightRatio.coerceIn(0f, 1f)
        val byShoulder = 1f - (shoulderWidthRatio * 3f).coerceIn(0f, 1f)
        val byFace = if (faceSizeRatio > 0f) 1f - (faceSizeRatio * 4f).coerceIn(0f, 1f) else byHeight
        return (byHeight * 0.5f + byShoulder * 0.25f + byFace * 0.25f).coerceIn(0f, 1f)
    }
}
