package dev.zissha.vr.tracking

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TrackingStats(
    val fps: Float = 0f,
    val poseMs: Float = 0f,
    val faceMs: Float = 0f,
    val handMs: Float = 0f,
    val segmentMs: Float = 0f,
    val lod: Int = 1,
)

/**
 * MediaPipe Tasks Vision integration (spec 4). Owns a dedicated inference thread and runs
 * independent tasks (Pose / Face / Hand / ImageSegmenter) with per-task enable flags driven by
 * the LOD controller and the thermal governor.
 *
 * Input frames arrive as RGBA buffers rendered by the GL thread at tracking resolution; results
 * are tagged with the camera frame timestamp so old results are never applied to newer frames
 * (spec 15).
 */
class TrackingEngine(private val context: Context) {

    companion object {
        private const val TAG = "TrackingEngine"
        const val TRACK_W = 320
        const val TRACK_H = 180
        private const val RESULT_HISTORY = 8
        private const val CONFIDENCE_GATE = 0.25f
        private const val WARP_SPEED = 1.8f // normalized units per second
        private const val STALE_MS = 350L
    }

    private val thread = HandlerThread("ZisshaTracking").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var running = false

    // MediaPipe tasks (created/destroyed on the tracking thread only)
    private var poseLandmarker: PoseLandmarker? = null
    private var faceLandmarker: FaceLandmarker? = null
    private var handLandmarker: HandLandmarker? = null
    private var segmenter: ImageSegmenter? = null

    private var frameBitmap: Bitmap? = null
    private var lastTsMs = -1L

    private val lod = LodController()
    private val stabilizer = Stabilizer()

    /** Governor level 0..4 (higher = more aggressive load reduction). -1 = normal. */
    @Volatile var thermalLevel: Int = 0
        set(value) {
            field = value
            lod.forceOverride(if (value >= 4) 0 else -1)
        }

    @Volatile var segmentationEnabled: Boolean = true

    private val pending = ArrayBlockingQueue<CameraFrame>(2)

    private data class CameraFrame(val tsMs: Long, val rgba: ByteBuffer)

    private val history = ArrayDeque<TrackingFrame>()
    private val historyLock = Any()

    private val _latest = MutableStateFlow<TrackingFrame?>(null)
    val latest: StateFlow<TrackingFrame?> = _latest

    private val _health = MutableStateFlow<FrameHealth?>(null)
    val health: StateFlow<FrameHealth?> = _health

    private val _stats = MutableStateFlow(TrackingStats())
    val stats: StateFlow<TrackingStats> = _stats

    // health bookkeeping
    private var lastPoseTs = 0L
    private val lastHipCenter = floatArrayOf(Float.NaN, Float.NaN)
    private var lastResultMonotonicMs = 0L
    private var framesProcessed = 0L
    private var framesAtLastStats = 0L
    private var statsWindowStart = 0L

    fun start() {
        if (running) return
        running = true
        handler.post {
            createTasks()
            loop()
        }
    }

    fun stop() {
        running = false
        pending.clear()
        handler.post {
            releaseTasks()
            thread.quitSafely()
        }
    }

    fun resetTracking() {
        handler.post {
            stabilizer.reset()
            synchronized(historyLock) { history.clear() }
            _latest.value = null
            lastHipCenter[0] = Float.NaN
            lastHipCenter[1] = Float.NaN
            lastPoseTs = 0
        }
    }

    /** Called from the GL thread. Copies the frame buffer and hands it to the tracking thread. */
    fun submitFrame(tsMs: Long, rgba: ByteBuffer) {
        if (!running) return
        // Keep at most the newest frames; drop stale ones to bound latency.
        while (pending.size >= 2) pending.poll()
        val copy = ByteBuffer.allocateDirect(rgba.remaining()).order(ByteOrder.nativeOrder())
        copy.put(rgba)
        copy.flip()
        pending.offer(CameraFrame(tsMs, copy))
    }

    private fun createTasks() {
        releaseTasks()
        try {
            poseLandmarker = createPose()
        } catch (e: Exception) {
            Log.e(TAG, "pose landmarker GPU create failed", e)
            try {
                poseLandmarker = createPose(Delegate.CPU)
            } catch (e2: Exception) {
                Log.e(TAG, "pose landmarker CPU fallback failed", e2)
            }
        }
        try {
            faceLandmarker = createFace()
        } catch (e: Exception) {
            Log.e(TAG, "face landmarker GPU create failed", e)
            try {
                faceLandmarker = createFace(Delegate.CPU)
            } catch (e2: Exception) {
                Log.e(TAG, "face landmarker CPU fallback failed", e2)
            }
        }
        try {
            handLandmarker = createHand()
        } catch (e: Exception) {
            Log.e(TAG, "hand landmarker GPU create failed", e)
            try {
                handLandmarker = createHand(Delegate.CPU)
            } catch (e2: Exception) {
                Log.e(TAG, "hand landmarker CPU fallback failed", e2)
            }
        }
        try {
            segmenter = createSegmenter()
        } catch (e: Exception) {
            Log.e(TAG, "segmenter GPU create failed", e)
            try {
                segmenter = createSegmenter(Delegate.CPU)
            } catch (e2: Exception) {
                Log.e(TAG, "segmenter CPU fallback failed", e2)
            }
        }
    }

    private fun baseOptions(asset: String, delegate: Delegate = Delegate.GPU): BaseOptions =
        BaseOptions.builder().setModelAssetPath(asset).setDelegate(delegate).build()

    private fun createPose(delegate: Delegate = Delegate.GPU): PoseLandmarker {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions("pose_landmarker_lite.task", delegate))
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return PoseLandmarker.createFromOptions(context, options)
    }

    private fun createFace(delegate: Delegate = Delegate.GPU): FaceLandmarker {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions("face_landmarker.task", delegate))
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    private fun createHand(delegate: Delegate = Delegate.GPU): HandLandmarker {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions("hand_landmarker.task", delegate))
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return HandLandmarker.createFromOptions(context, options)
    }

    private fun createSegmenter(delegate: Delegate = Delegate.GPU): ImageSegmenter {
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseOptions("selfie_segmenter.tflite", delegate))
            .setRunningMode(RunningMode.VIDEO)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        return ImageSegmenter.createFromOptions(context, options)
    }

    private fun releaseTasks() {
        runCatching { poseLandmarker?.close() }
        runCatching { faceLandmarker?.close() }
        runCatching { handLandmarker?.close() }
        runCatching { segmenter?.close() }
        poseLandmarker = null
        faceLandmarker = null
        handLandmarker = null
        segmenter = null
        frameBitmap?.recycle()
        frameBitmap = null
    }

    private fun loop() {
        while (running) {
            val frame = try {
                pending.poll(200, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            }
            processFrame(frame)
        }
    }

    private fun processFrame(frame: CameraFrame) {
        if (frame.tsMs <= lastTsMs) return
        lastTsMs = frame.tsMs
        val bmp = frameBitmap
            ?: Bitmap.createBitmap(TRACK_W, TRACK_H, Bitmap.Config.ARGB_8888).also { frameBitmap = it }
        bmp.copyPixelsFromBuffer(frame.rgba)
        val mpImage = BitmapImageBuilder(bmp).build()

        var pose: PoseResult? = null
        var face: FaceResult? = null
        var hands: HandResult? = null
        var seg: SegmentationResult? = null

        var poseMs = 0f
        var faceMs = 0f
        var handMs = 0f
        var segMs = 0f

        val governorStride = if (thermalLevel >= 3) 2 else 1
        val poseStride = (lod.poseStride * governorStride).coerceAtLeast(1)

        if (poseLandmarker != null && framesProcessed % poseStride == 0L) {
            try {
                val t0 = SystemClock.currentThreadTimeMillis()
                val result = poseLandmarker!!.detectForVideo(mpImage, frame.tsMs)
                poseMs = (SystemClock.currentThreadTimeMillis() - t0).toFloat()
                if (result.landmarks().isNotEmpty()) {
                    val lm = result.landmarks()[0]
                    val world = result.worldLandmarks()[0]
                    val list = lm.map { Landmark3(it.x(), it.y(), it.z(), it.visibility().orElse(1f)) }
                    val worldList = world.map { Landmark3(it.x(), it.y(), it.z(), it.visibility().orElse(1f)) }
                    pose = PoseResult(frame.tsMs, list, worldList)
                }
            } catch (e: Exception) {
                Log.w(TAG, "pose inference failed", e)
            }
        }

        if (faceLandmarker != null && lod.faceEnabled && framesProcessed % (2L * governorStride) == 0L) {
            try {
                val t0 = SystemClock.currentThreadTimeMillis()
                val result = faceLandmarker!!.detectForVideo(mpImage, frame.tsMs)
                faceMs = (SystemClock.currentThreadTimeMillis() - t0).toFloat()
                var shapes: Map<String, Float> = emptyMap()
                var headMatrix: FloatArray? = null
                result.faceBlendshapes().ifPresent { lists ->
                    if (lists.isNotEmpty()) {
                        val map = HashMap<String, Float>(lists[0].size)
                        for (c in lists[0]) map[c.categoryName()] = c.score()
                        shapes = map
                    }
                }
                result.facialTransformationMatrixes().ifPresent { mats ->
                    if (mats.isNotEmpty()) headMatrix = mats[0].copyOf(16)
                }
                if (shapes.isNotEmpty() || headMatrix != null) {
                    face = stabilizer.smoothFace(frame.tsMs, FaceResult(frame.tsMs, shapes, headMatrix))
                }
            } catch (e: Exception) {
                Log.w(TAG, "face inference failed", e)
            }
        }

        if (handLandmarker != null && lod.handsEnabled && framesProcessed % (2L * governorStride) == 0L) {
            try {
                val t0 = SystemClock.currentThreadTimeMillis()
                val result = handLandmarker!!.detectForVideo(mpImage, frame.tsMs)
                handMs = (SystemClock.currentThreadTimeMillis() - t0).toFloat()
                if (result.landmarks().isNotEmpty()) {
                    val skeletons = ArrayList<HandSkeleton>(result.landmarks().size)
                    for (i in result.landmarks().indices) {
                        val lm = result.landmarks()[i]
                        val world = result.worldLandmarks()[i]
                        val label = result.handedness().getOrNull(i)?.firstOrNull()?.categoryName() ?: "Right"
                        // We feed the mirrored (selfie-view) frame, so handedness labels are swapped.
                        val isLeft = label.equals("Right", ignoreCase = true)
                        val list = lm.map { Landmark3(it.x(), it.y(), it.z(), it.visibility().orElse(1f)) }
                        val worldList = world.map { Landmark3(it.x(), it.y(), it.z(), it.visibility().orElse(1f)) }
                        skeletons.add(HandSkeleton(isLeft, list, worldList))
                    }
                    hands = stabilizer.smoothHands(frame.tsMs, HandResult(frame.tsMs, skeletons))
                }
            } catch (e: Exception) {
                Log.w(TAG, "hand inference failed", e)
            }
        }

        val segStride = when {
            !segmentationEnabled -> 0
            thermalLevel >= 2 -> 3
            else -> 2
        }
        if (segmenter != null && segStride > 0 && framesProcessed % segStride == 0L) {
            try {
                val t0 = SystemClock.currentThreadTimeMillis()
                val result = segmenter!!.segmentForVideo(mpImage, frame.tsMs)
                segMs = (SystemClock.currentThreadTimeMillis() - t0).toFloat()
                val masks = result.confidenceMasks()
                if (masks.isPresent && masks.get().isNotEmpty()) {
                    val maskList = masks.get()
                    val maskImage = maskList[maskList.size - 1]
                    val bb = ByteBufferExtractor.extract(maskImage)
                    bb.order(ByteOrder.nativeOrder())
                    val fb = bb.asFloatBuffer()
                    val w = maskImage.width
                    val h = maskImage.height
                    val values = FloatArray(w * h)
                    val n = minOf(fb.remaining(), w * h)
                    for (i in 0 until n) values[i] = fb.get(i).coerceIn(0f, 1f)
                    seg = SegmentationResult(frame.tsMs, values, w, h)
                    maskImage.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "segmentation failed", e)
            }
        }

        framesProcessed++

        // --- stabilization + health evaluation -----------------------------------
        val stablePose = pose?.let { stabilizer.smoothPose(frame.tsMs, it) }
        val trackingFrame = TrackingFrame(frame.tsMs, stablePose, face, hands, seg)
        publish(trackingFrame)

        var healthy = true
        var reason: String? = null
        if (stablePose == null) {
            if (lastPoseTs != 0L && SystemClock.uptimeMillis() - lastResultMonotonicMs > STALE_MS) {
                healthy = false
                reason = "pose_timeout"
            }
        } else {
            val confidence = stablePose.confidence()
            // hip center warp detection (normalized view space)
            val hipX = (stablePose.landmarks[23].x + stablePose.landmarks[24].x) / 2f
            val hipY = (stablePose.landmarks[23].y + stablePose.landmarks[24].y) / 2f
            if (!lastHipCenter[0].isNaN() && lastPoseTs != 0L) {
                val dt = (frame.tsMs - lastPoseTs) / 1000f
                if (dt > 0f) {
                    val speed = hypot(
                        (hipX - lastHipCenter[0]).toDouble(),
                        (hipY - lastHipCenter[1]).toDouble(),
                    ).toFloat() / dt
                    if (speed > WARP_SPEED) {
                        healthy = false
                        reason = "landmark_warp"
                    }
                }
            }
            lastHipCenter[0] = hipX
            lastHipCenter[1] = hipY
            lastPoseTs = frame.tsMs
            lastResultMonotonicMs = SystemClock.uptimeMillis()
            if (confidence < CONFIDENCE_GATE) {
                healthy = false
                reason = "low_confidence"
            }
        }
        _health.value = FrameHealth(frame.tsMs, healthy, reason)

        // --- LOD update -----------------------------------------------------------
        val personHeight = stablePose?.bboxHeight() ?: 0f
        val faceSize = estimateFaceSize(stablePose)
        lod.update(SystemClock.uptimeMillis(), personHeight, faceSize)

        // --- stats ----------------------------------------------------------------
        val now = SystemClock.uptimeMillis()
        if (statsWindowStart == 0L) statsWindowStart = now
        if (now - statsWindowStart >= 1000) {
            val fps = (framesProcessed - framesAtLastStats) / ((now - statsWindowStart) / 1000f)
            _stats.value = TrackingStats(fps, poseMs, faceMs, handMs, segMs, lod.lod)
            framesAtLastStats = framesProcessed
            statsWindowStart = now
        }
    }

    /** Rough face size estimate from outer eye distance relative to frame width. */
    private fun estimateFaceSize(pose: PoseResult?): Float {
        pose ?: return 0f
        val le = pose.landmarks[2] // left eye outer
        val re = pose.landmarks[5] // right eye outer
        val dx = le.x - re.x
        val dy = le.y - re.y
        return (hypot(dx.toDouble(), dy.toDouble()) * 2.2f).toFloat()
    }

    private fun publish(frame: TrackingFrame) {
        synchronized(historyLock) {
            history.addLast(frame)
            while (history.size > RESULT_HISTORY) history.removeFirst()
        }
        _latest.value = frame
    }

    /**
     * Find the newest tracking frame whose timestamp is within [maxAgeMs] of [tsMs] and not
     * newer than the requested frame (never apply future results).
     */
    fun frameFor(tsMs: Long, maxAgeMs: Long = 150): TrackingFrame? {
        synchronized(historyLock) {
            for (i in history.indices.reversed()) {
                val f = history.elementAt(i)
                if (f.tsMs <= tsMs && tsMs - f.tsMs <= maxAgeMs) return f
            }
        }
        return null
    }

    fun currentLod(): Int = lod.lod
}
