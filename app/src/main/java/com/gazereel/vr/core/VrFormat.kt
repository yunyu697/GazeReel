package com.gazereel.vr.core

/**
 * PRD 3.3 / 6.2：视频格式三要素（投影、立体布局、视场）。
 * 识别结果只作为「建议」，用户覆盖后写入 format_override 表。
 */
enum class Projection { SPHERE_360, HALF_SPHERE_180, PLANE_2D, FISHEYE }

enum class StereoLayout { MONO, SBS, TAB }

/**
 * 视场。18:2x 加 220 / 270 两档 —— 它们是**等距柱状张角档**，不是鱼眼镜头 FOV。
 *
 * `degrees` 是球面网格的水平跨度，`null` 表示平面 2D 银幕（没有球面）。
 * 这个值以前只写进数据库、从不参与渲染（11-10 记的「FOV 是死的」），
 * 现在 `ProjectionMesh.curved(spanDeg)` 直接读它，几何由 `VrRenderer` 按 fov 变化重建。
 */
enum class FieldOfView(val degrees: Int?) {
    FOV_360(360), FOV_180(180), FOV_220(220), FOV_270(270),

    /**
     * 单镜头鱼眼。`degrees` 为 null 是**故意的**：鱼眼张角属于镜头，不属于片子 ——
     * 同一支 MKX200 拍的所有片子都是 200°，用户换镜头才会变，所以张角存在
     * `VrSettings.fisheyeFovDeg` 里全局可调，不按片记。这里只表达「这是鱼眼」。
     */
    FOV_FISHEYE(null),

    FLAT(null)
}

/**
 * 鱼眼预设：19:0x 的四个档位，都是**真实存在的单镜头张角**。
 *
 * 为什么给预设而不是让用户直接填数字：用户不知道自己的镜头是 200 还是 220，
 * 但知道「我用的是 MKX200」。选型号就落一个合理的默认张角，再让他微调。
 *
 * 只做**等距投影（equidistant）**：VR180 规范与消费级鱼眼（MKX / VRCA / RF5.2）
 * 默认都是等距；等立体角（equisolid）与立体投影（stereographic）本版不支持，
 * 认成等距会在边缘留一点形变，但不会像不反畸变那样整幅扭曲。
 * `circlePercent` 用于「画面圆没占满整幅」的机器 —— 圆只占 90% 时采样半径要乘 0.9。
 */
enum class FisheyePreset(val label: String, val fovDeg: Int, val circlePercent: Int) {
    GENERIC_180("180 通用", 180, 100),
    RF52_190("190 RF", 190, 100),
    MKX_200("200 MKX", 200, 100),
    VRCA_220("220 VRCA", 220, 100)
}

data class VrFormat(
    val projection: Projection,
    val layout: StereoLayout,
    val fov: FieldOfView,
    val confidence: Float = 1.0f
) {
    companion object {
        val DEFAULT = VrFormat(Projection.SPHERE_360, StereoLayout.MONO, FieldOfView.FOV_360)

        /**
         * 格式确认浮层的十个大按钮（18:2x：220 / 270 张角档加入，4 列 × 3 行）。
         * 两个平面项对应普通相机视频与 SBS 3D 电影，复用同一个平面网格，
         * 立体布局由顶点着色器的 uEyeRegion 取半幅纹理完成。
         *
         * 220 / 270 各给 Mono 与 SBS 两档：文件名没写标记时用户要能手动切到
         * 正确的张角**并且**不把单幅片源误切成左右两半，只给一个 SBS 项不够。
         * 它们的 projection 借用 HALF_SPHERE_180 表达「非整球」，真正的跨度由 fov 决定。
         */
        val QUICK_CHOICES = listOf(
            VrFormat(Projection.SPHERE_360, StereoLayout.MONO, FieldOfView.FOV_360),
            VrFormat(Projection.SPHERE_360, StereoLayout.SBS, FieldOfView.FOV_360),
            VrFormat(Projection.HALF_SPHERE_180, StereoLayout.SBS, FieldOfView.FOV_180),
            VrFormat(Projection.HALF_SPHERE_180, StereoLayout.TAB, FieldOfView.FOV_180),
            VrFormat(Projection.HALF_SPHERE_180, StereoLayout.MONO, FieldOfView.FOV_220),
            VrFormat(Projection.HALF_SPHERE_180, StereoLayout.SBS, FieldOfView.FOV_220),
            VrFormat(Projection.HALF_SPHERE_180, StereoLayout.MONO, FieldOfView.FOV_270),
            VrFormat(Projection.HALF_SPHERE_180, StereoLayout.SBS, FieldOfView.FOV_270),
            VrFormat(Projection.PLANE_2D, StereoLayout.MONO, FieldOfView.FLAT),
            VrFormat(Projection.PLANE_2D, StereoLayout.SBS, FieldOfView.FLAT),
            // 19:0x：单镜头鱼眼。Mono 是「一个鱼眼圆铺满整幅」；
            // SBS 是 VR180 双镜头相机（如 Canon RF5.2）的每眼一个圆 —— 左右半幅各一个，
            // 顶点着色器的 uEyeRegion 取半幅，半幅内再按鱼眼投影反畸变。
            VrFormat(Projection.FISHEYE, StereoLayout.MONO, FieldOfView.FOV_FISHEYE),
            VrFormat(Projection.FISHEYE, StereoLayout.SBS, FieldOfView.FOV_FISHEYE)
        )
    }

    /**
     * 4 列后每格只有 190px，完整 label（「360° 球面 · SBS 左右」）会被缩到 20px 下限
     * 再截断。格式页用这个短标签，完整 label 仍用于日志与格式记忆。
     */
    val shortLabel: String
        get() = buildString {
            append(
                when (fov) {
                    FieldOfView.FOV_360 -> "360°"
                    FieldOfView.FOV_180 -> "180°"
                    FieldOfView.FOV_220 -> "220°"
                    FieldOfView.FOV_270 -> "270°"
                    FieldOfView.FOV_FISHEYE -> "鱼眼"
                    FieldOfView.FLAT -> "2D"
                }
            )
            append(" · ")
            append(
                when (layout) {
                    StereoLayout.MONO -> "Mono"
                    StereoLayout.SBS -> "SBS"
                    StereoLayout.TAB -> "TAB"
                }
            )
        }

    /** V2.5：用户拍板 2D 采用世界锁定银幕，文案从「2D 平面」改为「2D 银幕」。 */
    val label: String
        get() = buildString {
            append(
                when {
                    projection == Projection.FISHEYE -> "鱼眼镜头"
                    fov == FieldOfView.FOV_220 -> "220° 全景"
                    fov == FieldOfView.FOV_270 -> "270° 全景"
                    projection == Projection.SPHERE_360 -> "360° 球面"
                    projection == Projection.HALF_SPHERE_180 -> "180° 半球"
                    else -> "2D 银幕"
                }
            )
            append(" · ")
            append(
                when (layout) {
                    StereoLayout.MONO -> "Mono"
                    StereoLayout.SBS -> "SBS 左右"
                    StereoLayout.TAB -> "TAB 上下"
                }
            )
        }
}

/**
 * 每眼采样区域：片元着色器以 uv * scale + offset 取纹理。
 * SBS 左右半宽、TAB 上下半高、Mono 全幅（PRD 6.1 第 4 条）。
 */
data class TextureRegion(
    val xScale: Float,
    val yScale: Float,
    val xOffset: Float,
    val yOffset: Float
) {
    fun toFloatArray(): FloatArray = floatArrayOf(xScale, yScale, xOffset, yOffset)
}

fun VrFormat.eyeTextureRegion(eye: Eye): TextureRegion = when (layout) {
    StereoLayout.MONO -> TextureRegion(1f, 1f, 0f, 0f)
    StereoLayout.SBS -> TextureRegion(0.5f, 1f, if (eye == Eye.LEFT) 0f else 0.5f, 0f)
    // 纹理坐标原点在左下：上眼取下偏移 0.5，下眼取 0；若 SurfaceTexture 变换矩阵已翻转则相反。
    StereoLayout.TAB -> TextureRegion(1f, 0.5f, 0f, if (eye == Eye.LEFT) 0.5f else 0f)
}
