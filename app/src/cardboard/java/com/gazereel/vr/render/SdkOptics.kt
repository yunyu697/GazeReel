package com.gazereel.vr.render

/**
 * C3 / C5 总开关：**默认关**。
 *
 * 打开后同时发生两件事，必须一起发生、不能只开一半：
 * 1. [CardboardHeadsetProvider.eyeParams] 改用 SDK 的每眼真实 FOV / IPD 投影
 *    （`getEyeProjectionMatrix` + `getEyeFromHeadMatrix`，C3，V2.30 已写好）；
 * 2. [CardboardHeadsetProvider.distortionComposer] 返回 [SdkDistortionComposer]，
 *    两眼先渲进全屏离屏纹理，再由 SDK 畸变网格合成到屏幕（C5，V2.32 新建）。
 *
 * 只开 FOV 不开桶形畸变，镜片边缘会把画面再拉一次；只开畸变不开 FOV，
 * 网格采样范围与投影视场对不上。**两者是同一个专项。**
 *
 * ## 真机校准前必须知道的事
 *
 * - `QrCode.getSavedDeviceParams()` 读的是用户扫过码保存的纸盒参数；没扫过就退回
 *   Cardboard V1 参数，与手上那副镜子大概率不是同一套。FOV 和畸变系数都会偏。
 * - 球面网格、`uiQuad()` 2D 银幕、视频层三层共用同一套 MVP，换投影后**三层要一起
 *   用网格线真机校准**，不能只看视频层说「没问题」。
 * - 这条链会多一次全屏纹理写入 + 一次全屏网格合成，帧预算与发热要在真机上量。
 *
 * 改这一行为 true 并重新编译即可在真机上 A/B；默认 false 时所有新增代码完全不介入，
 * 已验证过的分屏直出几何一帧不变。
 */
internal const val USE_SDK_OPTICS = false
