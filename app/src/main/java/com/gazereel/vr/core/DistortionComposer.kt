package com.gazereel.vr.core

/**
 * alpha39 纯复刻：「两眼先渲到离屏纹理 → SDK 桶形畸变网格 → 屏幕」的唯一渲染链。
 *
 * ## 为什么不直接把 Cardboard SDK 的类写进 VrRenderer
 *
 * [com.gazereel.vr.render.VrRenderer] 在 `src/main/java`，是**永远参与编译**的源码集；
 * 官方 SDK 的适配层在 `src/cardboard/java`，只有在 `app/libs/cardboard-sdk.aar`
 * 存在时才由 `build.gradle.kts` 条件加入。main 侧一旦 import
 * `com.google.cardboard.sdk.*`，没放 AAR 的机器就编译不过。
 * 所以这里只定接口，实现放在 cardboard 源码集，由 VrHeadsetProvider 注入。
 *
 * ## 这是唯一路径
 *
 * [VrHeadsetProvider.distortionComposer] 无条件返回 SDK 实现，渲染器没有
 * 「左右半屏直出」分支。[beginFrame] 返回 null 只表示**这一帧的 GL 资源没起来**，
 * 渲染器跳过本帧，不会切到另一套几何或投影。
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
     * 返回 null 表示这一帧不可用（FBO 不完整、SDK 网格拿不到、缺 native 库），
     * 渲染器跳过本帧；绝不能画到一个没绑上的 framebuffer 上。
     * 视口与官方 `CardboardViewApi` 一致：左眼占纹理左半、右眼占右半。
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
