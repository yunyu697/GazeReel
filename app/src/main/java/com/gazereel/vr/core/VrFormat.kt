package com.gazereel.vr.core

/**
 * PRD 3.3 / 6.2：视频格式三要素（投影、立体布局、视场）。
 * 识别结果只作为「建议」，用户覆盖后写入 format_override 表。
 */
enum class Projection { SPHERE_360, HALF_SPHERE_180, PLANE_2D }

enum class StereoLayout { MONO, SBS, TAB }

enum class FieldOfView { FOV_360, FOV_180, FLAT }

data class VrFormat(
    val projection: Projection,
    val layout: StereoLayout,
    val fov: FieldOfView,
    val confidence: Float = 1.0f
) {
    companion object {
        val DEFAULT = VrFormat(Projection.SPHERE_360, StereoLayout.MONO, FieldOfView.FOV_360)

        /**
         * 格式确认浮层的六个大按钮（V2.5：2D 世界锁定银幕正式入选）。
         * 两个平面项对应普通相机视频与 SBS 3D 电影，复用同一个 2 米处平面网格，
         * 立体布局由片元着色器的 uEyeRegion 取半幅纹理完成。
         */
        val QUICK_CHOICES = listOf(
            VrFormat(Projection.SPHERE_360, StereoLayout.MONO, FieldOfView.FOV_360),
            VrFormat(Projection.SPHERE_360, StereoLayout.SBS, FieldOfView.FOV_360),
            VrFormat(Projection.HALF_SPHERE_180, StereoLayout.SBS, FieldOfView.FOV_180),
            VrFormat(Projection.HALF_SPHERE_180, StereoLayout.TAB, FieldOfView.FOV_180),
            VrFormat(Projection.PLANE_2D, StereoLayout.MONO, FieldOfView.FLAT),
            VrFormat(Projection.PLANE_2D, StereoLayout.SBS, FieldOfView.FLAT)
        )
    }

    /** V2.5：用户拍板 2D 采用世界锁定银幕，文案从「2D 平面」改为「2D 银幕」。 */
    val label: String
        get() = buildString {
            append(
                when (projection) {
                    Projection.SPHERE_360 -> "360° 球面"
                    Projection.HALF_SPHERE_180 -> "180° 半球"
                    Projection.PLANE_2D -> "2D 银幕"
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
