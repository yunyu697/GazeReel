package com.gazereel.vr.core

/**
 * V2.44：头追姿态的**坐标约定**，照 Google Cardboard SDK 落地。
 *
 * ## 前四轮为什么全错（离线数值复现，非推测）
 *
 * `SensorManager.getRotationMatrixFromVector()` 给的是 Android ENU 世界的姿态：
 * **X 东、Y 北、Z 天**。而本工程从球面网格、准星、`recenterYaw` 到
 * [PoseMath.yawPitchRollDeg] 全部按 OpenGL 的 **Y 朝天、-Z 朝前** 解读。
 * 两者之间少了一次坐标变换，于是：
 *
 * 1. 正常戴镜、视线水平时，按 Y-up 分解出的 `pitch` 恒为 ±90°（万向节死锁），
 *    而且随用户站的方位乱跳（北 90° / 东 0° / 南 −90° / 西 0°+roll 90°）。
 * 2. alpha30 的 `withInvertedPitch` 在这个死锁点做欧拉分解重建：抬头 15° 时
 *    分解出的 yaw 从 0° 跳到 180°、roll 从 0° 跳到 −180° —— 真机看到的
 *    「开关把画面左右翻转了」就是这一次跳变。
 * 3. 俯仰响应因为轴错位本来就是反的，翻转又把 yaw/roll 搅乱，所以
 *    「抬头/低头方向还是反的」。
 * 4. 前几轮的开关都在这个错误坐标系里打补丁，效果随用户朝向变化，时好时坏。
 *
 * ## 参考实现怎么做的
 *
 * Google Cardboard SDK `sdk/head_tracker.cc` 的 `HeadTracker::GetRotation()`：
 *
 * ```
 * return SensorToDisplayRotations()[viewport] * predicted_rotation
 *        * EkfToHeadTrackerRotations()[viewport];
 * ```
 *
 * 其中 `EkfToHeadTrackerRotations` 就是干「传感器世界 → Y-up 头追世界」这一层转换的
 * 固定旋转常量（LandscapeLeft = `FromYawPitchRoll(-π/2, 0, -π/2)`）。SDK 的姿态
 * 从来没有方向开关，方向是坐标约定保证的，不是用户拨出来的。
 * VRTV / SourceVR 用的 `libgvr.so` 同样是这套约定。
 *
 * ## 这里的等价做法
 *
 * 我们不接 EKF（离线不可验证、且 AAR 缺 JNI 入口，见 CHECKLIST V2.41 拆包结论），
 * 但把 SDK 那层**坐标转换**补上：
 *
 * ```
 * headPose = C · R · S
 *   R = getRotationMatrixFromVector 的 device→ENU（9 元素行主序 → 4×4 列主序）
 *   S = screenToDevice(panelRotation)，屏幕轴 → 设备轴
 *   C = Rx(−90°)：ENU(东,北,天) → 渲染(右,上,后)，det = +1
 * ```
 *
 * C 把 ENU 的天 (0,0,1) 映成渲染的上 (0,1,0)、北 (0,1,0) 映成渲染的前 (0,0,−1)。
 * 它是**合法旋转不是镜像**，所以画面不会倒、不会左右翻，也不引入 roll。
 *
 * 补上 C 之后（离线四方位验证，见 `tools/PoseMathCheck.kt`）：
 * - 戴稳时 yaw/pitch/roll = (方位角, 0, 0)，pitch/roll 与用户站的方位无关；
 * - 抬头 → 视线落在球面上半（v>0.5，天花板）；低头 → 下半（v<0.5，地板）；
 * - 左右转 → u 正确增减；四个方位结果完全一致；
 * - det 恒 +1、roll 恒 0，`recenterYaw` 的 rotY 也终于绕真正的竖直轴。
 *
 * 全部函数不依赖 Android，可用 `tools/run-pose-check.sh` 在 JVM 上直接跑真代码。
 */
object HeadPose {

    /** Surface.ROTATION_0 / 90 / 180 / 270，与 `android.view.Surface` 数值一致。 */
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    /**
     * C = Rx(−90°)，列主序 4×4。
     * 列 0 = 东 → (1,0,0)；列 1 = 北 → (0,0,−1)；列 2 = 天 → (0,1,0)。
     * det = +1。
     */
    private val ENU_TO_RENDER = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, -1f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f
    )

    /**
     * 由旋转矢量矩阵与面板朝向算出**渲染世界里的头追姿态**。
     *
     * @param deviceToWorld9 `getRotationMatrixFromVector` 的 9 元素**行主序**矩阵
     * @param panelRotation `Display.getRotation()` 的 0..3
     * @return 列主序 4×4；列 0/1/2 = 屏幕的右/上/后在世界中的方向，−列 2 = 视线
     */
    fun headPose(deviceToWorld9: FloatArray, panelRotation: Int): FloatArray {
        require(deviceToWorld9.size >= 9) { "rotation matrix must have 9 elements" }
        val r = fromRowMajor9(deviceToWorld9)
        val s = screenToDevice(panelRotation)
        return multiply(multiply(ENU_TO_RENDER, r), s)
    }

    /** 9 元素行主序 3×3 → 列主序 4×4（纯旋转，平移为 0）。 */
    private fun fromRowMajor9(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        r[0] = m[0]; r[1] = m[3]; r[2] = m[6]; r[3] = 0f
        r[4] = m[1]; r[5] = m[4]; r[6] = m[7]; r[7] = 0f
        r[8] = m[2]; r[9] = m[5]; r[10] = m[8]; r[11] = 0f
        r[12] = 0f; r[13] = 0f; r[14] = 0f; r[15] = 1f
        return r
    }

    /**
     * 屏幕坐标 → 设备坐标，按 `Display.getRotation()`。
     *
     * 三列的物理含义：屏幕的右 / 上 / 外分别对应设备的哪根轴。
     * ROTATION_90：屏幕右 = 设备 +y，屏幕上 = 设备 −x
     * （同官方 `remapCoordinateSystem(R, AXIS_Y, AXIS_MINUS_X)`）。
     * 四个分支 det 都是 +1，不引入镜像。
     */
    fun screenToDevice(panelRotation: Int): FloatArray {
        val s = identity()
        when (panelRotation) {
            ROTATION_90 -> {
                s[0] = 0f; s[1] = 1f; s[2] = 0f
                s[4] = -1f; s[5] = 0f; s[6] = 0f
            }
            ROTATION_180 -> {
                s[0] = -1f; s[1] = 0f; s[2] = 0f
                s[4] = 0f; s[5] = -1f; s[6] = 0f
            }
            ROTATION_270 -> {
                s[0] = 0f; s[1] = -1f; s[2] = 0f
                s[4] = 1f; s[5] = 0f; s[6] = 0f
            }
            else -> Unit   // ROTATION_0：屏幕轴即设备轴
        }
        return s
    }

    private fun identity(): FloatArray {
        val m = FloatArray(16)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return m
    }

    /** 列主序 4×4 乘法：`a * b`。 */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = sum
            }
        }
        return out
    }

    /** 视线方向（单位向量）：约定为 −第 3 列。 */
    fun forward(pose: FloatArray): FloatArray =
        floatArrayOf(-pose[8], -pose[9], -pose[10])
}
