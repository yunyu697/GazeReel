package com.gazereel.vr.render

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log

/** GLSL ES 3.0 着色器。视频帧通过 samplerExternalOES 直接采样，无 CPU 拷贝。 */
object Shaders {
    // `#version 300 es` 必须是着色器源码的**第一行**：Kotlin 三引号会在开头留下一个
    // 换行和缩进，严格一点的 GLES 驱动（模拟器 ANGLE/SwiftShader、部分 Adreno）
    // 会直接报 "must occur on the first line"，随后把 in/out 当成 ES 1.00 语法再报一串错。
    // 所有着色器统一 trimIndent()，这是 GL 规范层面的硬要求，不是驱动兼容兜底。
    val VIDEO_VERTEX = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        uniform mat4 uMvp;
        uniform mat4 uSTMatrix;
        uniform vec4 uEyeRegion;
        uniform float uContentZoom;
        out vec2 vUv;
        void main() {
            gl_Position = uMvp * aPosition;
            // V2.7.9：每眼半幅必须在「视频画面坐标系」里切，再交给 ST 矩阵映射到纹理缓冲。
            // 旧实现是先过 uSTMatrix 再切半幅——uSTMatrix 一旦不是单位矩阵
            //（解码器把画面放进更大的纹理缓冲、或做了缩放），切出来的就不是半幅，
            // 整幅画面会被铺满视野，正中看到的正好是左右两幅的接缝。
            // 18:2x：内容远近感。围绕每半幅中心缩放采样范围（数字变焦）：
            // >1 拉近（少看一点、变大），<1 推远（多看一点、变小）。
            // 必须在 uEyeRegion 之前做，否则 SBS 会按整幅中心缩放、左右眼串幅。
            // clamp 到 0..1：片源没有「画面之外」的内容，越界采样会拉出条纹或黑边；
            // <1 档边缘会有极轻微拉伸，这是数字变焦的固有代价，别当成无损缩放。
            vec2 zoomed = clamp(
                (aTexCoord - vec2(0.5)) / uContentZoom + vec2(0.5),
                vec2(0.0), vec2(1.0)
            );
            vec2 uv = vec2(zoomed.x * uEyeRegion.x + uEyeRegion.z,
                           zoomed.y * uEyeRegion.y + uEyeRegion.w);
            vUv = (uSTMatrix * vec4(uv, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    val VIDEO_FRAGMENT = """
        #version 300 es
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        in vec2 vUv;
        uniform samplerExternalOES uVideo;
        uniform vec4 uEyeRegion;
        // PRD FR-12：戴镜状态下调节画面亮度，只作用于视频层，UI 文字保持原对比度。
        uniform float uBrightness;
        out vec4 fragColor;
        void main() {
            // PRD 6.1 第 4 条：每眼取对应半区。切半幅已经在顶点着色器里做完，
            // 这里直接采样，避免「在哪一层切」被写错两次。
            vec4 color = texture(uVideo, vUv);
            fragColor = vec4(color.rgb * uBrightness, color.a);
        }
    """.trimIndent()

    /**
     * 同一个视频片元着色器，只换 external 扩展的声明写法。
     *
     * GLSL ES 3.00 规范要求 `GL_OES_EGL_image_external_essl3`，但不少驱动也接受
     * 2.0 时代的 `GL_OES_EGL_image_external`；反过来也有只认前者的机型。
     * 两种都准备着，编译失败就换下一种——比让用户看到一页错误好。
     */
    val VIDEO_FRAGMENT_VARIANTS: List<String> = listOf(
        VIDEO_FRAGMENT.replace(
            "#extension GL_OES_EGL_image_external : require",
            "#extension GL_OES_EGL_image_external_essl3 : require"
        ),
        VIDEO_FRAGMENT
    )

    val UI_VERTEX = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        uniform mat4 uMvp;
        out vec2 vUv;
        void main() {
            gl_Position = uMvp * aPosition;
            vUv = aTexCoord;
        }
    """.trimIndent()

    val UI_FRAGMENT = """
        #version 300 es
        precision mediump float;
        in vec2 vUv;
        uniform sampler2D uUi;
        out vec4 fragColor;
        void main() {
            // UI 四边形顶部顶点使用 v=0，Bitmap 首行对应 t=0，方向一致无需翻转。
            vec4 color = texture(uUi, vUv);
            if (color.a < 0.01) discard;
            fragColor = color;
        }
    """.trimIndent()

    /**
     * alpha3 / 15:0x 场景层：带法线的 Lambert。
     *
     * 顶点只有世界坐标里的 pos / normal / color，没有模型矩阵 —— 房间就是世界本身，
     * 所以 mvp 只负责 view × projection。法线因此也不需要法线矩阵（单位变换）。
     * 同样的理由：`uLightDir` 是**世界空间**方向，不随头动，光才「钉在房间里」。
     */
    val SCENE_VERTEX = """
        #version 300 es
        in vec4 aPosition;
        in vec3 aNormal;
        in float aMat;
        uniform mat4 uMvp;
        out vec3 vNormal;
        out vec3 vWorld;
        out float vMat;
        void main() {
            gl_Position = uMvp * aPosition;
            vNormal = aNormal;
            vWorld = aPosition.xyz;
            vMat = aMat;
        }
    """.trimIndent()

    /**
     * 16:5x 影院的场景片元：程序化材质 + Cook-Torrance。
     *
     * 三条约束写在前面：
     * 1. **不采任何贴图**。木纹、布纹、丝绒全部用 hash 噪声现场生成，所以没有
     *    资产、没有 mipmap、没有采样器，GL 侧只有两个 uniform。
     * 2. **不做伽马转换**。视频层是外部纹理（sRGB 直接采样），场景若再转一次就会
     *    比画面亮一截，两者拼在一起会看出「房间比电影亮」这种假。
     * 3. 噪声只叠 3 个 octave。每眼 1328×1220，房间占屏幕比例不小，fbm 的开销是
     *    这条链路里最贵的一项，4 个 octave 在中端机就会掉帧。
     */
    val SCENE_FRAGMENT = """
        #version 300 es
        precision highp float;

        in vec3 vNormal;
        in vec3 vWorld;
        in float vMat;

        uniform vec3 uLightDir;
        uniform float uLuma;

        out vec4 fragColor;

        const float PI = 3.14159265;

        float hash21(vec2 p) {
            p = fract(p * vec2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float vnoise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float a = hash21(i);
            float b = hash21(i + vec2(1.0, 0.0));
            float c = hash21(i + vec2(0.0, 1.0));
            float d = hash21(i + vec2(1.0, 1.0));
            return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
        }

        float fbm(vec2 p) {
            float s = 0.0;
            float a = 0.5;
            for (int i = 0; i < 3; i++) {
                s += a * vnoise(p);
                p *= 2.03;
                a *= 0.5;
            }
            return s;
        }

        float dGGX(float NoH, float a) {
            float a2 = a * a;
            float d = NoH * NoH * (a2 - 1.0) + 1.0;
            return a2 / (PI * d * d + 1e-6);
        }

        float gSmith(float NoV, float NoL, float k) {
            float gv = NoV / (NoV * (1.0 - k) + k);
            float gl = NoL / (NoL * (1.0 - k) + k);
            return gv * gl;
        }

        vec3 fresnel(float VoH, vec3 f0) {
            return f0 + (1.0 - f0) * pow(1.0 - VoH, 5.0);
        }

        void main() {
            vec3 N = normalize(vNormal);
            vec3 V = normalize(-vWorld);
            vec3 L = normalize(uLightDir);
            vec3 H = normalize(L + V);

            vec3 albedo;
            float rough;
            float metal = 0.0;

            if (vMat < 0.5) {
                // 木地板：0.5 米宽的板条 + 纵向年轮 + 板缝压暗 + 每块板轻微色差
                float plankIdx = floor(vWorld.x * 2.0 + 0.5);
                float seam = smoothstep(0.0, 0.06, abs(fract(vWorld.x * 2.0 + 0.5) - 0.5));
                float grain = fbm(vec2(vWorld.x * 0.6, vWorld.z * 1.0));
                float streak = fbm(vec2(vWorld.x * 5.0, vWorld.z * 0.10));
                vec3 wood = mix(vec3(0.085, 0.048, 0.030), vec3(0.190, 0.115, 0.070), grain);
                wood *= 0.75 + 0.45 * streak;
                wood *= mix(0.55, 1.0, seam);
                wood *= 0.92 + 0.16 * hash21(vec2(plankIdx, 7.0));
                albedo = wood;
                rough = 0.42;
            } else if (vMat < 1.5) {
                // 墙面：深蓝灰吸音布，细密织纹 + 大块色斑
                float weave = vnoise(vec2(vWorld.x * 90.0 + vWorld.z * 90.0, vWorld.y * 90.0));
                float blotch = fbm(vec2(vWorld.x * 0.7 + vWorld.z * 0.7, vWorld.y * 0.7));
                albedo = vec3(0.058, 0.062, 0.072) * (0.86 + 0.28 * weave) * (0.90 + 0.20 * blotch);
                rough = 0.92;
            } else if (vMat < 2.5) {
                // 天花：更暗、更糙，几乎不反光
                float n2 = fbm(vec2(vWorld.x * 0.5, vWorld.z * 0.5));
                albedo = vec3(0.040, 0.040, 0.044) * (0.9 + 0.2 * n2);
                rough = 0.97;
            } else {
                // 幕框丝绒：深红，绒面起伏；掠射时有一点点高光，正是丝绒的特征
                float nap = fbm(vec2(vWorld.x * 12.0, vWorld.y * 12.0));
                albedo = mix(vec3(0.085, 0.016, 0.020), vec3(0.150, 0.030, 0.036), nap);
                rough = 0.78;
            }

            float NoL = max(dot(N, L), 0.0);
            float NoV = max(dot(N, V), 1e-4);
            float NoH = max(dot(N, H), 0.0);
            float VoH = max(dot(V, H), 0.0);

            float a = max(rough * rough, 0.002);
            float D = dGGX(NoH, a);
            float k = (rough + 1.0) * (rough + 1.0) / 8.0;
            float G = gSmith(NoV, NoL, k);
            vec3 F0 = mix(vec3(0.04), albedo, metal);
            vec3 F = fresnel(VoH, F0);

            vec3 spec = (D * G * F) / (4.0 * NoV * NoL + 1e-4);
            vec3 kd = (vec3(1.0) - F) * (1.0 - metal);
            vec3 direct = (kd * albedo / PI + spec) * NoL;

            // 银幕的间接光：强度跟着当前画面亮度走，面朝银幕的面吃得多。
            // 「画面亮整个厅跟着亮」是影院最直观的一条，也是用户能立刻察觉的细节。
            float facing = max(dot(N, normalize(vec3(0.0, 0.10, 1.0))), 0.0);
            vec3 bounce = albedo * uLuma * (0.55 + 0.45 * facing) * 0.90;

            // 环境光极暗：真实影院关灯后也不是纯黑，总有一点点。
            vec3 ambient = albedo * 0.055;

            // 距离衰减：房间跨度 17 米，不衰减的话所有墙一个亮度，又回到「纸盒」。
            float dist = length(vWorld);
            float atten = 1.0 / (1.0 + 0.012 * dist * dist);

            vec3 color = (direct + bounce) * atten + ambient;
            fragColor = vec4(color, 1.0);
        }
    """.trimIndent()
}

class GlProgram(vertexSource: String, fragmentSource: String) {
    val id: Int = GlUtils.createProgram(vertexSource, fragmentSource)
    fun use() = GLES20.glUseProgram(id)
    fun attrib(name: String): Int = GLES20.glGetAttribLocation(id, name)
    fun uniform(name: String): Int = GLES20.glGetUniformLocation(id, name)

    companion object {
        /** 视频程序：片元着色器按扩展名写法逐个试，第一个能编译链接的就用它。 */
        fun video(): GlProgram {
            var last: Throwable? = null
            for (fragment in Shaders.VIDEO_FRAGMENT_VARIANTS) {
                try {
                    return GlProgram(Shaders.VIDEO_VERTEX, fragment)
                } catch (t: Throwable) {
                    last = t
                }
            }
            throw last ?: IllegalStateException("视频着色器编译失败")
        }
    }
}

object GlUtils {
    private const val TAG = "CardboardVr.GL"

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader compile: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            error("Shader 编译失败")
        }
        return shader
    }

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "program link: ${GLES20.glGetProgramInfoLog(program)}")
            error("GL 程序链接失败")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        return ids[0]
    }

    fun createUiTexture(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        return ids[0]
    }

    fun uploadBitmap(textureId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
    }

    fun checkError(tag: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) Log.e(TAG, "$tag: GL error 0x${error.toString(16)}")
    }
}
