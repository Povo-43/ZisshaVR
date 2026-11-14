package dev.zissha.vr.gl

/**
 * All GLSL (ES 3.0) shader sources for the composition pipeline (spec 6).
 */
object ShaderLib {

    // ---------------------------------------------------------------- fullscreen quad
    const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUV;
void main() {
    vUV = aPos * 0.5 + 0.5;
    vUV.y = 1.0 - vUV.y; // texture space: origin top-left
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    // ------------------------------------------------------------- camera (OES) input
    /**
     * Samples the external camera texture with the SurfaceTexture transform, a 90-degree
     * rotation step and optional horizontal mirror. All conversions happen in the fragment
     * shader so the tracking readback and the composite share exactly the same mapping.
     */
    const val CAMERA_FS = """#version 300 es
#extension GL_OES_EGL_image_external : require
precision mediump float;
in vec2 vUV;
uniform samplerExternalOES uTex;
uniform mat4 uSTMatrix;
uniform int uRotation;   // 0..3 quarter turns applied to the image for display
uniform float uMirror;   // 1.0 = horizontally mirrored (front camera)
uniform float uFlipV;    // 1.0 = flip vertically (tracking readback: FBO rows are bottom-up)
uniform float uBrightness;
out vec4 fragColor;

void main() {
    // Flip in destination space *before* the ST/rotation mapping. Used only by the tracking
    // pass so the CPU readback (glReadPixels is bottom-up) matches the displayed composite.
    vec2 base = vec2(vUV.x, mix(vUV.y, 1.0 - vUV.y, uFlipV));
    vec2 uv = (uSTMatrix * vec4(base, 0.0, 1.0)).xy;
    if (uRotation == 1) {
        uv = vec2(uv.y, 1.0 - uv.x);
    } else if (uRotation == 2) {
        uv = vec2(1.0 - uv.x, 1.0 - uv.y);
    } else if (uRotation == 3) {
        uv = vec2(1.0 - uv.y, uv.x);
    }
    uv.x = mix(uv.x, 1.0 - uv.x, uMirror);
    vec4 c = texture(uTex, uv);
    fragColor = vec4(c.rgb * uBrightness, 1.0);
}
"""

    // ----------------------------------------------------------------------- blit pass
    const val BLIT_FS = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUV);
}
"""

    // ------------------------------------------------------------------ mask smoothing
    /** Temporal exponential smoothing of the person mask (edge flicker suppression, spec 5). */
    const val MASK_TEMPORAL_FS = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uPrev;
uniform sampler2D uNew;
uniform float uAlpha; // blend factor for the new frame
out vec4 fragColor;
void main() {
    float prev = texture(uPrev, vUV).r;
    float now = texture(uNew, vUV).r;
    fragColor = vec4(mix(prev, now, uAlpha), 0.0, 0.0, 1.0);
}
"""

    /** Separable 5-tap gaussian blur for the mask. */
    const val MASK_BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uTex;
uniform vec2 uStep; // texel step * direction
out vec4 fragColor;
void main() {
    float s = texture(uTex, vUV).r * 0.375;
    s += texture(uTex, vUV + uStep).r * 0.25;
    s += texture(uTex, vUV - uStep).r * 0.25;
    s += texture(uTex, vUV + uStep * 2.0).r * 0.0625;
    s += texture(uTex, vUV - uStep * 2.0).r * 0.0625;
    fragColor = vec4(s, 0.0, 0.0, 1.0);
}
"""

    // ------------------------------------------------------------------- avatar shader
    /** Skinning with bone matrices stored in a float texture (scales to any bone count). */
    const val AVATAR_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUV;
layout(location = 3) in vec4 aJoints;
layout(location = 4) in vec4 aWeights;

uniform sampler2D uBoneTex;    // 1 x boneCount*4 RGBA32F, matrix columns stored per row
uniform bool uSkinned;
uniform mat4 uViewProj;
uniform vec3 uModelOffset;     // root translation applied on CPU-side model matrix
uniform float uModelScale;

out vec2 vUV;
out vec3 vNormal;
out vec3 vWorldPos;

mat4 boneMatrix(float index) {
    int row = int(index) * 4;
    vec4 c0 = texelFetch(uBoneTex, ivec2(0, row + 0), 0);
    vec4 c1 = texelFetch(uBoneTex, ivec2(0, row + 1), 0);
    vec4 c2 = texelFetch(uBoneTex, ivec2(0, row + 2), 0);
    vec4 c3 = texelFetch(uBoneTex, ivec2(0, row + 3), 0);
    return mat4(c0, c1, c2, c3);
}

void main() {
    vec4 skinned;
    vec3 n;
    if (uSkinned) {
        mat4 skin =
            aWeights.x * boneMatrix(aJoints.x) +
            aWeights.y * boneMatrix(aJoints.y) +
            aWeights.z * boneMatrix(aJoints.z) +
            aWeights.w * boneMatrix(aJoints.w);
        skinned = skin * vec4(aPos, 1.0);
        n = normalize((skin * vec4(aNormal, 0.0)).xyz);
    } else {
        skinned = vec4(aPos, 1.0);
        n = aNormal;
    }
    vec3 world = skinned.xyz * uModelScale + uModelOffset;
    vUV = aUV;
    vNormal = n;
    vWorldPos = world;
    gl_Position = uViewProj * vec4(world, 1.0);
}
"""

    /**
     * Simplified MToon-style shading: lambert diffuse + rim light + base color texture.
     * Alpha modes: 0 opaque, 1 alpha-cutoff, 2 blend.
     * Output is premultiplied alpha (RGB already multiplied by alpha) for correct compositing.
     */
    const val AVATAR_FS = """#version 300 es
precision mediump float;
in vec2 vUV;
in vec3 vNormal;
in vec3 vWorldPos;
uniform sampler2D uBaseTex;
uniform bool uHasTexture;
uniform vec4 uBaseColorFactor;
uniform int uAlphaMode;      // 0 opaque, 1 mask, 2 blend
uniform float uAlphaCutoff;
uniform float uAlphaOverride;
uniform vec3 uLightDir;      // normalized, world space
out vec4 fragColor;

void main() {
    vec4 base = uBaseColorFactor;
    if (uHasTexture) {
        vec4 texel = texture(uBaseTex, vUV);
        base *= texel;
    }
    float alpha = base.a;
    if (uAlphaOverride >= 0.0) alpha = uAlphaOverride;
    if (uAlphaMode == 1 && alpha < uAlphaCutoff) discard;

    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    float ndl = max(dot(n, normalize(uLightDir)), 0.0);
    vec3 shade = mix(vec3(0.72), vec3(1.0), ndl);
    vec3 viewDir = normalize(vec3(0.0, 0.4, 1.0));
    float rim = pow(1.0 - max(dot(n, viewDir), 0.0), 2.5) * 0.18;
    vec3 color = base.rgb * shade + rim * base.rgb;
    fragColor = vec4(color * alpha, alpha);
}
"""

    // ------------------------------------------------------------ debug skeleton overlay
    const val SKELETON_VS = """#version 300 es
    layout(location = 0) in vec2 aPos;   // NDC
    void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
    """

    const val SKELETON_FS = """#version 300 es
    precision mediump float;
    uniform vec4 uColor;
    out vec4 fragColor;
    void main() { fragColor = uColor; }
    """

    // ----------------------------------------------------------------- final composite
    /**
     * Draws the already-rendered background (in the FBO) and overlays the avatar layer only
     * inside the person mask. Output is straight-alpha; the caller uses premultiplied
     * blending (ONE, ONE_MINUS_SRC_ALPHA) because the avatar FBO content is premultiplied.
     */
    const val MASKED_OVER_FS = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uAvatar;  // avatar layer (premultiplied RGBA)
uniform sampler2D uMask;    // person confidence mask (r channel)
uniform float uMaskEnabled;
out vec4 fragColor;
void main() {
    vec4 av = texture(uAvatar, vUV);
    float m = mix(1.0, texture(uMask, vUV).r, uMaskEnabled);
    m = smoothstep(0.35, 0.65, m);
    fragColor = vec4(av.rgb, av.a * m);
}
"""
}
