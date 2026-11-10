Cardboard SDK 本地依赖说明（已接入）
====================================

本目录下的 cardboard-sdk.aar 已构建完成并参与编译，工程可直接 assembleDebug。
本文件记录它的来源与重建方法，便于换机器或升级版本。

来源
----
官方仓库 googlevr/cardboard，tag v1.36.0，本地用 NDK + CMake 构建 :sdk:assembleDebug。
大小 565 KB，含 78 个 Java 类（HeadTracker / LensDistortion / HeadTransform / QrCode /
Initialize 等）与 native 库 libGfxPluginCardboard.so（arm64-v8a、armeabi-v7a）。

为什么用 debug AAR 而不是 release
--------------------------------
官方 sdk 模块的 release 构建开启 R8，实测产物只剩 10 个类，HeadTracker 与 LensDistortion
被裁掉，Java API 不可用。debug 变体未混淆，可直接引用。若后续要用 release，
需要在官方 sdk 模块的 proguard 规则里保留 com.google.cardboard.sdk.** 。

重建方法
--------
    bash tools/install_cardboard_sdk.sh

脚本会完成：拉源码 → 写 local.properties → 配置 JVM 代理参数 → 构建 :sdk:assembleDebug
→ 复制到本目录。依赖 SDK 组件：ndk;29.0.14206865、cmake;3.31.6。

工程侧如何生效
--------------
app/build.gradle.kts 检测到本目录存在 cardboard-sdk.aar 时会：
1. 把 src/cardboard/java 加入 main sourceSet（即 CardboardHeadsetProvider）；
2. 把 libs/*.aar、*.jar 加入 implementation。
VrActivity 通过反射尝试实例化 CardboardHeadsetProvider，失败（例如 x86 模拟器缺 native 库）
则回落 SensorHeadsetProvider，两者实现同一个 VrHeadsetProvider 接口。

已绑定的 SDK 能力
----------------
Initialize.initialize、HeadTracker.getPose（含屏幕旋转补偿）、
LensDistortion.getEyeFromHeadMatrix / getEyeProjectionMatrix（每眼 FOV 与 IPD）、
QrCode.getSavedDeviceParams（未扫码回落 Cardboard V1 参数）。

尚未绑定
--------
LensDistortion.getDistortionMesh / DistortionRenderer（桶形畸变预补偿）。当前为自绘分屏，
画面几何正确但未做畸变矫正，属阶段二工作；所有 SDK 调用集中在
src/cardboard/java/com/example/cardboardvr/render/CardboardHeadsetProvider.kt 一个文件内。

注意
----
SDK 已归档，建议 fork 维护分支。x86 / x86_64 模拟器无对应 .so，只会走传感器兜底路径，
要用 SDK 路径请装 arm64 真机。
