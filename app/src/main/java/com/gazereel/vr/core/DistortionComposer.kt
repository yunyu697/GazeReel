package com.gazereel.vr.core

/**
 * C5 / V2.32：可选的「两眼先渲到离屏纹理 → SDK 桶形畸变网格 → 屏幕」后处理链。
 *
 * ## 为什么不直接把 Cardboard SDK 的类写进 VrRenderer
 *
 * [com.gazereel.vr.render.VrRenderer] 在 `src/main/java`，是**永远参与编译**的源码集；
 * 官方 SDK 的适配层在 `src/cardboard/java`，只有在 `app/libs/cardboard-sdk.aar`
 * 存在时才由 `build.gradle.kts` 条件加入。main 侧一旦 import
 * `com.google.cardboard.sdk.*`，没放 AAR 的机器就编译不过。
 * 所以这里只定接口，实现放在 cardboard 源码集，由 VrHeadsetProvider 注入。
 *
 * ## 默认路径零变化
 *
 * [VrHeadsetProvider.distortionComposer] 默认返回 null，渲染器仍按原来的
 * 左右半屏 `glViewport` 直出。只有显式打开 SDK 光学开关，才会走
 * [beginFrame] / [endFrame] 这条离屏链。
 *
 * ## 线程约定
 *
 * 三个方法全部只能在 **GL 线程**调用。SDK 的 `DistortionRenderer` 构造与 close
 * 都要求 GL 上下文；[release] 由 VrActivity 通过 `queueEvent` 投递，不能在主线程调。
 */
interface DistortionComposer {

    /**
     * 每帧两眼绘制之前调用：创建 / 复用全屏离屏纹理并绑定它的 FBO。
     *
     * 返回 null 表示这条链不可用（初始化失败、缺 native、FBO 不完整），
     * 渲染器必须**当帧退回直出**，绝不能画到一个没绑上的 framebuffer 上。
     * 返回的视口与官方 `CardboardViewApi` 一致：左眼占纹理左半、右眼占右半，
     * 与现有直出链路的 `glViewport` 数值完全相同，只是落点从屏幕换成纹理。
     */
    fun beginFrame(width: Int, height: Int): DistortionTargets?

    /** 两眼画完后调用：解绑 FBO，用 SDK 畸变网格把纹理合成到默认 framebuffer。 */
    fun endFrame()

    /** GL 线程：释放 FBO / 纹理 / 深度缓冲 / SDK DistortionRenderer。 */
    fun release()
}

/** 两眼在离屏纹理上的视口，格式 `[x, y, width, height]`。 */
data class DistortionTargets(
    val leftViewport: IntArray,
    val rightViewport: IntArray
) {
    fun viewportOf(eye: Eye): IntArray =
        if (eye == Eye.LEFT) leftViewport else rightViewport
}
