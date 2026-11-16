package com.gazereel.vr.core

/**
 * 格式识别。判据只有三条，按可靠性排序（V2.40）：
 *
 *   1. **文件名** —— 你自己的命名。名字写 180 SBS，就是 180 SBS，任何自动判据都不许推翻；
 *   2. **容器元数据** —— `st3d` / `sv3d` / XMP 的 `GSpherical:` 与裁剪比；
 *   3. **几何** —— 每眼画幅比（360 → 2:1，180 → 1:1），前两条都没说话时兜底。
 *
 * ## 为什么把「抽帧比对像素」整条链路删了
 *
 * V2.36 曾把抽帧排到「容器 > 抽帧 > 文件名」，理由是「像素比标签硬」。真机证明这是错的：
 * **像素判据有噪声，标签是确定的**。抽帧要靠三帧一致、视差带成团、峰值独占一堆条件，
 * 室内暗光、镜头运动、大片纯色背景都会让它落空。一落空就退回 MONO，而 MONO 解释下
 * 4096×2048 正好是 2:1 → 判成「360° 球面」，整幅 SBS 铺满球面，u=0.5 正对眼前——
 * 用户看到的就是一打开正对一条接缝。
 *
 * 更糟的是这个结论会被写进格式记忆，下次打开直接命中，连纠正的机会都没有。
 * 一个会「时对时错」的自动判据比没有自动判据更贵：它把不确定性留给了用户。
 * 所以整条抽帧链路（判据、GL 读像素、证据回传、重算）这一版全部删除，
 * 识别变成纯确定性的：**同样的输入永远得到同样的结论**。
 *
 * ## 为什么「2:1 的裸流」默认按 180 SBS
 *
 * 3840×1920 / 4096×2048 这种没有任何元数据的 2:1 裸流，几何上是二义的：
 * MONO 解释成 360（每眼 2:1），SBS 解释成 180（每眼 1:1），两条都自洽。
 * 经验上：真 360 片源几乎都带 spatial media 声明（YouTube / Insta360 / GoPro / ffmpeg 都写），
 * 而 2016–2020 那批 180 SBS 片源往往什么都没有，只有一条裸流。所以二义时选立体：
 * 判 MONO 的代价是「正对接缝、没法看」，判立体的代价是「画面切两半、小一圈」，
 * 后者一眼看得出，也一键能改。
 *
 * ## 18:2x：220 / 270 只走文件名，不进几何反解
 *
 * 220° / 270° 是**等距柱状张角档**（见 README），不是鱼眼镜头 FOV。它们的每眼画幅比
 * 约 1.22 / 1.50，夹在 1:1 与 2:1 之间；若把这两个比例也加进 [fovOfRatio]，
 * 4:3（1.33）与 16:9（1.78）的普通电影就会被误判成球面 —— 这正是 V2.40 反复栽过的坑。
 * 所以这两档**只认文件名标记**，或由用户在格式确认浮层手动选；宁可多问一句，不猜。
 *
 * ## 19:2x：未缝合双鱼眼 360 是「明确拒绝」，不是「猜不准」
 *
 * 这类片源（`.insv` 等机内原始片）画面里两个圆是**同一只眼的前后镜头**。
 * 按单镜头鱼眼去解，每只眼只看得到一个圆、前后方向还错位，结果是「两团圆」——
 * 用户看不出是播放器算错了，只会以为片子废了。所以它走 [Refusal] 那条路：
 * 降级成 2D 银幕原样显示 + 一张说明卡，而不是继续猜一个投影。
 */
class FormatDetector {

    data class Result(
        val format: VrFormat,
        val needsConfirmation: Boolean,
        /** 这个结论主要靠哪条证据，写进运行日志方便对账。 */
        val source: String = "",
        /**
         * 19:2x：**明确拒绝**的片源（见 [Refusal]）。
         *
         * 非 null 时 [format] 已经被降级成 2D 银幕 —— 拒绝不等于不给看，
         * 而是「宁可原样铺在银幕上，也不做一套错的变换」。
         */
        val refusal: Refusal? = null
    )

    /** 布局是怎么定下来的，写进日志方便对账（真机报格式问题时第一个要看的东西）。 */
    data class LayoutDecision(
        val layout: StereoLayout,
        val source: String,
        val confidence: Float
    )

    /** 一种自洽的「布局 + 视场」解释。 */
    private data class Hypothesis(val layout: StereoLayout, val fov: FieldOfView)

    fun detect(
        fileName: String,
        width: Int = 0,
        height: Int = 0,
        probe: SphericalMetadata.Probe? = null
    ): Result {
        val name = fileName.lowercase()
        val marks = Markers.of(name)
        val w = probe?.width?.takeIf { it > 0 } ?: width
        val h = probe?.height?.takeIf { it > 0 } ?: height

        // ⓪ 19:2x：**明确拒绝**排在所有判据之前。
        //
        // 未缝合的双鱼眼 360 一旦落到下面的鱼眼分支，会被当成「一只眼一个圆」去解：
        // 每只眼只看得到一个圆，前后两圆方向错位，画面就是「两团圆」。
        // 那个结果比拒绝更糟 —— 用户看不出是播放器算错了，只会以为片子废了。
        //
        // 布局一律 MONO：两个圆是**同一只眼的前后镜头**，切半幅只会再错一次。
        if (marks.isDualFish) {
            return Result(
                VrFormat(Projection.PLANE_2D, StereoLayout.MONO, FieldOfView.FLAT, 0.95f),
                needsConfirmation = false,
                source = "双鱼眼未缝合 · 明确拒绝",
                refusal = Refusal.DUAL_FISHEYE_360
            )
        }

        // 视场的硬证据：文件名优先于容器。它同时会参与下面的「布局反解」。
        // 220 / 270 必须排在 180 前面 —— 有些命名（如 `220x180`）会同时命中 HAS_180，
        // 先判宽档才不会被窄档抢走。
        val fovHint = when {
            // 19:0x：鱼眼必须排在最前。`mkx220` 里的 220 会被数字边界命中，
            // 先判 220 就会把鱼眼当等距柱状 220° 播 —— 正是「认成 180 就扭曲」那类错。
            marks.isFish -> FieldOfView.FOV_FISHEYE
            marks.is270 -> FieldOfView.FOV_270
            marks.is220 -> FieldOfView.FOV_220
            marks.is180 -> FieldOfView.FOV_180
            marks.is360 -> FieldOfView.FOV_360
            else -> probe?.fov?.takeIf { it != FieldOfView.FLAT }
        }

        // ① 立体布局先定。它决定了「每眼画幅比」怎么算。
        val decision = resolveLayout(probe, marks, w, h, fovHint)
        val layout = decision.layout

        // ② 文件名点名「这是平面 2D」：只有容器明确说是球面时才让步。
        if (marks.isFlat && fovHint == null && probe?.spherical != true) {
            return Result(
                VrFormat(Projection.PLANE_2D, layout, FieldOfView.FLAT, 0.90f),
                needsConfirmation = false,
                source = "文件名 2D"
            )
        }

        // ③ 视场已由文件名 / 容器给出：最硬的一条，直接收工。
        if (fovHint != null) {
            return Result(
                VrFormat(projectionOf(fovHint), layout, fovHint, 0.97f),
                needsConfirmation = false,
                source = if (marks.isFish) "文件名鱼眼"
                else if (marks.is180 || marks.is360 || marks.is220 || marks.is270)
                    "文件名视场" else "容器视场"
            )
        }

        // ④ 几何：用已经定下来的布局算每眼画幅比。
        val byAspect = perEyeFov(w, h, layout)

        if (probe?.spherical == true) {
            val fov = byAspect ?: FieldOfView.FOV_360
            return Result(
                VrFormat(projectionOf(fov), layout, fov, if (byAspect != null) 0.95f else 0.85f),
                // 容器只说「是全景」但说不出视场时，问一句比猜错强。
                needsConfirmation = byAspect == null,
                source = "容器球面"
            )
        }

        if (byAspect != null) {
            return Result(
                VrFormat(projectionOf(byAspect), layout, byAspect, 0.90f),
                needsConfirmation = false,
                source = decision.source
            )
        }

        // ⑤ 有宽高但不像全景（16:9 / 4:3 / 竖屏 / 超宽）→ 世界锁定银幕。
        if (w > 0 && h > 0) {
            return Result(
                VrFormat(Projection.PLANE_2D, layout, FieldOfView.FLAT, 0.60f),
                // 普通视频也要问一次：产品本体是用户自己的片子，猜错比多问一句贵。
                needsConfirmation = true,
                source = "几何 非全景"
            )
        }

        // ⑥ 拿不到宽高才退回文件名。
        val byName = fileNameHints(name, marks)
        if (byName != null) return byName.copy(format = byName.format.copy(layout = layout))

        // ⑦ 什么证据都没有。
        return Result(
            VrFormat(Projection.PLANE_2D, StereoLayout.MONO, FieldOfView.FLAT, 0.30f),
            needsConfirmation = true,
            source = "无证据"
        )
    }

    /**
     * 布局取证顺序：**文件名 > 容器 > 几何**。
     *
     * 名字和元数据都在时，结论就是它们说的那个；都不在时用宽高比反解（见 [hypotheses]），
     * 二义则取立体。全程没有第三方的「猜测」参与，也没有会随帧变化的判据。
     * 已知视场是 220 / 270 时，几何表里没有对应项，匹配自然为空 —— 布局只由
     * 文件名 / 容器说话，不会拿 1.22 / 1.50 去硬套 1:1 或 2:1。
     */
    fun resolveLayout(
        probe: SphericalMetadata.Probe?,
        marks: Markers,
        width: Int = 0,
        height: Int = 0,
        fovHint: FieldOfView? = null
    ): LayoutDecision {
        // ① 文件名。`mono` 是显式声明「不要给我切两半」，比什么都硬。
        if (marks.monoExplicit) {
            return LayoutDecision(StereoLayout.MONO, "文件名 mono", 0.95f)
        }
        if (marks.layout != StereoLayout.MONO) {
            return LayoutDecision(marks.layout, "文件名 SBS/TAB", 0.95f)
        }

        // ② 容器里的 st3d。
        probe?.layout?.let { return LayoutDecision(it, "容器 st3d", 0.98f) }

        // ③ 几何反解。已知视场时，布局就被宽高比唯一定死了（每眼必须是 1:1 或 2:1）。
        val hyps = hypotheses(width, height)
        if (fovHint != null) {
            val matching = hyps.filter { it.fov == fovHint }
            if (matching.size == 1) {
                return LayoutDecision(matching[0].layout, "几何(${fovHint.name})", 0.90f)
            }
        }
        if (hyps.size == 1) {
            return LayoutDecision(hyps[0].layout, "几何唯一", 0.85f)
        }
        // ④ 二义。取舍按「错在哪边更难受」排，而不是一律取立体：
        //    - 2:1 是 [MONO 360] 与 [SBS 180]。判 MONO 会正对接缝、没法看，
        //      所以取 SBS——画面切两半、小一圈，一眼看得出也能一键改；
        //    - 1:1 是 [MONO 180] 与 [TAB 360]。这里反过来：1:1 的片子大多是
        //      180 Mono，而 TAB 是三种布局里最少见的一种，切错会把画面横着劈开。
        hyps.firstOrNull { it.layout == StereoLayout.SBS }?.let {
            return LayoutDecision(it.layout, "几何二义-取SBS", 0.72f)
        }
        hyps.firstOrNull { it.layout == StereoLayout.MONO }?.let {
            return LayoutDecision(it.layout, "几何二义-取MONO", 0.72f)
        }
        return LayoutDecision(StereoLayout.MONO, "无证据", 0.50f)
    }

    /**
     * 220 / 270 借 `HALF_SPHERE_180` 表达「非整球」；球面真实跨度由 fov.degrees
     * 在 `ProjectionMesh.curved()` 里决定，与这里的 projection 标签无关。
     */
    private fun projectionOf(fov: FieldOfView): Projection = when (fov) {
        FieldOfView.FOV_180, FieldOfView.FOV_220, FieldOfView.FOV_270 -> Projection.HALF_SPHERE_180
        FieldOfView.FOV_360 -> Projection.SPHERE_360
        FieldOfView.FOV_FISHEYE -> Projection.FISHEYE
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
        return fovOfRatio(perEyeRatio(width, height, layout))
    }

    /**
     * 画幅比 → 视场。360 的每眼是 2:1，180 的每眼是 1:1。
     *
     * 容差只给 ±5% / ±6%：真实片源的宽高都很规整（3840×1920、4096×2048、5760×2880）。
     * 放宽到 ±15% 会把 16:9（1.78）当成 2:1 —— 普通电影直接进了球面，
     * 这是自检抓到的真实回归，别再加。
     *
     * **故意不认 220 / 270 的 1.22 / 1.50**：它们离 4:3（1.33）、16:9（1.78）太近，
     * 一旦进这张表就会把普通电影判成球面。这两档只走文件名与手选。
     */
    private fun fovOfRatio(ratio: Float): FieldOfView? = when {
        near(ratio, 1f) -> FieldOfView.FOV_180
        near(ratio, 2f) -> FieldOfView.FOV_360
        else -> null
    }

    private fun near(value: Float, target: Float): Boolean =
        value >= target * 0.95f && value <= target * 1.06f

    /**
     * 用整幅宽高比反解出所有自洽的「布局 + 视场」。
     *
     * 这张表是识别的底座：4096×2048 有两条自洽解释（MONO 360 / SBS 180），
     * 7680×1920 只有一条（SBS 360）。只要知道其中任意一半（名字说了 180，或容器写了 SBS），
     * 另一半就被唯一定死——不需要再猜。
     */
    private fun hypotheses(width: Int, height: Int): List<Hypothesis> {
        if (width <= 0 || height <= 0) return emptyList()
        val full = width.toFloat() / height
        val out = ArrayList<Hypothesis>(4)
        fun consider(layout: StereoLayout, perEye: Float) {
            val fov = fovOfRatio(perEye) ?: return
            if (out.none { it.layout == layout && it.fov == fov }) {
                out.add(Hypothesis(layout, fov))
            }
        }
        consider(StereoLayout.MONO, full)
        consider(StereoLayout.SBS, full / 2f)
        consider(StereoLayout.TAB, full * 2f)
        return out
    }

    // ---- 文件名 ----

    /**
     * 把名字切成纯字母数字 token 再精确匹配，方括号 / 圆括号 / 下划线 / 空格一视同仁。
     * 旧实现只认 `[_\-. ]` 分隔，`[180][3dh][LR]` 一条都不命中；
     * 而 `name.contains("vr")` 又会命中 `BaDoinkVR` 里的 `vr`，把不相干的文件判成 explicit。
     *
     * `180` / `360` / `220` / `270` 额外走「数字边界」匹配，这样 `VR180`、`180x180`、
     * `180SBS` 都能命中，又不会把 `1080p`（含 080 但没有独立 180）或 `IMG_1801` 当成 180。
     */
    class Markers(
        val is180: Boolean,
        val is360: Boolean,
        /** 18:2x：等距柱状张角档。默认 false，兼容其它构造点。 */
        val is220: Boolean = false,
        val is270: Boolean = false,
        /** 19:0x：单镜头鱼眼。命名标记或中文「鱼眼」。 */
        val isFish: Boolean = false,
        /**
         * 19:2x：**未缝合的双鱼眼 360**。这类片源要明确拒绝，不能按单镜头鱼眼解。
         * 与 [isFish] 同时为真是正常的（名字里两个词都在）。
         */
        val isDualFish: Boolean = false,
        val sbs: Boolean,
        val tab: Boolean,
        val isFlat: Boolean,
        val isVr: Boolean,
        val has3d: Boolean,
        val explicitPair: Boolean,
        val threeDHint: Boolean,
        /** 名字里明确写了 `mono` / `2d360`：不要切两半。 */
        val monoExplicit: Boolean = false
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
            private val HAS_180 = Regex("(?<![0-9])180(?![0-9])")
            private val HAS_360 = Regex("(?<![0-9])360(?![0-9])")
            private val HAS_220 = Regex("(?<![0-9])220(?![0-9])")
            private val HAS_270 = Regex("(?<![0-9])270(?![0-9])")

            fun of(raw: String): Markers {
                // 19:2x：**自己转小写**，不再依赖调用方先转。
                // 以前只有 `detect()` 会 lowercase，于是单独调 `Markers.of("Gear360.mp4")`
                // 会切出 `ear360` 这种 token，标记全都不命中 —— 自检当场抓到的坑。
                val name = raw.lowercase()
                val tokens = name.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
                fun has(vararg candidates: String) = candidates.any { it in tokens }

                val flatPhrase = listOf("side_by_side", "side-by-side", "left_right", "left-right")
                val tabPhrase = listOf("over_under", "over-under", "top_bottom", "top-bottom")
                val flatWord = has("lr", "sbs", "hsbs", "halfsbs", "3dh")
                val tabWord = has("tb", "ou", "htab", "3dv")
                val explicitPair = has("lr", "sbs", "hsbs", "halfsbs", "tb", "ou", "htab") ||
                    flatPhrase.any { name.contains(it) } || tabPhrase.any { name.contains(it) }

                val is180 = has("180", "180x180", "vr180", "180vr", "fov180", "180sbs", "180tb") ||
                    name.contains("180°") || HAS_180.containsMatchIn(name)
                val is360 = has("360", "360x360", "vr360", "360vr", "fov360", "360sbs") ||
                    HAS_360.containsMatchIn(name)
                // 18:2x：只认等距柱状张角档的写法。`mkx220` / `vrca220` 是**鱼眼镜头**档，
                // 本工程没有径向反畸变与镜头内参，故意不认 —— 认了只会给出变形的画面。
                val is220 = has("220", "pano220", "fov220", "220sbs", "220tb", "vr220") ||
                    HAS_220.containsMatchIn(name)
                val is270 = has("270", "pano270", "fov270", "270sbs", "270tb", "vr270") ||
                    HAS_270.containsMatchIn(name)
                // 19:0x：鱼眼镜头标记。`mkx200` / `mkx220` / `vrca220` / `rf52` 是真实镜头型号，
                // 用户照着镜头改名就能命中；`fisheye` / `fish` / `鱼眼` 是通用写法。
                // 注意不要认裸 `200`：普通片段里 200 太常见（码率、序号），误判代价是整幅变形。
                val isFish = has(
                    "fisheye", "fish", "dfisheye", "mkx200", "mkx220", "vrca220", "rf52",
                    "fisheye180", "fisheye200", "fisheye220"
                ) || name.contains("鱼眼")
                // 19:2x：**未缝合双鱼眼 360** 的标记。三条路，任意一条命中就拒绝：
                //   1. 明确的写法 `dfisheye` / `dualfisheye`（ffmpeg 与 Insta360 Studio 的默认命名）；
                //   2. 机内原始片：`.insv` / `insta360` / `gear360` / `onex2|3|4` / `unstitched`
                //      —— 这些后缀本身就是「还没缝」的意思，不需要再看到 fish；
                //   3. 鱼眼标记 + `dual`（英文 `dual fisheye` 会被切成两个 token）。
                // 只认 `dual` 一个词是不够的：`Dual Audio` 这种普通片会被误伤。
                val rawDual = has(
                    "insv", "insta360", "gear360", "onex2", "onex3", "onex4",
                    "unstitched", "unstitch"
                )
                val isDualFish = has("dfisheye", "dualfisheye") || rawDual ||
                    (isFish && has("dual")) ||
                    listOf("dual fisheye", "dual-fisheye", "双鱼眼", "前后鱼眼")
                        .any { name.contains(it) }

                val isFlat = has("2d", "flat", "plane", "screen")
                val monoExplicit = has("mono", "monoscopic", "2d180", "2d360")

                return Markers(
                    is180 = is180,
                    is360 = is360,
                    is220 = is220,
                    is270 = is270,
                    isFish = isFish,
                    isDualFish = isDualFish,
                    sbs = flatWord || flatPhrase.any { name.contains(it) },
                    tab = tabWord || tabPhrase.any { name.contains(it) },
                    isFlat = isFlat,
                    // `vr` 必须是独立 token：BaDoinkVR / CzechVR 里的 vr 不算标记。
                    isVr = has("vr", "oculus", "quest", "cardboard", "vr180", "vr360"),
                    has3d = has("3d", "3dh", "3dv", "3dsbs", "3dtab"),
                    explicitPair = explicitPair,
                    threeDHint = has("3dh", "3dv"),
                    monoExplicit = monoExplicit
                )
            }
        }
    }

    private fun fileNameHints(name: String, m: Markers): Result? {
        val explicit = m.isFish || m.is180 || m.is360 || m.is220 || m.is270 ||
            m.sbs || m.tab || m.isFlat || m.isVr || m.has3d
        if (!explicit) return null

        // 平面优先于「看到 3d 就猜球面」：SBS 3D 电影同样落在世界锁定银幕上。
        if (m.isFlat && !m.isFish && !m.is180 && !m.is360 && !m.is220 && !m.is270) {
            return Result(
                VrFormat(Projection.PLANE_2D, m.layout, FieldOfView.FLAT, 0.90f),
                needsConfirmation = false,
                source = "文件名 2D"
            )
        }

        val ambiguous = m.ambiguousStereo
        return when {
            m.isFish -> Result(
                VrFormat(Projection.FISHEYE, m.layout, FieldOfView.FOV_FISHEYE, 0.95f),
                needsConfirmation = ambiguous,
                source = "文件名鱼眼"
            )
            m.is270 -> Result(
                VrFormat(Projection.HALF_SPHERE_180, m.layout, FieldOfView.FOV_270, 0.95f),
                needsConfirmation = ambiguous,
                source = "文件名 270"
            )
            m.is220 -> Result(
                VrFormat(Projection.HALF_SPHERE_180, m.layout, FieldOfView.FOV_220, 0.95f),
                needsConfirmation = ambiguous,
                source = "文件名 220"
            )
            m.is180 -> Result(
                VrFormat(
                    Projection.HALF_SPHERE_180, m.layout, FieldOfView.FOV_180,
                    if (ambiguous) 0.60f else 0.95f
                ),
                needsConfirmation = ambiguous,
                source = "文件名 180"
            )
            m.is360 -> Result(
                VrFormat(Projection.SPHERE_360, m.layout, FieldOfView.FOV_360, 0.95f),
                needsConfirmation = false,
                source = "文件名 360"
            )
            // 只有 SBS / TAB 没有视场：最常见的 SBS 片是 180，但仍标明是猜的。
            m.sbs || m.tab -> Result(
                VrFormat(Projection.HALF_SPHERE_180, m.layout, FieldOfView.FOV_180, 0.70f),
                needsConfirmation = ambiguous,
                source = "文件名 只有布局"
            )
            else -> Result(
                VrFormat.DEFAULT.copy(confidence = 0.60f),
                needsConfirmation = true,
                source = "文件名 弱线索"
            )
        }
    }
}
