package com.gazereel.vr.core

/**
 * PRD 6.2：格式识别。V2.7.6 起判据按可靠性排序，文件名退到很后面：
 *
 *   1. 容器里的球面声明（sv3d / XMP GSpherical）—— 拍摄方写的，几乎不会错；
 *   2. 真实宽高按「每眼」算画幅比 —— 180 每眼是 1:1，360 每眼是 2:1，几何不会骗人；
 *   3. 文件名标记 —— 第三方发布组的私人习惯，只用来解冲突和兜底；
 *   4. 什么都没有 —— 给 2D 银幕建议并要求确认（产品本体是用户自己的视频）。
 *
 * 为什么不再以文件名为主：`[180][3dh][LR]` 这种方括号形态旧正则一条都不命中，
 * 反而被 `BaDoinkVR` 里的 `vr` 判成 explicit，最后落到 DEFAULT(360 球面 Mono)，
 * 于是左右分幅的 180 内容被铺成整球，用户看到的只能是画面边缘。
 */
class FormatDetector {

    data class Result(val format: VrFormat, val needsConfirmation: Boolean)

    fun detect(
        fileName: String,
        width: Int = 0,
        height: Int = 0,
        probe: SphericalMetadata.Probe? = null
    ): Result {
        val name = fileName.lowercase()
        val marks = Markers.of(name)
        // 容器说了立体排布就听容器的；没说才看文件名。
        val layout = probe?.layout ?: marks.layout

        val probeWidth = probe?.width?.takeIf { it > 0 } ?: width
        val probeHeight = probe?.height?.takeIf { it > 0 } ?: height

        // ① 容器声明是全景：投影基本确定，只剩 180 / 360 要定。
        if (probe != null && probe.spherical) {
            val byCrop = probe.fov
            val byAspect = perEyeFov(probeWidth, probeHeight, layout)
            val fov = byCrop ?: byAspect ?: FieldOfView.FOV_360
            val projection =
                if (fov == FieldOfView.FOV_180) Projection.HALF_SPHERE_180 else Projection.SPHERE_360
            val certain = byCrop != null || byAspect != null
            return Result(
                VrFormat(projection, layout, fov, if (certain) 0.98f else 0.85f),
                // 容器只说「是全景」但说不出视场时，问一句比猜错强。
                needsConfirmation = !certain
            )
        }

        // ② 有真实宽高：按每眼画幅比判，比整幅可靠得多。
        val byAspect = aspectHeuristic(probeWidth, probeHeight, layout)
        val byName = fileNameHints(name, marks)

        if (byAspect != null) {
            if (byName != null && byName.format.projection != byAspect.format.projection) {
                // 几何和命名打架：听命名（发布方知道内容），但明确问一次。
                return Result(
                    byName.format.copy(confidence = 0.55f),
                    needsConfirmation = true
                )
            }
            return byAspect
        }

        // ③ 拿不到宽高才退回文件名。
        if (byName != null) return byName

        // ④ 什么证据都没有：给 2D 银幕建议并问一次，用户可在六项里改回球面。
        return Result(
            VrFormat(Projection.PLANE_2D, StereoLayout.MONO, FieldOfView.FLAT, 0.30f),
            needsConfirmation = true
        )
    }

    // ---- ② 几何 ----

    /**
     * 每眼画幅比。等距柱状：整球是 2:1，半全景是 1:1；
     * 左右分幅时每一半单独算，上下分幅时每一半单独算。
     */
    private fun perEyeRatio(width: Int, height: Int, layout: StereoLayout): Float = when (layout) {
        StereoLayout.SBS -> (width / 2f) / height
        StereoLayout.TAB -> width / (height / 2f)
        StereoLayout.MONO -> width.toFloat() / height
    }

    private fun perEyeFov(width: Int, height: Int, layout: StereoLayout): FieldOfView? {
        if (width <= 0 || height <= 0) return null
        val ratio = perEyeRatio(width, height, layout)
        return when {
            ratio in 0.85f..1.18f -> FieldOfView.FOV_180
            ratio in 1.85f..2.20f -> FieldOfView.FOV_360
            else -> null
        }
    }

    private fun aspectHeuristic(width: Int, height: Int, layout: StereoLayout): Result? {
        if (width <= 0 || height <= 0) return null
        val ratio = perEyeRatio(width, height, layout)
        return when {
            // 每眼 1:1 → 半全景
            ratio in 0.85f..1.18f -> Result(
                VrFormat(Projection.HALF_SPHERE_180, layout, FieldOfView.FOV_180, 0.85f),
                // 单眼的正方形也可能是普通方视频，问一句。
                needsConfirmation = layout == StereoLayout.MONO
            )
            // 每眼 2:1 → 整球
            ratio in 1.85f..2.20f -> Result(
                VrFormat(Projection.SPHERE_360, layout, FieldOfView.FOV_360, 0.88f),
                needsConfirmation = false
            )
            // 其余（16:9 / 4:3 / 竖屏 / 超宽）→ 2D 银幕
            else -> Result(
                VrFormat(Projection.PLANE_2D, layout, FieldOfView.FLAT, 0.50f),
                needsConfirmation = true
            )
        }
    }

    // ---- ③ 文件名（降级为兜底与解冲突） ----

    /**
     * 把名字切成纯字母数字 token 再精确匹配，方括号 / 圆括号 / 下划线 / 空格一视同仁。
     * 旧实现只认 `[_\-. ]` 分隔，`[180][3dh][LR]` 一条都不命中；
     * 而 `name.contains("vr")` 又会命中 `BaDoinkVR` 里的 `vr`，把不相干的文件判成 explicit。
     */
    private class Markers(
        val is180: Boolean,
        val is360: Boolean,
        val sbs: Boolean,
        val tab: Boolean,
        val isFlat: Boolean,
        val isVr: Boolean,
        val has3d: Boolean,
        val explicitPair: Boolean,
        val threeDHint: Boolean
    ) {
        val layout: StereoLayout
            get() = when {
                sbs -> StereoLayout.SBS
                tab -> StereoLayout.TAB
                else -> StereoLayout.MONO
            }

        /** 只有 `3dh` / `3dv` 而没有 LR / TB 这类明确排布时才有歧义。 */
        val ambiguousStereo: Boolean get() = threeDHint && !explicitPair

        companion object {
            fun of(name: String): Markers {
                val tokens = name.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
                fun has(vararg candidates: String) = candidates.any { it in tokens }

                val flatPhrase = listOf("side_by_side", "side-by-side", "left_right", "left-right")
                val tabPhrase = listOf("over_under", "over-under", "top_bottom", "top-bottom")
                val flatWord = has("lr", "sbs", "hsbs", "halfsbs", "3dh")
                val tabWord = has("tb", "ou", "htab", "3dv")
                val explicitPair = has("lr", "sbs", "hsbs", "halfsbs", "tb", "ou", "htab") ||
                    flatPhrase.any { name.contains(it) } || tabPhrase.any { name.contains(it) }

                val is180 = has("180", "180x180", "vr180", "180vr", "fov180", "180sbs", "180tb") ||
                    name.contains("180°")
                val is360 = has("360", "360x360", "vr360", "360vr", "fov360", "360sbs")

                return Markers(
                    is180 = is180,
                    is360 = is360,
                    sbs = flatWord || flatPhrase.any { name.contains(it) },
                    tab = tabWord || tabPhrase.any { name.contains(it) },
                    isFlat = has("2d", "flat", "plane", "screen"),
                    // `vr` 必须是独立 token：BaDoinkVR / CzechVR 里的 vr 不算标记。
                    isVr = has("vr", "oculus", "quest", "cardboard", "vr180", "vr360"),
                    has3d = has("3d", "3dh", "3dv", "3dsbs", "3dtab"),
                    explicitPair = explicitPair,
                    threeDHint = has("3dh", "3dv")
                )
            }
        }
    }

    private fun fileNameHints(name: String, m: Markers): Result? {
        val explicit = m.is180 || m.is360 || m.sbs || m.tab || m.isFlat || m.isVr || m.has3d
        if (!explicit) return null

        // 平面优先于「看到 3d 就猜球面」：SBS 3D 电影同样落在世界锁定银幕上。
        if (m.isFlat && !m.is180 && !m.is360) {
            return Result(
                VrFormat(Projection.PLANE_2D, m.layout, FieldOfView.FLAT, 0.90f),
                needsConfirmation = false
            )
        }

        val ambiguous = m.ambiguousStereo
        return when {
            m.is180 -> Result(
                VrFormat(
                    Projection.HALF_SPHERE_180, m.layout, FieldOfView.FOV_180,
                    if (ambiguous) 0.60f else 0.95f
                ),
                needsConfirmation = ambiguous
            )
            m.is360 -> Result(
                VrFormat(Projection.SPHERE_360, m.layout, FieldOfView.FOV_360, 0.95f),
                needsConfirmation = false
            )
            m.sbs || m.tab -> Result(
                VrFormat(Projection.SPHERE_360, m.layout, FieldOfView.FOV_360, 0.80f),
                needsConfirmation = ambiguous
            )
            else -> Result(VrFormat.DEFAULT.copy(confidence = 0.60f), needsConfirmation = true)
        }
    }
}
