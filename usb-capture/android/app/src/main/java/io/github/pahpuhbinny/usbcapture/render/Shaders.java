package io.github.pahpuhbinny.usbcapture.render;

/** GLSL ES 3.00 sources. */
final class Shaders {
    private Shaders() {
    }

    static final String VERTEX =
            "#version 300 es\n" +
            "layout(location = 0) in vec2 a_pos;\n" +
            "layout(location = 1) in vec2 a_uv;\n" +
            "out vec2 v_uv;\n" +
            "void main() {\n" +
            "  v_uv = a_uv;\n" +
            "  gl_Position = vec4(a_pos, 0.0, 1.0);\n" +
            "}\n";

    /** Common part of every "convert to RGB" shader. FBO pixel (x,y) = source pixel (x,y). */
    private static final String CONVERT_HEAD =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform highp sampler2D t0;\n" +
            "uniform highp sampler2D t1;\n" +
            "uniform highp sampler2D t2;\n" +
            "uniform mat3 u_yuv;\n" +
            "uniform vec3 u_off;\n" +
            "uniform vec2 u_scale;\n" +
            "uniform ivec2 u_size;\n" +
            "uniform int u_flipY;\n" +
            "uniform int u_swap;\n" +
            "uniform float u_time;\n" +
            "uniform float u_frame;\n" +
            "in vec2 v_uv;\n" +
            "out vec4 o;\n" +
            "vec3 yuv(float y, float u, float v) {\n" +
            "  vec3 c = vec3((y + u_off.x) * u_scale.x, (u + u_off.y) * u_scale.y, (v + u_off.z) * u_scale.y);\n" +
            "  return clamp(u_yuv * c, 0.0, 1.0);\n" +
            "}\n" +
            "ivec2 px() {\n" +
            "  ivec2 p = ivec2(gl_FragCoord.xy);\n" +
            "  if (u_flipY == 1) p.y = u_size.y - 1 - p.y;\n" +
            "  return p;\n" +
            "}\n";

    /**
     * Packed 4:2:2 (two pixels per RGBA texel). The swizzles pick the
     * first/second luma sample and the (U,V) pair out of the texel.
     */
    static String packed422(String yEven, String yOdd, String uv) {
        return CONVERT_HEAD +
                "void main() {\n" +
                "  ivec2 p = px();\n" +
                "  int hx = p.x >> 1;\n" +
                "  vec4 t = texelFetch(t0, ivec2(hx, p.y), 0);\n" +
                "  bool odd = (p.x & 1) == 1;\n" +
                "  float y = odd ? t." + yOdd + " : t." + yEven + ";\n" +
                "  vec2 c = t." + uv + ";\n" +
                "  if (odd) {\n" +
                "    int nx = min(hx + 1, max((u_size.x >> 1) - 1, 0));\n" +
                "    vec4 n = texelFetch(t0, ivec2(nx, p.y), 0);\n" +
                "    c = 0.5 * (c + n." + uv + ");\n" +
                "  }\n" +
                "  o = vec4(yuv(y, c.x, c.y), 1.0);\n" +
                "}\n";
    }

    static final String YUY2 = packed422("r", "b", "ga");
    static final String UYVY = packed422("g", "a", "rb");
    static final String YVYU = packed422("r", "b", "ag");

    static final String NV12 = CONVERT_HEAD +
            "void main() {\n" +
            "  ivec2 p = px();\n" +
            "  float y = texelFetch(t0, p, 0).r;\n" +
            "  vec2 c = texelFetch(t1, p >> 1, 0).rg;\n" +
            "  if (u_swap == 1) c = c.yx;\n" +
            "  o = vec4(yuv(y, c.x, c.y), 1.0);\n" +
            "}\n";

    static final String PLANAR = CONVERT_HEAD +
            "void main() {\n" +
            "  ivec2 p = px();\n" +
            "  float y = texelFetch(t0, p, 0).r;\n" +
            "  float u = texelFetch(t1, p >> 1, 0).r;\n" +
            "  float v = texelFetch(t2, p >> 1, 0).r;\n" +
            "  o = vec4(yuv(y, u, v), 1.0);\n" +
            "}\n";

    static final String GREY = CONVERT_HEAD +
            "void main() {\n" +
            "  ivec2 p = px();\n" +
            "  float y = texelFetch(t0, p, 0).r;\n" +
            "  o = vec4(yuv(y, 128.0 / 255.0, 128.0 / 255.0), 1.0);\n" +
            "}\n";

    static final String RGB = CONVERT_HEAD +
            "void main() {\n" +
            "  ivec2 p = px();\n" +
            "  vec4 c = texelFetch(t0, p, 0);\n" +
            "  o = vec4(u_swap == 1 ? c.bgr : c.rgb, 1.0);\n" +
            "}\n";

    static final String PATTERN = CONVERT_HEAD +
            "void main() {\n" +
            "  vec2 res = vec2(u_size);\n" +
            "  vec2 uv = gl_FragCoord.xy / res;\n" +
            "  vec3 col;\n" +
            "  if (uv.y < 0.58) {\n" +
            "    int bar = clamp(int(floor(uv.x * 7.0)), 0, 6);\n" +
            "    vec3 bars[7] = vec3[7](vec3(0.75), vec3(0.75, 0.75, 0.0), vec3(0.0, 0.75, 0.75), vec3(0.0, 0.75, 0.0),\n" +
            "                           vec3(0.75, 0.0, 0.75), vec3(0.75, 0.0, 0.0), vec3(0.0, 0.0, 0.75));\n" +
            "    col = bars[bar];\n" +
            "  } else if (uv.y < 0.66) {\n" +
            "    int cell = int(floor(uv.x * 60.0));\n" +
            "    int lit = int(mod(u_frame, 60.0));\n" +
            "    col = cell == lit ? vec3(1.0) : ((cell % 10) == 0 ? vec3(0.25) : vec3(0.08));\n" +
            "  } else if (uv.y < 0.78) {\n" +
            "    col = vec3(floor(uv.x * 11.0) / 10.0);\n" +
            "  } else {\n" +
            "    col = vec3(uv.x);\n" +
            "    col.r *= step(0.89, uv.y) + step(uv.y, 0.85);\n" +
            "  }\n" +
            "  vec2 box = vec2(0.5 + 0.42 * sin(u_time * 1.7), 0.89);\n" +
            "  vec2 d = abs(uv - box) * res;\n" +
            "  if (d.x < res.y * 0.04 && d.y < res.y * 0.04) col = vec3(1.0, 0.25, 0.55);\n" +
            "  o = vec4(col, 1.0);\n" +
            "}\n";

    static final String PRESENT =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "in vec2 v_uv;\n" +
            "uniform sampler2D u_tex;\n" +
            "uniform vec2 u_texSize;\n" +
            "uniform int u_sharpFilter;\n" +
            "uniform float u_prescale;\n" +
            "uniform int u_adjust;\n" +
            "uniform float u_brightness;\n" +
            "uniform float u_contrast;\n" +
            "uniform float u_saturation;\n" +
            "uniform float u_gamma;\n" +
            "uniform float u_hue;\n" +
            "uniform int u_mono;\n" +
            "uniform float u_sharpen;\n" +
            "uniform float u_scanlines;\n" +
            "uniform int u_zebra;\n" +
            "uniform float u_zebraLevel;\n" +
            "uniform int u_peaking;\n" +
            "uniform float u_peakThreshold;\n" +
            "uniform vec3 u_peakColor;\n" +
            "uniform int u_falseColor;\n" +
            "out vec4 o;\n" +
            "const mat3 toYIQ = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);\n" +
            "const mat3 toRGB = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);\n" +
            "vec3 fetch(vec2 uv) {\n" +
            "  if (u_sharpFilter == 1) {\n" +
            "    vec2 texel = uv * u_texSize;\n" +
            "    vec2 tf = floor(texel);\n" +
            "    vec2 s = fract(texel);\n" +
            "    float rr = 0.5 - 0.5 / u_prescale;\n" +
            "    vec2 cd = s - 0.5;\n" +
            "    vec2 f = (cd - clamp(cd, -rr, rr)) * u_prescale + 0.5;\n" +
            "    return texture(u_tex, (tf + f) / u_texSize).rgb;\n" +
            "  }\n" +
            "  return texture(u_tex, uv).rgb;\n" +
            "}\n" +
            "float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\n" +
            "vec3 falseColor(float y) {\n" +
            "  if (y < 0.025) return vec3(0.55, 0.0, 0.75);\n" +
            "  if (y < 0.10) return vec3(0.0, 0.3, 1.0);\n" +
            "  if (y < 0.38) return vec3(y * 0.85);\n" +
            "  if (y < 0.46) return vec3(0.1, 0.8, 0.25);\n" +
            "  if (y < 0.52) return vec3(y);\n" +
            "  if (y < 0.58) return vec3(1.0, 0.55, 0.72);\n" +
            "  if (y < 0.93) return vec3(y * 0.9);\n" +
            "  if (y < 0.99) return vec3(1.0, 0.85, 0.0);\n" +
            "  return vec3(1.0, 0.0, 0.0);\n" +
            "}\n" +
            "void main() {\n" +
            "  vec3 c = fetch(v_uv);\n" +
            "  float edge = 0.0;\n" +
            "  if (u_sharpen > 0.0 || u_peaking == 1) {\n" +
            "    vec2 px = 1.0 / u_texSize;\n" +
            "    vec3 n = texture(u_tex, v_uv - vec2(0.0, px.y)).rgb;\n" +
            "    vec3 s = texture(u_tex, v_uv + vec2(0.0, px.y)).rgb;\n" +
            "    vec3 e = texture(u_tex, v_uv + vec2(px.x, 0.0)).rgb;\n" +
            "    vec3 w = texture(u_tex, v_uv - vec2(px.x, 0.0)).rgb;\n" +
            "    if (u_sharpen > 0.0) c = clamp(c + (c - (n + s + e + w) * 0.25) * u_sharpen, 0.0, 1.0);\n" +
            "    if (u_peaking == 1) edge = length(vec2(luma(e) - luma(w), luma(s) - luma(n)));\n" +
            "  }\n" +
            "  if (u_adjust == 1) {\n" +
            "    if (u_hue != 0.0) {\n" +
            "      vec3 q = toYIQ * c;\n" +
            "      float cs = cos(u_hue), sn = sin(u_hue);\n" +
            "      q.yz = vec2(q.y * cs - q.z * sn, q.y * sn + q.z * cs);\n" +
            "      c = toRGB * q;\n" +
            "    }\n" +
            "    c = (c - 0.5) * u_contrast + 0.5 + u_brightness;\n" +
            "    c = mix(vec3(luma(c)), c, u_saturation);\n" +
            "    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / u_gamma));\n" +
            "    if (u_mono == 1) c = vec3(luma(c));\n" +
            "  }\n" +
            "  if (u_scanlines > 0.0) {\n" +
            "    float ph = fract(v_uv.y * u_texSize.y);\n" +
            "    float s = 0.5 - 0.5 * cos(ph * 6.2831853);\n" +
            "    c *= 1.0 - u_scanlines * (1.0 - s);\n" +
            "  }\n" +
            "  float y = luma(c);\n" +
            "  if (u_falseColor == 1) c = falseColor(y);\n" +
            "  if (u_zebra == 1 && y >= u_zebraLevel) {\n" +
            "    if (mod(gl_FragCoord.x + gl_FragCoord.y, 14.0) < 7.0) c = vec3(0.05);\n" +
            "  }\n" +
            "  if (u_peaking == 1 && edge > u_peakThreshold) c = u_peakColor;\n" +
            "  o = vec4(clamp(c, 0.0, 1.0), 1.0);\n" +
            "}\n";
}
