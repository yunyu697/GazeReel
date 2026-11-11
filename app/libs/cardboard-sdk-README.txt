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

当前接入状态（V2.30 起）
------------------------
头追已整体切到 SDK：VrActivity.createHeadset() 用反射实例化 CardboardHeadsetProvider，
读它的 sdkActive；缺 AAR / 缺 native so / SDK 初始化失败都自动回落 SensorHeadsetProvider。
两者实现同一个 VrHeadsetProvider 接口，运行日志会写 `[headset] mode=cardboard|sensor`。

已绑定：Initialize.initialize、HeadTracker.getPose（EKF 融合 / 陀螺零偏 / 50ms 预测 /
显示空间旋转补偿 / neck model）、重定正前方、视角锁定、触发键注入。

三个必须遵守的约定（当初没敢启用就是栽在这三处，逐条对照官方源码核实过）
----------------------------------------------------------------------
1. getPose 的第 1 个参数是「相对当前时刻的 delta」，不是绝对时间。
   java_api/src/main/jni/cardboard_jni.cc:382 内部是
   `timestamp_ns = GetBootTimeNano() + delta_time_ns`。
   官方 CardboardViewApi 默认 50_000_000 ns。传 System.nanoTime() 会让 EKF
   按角速度外推到极远未来，姿态彻底失控。
2. SDK 四元数转出来的矩阵是 head view（世界→头），不是设备姿态。
   hellocardboard-android/.../hello_cardboard_app.cc:181 直接 `head_view_ = GetPose()`；
   CardboardView.java:1056 把它塞进 headTransform.getHeadView()。
   工程接口 VrHeadsetProvider.headRotation() 要 pose（设备→世界），必须转置一次。
3. deviceOrientation 参数是 CardboardViewportOrientation 枚举，数值与
   Surface.ROTATION_* 不同：ROTATION_0→2、90→0、180→3、270→1
   （见 CardboardViewApi.setDeviceOrientation 与 include/cardboard.h:46-67）。

尚未绑定
--------
- LensDistortion.getEyeFromHeadMatrix / getEyeProjectionMatrix：适配层里已实现，
  但 USE_SDK_OPTICS 默认 false。它会改变现有球面网格的取景范围，必须与
- LensDistortion.getDistortionMesh / DistortionRenderer（桶形畸变预补偿）
  一起做专项校准，不能单独打开。当前为自绘分屏直出，画面几何零变化。
所有 SDK 调用集中在 src/cardboard/java/com/gazereel/vr/render/CardboardHeadsetProvider.kt
一个文件内。

注意
----
SDK 已归档，建议 fork 维护分支。x86 / x86_64 模拟器无对应 .so，只会走传感器兜底路径，
要用 SDK 路径请装 arm64 真机。
