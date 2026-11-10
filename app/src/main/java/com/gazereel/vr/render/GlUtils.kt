package com.gazereel.vr.render

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log

/** GLSL ES 3.0 着色器。视频帧通过 samplerExternalOES 直接采样，无 CPU 拷贝。 */
object Shaders {
    const val VIDEO_VERTEX = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        uniform mat4 uMvp;
        uniform mat4 uSTMatrix;
        uniform vec4 uEyeRegion;
        out vec2 vUv;
        void main() {
            gl_Position = uMvp * aPosition;
            // V2.7.9：每眼半幅必须在「视频画面坐标系」里切，再交给 ST 矩阵映射到纹理缓冲。
            // 旧实现是先过 uSTMatrix 再切半幅——uSTMatrix 一旦不是单位矩阵
            //（解码器把画面放进更大的纹理缓冲、或做了缩放），切出来的就不是半幅，
            // 整幅画面会被铺满视野，正中看到的正好是左右两幅的接缝。
            vec2 uv = vec2(aTexCoord.x * uEyeRegion.x + uEyeRegion.z,
                           aTexCoord.y * uEyeRegion.y + uEyeRegion.w);
            vUv = (uSTMatrix * vec4(uv, 0.0, 1.0)).xy;
        }
    """

    const val VIDEO_FRAGMENT = """
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
    """

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

    const val UI_VERTEX = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        uniform mat4 uMvp;
        out vec2 vUv;
        void main() {
            gl_Position = uMvp * aPosition;
            vUv = aTexCoord;
        }
    """

    const val UI_FRAGMENT = """
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
    """
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
