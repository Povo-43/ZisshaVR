package dev.zissha.vr.avatar.rig

import dev.zissha.vr.tracking.FaceResult

/**
 * Maps MediaPipe face blendshapes onto VRM preset expressions (spec 3.1 表情・BlendShape).
 */
object ExpressionMapper {

    /** Preset expression weights derived from blendshapes. All values in [0..1]. */
    class ExpressionWeights {
        val values = HashMap<String, Float>(16)

        fun add(name: String, value: Float) {
            if (value <= 0.001f) return
            values[name] = ((values[name] ?: 0f) + value).coerceIn(0f, 1f)
        }
    }

    fun map(face: FaceResult?, out: ExpressionWeights) {
        out.values.clear()
        face ?: return
        fun s(name: String) = face.score(name)

        // mouth shapes (VRM presets: aa, ih, ou, ee, oh)
        out.add("aa", s("jawOpen") * 0.95f)
        out.add("ih", (s("mouthStretchLeft") + s("mouthStretchRight")) * 0.5f * 0.9f + s("jawOpen") * 0.1f)
        out.add("ou", s("mouthPucker") * 0.9f + s("jawOpen") * 0.1f)
        out.add("ee", (s("mouthStretchLeft") + s("mouthStretchRight")) * 0.5f * 0.7f + s("mouthPressLeft") * 0.2f)
        out.add("oh", s("mouthFunnel") * 0.75f + s("jawOpen") * 0.35f)

        // blinks
        out.add("blink", (s("eyeBlinkLeft") + s("eyeBlinkRight")) * 0.5f)
        out.add("blinkLeft", s("eyeBlinkLeft"))
        out.add("blinkRight", s("eyeBlinkRight"))

        // emotions
        out.add("happy", (s("mouthSmileLeft") + s("mouthSmileRight")) * 0.5f)
        out.add("angry", (s("browDownLeft") + s("browDownRight")) * 0.5f)
        out.add("sad", (s("mouthFrownLeft") + s("mouthFrownRight")) * 0.5f * 0.7f + s("browInnerUp") * 0.3f)
        out.add("relaxed", (s("mouthPressLeft") + s("mouthPressRight")) * 0.4f)
        out.add("surprised", s("browInnerUp") * 0.6f + (s("eyeWideLeft") + s("eyeWideRight")) * 0.25f)

        // eye look (used as VRM 1.0 look* preset expressions; mirrored view flips horizontal)
        val lookX = (s("eyeLookInLeft") + s("eyeLookOutRight") - s("eyeLookOutLeft") - s("eyeLookInRight")) * 0.5f
        val lookY = (s("eyeLookUpLeft") + s("eyeLookUpRight")) * 0.5f - (s("eyeLookDownLeft") + s("eyeLookDownRight")) * 0.5f
        if (lookX >= 0f) out.add("lookLeft", lookX) else out.add("lookRight", -lookX)
        if (lookY >= 0f) out.add("lookUp", lookY) else out.add("lookDown", -lookY)
    }
}
