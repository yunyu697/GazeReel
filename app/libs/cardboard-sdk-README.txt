Cardboard SDK 本地依赖说明（alpha42 起）
======================================

本工程用官方 googlevr/cardboard v1.36.0 作为头追 + 光学的唯一底座。
本地依赖分三块，**缺一块就进不了 VR**：

  app/libs/cardboard-sdk.aar                              官方 :sdk:assembleDebug 产出
  app/src/main/jniLibs/<abi>/libcardboard_sdk_jni.so    官方 paperscope target 产出
  app/build.gradle.kts 里的三条 Maven 依赖              官方 sdk/build.gradle 的 Java 传递依赖

alpha39 / alpha40 / alpha41 三次「点进 VR 整个 APP 闪退」分别就是漏了其中一块，
详见下面的崩溃链。


一、AAR 不含 Java JNI 桥接库（alpha39 崩溃）
---------------------------------------------
官方 sdk 模块的 CMake 只构建 libGfxPluginCardboard.so（C API，给 Unity 插件和
NDK demo 用）。classes.jar 里 Java 类（Initialize / HeadTracker / LensDistortion /
DistortionRenderer / QrCode）的 native 方法实现，定义在
paperscope/CMakeLists.txt 的 cardboard_sdk_jni target，只有官方 demo app 才构建。
AAR 里没有 libcardboard_sdk_jni.so，Initialize.<clinit> 的
System.loadLibrary("cardboard_sdk_jni") 必抛 UnsatisfiedLinkError。

alpha39 真机日志（Xiaomi 25098PN5AC / API 37）：
  java.lang.NoClassDefFoundError: com.google.cardboard.sdk.Initialize
  Caused by: java.lang.UnsatisfiedLinkError:
    dlopen failed: library "libcardboard_sdk_jni.so" not found

补法（照官方 target 构建，没有一行自研 JNI 代码）：
    bash tools/build_cardboard_jni.sh
源文件 100% 来自官方仓库：
  java_api/src/main/jni/cardboard_jni.cc
  sdk/jni_utils/android/jni_utils.cc
target 定义见 tools/cardboard-jni/CMakeLists.txt，原样摘抄
paperscope/CMakeLists.txt:94-105,123。产物按 ABI 落到 app/src/main/jniLibs/，
AGP 自动合并进 APK。链接参数 -Wl,-z,max-page-size=16384 满足 Android 15+
的 16KB 页要求。

完整性自检：
  llvm-nm -D libcardboard_sdk_jni.so | grep Java_com_google_cardboard_sdk  → 20 个符号
  llvm-readelf -d ... | grep NEEDED  → 必须含 libGfxPluginCardboard.so，
                                        且不含 libc++_shared.so（用 c++_static）
  llvm-readelf -l ... | grep LOAD    → Align 为 0x4000


二、AAR 不带 POM：Java 传递依赖必须显式补齐（alpha40 崩溃）
------------------------------------------------------------
implementation(fileTree("libs")) 引入本地 AAR **不会**带上官方 sdk/build.gradle
的依赖。官方依赖里有三个是运行期真会用到的，缺了不会编译报错，但会在
LensDistortion 构造时炸在 native 层：

  implementation("com.google.android.gms:play-services-vision:20.1.3")
  implementation("com.google.protobuf:protobuf-javalite:3.19.4")
  implementation("org.jspecify:jspecify:1.0.0")

（另需 androidx.appcompat / material，工程本来就有。）
alpha40 在模拟器 emulator-5554 上复现到的 logcat：
  Fatal signal 6 (SIGABRT)
  JNI NewGlobalRef called with pending exception
    java.lang.NoClassDefFoundError:
      Failed resolution of: Lcom/google/protobuf/ExtensionRegistryLite;
    at DeviceParamsUtils.parseCardboardDeviceParams(DeviceParamsUtils.java:45)
    at LensDistortion.nativeLensDistortionCreate
    at CardboardHeadsetProvider.<init>(CardboardHeadsetProvider.kt:85)
模型：Java 抛异常 → native 没清 pending exception 就调 JNI → CheckJNI abort。
dex 里只有「引用字符串」不算数，要用 dexdump 看到 Class descriptor 定义：
alpha42 的 APK 实测 com/google/protobuf/ 358 个类、
com/google/android/gms/vision/ 101 个类、org/jspecify/ 4 个类、GMS 传递依赖齐全。


三、Initialize.initialize() 必须传 Activity（alpha41 崩溃）
-----------------------------------------------------------
官方 Initialize.java 的 Javadoc 明确写：
  "It is generally an Activity instance or wraps one ... getDisplay()"
官方 demo（MainActivity / SettingsActivity）一律传 this。
native 侧 ScreenParamsUtils.getScreenPixelDensity(context) 在 API 30+ 调
context.getDisplay()；applicationContext 不是 visual Context，直接抛
UnsupportedOperationException，JNI 没清异常继续 GetFieldID → CheckJNI SIGABRT。

alpha41 模拟器 tombstone：
  JNI GetFieldID called with pending exception
    java.lang.UnsupportedOperationException:
      Tried to obtain display from a Context not associated with one.
    at ScreenParamsUtils.getScreenPixelDensity(ScreenParamsUtils.java:70)
    at LensDistortion.nativeLensDistortionCreate
所以 CardboardHeadsetProvider.init 里是 Initialize.initialize(context)，
context 由 VrActivity.createHeadset() 传入 this，**不能**改成 applicationContext。


四、姿态矩阵约定（alpha35 双轴反向的真正根因）
-----------------------------------------------
官方链（CardboardView.java:967-971、1056 与 applyHeadView、以及
paperscope/cardboard_launcher.cc 的 GetPose）：

  M = MathUtils.getMatrixFromPose(position, orientation)   // 四元数矩阵，不转置
  headView = M
  eyeView  = eyeFromHead × headView

M 是 **view 矩阵（世界→头）**。证据：官方 HeadTransform.getForwardVector()
注释写明「Same as multiplying the inverse of the rotation component ... by (0,0,-1)」，
取的是 -headView[2+i*4]（第 3 行），不是第 3 列。

工程 headRotation() 的契约是 **pose（设备→世界）**，UI / 凝视 / PostureTracker
都按「第 3 列取负 = 世界系视线」读。因此在 Provider 边界转置一次：
  refresh():  P = transpose(M)  → rig.submit(P)
  eyeParams(): HeadSpace.viewMatrix(P) = transpose(P) = M
净结果 view 与官方 M 完全一致，双轴不反；同时 forwardOf(P) 与官方
getForwardVector(M) 一致（可用纯矩阵验算复现）。

旧 README 的「SDK 四元数是 head view 必须转置」和「按 pose 持有、只转一次」
两种说法都不完整，已作废。判据是官方源码，不是过往结论。


五、必须遵守的其它 SDK 约定
---------------------------
1. getPose 第 1 参数是相对当前时刻的 delta（50_000_000 ns），不是绝对时间。
   java_api/src/main/jni/cardboard_jni.cc 内部是
   timestamp_ns = GetBootTimeNano() + delta_time_ns。
2. deviceOrientation 是 CardboardViewportOrientation 枚举，数值与
   Surface.ROTATION_* 不同：ROTATION_0→2、90→0、180→3、270→1。
   旋转值按官方 CardboardViewApi 用 DisplayManager.getDisplay(DEFAULT_DISPLAY)
   .getRotation() 取，不用已弃用的 WindowManager.defaultDisplay。
3. 着色器 `#version 300 es` 必须是源码第一行。Kotlin 三引号会留前导换行，
   严格驱动会编译失败，GlUtils.Shaders 四个着色器统一 .trimIndent()。
4. VR 启动成功后 VrActivity 会调 CrashLog.clear()，写 native 崩溃水位线。
   native 崩溃（SIGABRT/SIGSEGV）杀进程时 Java try-catch 接不住，配置页改由
   ActivityManager.getHistoricalProcessExitReasons() 读 REASON_CRASH_NATIVE
   与 tombstone 摘要展示。alpha42 已在模拟器上端到端验证：崩溃卡片 +
   详情弹窗可读到完整调用栈。


六、重建与验证
--------------
    bash tools/install_cardboard_sdk.sh
脚本完成：拉官方源码 v1.36.0 → 写 local.properties → 构建 :sdk:assembleDebug
→ 复制 AAR → 调用 build_cardboard_jni.sh 补 JNI 库（第 6 步）。
依赖组件：ndk;29.0.14206865、cmake;3.31.6、ninja。
必须用 debug 变体：官方 release 开 R8，会把 HeadTracker / LensDistortion 裁掉。

    bash tools/gradlew-direct.sh --no-daemon :app:assembleDebug
本机 ~/.gradle/gradle.properties 残留失效代理 127.0.0.1:62755，联网解析依赖
会 Connection refused；项目级空 systemProp 覆盖、nonProxyHosts=*、--offline
都无效。该脚本只在编译期间临时注释掉全局代理行，退出时自动还原。

ABI：只构建 arm64-v8a / armeabi-v7a。Android 模拟器的
x86_64 + native bridge（ro.product.cpu.abilist 含 arm64-v8a）可以装并跑起来，
alpha42 就是在 emulator-5554 上验证的：进程存活、无 native 崩溃、
[C5] 离屏靶 2400×1080 就绪、SDK 桶形畸变网格装载成功、左右眼 UI 正常出图。
没有 x86 / x86_64 原生库，其它模拟器不一定可用。
