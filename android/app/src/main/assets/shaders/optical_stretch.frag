#version 300 es
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Effect pass for every display mode. With uOptical = 0 the stream is shown 1:1 on the
// view (the layout already sized it for Normal / Zoom / Stretch); with uOptical = 1 the
// "Stretch óptico" lens remaps the 16:9 stream to fill the whole view without bars.
// Geometry of the Anamorfica "wrap" lens (see _mods/WRAP_COLOR_HQ_21-9.glsl):
//  - stretched axis: u = atan(k x) / atan(k), almost faithful in the center,
//    more stretched towards the edges
//  - other axis: zoom ZC * D^VCOMP with D = 1 + k^2 x^2 (cylindrical wrap),
//    KEEP holds the outer rows in place
// On views taller than the source the lens is rotated 90° (uSwap).
// k, atan(k), W and uSwap are solved on the CPU (OpticalLens.kt).
// With the lens on, the view must cover the whole screen ("Stretch" layout).
// Same pass: resampling → debanding (uDebandSteps) → sharpening (uHQ) → color grading
// (uColor, ColorGrade.kt) → vignette → grain.
precision highp float;
// ints default to mediump in fragment shaders: 16 bits on Mali, which breaks the PCG noise
precision highp int;

in vec2 vPos;                 // 0..1, origin top-left
out vec4 fragColor;

uniform sampler2D uTex;       // stream-sized copy of the decoder frame
uniform vec2 uOutSize;        // view size in px
uniform vec2 uStreamSize;     // stream resolution in px

uniform float uOptical;       // 1.0 = optical stretch lens, 0.0 = plain mapping
uniform float uSwap;          // 1.0 = lens works on the vertical axis
uniform float uLensW;         // W: lens width (1.0 with full cover)
uniform float uK;             // k / atan(k) = s0
uniform float uAtanK;         // atan(k)
uniform float uZc;
uniform float uVcomp;
uniform float uKeep;

uniform float uHQ;            // 1.0 = Catmull-Rom + anti-ringing + CAS (17 reads), 0.0 = 1 bilinear read
uniform float uAntiring;      // 0..1, clamps Catmull-Rom overshoot to the 4 nearest texels
uniform float uSharp;         // CAS strength where the stream is magnified
uniform float uSharpMax;      // max brightness CAS may add or remove

uniform float uVigAmount;     // darkening at the edge
uniform float uVigStart;      // 0..1 from the center, where the vignette begins
uniform float uVigAxis;       // 0 = sides, 1 = top/bottom, 2 = all four sides, 3 = ellipse

// color block ("Color" toggle), precomputed on the CPU (ColorGrade.kt)
uniform float uColor;         // 1.0 = color grading on
uniform vec3  uColorMul;      // exposure * temperature * tint * contrast
uniform float uColorAdd;      // contrast offset (pivot 0.5) + brightness
uniform float uSatBase;       // saturation factor = uSatBase - uSatVib * spread
uniform float uSatVib;
uniform vec4  uCurveA;        // master curve, Catmull-Rom; one component per segment
uniform vec4  uCurveB;
uniform vec4  uCurveC;
uniform vec4  uCurveD;
uniform vec3  uSplitShadow;   // split toning tints
uniform vec3  uSplitHigh;
uniform float uSplitAmount;
uniform vec2  uSplitRange;    // luma where the tint goes from shadows to highlights (balance)
uniform float uBw;            // 1.0 = black and white (replaces saturation + vibrance)
uniform vec3  uBwWeights;     // channel mix of the b&w filter

// light debanding ("Deband" toggle)
uniform int   uDebandSteps;     // 0 = off, 1..4 iterations of 4 reads each
uniform float uDebandThreshold; // max difference still treated as a smooth gradient (1st step)
uniform float uDebandRange;     // search radius of the 1st step, stream px (grows each step)
uniform float uGrain;           // final noise amplitude, hides the remaining steps (0 = off)
uniform float uFrame;           // frame counter, changes the noise every frame

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec3 src(vec2 uv) { return texture(uTex, vec2(uv.x, 1.0 - uv.y)).rgb; }

// p = view position (0..1). Returns the position in the stream (0..1) and the
// lens derivatives sl = d(uv)/d(p) per axis (below 1 = the image is enlarged).
vec2 lens(vec2 p, out vec2 sl) {
    if (uOptical < 0.5) { sl = vec2(1.0); return p; }
    vec2 q = p * 2.0 - 1.0;
    if (uSwap > 0.5) q = q.yx;

    // stretched axis: atan profile
    float a = q.x / uLensW;
    float D = 1.0 + uK * uK * a * a;
    float u = atan(uK * a) / uAtanK;
    float su = uK / (D * uAtanK * uLensW);

    // other axis: zoom that grows towards the stretched edges
    float inv = 1.0 / (uZc * pow(D, uVcomp));
    float t   = q.y;
    float v   = t * inv + uKeep * (1.0 - inv) * t * t * t;
    float sv  = inv + 3.0 * uKeep * (1.0 - inv) * t * t;

    vec2 n = vec2(u, v);
    sl = vec2(su, sv);
    if (uSwap > 0.5) { n = n.yx; sl = sl.yx; }
    return 0.5 + 0.5 * n;
}

// Catmull-Rom (9 bilinear reads) + anti-ringing (4 reads)
vec3 sampleCR(vec2 uv) {
    vec2 pt  = 1.0 / uStreamSize;
    vec2 tc  = uv * uStreamSize;
    vec2 tp  = floor(tc - 0.5) + 0.5;
    vec2 f   = tc - tp;
    vec2 w0  = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1  = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2  = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3  = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 t0  = (tp - 1.0) * pt;
    vec2 t3  = (tp + 2.0) * pt;
    vec2 t12 = (tp + w2 / w12) * pt;

    vec3 r = src(vec2(t0.x,  t0.y))  * (w0.x  * w0.y)
           + src(vec2(t12.x, t0.y))  * (w12.x * w0.y)
           + src(vec2(t3.x,  t0.y))  * (w3.x  * w0.y)
           + src(vec2(t0.x,  t12.y)) * (w0.x  * w12.y)
           + src(vec2(t12.x, t12.y)) * (w12.x * w12.y)
           + src(vec2(t3.x,  t12.y)) * (w3.x  * w12.y)
           + src(vec2(t0.x,  t3.y))  * (w0.x  * w3.y)
           + src(vec2(t12.x, t3.y))  * (w12.x * w3.y)
           + src(vec2(t3.x,  t3.y))  * (w3.x  * w3.y);

    vec2 a0 = tp * pt;
    vec2 a1 = (tp + 1.0) * pt;
    vec3 n0 = src(a0);
    vec3 n1 = src(vec2(a1.x, a0.y));
    vec3 n2 = src(vec2(a0.x, a1.y));
    vec3 n3 = src(a1);
    vec3 lo = min(min(n0, n1), min(n2, n3));
    vec3 hi = max(max(n0, n1), max(n2, n3));
    return mix(r, clamp(r, lo, hi), uAntiring);
}

// adaptive sharpening (CAS on luma, per axis), 4 reads
// magnification: view px per stream px; only sharpens where the stream is enlarged
vec3 sharpen(vec3 col, vec2 uv, vec2 sl) {
    vec2  mag  = uOutSize / max(sl * uStreamSize, vec2(1e-5));
    vec2  gain = uSharp * clamp((mag - 0.9) * 0.9, 0.0, 1.2);
    vec2  st   = 1.0 / uStreamSize;
    float c  = luma(col);
    float l  = luma(src(uv - vec2(st.x, 0.0)));
    float r  = luma(src(uv + vec2(st.x, 0.0)));
    float d  = luma(src(uv - vec2(0.0, st.y)));
    float u  = luma(src(uv + vec2(0.0, st.y)));
    float mn = min(c, min(min(l, r), min(u, d)));
    float mx = max(c, max(max(l, r), max(u, d)));
    // full strength on soft detail, fades out on hard edges (HUD, text)
    float amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, 1e-4), 0.0, 1.0));
    float add = amp * dot(gain, vec2(c - 0.5 * (l + r), c - 0.5 * (u + d)));
    return max(col + clamp(add, -uSharpMax, uSharpMax), 0.0);
}

// PCG hash: good quality noise from integers, no sin() precision issues on mobile GPUs
uint pcg(uint v) {
    uint s = v * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}

// random number in 0..1, advances the seed
float rnd(inout uint seed) {
    seed = pcg(seed);
    return float(seed >> 8u) * (1.0 / 16777216.0);
}

// Debanding (like mpv's deband): average 4 points around the pixel at a random angle and
// distance; if they barely differ from the pixel it is a smooth gradient with steps, so use
// the average. Each step searches further away with a stricter threshold. 4 reads per step.
vec3 deband(vec3 col, vec2 uv, inout uint seed) {
    vec2 pt = 1.0 / uStreamSize;
    for (int i = 1; i <= 4; i++) {
        if (i > uDebandSteps) break;
        float fi  = float(i);
        float d   = rnd(seed) * uDebandRange * fi;
        float ang = rnd(seed) * 6.2831853;
        vec2  o   = d * vec2(cos(ang), sin(ang)) * pt;
        vec2  o2  = vec2(-o.y, o.x);
        vec3  avg = 0.25 * (src(uv + o) + src(uv - o) + src(uv + o2) + src(uv - o2));
        col = mix(avg, col, step(vec3(uDebandThreshold / fi), abs(col - avg)));
    }
    return col;
}

// picks the coefficient of the segment of each channel, without branches
vec3 pickSeg(vec4 c, vec3 s1, vec3 s2, vec3 s3) {
    return c.x + s1 * (c.y - c.x) + s2 * (c.z - c.y) + s3 * (c.w - c.z);
}

// master curve through 5 points (x = 0, .25, .5, .75, 1), returns 0..1
vec3 curve(vec3 x) {
    x = clamp(x, 0.0, 1.0) * 4.0;
    vec3 s1 = step(1.0, x), s2 = step(2.0, x), s3 = step(3.0, x);
    vec3 t  = x - (s1 + s2 + s3);
    vec3 a = pickSeg(uCurveA, s1, s2, s3);
    vec3 b = pickSeg(uCurveB, s1, s2, s3);
    vec3 c = pickSeg(uCurveC, s1, s2, s3);
    vec3 d = pickSeg(uCurveD, s1, s2, s3);
    return clamp(a + t * (b + t * (c + t * d)), 0.0, 1.0);
}

// color grading, no texture reads
vec3 grade(vec3 col) {
    // exposure, temperature and contrast in one multiply + add
    col = col * uColorMul + uColorAdd;

    if (uBw > 0.5) {
        // black and white through a color filter
        col = vec3(dot(col, uBwWeights));
    } else {
        // saturation + vibrance (more saturation where there is little)
        float L  = luma(col);
        float sp = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
        col = mix(vec3(L), col, uSatBase - uSatVib * clamp(sp * 1.5, 0.0, 1.0));
    }

    col = curve(col);

    // split toning, soft light blend
    vec3 tn = mix(uSplitShadow, uSplitHigh, smoothstep(uSplitRange.x, uSplitRange.y, luma(col)));
    return mix(col, (1.0 - 2.0 * tn) * col * col + 2.0 * tn * col, uSplitAmount);
}

void main() {
    vec2 sl;
    vec2 uv = lens(vPos, sl);
    // same value for every pixel, so the GPU really skips the branch not taken
    // (an if, not ?:, which compilers may turn into "compute both and select")
    vec3 col;
    if (uHQ > 0.5)
        col = sampleCR(uv);
    else
        col = src(uv);

    // different noise for every pixel and frame
    uint seed = pcg(uint(gl_FragCoord.x) + pcg(uint(gl_FragCoord.y) + pcg(uint(uFrame))));

    // before sharpening, so it does not sharpen the steps
    if (uDebandSteps > 0)
        col = deband(col, uv, seed);

    if (uHQ > 0.5)
        col = sharpen(col, uv, sl);

    if (uColor > 0.5)
        col = grade(col);

    // vignette, on the finished image
    vec2 q = abs(vPos * 2.0 - 1.0);
    if (uVigAxis > 2.5) {
        // ellipse: by distance from the center, 1.0 in the corners
        col *= 1.0 - uVigAmount * smoothstep(uVigStart, 1.0, length(q) * 0.70710678);
    } else {
        vec2 vs = smoothstep(vec2(uVigStart), vec2(1.0), q);
        vec2 on = vec2(1.0 - step(0.5, uVigAxis) + step(1.5, uVigAxis), step(0.5, uVigAxis)); // sides, top/bottom
        col *= (1.0 - uVigAmount * vs.x * on.x) * (1.0 - uVigAmount * vs.y * on.y);
    }

    // grain (triangular noise), last so it also hides steps made by the grading
    if (uGrain > 0.0)
        col += uGrain * (rnd(seed) + rnd(seed) - 1.0);

    // black outside the stream (only reachable with ZC < 1)
    vec2 e = step(vec2(-1e-4), uv) * step(uv, vec2(1.0 + 1e-4));
    fragColor = vec4(clamp(col, 0.0, 1.0) * (e.x * e.y), 1.0);
}
