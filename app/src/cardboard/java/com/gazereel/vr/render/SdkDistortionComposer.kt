package com.gazereel.vr.render

import android.opengl.GLES20
import com.gazereel.vr.core.DistortionComposer
import com.gazereel.vr.core.DistortionTargets
import com.gazereel.vr.data.RunLog
import com.google.cardboard.sdk.DistortionRenderer
import com.google.cardboard.sdk.LensDistortion
import com.google.cardboard.sdk.nativetypes.EyeTextureDescription
import com.google.cardboard.sdk.nativetypes.EyeType

/**
 * C5 / V2.32：把工程自绘的两眼画面接到官方 Cardboard SDK 的桶形畸变后处理。
 *
 * ## 形状完全照抄官方 CardboardViewApi（v1.36.0）
 *
 * 官方用的是**一张全屏纹理**，不是两张：左右眼分别渲到它的左半 / 右半，
 * 然后 `DistortionRenderer.renderEyeToDisplay()` 用畸变网格把两个半区各采样一次
 * 输出到屏幕。关键点逐条核对过官方源码：
 *
 * - `NATIVE_TARGET_DISPLAY = 0`：`renderEyeToDisplay` 的第一个 long 参数是
 *   **显示目标句柄**，不是纹理 id；官方直接传 0。纹理 id 在
 *   [EyeTextureDescription.texture] 里。
 * - 左眼 `leftU=0 / rightU=0.5`，右眼 `leftU=0.5 / rightU=1`，
 *   两眼 `topV=1 / bottomV=0`。FBO 的 y=0 在下、纹理 v=0 也在下，不需要翻 v。
 * - 每眼 viewport 是 `(0,0,w/2,h)` 与 `(w/2,0,w/2,h)`，与工程直出链路数值一致，
 *   所以换成离屏后每眼画到的像素范围不变，只是落点从屏幕变成纹理。
 *
 * ## 失败策略
 *
 * 任何一步失败（FBO 不完整、SDK 网格拿不到、缺 native 库）都返回 null，
 * 让 [VrRenderer] 当帧退回直出，并把整条链标成 failed 不再重试——
 * 绝不每帧刷日志，也绝不把一个没绑上的 framebuffer 留给绘制循环。
 *
 * ## GL 线程约定
 *
 * [DistortionRenderer] 的构造 / [release] 必须在 GL 线程；本类的实例由
 * [CardboardHeadsetProvider.distortionComposer] 在主线程创建，但**不碰任何 GL 资源**，
 * 真正分配发生在第一次 [beginFrame]（GL 线程）。
 */
internal class SdkDistortionComposer(
    private val lens: LensDistortion
) : DistortionComposer {

    private var fboId = 0
    private var texId = 0
    private var depthId = 0
    private var texWidth = 0
    private var texHeight = 0
    private var distortionRenderer: DistortionRenderer? = null
    private var failed = false
    private var loggedMesh = false

    private val leftDesc = EyeTextureDescription()
    private val rightDesc = EyeTextureDescription()

    private var frameWidth = 0
    private var frameHeight = 0

    override fun beginFrame(width: Int, height: Int): DistortionTargets? {
        if (failed || width <= 0 || height <= 0) return null
        if (!ensureTargets(width, height) || !ensureMesh()) {
            failed = true
            return null
        }
        frameWidth = width
        frameHeight = height
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        // glClear 不受 viewport 影响（未开 scissor）；这里仍设成整张纹理，
        // 免得后续有人加了 scissor 之后只清掉半张。
        GLES20.glViewport(0, 0, width, height)
        return DistortionTargets(
            intArrayOf(0, 0, width / 2, height),
            intArrayOf(width / 2, 0, width / 2, height)
        )
    }

    override fun endFrame() {
        // 即便渲染器不在了也必须先解绑：beginFrame 已经把离屏 FBO 绑上了，
        // 留在那儿会让下一帧的清屏 / 抽帧靶全画进纹理，画面直接黑掉。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, frameWidth, frameHeight)
        val renderer = distortionRenderer ?: return

        val texture = texId.toLong()
        leftDesc.apply {
            this.texture = texture
            leftU = 0f; rightU = 0.5f; topV = 1f; bottomV = 0f
        }
        rightDesc.apply {
            this.texture = texture
            leftU = 0.5f; rightU = 1f; topV = 1f; bottomV = 0f
        }

        // 工程全局为 UI 半透明开了 GL_BLEND（ONE / ONE_MINUS_SRC_ALPHA）。
        // SDK 的畸变网格是不透明全屏覆盖，混合只会让边缘露出上一帧，
        // 合成期间先关掉、之后按 VrRenderer.onSurfaceCreated 的原值恢复。
        val blendWasOn = GLES20.glIsEnabled(GLES20.GL_BLEND)
        if (blendWasOn) GLES20.glDisable(GLES20.GL_BLEND)
        try {
            renderer.renderEyeToDisplay(
                NATIVE_TARGET_DISPLAY, 0, 0, frameWidth, frameHeight,
                leftDesc, rightDesc
            )
        } finally {
            if (blendWasOn) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
    }

    /** GL 线程。上下文重建 / 页面销毁都会走这里，重复调用必须安全。 */
    override fun release() {
        runCatching { distortionRenderer?.close() }
            .onFailure {
                RunLog.append("gl", "[C5] SDK DistortionRenderer 关闭失败：${it.message}")
            }
        distortionRenderer = null
        destroyTargets()
        failed = false
        loggedMesh = false
    }

    // ---------------------------------------------------------------------
    // 离屏靶
    // ---------------------------------------------------------------------

    private fun ensureTargets(width: Int, height: Int): Boolean {
        if (fboId != 0 && texWidth == width && texHeight == height) return true
        destroyTargets()
        return runCatching {
            val ids = IntArray(3)
            GLES20.glGenFramebuffers(1, ids, 0)
            GLES20.glGenTextures(1, ids, 1)
            GLES20.glGenRenderbuffers(1, ids, 2)
            fboId = ids[0]; texId = ids[1]; depthId = ids[2]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            // 工程当前没开 GL_DEPTH_TEST，但 glClear 带了 DEPTH_BUFFER_BIT；
            // 挂上深度附着让清屏语义与直出一致，也免得以后开深度测试时再补。
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthId)
            GLES20.glRenderbufferStorage(
                GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texId, 0
            )
            GLES20.glFramebufferRenderbuffer(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, depthId
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw IllegalStateException("FBO 不完整：0x${Integer.toHexString(status)}")
            }
            // 不能把「当前 FBO 的颜色附着」留在 GL_TEXTURE0 的 GL_TEXTURE_2D 绑定上：
            // 绘制循环在这条链里仍会用同一个纹理单元绑 UI / external 视频纹理，
            // 某些驱动会把「附着物同时被绑着」判成反馈环，画面直接黑掉或读到旧像素。
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
            texWidth = width
            texHeight = height
            RunLog.append("gl", "[C5] 离屏靶就绪 · ${width}×${height} · 左右各半")
            true
        }.getOrElse { t ->
            RunLog.append(
                "gl",
                "[C5] 离屏靶创建失败，退回直出：${t.javaClass.simpleName}: ${t.message}"
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            destroyTargets()
            false
        }
    }

    private fun destroyTargets() {
        // 先解绑再删：删除仍被绑定的对象在部分驱动上会留下一个「僵尸绑定」，
        // 下一帧沿用旧 id 时直接读到黑屏。上下文重建时这条尤其关键。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (fboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
        if (depthId != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(depthId), 0)
        fboId = 0; texId = 0; depthId = 0
        texWidth = 0; texHeight = 0
    }

    // ---------------------------------------------------------------------
    // SDK 畸变网格
    // ---------------------------------------------------------------------

    private fun ensureMesh(): Boolean {
        if (distortionRenderer != null) return true
        return runCatching {
            val renderer = DistortionRenderer()
            // 官方 CardboardViewApi 在每次 onSurfaceChanged 后按眼 setMesh；
            // 这里网格只与纸盒参数有关、与屏幕尺寸无关，装载一次即可。
            renderer.setMesh(lens.getDistortionMesh(EyeType.LEFT), EyeType.LEFT)
            renderer.setMesh(lens.getDistortionMesh(EyeType.RIGHT), EyeType.RIGHT)
            distortionRenderer = renderer
            if (!loggedMesh) {
                loggedMesh = true
                RunLog.append("gl", "[C5] SDK 桶形畸变网格已装载 · 左右眼 · 默认显示目标")
            }
            true
        }.getOrElse { t ->
            RunLog.append(
                "gl",
                "[C5] SDK 畸变网格装载失败，退回直出：${t.javaClass.simpleName}: ${t.message}"
            )
            runCatching { distortionRenderer?.close() }
            distortionRenderer = null
            false
        }
    }

    private companion object {
        /** 官方 CardboardViewApi 的 NATIVE_TARGET_DISPLAY；不是纹理 id。 */
        const val NATIVE_TARGET_DISPLAY = 0L
    }
}
