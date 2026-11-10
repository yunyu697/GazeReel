package com.gazereel.vr.core

/**
 * PRD 6.2：格式识别。判据按可靠性排序，文件名退到很后面：
 *
 *   1. 容器里的球面声明（sv3d / XMP GSpherical）—— 拍摄方写的，几乎不会错；
 *   2. **抽帧比对左右 / 上下半幅**（V2.20 / C1）—— 直接看像素，比任何标签都硬；
 *   3. 真实宽高按「每眼」算画幅比 —— 180 每眼是 1:1，360 每眼是 2:1，几何不会骗人；
 *   4. 文件名标记 —— 第三方发布组的私人习惯，只用来解冲突和兜底；
 *   5. 什么都没有 —— 给 2D 银幕建议并要求确认（产品本体是用户自己的视频）。
 *
 * ## V2.20：为什么必须「先定布局，再算视场」
 *
 * 老代码的顺序是反的：先假设一个 `layout`（容器没说是 Stereo 就取 MONO），
 * 再拿这个假设去算每眼画幅比。于是 3840×1920 的左右分幅 180 片，
 * 被按 MONO 算成 2:1 → 判成「360 球面 · Mono」，而**文件名里的 SBS 标记
 * 被静默丢掉了**（只有投影打架才问，布局打架不问）。
 *
 * 结果就是用户看到的那一幕：整幅 SBS 被铺满球面，两幅画面在 u=0.5 处相遇，
 * 而 u=0.5 正是「正前方」——**一打开就正对着接缝**。
 *
 * 现在布局是一个独立的结论（容器 > 抽帧 > 文件名），定下来之后再拿它算每眼画幅比。
 * 几何只能判视场，判不了布局：同一张 3840×1920，MONO 是 2:1、SBS 是 1:1，
 * 两种解释都自洽。这一点必须承认，然后交给抽帧去看像素。
 */
class FormatDetector {

    data class Result(val format: VrFormat, val needsConfirmation: Boolean)

    /** 布局是怎么定下来的，写进日志方便对账（真机报格式问题时第一个要看的东西）。 */
    data class LayoutDecision(
        val layout: StereoLayout,
        val source: String,
        val confidence: Float
    )

    fun detect(
        fileName: String,
        width: Int = 0,
        height: Int = 0,
        probe: SphericalMetadata.Probe? = null,
        frame: StereoEvidence? = null
    ): Result {
        val name = fileName.lowercase()
        val marks = Markers.of(name)
        val w = probe?.width?.takeIf { it > 0 } ?: width
        val h = probe?.height?.takeIf { it > 0 } ?: height

        // ① 立体布局先定。它决定了后面「每眼画幅比」怎么算。
        val decision = resolveLayout(probe, frame, marks)
        val layout = decision.layout

        // ② 容器声明了视场：最硬的一条，直接收工。
        val containerFov = probe?.fov?.takeIf { it != FieldOfView.FLAT }
        if (containerFov != null) {
            return Result(
                VrFormat(projectionOf(containerFov), layout, containerFov, 0.98f),
                needsConfirmation = false
            )
        }

        // ③ 几何：用已经定下来的布局算每眼画幅比。
        val byAspect = perEyeFov(w, h, layout)

        if (probe?.spherical == true) {
            val fov = byAspect ?: FieldOfView.FOV_360
            val certain = byAspect != null
            return Result(
                VrFormat(projectionOf(fov), layout, fov, if (certain) 0.97f else 0.85f),
                // 容器只说「是全景」但说不出视场时，问一句比猜错强。
                needsConfirmation = !certain
            )
        }

        if (byAspect != null) {
            val byName = fileNameHints(name, marks)
            // 名字和几何对投影的说法不一致：名字通常更懂内容（比如 180 被补成 2:1 的片子），
            // 但几何也不该被无声丢掉，所以按名字来、同时明确问一次。
            if (byName != null && byName.format.projection != projectionOf(byAspect)) {
                return Result(byName.format.copy(layout = layout, confidence = 0.55f), true)
            }
            return Result(VrFormat(projectionOf(byAspect), layout, byAspect, 0.90f), false)
        }

        // ④ 有宽高但不像全景（16:9 / 4:3 / 竖屏 / 超宽）→ 世界锁定银幕。
        if (w > 0 && h > 0) {
            return Result(
                VrFormat(Projection.PLANE_2D, layout, FieldOfView.FLAT, 0.60f),
                // 普通视频也要问一次：产品本体是用户自己的片子，猜错比多问一句贵。
                needsConfirmation = true
            )
        }

        // ⑤ 拿不到宽高才退回文件名。
        val byName = fileNameHints(name, marks)
        if (byName != null) return byName.copy(format = byName.format.copy(layout = layout))

        // ⑥ 什么证据都没有。
        return Result(
            VrFormat(Projection.PLANE_2D, StereoLayout.MONO, FieldOfView.FLAT, 0.30f),
            needsConfirmation = true
        )
    }

    /**
     * 布局取证顺序：容器 > 抽帧 > 文件名。
     *
     * 抽帧排在文件名前面，是因为文件名里的 `3d` / `vr` 只是发布组的习惯，
     * 而像素是真的——真 3D 片左右两幅几乎是同一张图（只有视差），
     * 假 3D（2D 转制、或根本不是立体）两半差别很大。这个判据见 [StereoFrameJudge]。
     */
    fun resolveLayout(
        probe: SphericalMetadata.Probe?,
        frame: StereoEvidence?,
        marks: Markers
    ): LayoutDecision {
        probe?.layout?.let {
            return LayoutDecision(it, "容器声明", 0.98f)
        }
        if (frame != null && frame.confidence >= 0.70f) {
            return LayoutDecision(frame.layout, "抽帧(${frame.note})", frame.confidence)
        }
        if (marks.layout != StereoLayout.MONO) {
            return LayoutDecision(marks.layout, "文件名", 0.80f)
        }
        return LayoutDecision(StereoLayout.MONO, "无证据", 0.50f)
    }

    private fun projectionOf(fov: FieldOfView): Projection = when (fov) {
        FieldOfView.FOV_180 -> Projection.HALF_SPHERE_180
        FieldOfView.FOV_360 -> Projection.SPHERE_360
        FieldOfView.FLAT -> Projection.PLANE_2D
    }

    // ---- 几何 ----

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

    // ---- 文件名（降级为兜底与解冲突） ----

    /**
     * 把名字切成纯字母数字 token 再精确匹配，方括号 / 圆括号 / 下划线 / 空格一视同仁。
     * 旧实现只认 `[_\-. ]` 分隔，`[180][3dh][LR]` 一条都不命中；
     * 而 `name.contains("vr")` 又会命中 `BaDoinkVR` 里的 `vr`，把不相干的文件判成 explicit。
     */
    class Markers(
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
