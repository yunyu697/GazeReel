package com.gazereel.vr.ui

import android.os.SystemClock
import com.gazereel.vr.core.ClipRequest
import com.gazereel.vr.core.InputChannel
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.SourceType
import com.gazereel.vr.core.Optics
import com.gazereel.vr.core.VrAction
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.data.FormatProber
import com.gazereel.vr.data.LibraryRepository
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.data.db.FileNameOrder
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.db.PlayProgressEntity
import com.gazereel.vr.data.db.SourceEntity
import com.gazereel.vr.data.parseUnc
import com.gazereel.vr.input.SettingsRepository
import com.gazereel.vr.player.DecoderCaps
import com.gazereel.vr.player.PlaybackState
import com.gazereel.vr.player.PlayerController
import com.gazereel.vr.player.SubtitleRepository
import com.gazereel.vr.player.SystemVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.gazereel.vr.data.db.mediaKeyOf

/**
 * PRD 4.5 界面状态机 + 6.5 动作分发落点：
 * 配置完成进片库 → 选片（无格式记忆则弹确认）→ 播放 → 播完自动连播 → 长按返回上一层。
 * 所有写状态都在主线程，VrRenderer 通过 stateProvider 只读。
 */
class VrCoordinator(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val player: PlayerController,
    private val settings: SettingsRepository,
    private val headset: VrHeadsetProvider,
    private val subtitles: SubtitleRepository,
    /** V2.14：音量只认系统媒体音量，见 SystemVolume 的类注释。 */
    private val systemVolume: SystemVolume,
    /** V2.7.6：播放前从容器读真实格式；为 null 时退回宽高比 + 文件名。 */
    private val prober: FormatProber? = null,
    private val onExit: () -> Unit,
    /** V2.4：重定正前方后由 VrActivity 清掉凝视 / 手势窗口，避免首帧误触发。 */
    private val onRecenter: () -> Unit = {}
) {
    @Volatile
    var uiState: VrUiState = VrUiState()
        private set

    init {
        // alpha43：手机端设置（瞳距 / 缩放 / 快进步长 / 倒计时…）必须在进 VR 那一刻
        // 就进 state。原来这里一直是 VrSettings() 的构造默认值，只有 VR 内主动 toggle
        // 过一次才会 copy(settings = next) 同步 —— 于是戴镜前设好的值在 state 里读不到
        // （连设置页显示的瞳距都是假的 62.0mm）。新增两项设置同样依赖这条同步。
        uiState = VrUiState(settings = settings.current)
    }

    private companion object {
        /**
         * V2.33 / B14：静止后的淡出时长。对齐 AOSP `VolumeDialogImpl` 的默认 3000ms 量级。
         * 关键不是这个数字，而是**它不再是从激活那一刻起算的固定倒计时**。
         */
        const val FEEDBACK_MS = 2_600L

        /**
         * 一次调节之后的存活时间：够「看清数值 + 转头找到按钮 + 凝视 1.2 秒确认」。
         * 原实现只有 2.6 秒且那张卡没有凝视目标，真机反馈「来不及操作就消失了」。
         */
        const val ADJUST_ACTIVE_MS = 8_000L

        /**
         * 准星停在调节条上时的长保持。对齐 AOSP `VolumeDialogImpl` 的
         * `DIALOG_HOVERING_TIMEOUT_MILLIS = 16000`：用户正在瞄按钮，条就不许消失。
         */
        const val ADJUST_HOVER_MS = 16_000L

        /**
         * V2.38：双击立刻重定用 0.4 秒缓转。
         *
         * 「立刻」是语义要求（不排队、不等倒计时），但整幅画面在一帧里平移几十度
         * 是最典型的晕动来源，所以不取 0——0.4 秒刚好够看清「它转过去了」。
         */
        const val RECENTER_NOW_MS = 400L

        /**
         * V2.38：视线角速度低于它就算「没在动」，约 8°/s。
         *
         * 人手握着手机的自然抖动在 2°/s 以内；主动转头找方向是 20–60°/s。
         * 门槛放在中间，才分得清「稳住了」和「还在调」。
         */
        const val ALIGN_STEADY_DPS = 8f

        /** 连续稳住这么久才亮「稳住了」。0.5 秒：够短，又不至于被一次抖动打断。 */
        const val ALIGN_STEADY_MS = 500L
    }

    /** V2.33 / B13：VrActivity 用它决定音量键是调音量还是临时借给瞳距。 */
    val isIpdTuning: Boolean get() = uiState.ipdTuning

    /** AOSP `computeTimeoutH()` 里 mHovering 的角色：准星是否停在调节条上。 */
    private var adjustHovered = false

    var sources: Map<Long, SourceEntity> = emptyMap()

    /** V2.19 / B4+B5：姿态闭环。它只发现「姿势变了」，动画面的是 Provider。 */
    private val posture = PostureTracker()

    // ── V2.38：重定「正前方」的倒计时 ────────────────────────────────
    /** 倒计时开始那一刻的姿态，用来报告「你一共调了多少度」。 */
    private var alignStartPose: FloatArray? = null
    /** 上一帧的姿态与时刻，用来算视线角速度（判断「稳住了没有」）。 */
    private var alignLastPose: FloatArray? = null
    private var alignLastAtMs = 0L
    /** 归零时刻（SystemClock.elapsedRealtime）。 */
    private var alignUntilMs = 0L
    /** 已经连续稳住多久（毫秒）；一动就清零。 */
    private var alignSteadyMs = 0L

    private fun degrees(rad: Float): Int = Math.toDegrees(rad.toDouble()).toInt()

    /** 姿态矩阵的前向单位向量；与 `VrUiLayer.gazePoint` 同一约定（列主序第三列取负）。 */
    private fun forwardOf(rotation: FloatArray): FloatArray =
        floatArrayOf(-rotation[8], -rotation[9], -rotation[10])

    /** 两个姿态之间「视线方向」的夹角（度）。 */
    private fun angleBetweenDeg(a: FloatArray, b: FloatArray): Float {
        val fa = forwardOf(a)
        val fb = forwardOf(b)
        val dot = (fa[0] * fb[0] + fa[1] * fb[1] + fa[2] * fb[2]).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(dot).toDouble()).toFloat()
    }

    /**
     * V2.28：自动姿态闭环已停用，这里只剩手动那条路（V2.38 起是倒计时）。
     *
     * alpha14 把首次基准死区收到 2°，并允许「戴稳之后」继续静默校准，于是进 VR
     * 头 6 秒内任何 2–25° 的稳定姿态都会触发一次 0.8 秒自动重定向：塞手机、
     * 按开始、挑片时各定一次正前方，抬头看片就偏十来度；切换视频时再动一次头
     * 又「突然回到画面中央」。真机反馈「更严重了、非常混乱」就是它。
     *
     * 正前方改为只在进 VR 时定一次——`HeadRig` 等姿态稳定下来的第一个瞬间
     * （见 HeadRig.submit 的首次基准），之后不再自动动画面。
     * 用户仍可以自己重定，两条路都不需要瞄准任何按钮（见 [startAlignCountdown]）。
     *
     * 这个方法每帧被 VrActivity 的 tick 调用，拿到的就是当前姿态，所以
     * 「归零那一刻头朝哪儿」不需要任何额外采样。
     *
     * alpha35 / 02：**还没上膛**时不再每帧写视线——「戴稳了？」改成头锁定层
     * （VrRenderer 用相机空间渲染），与姿态零耦合，从而消除「闪」。
     */
    fun updatePosture(rotation: FloatArray, nowMs: Long) {
        // alpha35 / 02：「戴稳了？」改为头锁定层后，不再需要每帧把视线写进状态。
        if (!uiState.gazeArmed) return
        val cd = uiState.alignCountdown ?: return
        val dt = (nowMs - alignLastAtMs).coerceIn(0L, 200L)
        if (dt > 0L) {
            val speed = angleBetweenDeg(alignLastPose ?: rotation, rotation) * 1000f / dt
            alignSteadyMs = if (speed <= ALIGN_STEADY_DPS) {
                (alignSteadyMs + dt).coerceAtMost(2_000L)
            } else {
                0L
            }
        }
        alignLastPose = rotation.copyOf()
        alignLastAtMs = nowMs

        val remaining = (alignUntilMs - nowMs).coerceAtLeast(0L)
        if (remaining == 0L) {
            applyAlign(rotation, cd)
            return
        }
        uiState = uiState.copy(
            alignCountdown = cd.copy(
                remainingMs = remaining,
                // 面板朝向 = 当前视线方向：卡片因此贴在眼前，用户永远不用去找它。
                yaw = posture.yawOf(rotation),
                pitch = posture.pitchOf(rotation),
                movedDeg = angleBetweenDeg(alignStartPose ?: rotation, rotation).toInt(),
                steady = alignSteadyMs >= ALIGN_STEADY_MS
            )
        )
    }

    /**
     * V2.38：开始重定「正前方」的倒计时。
     *
     * 旧实现是「凝视『正前方』→ 弹确认卡 → 再凝视『挪到这里』」，而用户为了点到
     * 这两个按钮，头已经转到按钮那边去了——被记下的自然是**按钮的位置**，不是
     * 他想要的正前方。真机上「怎么调都差一点」，根子在这里，不在 `recenterTo`。
     *
     * 新语义：看哪儿，哪儿就是正前方。倒计时期间什么都不用点，只要把姿态摆好、
     * 停住；归零那一刻的朝向直接成为新基准。卡片跟着视线走（`panelSpec`），
     * 所以也没有「去找卡片」这一步。
     */
    private fun startAlignCountdown() {
        if (uiState.alignCountdown != null) return
        val now = SystemClock.elapsedRealtime()
        val total = settings.current.alignCountdownMs.coerceIn(
            VrSettings.ALIGN_MIN_MS, VrSettings.ALIGN_MAX_MS
        )
        val rotation = headset.headRotation()
        alignStartPose = rotation.copyOf()
        alignLastPose = rotation.copyOf()
        alignLastAtMs = now
        alignUntilMs = now + total
        alignSteadyMs = 0L
        uiState = uiState.copy(
            // 独占的一层：浮层与控制台全收起来。画面正中要留给用户判断方向，
            // 也保证这一刻不会有第二块 UI 挡在「跟着视线走」的卡片前面。
            overlay = VrOverlay.NONE,
            consoleVisible = false,
            feedback = null,
            message = null,
            messageUntilMs = 0L,
            alignCountdown = AlignCountdown(
                totalMs = total,
                remainingMs = total,
                yaw = posture.yawOf(rotation),
                pitch = posture.pitchOf(rotation),
                movedDeg = 0,
                steady = false
            )
        )
        // 刚凝视过按钮，凝视停留与手势窗口必须清零，否则旧落点会当场再触发一次。
        onRecenter()
        RunLog.append("posture", "重定倒计时 · ${total / 1000} 秒 · 归零处即新的正前方")
    }

    /**
     * 归零：把此刻的视线方向写成新的正前方。
     *
     * 缓转而不是瞬跳：整个视界在一帧里平移几十度是最典型的晕动来源。
     * 结束后 [PostureTracker.suppress] 再静默一个缓转周期，避免「刚定完就被
     * 某条检测抢走画面」。
     */
    private fun applyAlign(rotation: FloatArray, cd: AlignCountdown) {
        val now = SystemClock.elapsedRealtime()
        val steady = alignSteadyMs >= ALIGN_STEADY_MS
        // V2.46 / P0：startAlignCountdown 把 consoleVisible 置成了 false，
        // 结束时必须还回来。否则倒计时一过，播放页 drawPlayer() 直接 return、
        // 准星也不画，用户被丢进一个「无按钮、无光标、无完成提示」的纯视频画面。
        uiState = uiState.copy(
            alignCountdown = null,
            consoleVisible = true,
            lastActivityMs = now
        )
        alignStartPose = null
        alignLastPose = null
        alignSteadyMs = 0L
        posture.suppress(now)
        headset.recenterTo(rotation, PostureTracker.ANIM_MS)
        onRecenter()
        showMessage("正前方已定在刚才的方向 · 调整 ${cd.movedDeg}°")
        RunLog.append(
            "posture",
            "重定完成 · 调整 ${cd.movedDeg}° · 结束时${if (steady) "已稳住" else "仍在动"} · " +
                "用 ${cd.totalMs / 1000} 秒"
        )
    }

    /** 倒计时里单击 = 「现在就定在这里」。 */
    private fun confirmAlignNow() {
        val cd = uiState.alignCountdown ?: return
        applyAlign(headset.headRotation(), cd)
    }

    private fun cancelAlign() {
        // V2.46 / P0：同 applyAlign，控制台要还回来；另外取消也得清凝视窗口，
        // 否则倒计时期间转头找方向留下的 dwell 会在取消那一帧误开一部片。
        uiState = uiState.copy(
            alignCountdown = null,
            consoleVisible = true,
            lastActivityMs = SystemClock.elapsedRealtime()
        )
        alignStartPose = null
        alignLastPose = null
        alignSteadyMs = 0L
        onRecenter()
        showMessage("已取消 · 正前方没动")
        RunLog.append("posture", "重定倒计时已取消")
    }

    /**
     * V2.38：双击触发键，立刻把正前方对齐到现在的朝向。
     *
     * 这是「不想等倒计时」的快捷方式，和倒计时并存。它同样不碰任何按钮，
     * 所以不存在「被按钮位置污染」的问题——记下的就是用户此刻看向的方向。
     */
    private fun recenterNow() {
        val rotation = headset.headRotation()
        val now = SystemClock.elapsedRealtime()
        val yaw = degrees(posture.yawOf(rotation))
        val pitch = degrees(posture.pitchOf(rotation))
        posture.suppress(now)
        headset.recenterTo(rotation, RECENTER_NOW_MS)
        onRecenter()
        showMessage("正前方已对齐到你现在看的方向")
        RunLog.append("posture", "双击立刻重定 · 原朝向 偏航 $yaw° 俯仰 $pitch°")
    }

    /**
     * 自动姿态候选的确认（V2.19c 的「挪到这里」）。
     *
     * V2.28 起自动检测已停用，所以这条分支实际上不会触发；保留是因为
     * 「候选卡」这条链路（PostureTracker 的 TTL / 静默期）还在，删一半会留下死引用。
     */
    private fun confirmPosture() {
        val now = SystemClock.elapsedRealtime()
        val pose = posture.confirm(now)
        if (pose == null) {
            showMessage("没有需要调整的姿势")
            return
        }
        headset.recenterTo(pose, PostureTracker.ANIM_MS)
        // 缓转期间必须把凝视停留与手势窗口清零——只暂停命中不清零，
        // 缓动结束首帧就会拿着缓动前的位置当场再触发一次。
        onRecenter()
        uiState = uiState.copy(postureCandidate = null, postureYaw = 0f, postureRemainingMs = 0L)
        showMessage("正前方已挪到你现在的姿势")
    }

    private fun dismissPosture() {
        posture.dismiss(headset.headRotation())
        uiState = uiState.copy(postureCandidate = null, postureYaw = 0f, postureRemainingMs = 0L)
    }
    private var pendingOpen: Triple<MediaItemEntity, MediaRef, com.gazereel.vr.core.VrFormat>? = null

    /**
     * alpha35：正前方是否已经由**纸盒物理触发键**定过。
     *
     * 定过之后 `startPlayback()` 不能再调 `recenterYaw()`：V2.35 那条修复的前提是
     * 「基准定在塞手机那一刻」，本轮基准改由戴稳后的触发键给定，前提已消失；
     * 再在打开新片时把 yaw 对齐到「凝视卡片那一刻」，反而会转走刚定好的正前方。
     * 没经过 ArmGaze 的异常恢复路径仍保留 V2.35 的兜底。
     */
    private var forwardAnchored = false

    /**
     * alpha35：正在播放这一部的 MediaRef。
     *
     * `applyFormat()` 原来只从 `pendingOpen` 取 ref，而它在 `startPlayback()` 里
     * 已经置 null —— 于是「播放中手选格式」只对本次生效，下次又回到错判。
     * 格式记忆的钥匙是 `sourceId:path`，播放中的当前片必须一直拿得到。
     */
    private var currentRef: MediaRef? = null

    /**
     * V2.17：被隐藏的片库不进 VR 的片库页。
     *
     * `null` 表示「还不知道有哪些片库」——首帧时片源列表可能比媒体列表晚到，
     * 这时若按空集过滤，片库会先空一下再跳出来。所以 null 不做过滤。
     */
    private var enabledSourceIds: Set<Long>? = null

    /** 过滤之前的全量，片源列表晚到时用它重算一次，不必等媒体表再变一次。 */
    private var rawItems: List<MediaItemEntity> = emptyList()

    /** 片源列表 + 「哪些没被隐藏」一起给，隐藏/显示立刻反映到片库页。 */
    fun setSources(map: Map<Long, SourceEntity>, enabled: Set<Long>) {
        sources = map
        enabledSourceIds = enabled
        loadItems(rawItems)
    }

    init {
        // 上次退出时若锁定视角，重建后仍保持一致。
        headset.setViewLocked(settings.current.lockView)
        syncVolumeFromSystem()
    }

    /**
     * V2.14：进 VR 时以系统媒体音量为准。
     * 用户很可能在戴镜前就用侧键把音量调了，app 里存的那份早过期了。
     */
    private fun syncVolumeFromSystem(): Int {
        val actual = systemVolume.percent()
        if (actual < 0) return settings.current.volume
        val next = settings.update { it.copy(volume = actual) }
        uiState = uiState.copy(settings = next)
        return actual
    }

    /**
     * V2.8：片源列表刷新时不再把游标打回第一页。
     *
     * 设计文档 08.5 明确要求 `loadItems()` 不再重置 `selectedIndex`。
     * 原来的 `copy(items = items, selectedIndex = 0)` 有个副作用：后台重扫、断点回写
     * 都会重新发射一次列表，用户正翻到第 30 页也会被弹回第 1 页。
     * 现在只要当前页还存在就原地不动，选中项尽量保住。
     */
    fun loadItems(items: List<MediaItemEntity>) {
        // alpha35：不管数据从哪条 Flow 来，进片库页之前都收口到同一份文件名升序，
        // 与集合内、自动连播完全一致。DAO 已经这么排了，这里再排一次是幂等保险。
        rawItems = FileNameOrder.sorted(items)
        // V2.17：隐藏的片库连它的片子一起不进片库页。索引与断点都还在库里，只是不显示。
        val allowed = enabledSourceIds?.let { ids -> rawItems.filter { it.sourceId in ids } } ?: rawItems

        // V2.18：柜子那一层。选中的柜子要是已经没片子了（片源被删/被隐藏），回落到「全部」，
        // 否则用户会看到一个空片库，而且不知道为什么会空。
        val cabinets = buildCabinets(allowed)
        val cabinet = uiState.cabinet
            .takeIf { id -> id == 0L || allowed.any { it.sourceId == id } }
            ?: 0L
        val visible = if (cabinet == 0L) allowed else allowed.filter { it.sourceId == cabinet }

        val keepId = uiState.selectedItem?.id
        val index = visible.indexOfFirst { it.id == keepId }
            .takeIf { it >= 0 }
            ?: 0
        // V2.16：叠跟着片子一起重算；原来待着的那一叠如果没了（换了片源、重扫后文件夹改名），
        // 就退回「全部叠」，不要留一个指向空列表的 activeStack。
        val stacks = StackBuilder.build(visible)
        val activeStack = uiState.activeStack?.takeIf { key -> stacks.any { it.key == key } }
        val next = uiState.copy(
            items = visible,
            stacks = stacks,
            activeStack = activeStack,
            cabinets = cabinets,
            cabinet = cabinet,
            selectedIndex = index.coerceIn(0, maxOf(0, visible.size - 1))
        )
        // 页码要按「这一屏现在有几个格子」收敛：成叠之后页数会少一大截。
        uiState = next.copy(page = uiState.page.coerceIn(0, next.pageCount - 1))
        probeUnsupportedForCurrentPage()
    }

    /** 20:2x：已经问过一次的片子，失败也算问过，避免后台反复读 SMB 文件头。 */
    private val unsupportedProbed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 20:2x：给片库当前页打「本机解不了」标记。
     *
     * 只探当前页 9 部 + 下一页 9 部做预取，后台串行读文件头，不阻塞 UI、不写 Room。
     * `FormatProber` 自带 LRU 缓存与 2.5s 墙钟上限，最坏一部坏片也只拖 2.5 秒，
     * 而且只拖后台线程。真实 mime 与宽高来自 **stsd**（不是文件名 —— 真机上
     * 标 `_4K_HEVC_` 的片子实际是 VP9 或 H.264），判定交给
     * `DecoderCaps.isUnsupported`，它只认平台明确说不支持的那一支。
     *
     * 这里不新增数据库列：设备能力是静态的，这是「片源规格 × 本机能力」的派生结果，
     * 加列要动 migration，本工程曾因手写 DDL 与 Room 生成结果不一致把库删了。
     */
    private fun probeUnsupportedForCurrentPage() {
        val p = prober ?: return
        val all = uiState.visibleItems
        val from = uiState.page * VrUiState.PAGE_SIZE
        if (from >= all.size) return
        val batch = all.subList(from, minOf(all.size, from + VrUiState.PAGE_SIZE * 2))
        // buildRef 读 sources 映射，必须在主线程先做好再交给 IO。
        val jobs = batch.mapNotNull { item -> buildRef(item)?.let { item to it } }
        if (jobs.isEmpty()) return
        scope.launch {
            val bad = withContext(Dispatchers.IO) {
                jobs.mapNotNull { (item, ref) ->
                    val key = mediaKeyOf(item)
                    if (!unsupportedProbed.add(key)) return@mapNotNull null
                    val probe = runCatching { p.probe(ref) }.getOrNull() ?: return@mapNotNull null
                    if (probe.width > 0 && probe.mime != null &&
                        DecoderCaps.isUnsupported(probe.mime, probe.width, probe.height)
                    ) key else null
                }.toSet()
            }
            if (bad.isNotEmpty()) {
                uiState = uiState.copy(unsupportedKeys = uiState.unsupportedKeys + bad)
                RunLog.append("codec", "片库标记 ${bad.size} 部：本机解不了")
            }
        }
    }

    /** 阶段二 FR-10：片库卡片据此显示「上次看到 mm:ss」。 */
    fun loadProgress(all: List<PlayProgressEntity>) {
        uiState = uiState.copy(progress = all.associateBy { it.mediaKey })
    }

    /**
     * V2.18：把「柜子」这一层算出来。第一个永远是「全部片库」。
     *
     * 只有一个片源时这一层没有意义，UI 会据此不画「换片库」按钮——
     * 多一层就是多一次要理解的选择，没有收益就别加。
     */
    private fun buildCabinets(items: List<MediaItemEntity>): List<Cabinet> {
        if (items.isEmpty()) return emptyList()
        val out = mutableListOf(Cabinet(0L, "全部片库", items.size))
        val groups = items.groupBy { it.sourceId }
        for ((id, list) in groups) {
            val source = sources[id] ?: continue
            out += Cabinet(
                sourceId = id,
                label = source.displayName.ifBlank { "片库 $id" },
                count = list.size,
                chip = cabinetChip(source.type)
            )
        }
        // 柜子按名字排，和戴镜前配置页里的顺序一致，用户不用重新建立方位感。
        return listOf(out.first()) + out.drop(1).sortedBy { it.label }
    }

    /** 卡片上不写协议缩写，写「这是什么」。与配置页 `chipFor` 保持同一套说法。 */
    private fun cabinetChip(type: String): String = when (type) {
        "LOCAL" -> "本机"
        "SMB" -> "共享"
        "WEBDAV" -> "网盘"
        "HTTP" -> "网页目录"
        "DLNA" -> "媒体服务器"
        else -> type
    }

    /**
     * 换整柜：一次凝视换一个片源，不弹菜单、不重新扫描。
     * 换柜之后回到「全部集合」，因为上一个柜子的集合和这个柜子没有关系。
     */
    private fun switchCabinet(sourceId: Long) {
        val label = uiState.cabinets.firstOrNull { it.sourceId == sourceId }?.label ?: "全部片库"
        uiState = uiState.copy(
            cabinet = sourceId,
            cabinetsOpen = false,
            activeStack = null,
            page = 0,
            selectedIndex = 0
        )
        loadItems(rawItems)
        RunLog.append("ui", "换柜 → $label")
        showMessage("现在是「$label」")
    }

    fun handle(action: VrAction, channel: InputChannel) {
        // V2.38：倒计时期间整个输入面都让给它。
        //
        // 这一刻用户正在靠转头把姿态摆到想要的位置——低头、左右扫视都是调整本身，
        // 任何落在别处的动作（单击空白 = 暂停、点头 = 切播放）都是误伤。
        // 只留三个语义：单击 = 现在就定、长按/返回 = 取消、其余一律吞掉并留一行日志。
        if (uiState.alignCountdown != null) {
            when (action) {
                VrAction.ConfirmAlign -> confirmAlignNow()
                VrAction.CancelAlign, VrAction.Back -> cancelAlign()
                // V2.46 / P0：倒计时不该是模态牢笼。用户想退出 VR 时必须有出口，
                // 否则戴镜状态下要干等 10 秒才能离开。
                VrAction.Exit -> {
                    player.saveProgress()
                    onExit()
                }
                else -> RunLog.append(
                    "posture", "倒计时中忽略 ${action.javaClass.simpleName} via $channel"
                )
            }
            return
        }
        touch()
        // V2.33 / B13：「边看边调」只在「播放页 + 无浮层」成立。
        // 任何换屏 / 退出动作都先把音量键还回去，否则用户进了设置浮层，
        // 侧键还在偷偷改瞳距——这是最典型的「借用不归还」。
        // Back 例外：它由 back() 自己处理，先退调瞳距再谈退回片库。
        if (uiState.ipdTuning && !keepsIpdTuning(action)) stopIpdTuning()
        // V2.7.8：换片这类不可撤销的动作必须能追溯「是谁按的」。
        // 上一轮「播几秒自己跳下一个」排查了半天，就是因为日志里只有结果、没有来源。
        when (action) {
            VrAction.Next, VrAction.Previous, VrAction.TogglePlay, VrAction.Back ->
                RunLog.append("ui", "${action.javaClass.simpleName} via $channel")
            is VrAction.PageFlip ->
                RunLog.append("ui", "PageFlip ${action.delta} via $channel")
            is VrAction.OpenMedia ->
                RunLog.append("ui", "OpenMedia ${action.mediaId} via $channel")
            else -> Unit
        }
        when (action) {
            is VrAction.Confirm -> if (action.targetId == "open") openSelected()
            is VrAction.OpenMedia -> {
                uiState = uiState.copy(
                    selectedIndex = uiState.items.indexOfFirst { it.id == action.mediaId }.coerceAtLeast(0)
                )
                openSelected()
            }
            is VrAction.PageFlip -> flipPage(action.delta)
            is VrAction.OpenStack -> openStack(action.key)
            VrAction.CloseStack -> closeStack()
            // V2.18：换柜子整层（B2）
            VrAction.OpenCabinets -> uiState = uiState.copy(cabinetsOpen = true)
            VrAction.CloseCabinets -> uiState = uiState.copy(cabinetsOpen = false)
            is VrAction.SwitchCabinet -> switchCabinet(action.sourceId)
            // V2.19：姿态闭环
            VrAction.ConfirmPosture -> confirmPosture()
            VrAction.DismissPosture -> dismissPosture()
            // V2.38：「正前方」= 开始跟着视线走的倒计时；双击触发键 = 立刻重定。
            VrAction.AlignForward -> startAlignCountdown()
            VrAction.RecenterNow -> recenterNow()
            VrAction.ConfirmAlign -> confirmAlignNow()
            VrAction.CancelAlign -> cancelAlign()
            VrAction.ResumePlayback -> resumePlayback()
            VrAction.TogglePlay -> player.togglePlay()
            // V2.8：片库里 Next / Previous 只能是翻页。
            // 原来它直接 playRelative(±1)，任何在片库里发出的「下一部」都会当场开始播放，
            // 和「翻页」这个意图完全不是一回事。
            VrAction.Next ->
                if (uiState.screen == VrScreen.LIBRARY) flipPage(1) else playRelative(1)
            VrAction.Previous ->
                if (uiState.screen == VrScreen.LIBRARY) flipPage(-1) else playRelative(-1)
            is VrAction.SeekTo -> player.seekToFraction(action.fraction)
            // alpha35：输入层必须在 dispatch 前经 VrUiLayer.resolveAction() 把
            // SeekAt 换成带 fraction 的 SeekTo。能走到这里说明有第三条输入通道漏接线，
            // 宁可不跳也不要按猜的位置跳，日志留痕方便定位。
            VrAction.SeekAt -> RunLog.append(
                "ui", "SeekAt 未经 resolve 到达 coordinator，本次跳转已忽略"
            )
            is VrAction.SeekBy -> player.seekBy(action.seconds)
            is VrAction.SetFormat -> applyFormat(action.format)
            VrAction.OpenFormat -> uiState = uiState.copy(overlay = VrOverlay.FORMAT)
            VrAction.OpenSettings -> uiState = uiState.copy(overlay = VrOverlay.SETTINGS)
            VrAction.CloseOverlay -> uiState = uiState.copy(overlay = VrOverlay.NONE)
            VrAction.Back -> back()
            VrAction.Exit -> {
                // V2.46 / P0：退出前落盘断点。原来只有 back() 的 PLAYER→LIBRARY
                // 分支会存，直接凝视「退出」的用户下次进 VR 就从头播。
                player.saveProgress()
                onExit()
            }
            VrAction.Retry -> retryCurrent()
            VrAction.ShowConsole -> uiState = uiState.copy(
                consoleVisible = true,
                lastActivityMs = SystemClock.elapsedRealtime()
            )
            VrAction.ArmGaze -> {
                // V2.9：戴稳之后的第一次触发键。这里只负责解锁凝视，
                // 不顺带确认任何目标——准星此刻压着的东西要重新「进入」才算数。
                //
                // alpha35：这一刻同时**定正前方**。用户澄清过「按键」是纸盒上的
                // 物理触发键——纸盒已扣好、人已戴稳、正看着「戴稳了？」卡片，
                // 此刻的朝向就是他想看的方向。V2.23「基准绝不能取按键那一刻」
                // 说的是手机端入盒引导页按钮（按完还要往沙发里靠，几秒差 40°–50°），
                // 不是同一时刻、同一界面、同一输入通道，不冲突。
                val pose = headset.headRotation()
                RunLog.append(
                    "posture",
                    "戴稳 · 物理触发键定正前方 · 原偏离首帧基准 " +
                        "${degrees(posture.offsetOf(pose))}°"
                )
                headset.recenterTo(pose, RECENTER_NOW_MS)
                // 缓转期间必须把凝视停留与手势窗口清零；参考系一变，
                // 旧落点会在首帧当场误触发一次。
                onRecenter()
                posture.suppress(SystemClock.elapsedRealtime())
                forwardAnchored = true
                uiState = uiState.copy(gazeArmed = true, consoleVisible = true)
                RunLog.append("ui", "凝视已上膛 via $channel · 正前方已锚定")
                showMessage("已开始 · 凝视 ${settings.current.gazeDurationMs} 毫秒即确认", 2_200L)
            }
            VrAction.LockView -> toggleViewLock()
            is VrAction.Adjust -> adjust(action.key, action.delta)
            is VrAction.SettingsPage -> uiState = uiState.copy(settingsPage = action.page)
            is VrAction.ApplyFisheye -> {
                // 预设只改张角与画面圆，不动当前片子的格式 —— 是否鱼眼由格式决定，
                // 预设是「我用的是哪支镜头」的全局偏好。
                // 19:2x：预设描述的是「圆 + 贴边」那条形态（180/190/200/220 四个镜头档），
                // 所以它顺带把全画幅关掉 —— 不然选了 MKX200 却仍是全画幅网格，
                // 张角写 200° 但画面不是这支镜头该有的样子。
                val next = settings.update {
                    it.copy(
                        fisheyeFovDeg = action.preset.fovDeg,
                        fisheyeCirclePercent = action.preset.circlePercent,
                        fisheyeFullFrame = false
                    )
                }
                uiState = uiState.copy(settings = next)
                RunLog.append("ui", "鱼眼预设 → ${action.preset.label} / ${action.preset.fovDeg}° · 圆形")
            }
            VrAction.ResetFisheye -> {
                val next = settings.update {
                    it.copy(
                        fisheyeFovDeg = VrSettings.FISHEYE_FOV_DEFAULT,
                        fisheyeCirclePercent = VrSettings.FISHEYE_CIRCLE_DEFAULT,
                        fisheyeFullFrame = false
                    )
                }
                uiState = uiState.copy(settings = next)
                RunLog.append("ui", "鱼眼已恢复默认 · ${VrSettings.FISHEYE_FOV_DEFAULT}° / 圆形 / 100%")
            }
            VrAction.DismissRefusal -> {
                // 「就这样看」：拒绝卡收起，画面继续按 2D 银幕原样放着。
                // 不改成鱼眼 —— 那正是这张卡要挡住的事。
                uiState = uiState.copy(refusal = null)
                RunLog.append("fmt", "拒绝卡已收起 · 按 2D 银幕原样显示")
            }
            is VrAction.ToggleOption -> toggleOption(action.key)
            VrAction.StartIpdTuning -> startIpdTuning()
            VrAction.StopIpdTuning -> stopIpdTuning()
            VrAction.ToggleIpdMode -> toggleIpdMode()
            VrAction.DismissAdjust -> {
                adjustHovered = false
                uiState = uiState.copy(feedback = null)
            }
            VrAction.ClearFormatMemory -> {
                scope.launch(Dispatchers.IO) { library.clearFormatMemory() }
                showMessage("格式记忆已清除")
            }
        }
    }

    /** 只有「还在调」这一组动作能留在瞳距态里；其余一律先退出。 */
    private fun keepsIpdTuning(action: VrAction): Boolean = when (action) {
        is VrAction.Adjust, VrAction.ToggleIpdMode, VrAction.Back -> true
        else -> false
    }

    /** V2.46 / P0：播放错误卡上的「重试」。重新打开当前这一部，不需要摘手机。 */
    private fun retryCurrent() {
        val id = uiState.currentMediaId
        uiState = uiState.copy(
            message = null,
            messageUntilMs = 0L,
            consoleVisible = true,
            lastActivityMs = SystemClock.elapsedRealtime()
        )
        if (id == 0L) {
            showMessage("回片库再选一次")
            return
        }
        val index = uiState.items.indexOfFirst { it.id == id }
        if (index >= 0) uiState = uiState.copy(selectedIndex = index)
        RunLog.append("ui", "重试当前片 · mediaId=$id")
        openSelected()
    }

    /**
     * 真机验收专用：按片名片段点名起播，返回命中的标题；没命中返回 null。
     *
     * 走的是和「凝视点格子」完全同一条路（选中 → openSelected），所以验收看到的就是
     * 用户看到的：格式识别、拒绝卡、预设记忆一个都不绕过。只用一条 adb 命令替代翻页，
     * 免得手机在纸盒里、人却要伸手去点 3×3 格子。
     *
     * 命中失败一律返回 null 并由调用方写日志：**没有「大概就是这一部」**，
     * 验错片比没验更糟。柜子先回到「全部」，否则目标可能被当前柜子过滤掉。
     */
    fun playByTitle(query: String): String? {
        val q = ClipRequest.normalize(query) ?: return null
        val ordered = FileNameOrder.sorted(rawItems)
        val at = ClipRequest.pick(ordered.map { it.title }, q)
        if (at < 0) return null
        val target = ordered[at]
        uiState = uiState.copy(cabinet = 0L, activeStack = null)
        // 重算可见列表：刚才把柜子改回「全部」了，items 还是旧柜子的切片。
        loadItems(rawItems)
        val index = uiState.items.indexOfFirst { it.id == target.id }
        if (index < 0) return null          // 片源被隐藏了：片子还在库里，但不在这一屏
        uiState = uiState.copy(
            selectedIndex = index,
            page = index / VrUiState.PAGE_SIZE
        )
        openSelected()
        return target.title
    }

    private fun openSelected() {
        scope.launch {
            val item = uiState.selectedItem ?: return@launch
            val ref = buildRef(item) ?: return@launch
            val resolution = resolveFormatFor(item, ref)
            pendingOpen = Triple(item, ref, resolution.format)
            uiState = uiState.copy(
                suggestedFormat = resolution.format,
                format = resolution.format,
                // 19:2x：拒绝是「这一次打开的结论」，必须跟片走 —— 换到别的片子要清掉，
                // 否则上一部片的说明卡会跟着出现在下一部上。
                refusal = resolution.refusal,
                overlay = if (resolution.needsConfirmation) VrOverlay.FORMAT else VrOverlay.NONE
            )
            if (!resolution.needsConfirmation) startPlayback()
        }
    }

    /**
     * V2.7.6：先问文件，再问几何，最后才看名字。
     * 探测本身失败不影响播放——拿不到就当没探测过。
     *
     * V2.7.7：已经记过格式的文件直接返回，不再探一次。
     * 否则每切一部片都要先读 1MB 才能起播，翻片会一直卡两秒。
     */
    private suspend fun resolveFormatFor(
        item: MediaItemEntity,
        ref: MediaRef
    ): LibraryRepository.FormatResolution {
        val cached = withContext(Dispatchers.IO) {
            runCatching { library.cachedFormat(ref) }.getOrNull()
        }
        if (cached != null) {
            // 命中记忆就不再探测：这一部片已经定过格式，结论直接可用。
            return LibraryRepository.FormatResolution(cached, false)
        }

        val probe = prober?.let {
            withContext(Dispatchers.IO) {
                runCatching { withTimeoutOrNull(8_000L) { it.probe(ref) } }.getOrNull()
            }
        }
        return withContext(Dispatchers.IO) { library.resolveFormat(ref, item, probe) }
    }

    private fun startPlayback() {
        val pending = pendingOpen ?: return
        pendingOpen = null
        val (item, ref, format) = pending
        // alpha35：播放中的格式确认要靠它落记忆，不能只认 pendingOpen。
        currentRef = ref
        // 这次打开的格式只属于这部片：currentMediaId 是片库页断点与进度归属的依据。
        uiState = uiState.copy(
            screen = VrScreen.PLAYER,
            overlay = VrOverlay.NONE,
            format = format,
            suggestedFormat = format,
            currentMediaId = item.id,
            currentTitle = item.title,
            consoleVisible = true,
            subtitles = emptyList(),
            // 19:2x：换片要把上一部的缓冲信息清掉，否则新片起播前会看到旧片的缓冲段。
            buffering = false,
            bufferedMs = 0L,
            lastActivityMs = SystemClock.elapsedRealtime()
        )
        // V2.35：把「画面中心」钉在用户此刻看的方向上。
        //
        // 球面网格的正前方一直是左眼 u=0.25（180° SBS 左右两幅各自的正中），
        // 几何从来没算错——会偏的是**参考系**。基准是在「手机刚塞进纸盒」那一刻
        // 定的（日志里是「静止 0 帧」，说明 4 秒到点时手机还在动），只要那一刻的
        // 朝向和戴稳后差 90°，两幅的接缝就正好转到视野正中，这就是「打开总对着接缝」。
        //
        // 打开一部新片是唯一「整块画面被换掉」的时刻：在这里对齐方位角，
        // 前一帧还是片库、后一帧已经是视频，用户看不到任何跳变。
        // 只转 Y 轴，俯仰与横滚不动 —— 低头点开下一部时地平线也不会跟着歪。
        //
        // alpha35：正前方已由戴稳后的物理触发键定过时**跳过这一步**。
        // 那条修复的前提（基准定在塞手机那一刻）已经消失，再对齐一次会把
        // 刚定好的方向转走；没经过 ArmGaze 的异常恢复路径仍按旧逻辑兜底。
        if (forwardAnchored) {
            RunLog.append("posture", "画面中心对齐 · 正前方已由触发键锚定，跳过二次 recenterYaw")
        } else {
            val yawBefore = degrees(posture.yawOf(headset.headRotation()))
            headset.recenterYaw()
            // 参考系一变，凝视停留与手势窗口必须清零，否则会拿旧落点当场再触发一次。
            onRecenter()
            RunLog.append("posture", "画面中心对齐 · 打开前偏航 ${yawBefore}°")
        }
        scope.launch {
            val resume = withContext(Dispatchers.IO) { library.resumePosition(mediaKeyOf(item)) }
            // V2.14：播放器自身固定满增益，音量交给系统媒体音量，
            // 不再出现「app 存 75 / 系统是 0」这种两套数值打架的情况。
            player.setVolume(100)
            syncVolumeFromSystem()
            player.open(item, ref, resume)
        }
        // 字幕走独立低优先级任务：同名字幕不存在时的网络超时不能拖慢起播。
        scope.launch {
            val cues = withContext(Dispatchers.IO) {
                runCatching { subtitles.load(ref) }.getOrDefault(emptyList())
            }
            uiState = uiState.copy(subtitles = cues)
        }
    }

    private fun applyFormat(format: com.gazereel.vr.core.VrFormat) {
        // alpha35：播放中改格式时 pendingOpen 已是 null，必须回落到当前片。
        // 原来只认 pendingOpen，于是「手选一次只管这一次」，下次打开又回到错判。
        val ref = pendingOpen?.second ?: currentRef
        if (ref != null) {
            scope.launch(Dispatchers.IO) { library.rememberFormat(ref, format) }
            RunLog.append("fmt", "手选格式已记住 · ${ref.mediaKey} → ${format.label}")
        }
        // 19:2x：手动选格式 = 用户明确表态，拒绝卡必须撤掉（他可能就想看那两个圆）。
        // 这不是「放行」而是「把决定权交回给他」——卡片的职责是说明，不是把关。
        val refusing = uiState.refusal != null
        uiState = uiState.copy(format = format, overlay = VrOverlay.NONE, refusal = null)
        if (refusing) RunLog.append("fmt", "用户手选格式，已撤掉拒绝卡 · ${format.label}")
        // 仅「首次选片」需要继续启动播放；播放中改格式不重开片源。
        if (pendingOpen != null) startPlayback()
    }

    /** 自动连播：不弹格式确认，直接套用识别结果并复用共享会话（PRD 5.2）。 */
    fun autoPlayNext(mediaId: Long) {
        if (!settings.current.autoNext) {
            uiState = uiState.copy(
                screen = VrScreen.LIBRARY, playing = false, wantPlay = false
            )
            return
        }
        scope.launch {
            val next = withContext(Dispatchers.IO) { library.nextOf(mediaId) } ?: return@launch
            val ref = buildRef(next) ?: return@launch
            val resolution = resolveFormatFor(next, ref)
            pendingOpen = Triple(next, ref, resolution.format)
            // 自动连播也是一次「新打开」：格式按新片自己的证据重判，不继承上一部。
            uiState = uiState.copy(
                selectedIndex = uiState.items.indexOfFirst { it.id == next.id }.coerceAtLeast(0),
                format = resolution.format,
                suggestedFormat = resolution.format,
                refusal = resolution.refusal,
                overlay = VrOverlay.NONE,
                consoleVisible = false
            )
            startPlayback()
        }
    }

    private fun playRelative(delta: Int) {
        scope.launch {
            val currentId = uiState.currentMediaId.takeIf { it != 0L }
                ?: uiState.selectedItem?.id ?: return@launch
            val next = withContext(Dispatchers.IO) {
                if (delta > 0) library.nextOf(currentId) else library.previousOf(currentId)
            } ?: return@launch
            uiState = uiState.copy(
                selectedIndex = uiState.items.indexOfFirst { it.id == next.id }.coerceAtLeast(0)
            )
            openSelected()
        }
    }

    /**
     * V2.8：整页翻。到边界给一句人话，不要静默吞掉——
     * 摇头没反应和「已经最后一页」在戴镜时是分不出来的。
     */
    private fun flipPage(delta: Int) {
        val state = uiState
        val next = state.page + delta
        when {
            next < 0 -> showMessage("已经是第一页")
            next >= state.pageCount -> showMessage("已经是最后一页")
            else -> {
                uiState = state.copy(page = next)
                // 20:2x：翻到新的一页就补探那 9 部，角标才不会只出现在第一页。
                probeUnsupportedForCurrentPage()
            }
        }
    }

    // ── V2.16 成叠（B1 的第一层）与「回到播放」 ─────────────────────────

    private fun openStack(key: String) {
        val stack = uiState.stacks.firstOrNull { it.key == key } ?: return
        uiState = uiState.copy(activeStack = key, page = 0, consoleVisible = true)
        probeUnsupportedForCurrentPage()
        RunLog.append("ui", "进入集合 ${stack.label}（${stack.items.size} 部）")
    }

    private fun closeStack() {
        val state = uiState
        if (state.activeStack == null) return
        // 回到这一叠所在的页，而不是第 1 页——叠多的时候弹回第一页等于让人重新找。
        val index = state.stacks.indexOfFirst { it.key == state.activeStack }
        uiState = state.copy(
            activeStack = null,
            page = if (index >= 0) index / VrUiState.PAGE_SIZE else 0,
            consoleVisible = true
        )
    }

    /**
     * 播放中调出片库之后回到正在看的那一部。
     * 播放器一直把这一部开着（只是暂停），所以这里只切屏 + 继续播，不重新打开文件。
     */
    private fun resumePlayback() {
        val state = uiState
        if (state.currentMediaId == 0L) {
            showMessage("还没有在放的片子")
            return
        }
        uiState = state.copy(
            screen = VrScreen.PLAYER,
            playing = true,
            // 19:2x：键形看的是意图，这里要一起乐观置位，否则键会先闪一下「播放」。
            wantPlay = true,
            consoleVisible = true,
            lastActivityMs = SystemClock.elapsedRealtime()
        )
        player.resume()
        RunLog.append("ui", "回到播放：${state.currentTitle}")
    }

    private fun back() {
        // V2.46 / P0：播放错误卡是模态层（会清空下层凝视目标）。用户点
        // 「回片库」时必须把错误一起撤掉，否则片库页也被这张卡锁住，
        // 只能在「重试 / 回片库」之间打转，连「退出」都点不到。
        if (uiState.message != null && uiState.messageUntilMs == 0L) {
            uiState = uiState.copy(message = null, messageUntilMs = 0L)
        }
        // 19:2x：拒绝卡同样是最上层，返回时一起撤，别让它跟着到片库去。
        if (uiState.refusal != null) uiState = uiState.copy(refusal = null)
        val state = uiState
        when {
            // V2.33 / B13：瞳距「边看边调」是最上层的一层，长按先退它，
            // 否则用户只想结束调瞳距，结果直接退出了正在看的片子。
            state.ipdTuning -> stopIpdTuning()
            // V2.19：姿态确认浮层在最上层，返回键先撤它。
            state.postureCandidate != null -> dismissPosture()
            state.overlay != VrOverlay.NONE -> uiState = state.copy(overlay = VrOverlay.NONE)
            state.screen == VrScreen.PLAYER -> {
                player.saveProgress()
                player.pause()
                // V2.8：回到正在看的这一部所在的页，而不是第 1 页。
                // 「播放中按片库」十有八九是接着翻同一批，弹回第一页等于让人重新翻一遍。
                // V2.16：页码要按「当前这一屏」算——进了某叠，那一部在叠里的位置才是对的。
                val visible = state.visibleItems
                val index = visible.indexOfFirst { it.id == state.currentMediaId }
                val global = state.items.indexOfFirst { it.id == state.currentMediaId }
                uiState = state.copy(
                    screen = VrScreen.LIBRARY,
                    playing = false,
                    wantPlay = false,
                    consoleVisible = true,
                    selectedIndex = if (global >= 0) global else state.selectedIndex,
                    page = if (index >= 0) index / VrUiState.PAGE_SIZE else state.page
                )
            }
            // V2.18：柜子层按返回，退回片库，不要直接说「已在首页」。
            state.cabinetsOpen -> uiState = state.copy(cabinetsOpen = false)
            // V2.16：在某一叠里按返回，先退回「全部叠」，而不是直接说「已在首页」。
            state.activeStack != null -> closeStack()
            else -> {
                // V2.46 / P0：片库首页再按返回 = 退出 VR。
                // 原来这里只 showMessage("凝视「退出」可返回配置")，可播放页的「退出」
                // 会随 8 秒控制台淡出消失，而这条提示本身在播放页根本不显示——
                // 用户连按返回键永远出不去，只能把手机从纸盒里取出来。
                player.saveProgress()
                RunLog.append("ui", "片库首页再按返回 · 退出 VR")
                onExit()
            }
        }
    }

    /**
     * V2.11 建立、V2.33 / B14 重做：档位制调节 + **可操作的、交互感知的**调节条。
     *
     * 真机两条反馈都在这一层被推翻：
     * 1. 「音量和亮度条激活之后，用户来不及操作就消失了」——原来 2.6 秒是从激活
     *    那一刻起算的固定倒计时，而且那张卡一个凝视目标都没注册，等于不可操作。
     *    现在照 AOSP `VolumeDialogImpl.rescheduleTimeoutH()`：每次调节重排为
     *    [ADJUST_ACTIVE_MS]，准星停在条上时按 [ADJUST_HOVER_MS] 长保持，
     *    离开才回落到 [FEEDBACK_MS]。
     * 2. 「音量为 0 的时候也会显示一格」——原来把 1 基档号 `level + 1` 直接当成
     *    「点亮段数」，第 0 档（静音）也亮一段。现在**不再画段条**：一条轨道只按
     *    `fraction`（真实值 / 100）填充，静音 = 0 宽度，档位只由刻度线表达。
     */
    private fun adjust(key: String, delta: Int) {
        // 音量键走的是 InputChannel.SYSTEM，不经过 TriggerDispatcher，
        // 这里要自己把淡出计时推后，否则连按音量时控制台会中途消失。
        // 注意用 keepConsoleAlive 而不是 touch：按音量键不该把控制台叫出来。
        keepConsoleAlive()
        when (key) {
            "volume" -> {
                val steps = VrSettings.VOLUME_STEPS
                // 以系统音量当前值为基准，而不是以 app 里存的值为基准——
                // 用户可能在戴镜前用侧键调过，那份存储早就不是真的了。
                val live = systemVolume.percent().takeIf { it >= 0 } ?: settings.current.volume
                val level = (VrSettings.volumeStep(live) + delta)
                    .coerceIn(0, steps.lastIndex)
                systemVolume.setPercent(steps[level])
                val next = settings.update { it.copy(volume = steps[level]) }
                showAdjust(
                    next = next,
                    key = "volume",
                    label = "音量",
                    detail = if (steps[level] == 0) "静音" else "${level + 1} / ${steps.size}",
                    // 5 档去掉「静音」正好 4 段，3 条刻度落在 25 / 50 / 75。
                    steps = steps.size - 1,
                    fraction = steps[level] / 100f,
                    hint = "音量键 ± · 或凝视下方按钮 · 准星停在条上不会消失"
                )
            }
            "brightness" -> {
                val steps = VrSettings.BRIGHTNESS_STEPS
                val level = (VrSettings.brightnessStep(settings.current.brightness) + delta)
                    .coerceIn(0, steps.lastIndex)
                val next = settings.update { it.copy(brightness = steps[level]) }
                showAdjust(
                    next = next,
                    key = "brightness",
                    label = "亮度",
                    detail = VrSettings.BRIGHTNESS_LABELS[level],
                    // 35 / 70 / 100 不是均分，画均分刻度等于说谎，所以不画。
                    steps = 0,
                    fraction = steps[level] / 100f,
                    hint = "凝视下方按钮 · 准星停在条上不会消失"
                )
            }
            "ipd" -> {
                val coarse = uiState.feedback?.takeIf { it.key == "ipd" }?.coarse ?: true
                val step = if (coarse) VrSettings.IPD_COARSE_HALF else VrSettings.IPD_FINE_HALF
                val next = settings.update {
                    it.copy(
                        ipdHalfMm = (it.ipdHalfMm + delta * step)
                            .coerceIn(VrSettings.IPD_MIN_HALF, VrSettings.IPD_MAX_HALF)
                    )
                }
                val tuning = uiState.ipdTuning
                showAdjust(
                    next = next,
                    key = "ipd",
                    label = if (tuning) "瞳距 · 边看边调" else "瞳距",
                    detail = VrSettings.ipdLabel(next.ipdHalfMm) +
                        " · 每眼 ${"%.1f".format(Optics.lensShiftDeg(next.ipdMm))}°",
                    steps = 0,
                    fraction = ipdFraction(next.ipdHalfMm),
                    // 设置浮层里不是「边看边调」态：音量键仍归音量、单击仍是切粗微之外的语义。
                    // 提示必须和这一刻真正能用的输入一致，否则又是一句假话。
                    hint = if (tuning) ipdHint(coarse)
                    else "一次 2.0mm · 想边看边调请点下方「瞳距 · 边看边调」",
                    coarse = coarse
                )
                // V2.39：日志里带上「每眼光轴位移几度」。瞳距是唯一一个
                // 看不到数字就看不出变化的参数——没有这一句，下次还是只能靠猜。
                RunLog.append(
                    "ui",
                    "瞳距 → ${VrSettings.ipdLabel(next.ipdHalfMm)}（${if (coarse) "粗调" else "微调"}）" +
                        " · 每眼光轴位移 ${"%.1f".format(Optics.lensShiftDeg(next.ipdMm))}°"
                )
            }
            "gaze" -> {
                val next = settings.update {
                    it.copy(gazeDurationMs = (it.gazeDurationMs + delta * 200L).coerceIn(600L, 2_400L))
                }
                uiState = uiState.copy(settings = next)
            }
            // 18:2x：两个新档位。循环而不是钳位 —— 到头按不动会让人以为按钮坏了。
            // 不弹 showAdjust 浮层：设置页本身就显示当前值，浮层会盖住列表。
            "screenWidth" -> {
                val steps = VrSettings.SCREEN_WIDTH_STEPS
                val idx = VrSettings.stepOf(steps, settings.current.screenWidthPercent)
                val value = steps[(idx + delta + steps.size) % steps.size]
                val next = settings.update { it.copy(screenWidthPercent = value) }
                uiState = uiState.copy(settings = next)
                RunLog.append("ui", "银幕宽度 → ${VrSettings.screenWidthLabel(value)}")
            }
            "contentZoom" -> {
                val steps = VrSettings.CONTENT_SCALE_STEPS
                val idx = VrSettings.stepOf(steps, settings.current.contentScalePercent)
                val value = steps[(idx + delta + steps.size) % steps.size]
                val next = settings.update { it.copy(contentScalePercent = value) }
                uiState = uiState.copy(settings = next)
                RunLog.append("ui", "画面缩放 → ${value}%")
            }
            "fisheyeFov" -> {
                val steps = VrSettings.FISHEYE_FOV_STEPS
                val idx = VrSettings.stepOf(steps, settings.current.fisheyeFovDeg)
                val value = steps[(idx + delta + steps.size) % steps.size]
                val next = settings.update { it.copy(fisheyeFovDeg = value) }
                uiState = uiState.copy(settings = next)
                RunLog.append("ui", "鱼眼张角 → ${VrSettings.fisheyeFovLabel(value)}")
            }
            "fisheyeCircle" -> {
                val steps = VrSettings.FISHEYE_CIRCLE_STEPS
                val idx = VrSettings.stepOf(steps, settings.current.fisheyeCirclePercent)
                val value = steps[(idx + delta + steps.size) % steps.size]
                // 19:2x：全画幅下画面圆不参与几何。用户来拨它，意思是「我要圆那种形态」——
                // 直接跟着退回圆形，比让他先去找开关、再回来拨两下更省事，页面也写了这句。
                val wasFull = settings.current.fisheyeFullFrame
                val next = settings.update {
                    it.copy(fisheyeCirclePercent = value, fisheyeFullFrame = false)
                }
                uiState = uiState.copy(settings = next)
                RunLog.append(
                    "ui",
                    "鱼眼画面圆 → ${VrSettings.fisheyeCircleLabel(value)}" +
                        if (wasFull) " · 已退出全画幅" else ""
                )
            }
        }
    }

    private fun ipdFraction(half: Int): Float =
        (half - VrSettings.IPD_MIN_HALF).toFloat() /
            (VrSettings.IPD_MAX_HALF - VrSettings.IPD_MIN_HALF).toFloat()

    private fun ipdHint(coarse: Boolean): String =
        (if (coarse) "粗调 2.0mm" else "微调 0.5mm") +
            " · 音量键 ± · 单击切粗微 · 长按完成"

    /** 一次真实调节 = AOSP 的一次 `rescheduleTimeoutH()`：取消旧计时、重排。 */
    private fun showAdjust(
        next: VrSettings,
        key: String,
        label: String,
        detail: String,
        steps: Int,
        fraction: Float,
        hint: String,
        coarse: Boolean = true
    ) {
        val tuning = uiState.ipdTuning
        adjustHovered = false
        uiState = uiState.copy(
            settings = next,
            feedback = AdjustFeedback(
                key = key,
                label = label,
                detail = detail,
                steps = steps,
                fraction = fraction.coerceIn(0f, 1f),
                // 调瞳距期间常驻：用户要反复抬头看画面能不能合上，条不能中途消失。
                untilMs = if (tuning) Long.MAX_VALUE
                else SystemClock.elapsedRealtime() + ADJUST_ACTIVE_MS,
                hint = hint,
                coarse = coarse
            )
        )
    }

    /**
     * V2.33 / B13：从设置浮层进入「边看边调」。
     *
     * 三条硬约束都由这里保证：**视频不暂停、不重开、不铺压暗层**。
     * 设置浮层用 0xEE 面板铺满 1024×512，一进去画面就被盖住，而瞳距必须
     * 看着左右两眼能不能合上才调得准——所以进态时直接把浮层关掉、把控制台收起，
     * 只留上方一条细调节条（视频正中仍然空着）。
     */
    private fun startIpdTuning() {
        if (uiState.screen != VrScreen.PLAYER) {
            showMessage("先播放一部片子 · 瞳距要看着画面才调得准")
            return
        }
        val s = settings.current
        adjustHovered = false
        uiState = uiState.copy(
            overlay = VrOverlay.NONE,
            ipdTuning = true,
            consoleVisible = false,
            lastActivityMs = SystemClock.elapsedRealtime(),
            message = null,
            messageUntilMs = 0L,
            feedback = AdjustFeedback(
                key = "ipd",
                label = "瞳距 · 边看边调",
                detail = VrSettings.ipdLabel(s.ipdHalfMm),
                steps = 0,
                fraction = ipdFraction(s.ipdHalfMm),
                untilMs = Long.MAX_VALUE,
                hint = ipdHint(true),
                coarse = true
            )
        )
        RunLog.append("ui", "进入瞳距边看边调 · 起始 ${VrSettings.ipdLabel(s.ipdHalfMm)}")
    }

    /** 退出调瞳距：音量键立刻归还，值已经每帧被渲染层读到、无需二次应用。 */
    private fun stopIpdTuning() {
        if (!uiState.ipdTuning) return
        val label = VrSettings.ipdLabel(settings.current.ipdHalfMm)
        adjustHovered = false
        uiState = uiState.copy(
            ipdTuning = false,
            feedback = null,
            consoleVisible = true,
            lastActivityMs = SystemClock.elapsedRealtime()
        )
        showMessage("瞳距 $label 已记住 · 音量键已归还")
        RunLog.append("ui", "退出瞳距边看边调 · $label")
    }

    /** 粗调 / 微调共用同一个 0.5mm 整数值：不是两套值，是两种步长。 */
    private fun toggleIpdMode() {
        if (!uiState.ipdTuning) return
        val fb = uiState.feedback ?: return
        val coarse = !fb.coarse
        uiState = uiState.copy(
            feedback = fb.copy(coarse = coarse, hint = ipdHint(coarse), untilMs = Long.MAX_VALUE)
        )
        RunLog.append("ui", "瞳距切到${if (coarse) "粗调 2.0mm" else "微调 0.5mm"}")
    }

    private fun toggleViewLock() {
        val next = settings.update { it.copy(lockView = !it.lockView) }
        headset.setViewLocked(next.lockView)
        uiState = uiState.copy(
            settings = next,
            message = if (next.lockView) "视角已锁定 · 再点一次解锁" else "视角已解锁"
        )
    }

    private fun toggleOption(key: String) {
        if (key == "lockView") {
            toggleViewLock()
            return
        }
        val next = settings.update { current ->
            when (key) {
                "autoNext" -> current.copy(autoNext = !current.autoNext)
                "autoFade" -> current.copy(autoFade = !current.autoFade)
                "subtitles" -> current.copy(subtitlesEnabled = !current.subtitlesEnabled)
                "cursor" -> current.copy(cursorEnabled = !current.cursorEnabled)
                "wakeZone" -> current.copy(wakeZoneEnabled = !current.wakeZoneEnabled)
                // 19:2x：全画幅是模式开关（换一只网格），不是叠加在画面圆上的旋钮。
                "fisheyeFullFrame" -> current.copy(fisheyeFullFrame = !current.fisheyeFullFrame)
                else -> current
            }
        }
        uiState = uiState.copy(settings = next)
    }

    fun onPlaybackState(state: PlaybackState) {
        // 播放状态回调很频繁；只有在真的报错时才覆盖提示，
        // 否则 showMessage 的短反馈会在下一帧被 null 抹掉。
        val error = state.error?.takeIf { it.isNotBlank() }
        uiState = uiState.copy(
            playing = state.playing,
            // 19:2x：意图 / 缓冲 / 缓冲进度分开存，控制台按各自语义用。
            wantPlay = state.playWhenReady,
            buffering = state.buffering,
            bufferedMs = state.bufferedMs,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            message = error ?: uiState.message,
            messageUntilMs = if (error != null) 0L else uiState.messageUntilMs,
            // 2.1.0：错误卡第二行跟第一行走同一条「有错才覆盖、否则保留」的规则，
            // 否则下一帧的 emitState(null) 会把建议抹掉，只剩一句没有出路的结论。
            errorHint = if (error != null) state.errorHint else uiState.errorHint
        )
        // V2.39：第一次真的播起来时问一次方向。放在这里而不是进 VR 时，
        // 是因为「抬头看到什么」必须有画面才能判断。
    }

    /**
     * 一次「真操作」刷新时间戳并恢复控制台。
     *
     * V2.14 两个修正：
     * 1. 原来只有「距上次活动超过 1 秒」才更新时间戳。连按时每次间隔都不到 1 秒，
     *    时间戳一直停在第一次按键上，8 秒一到控制台照样消失——正是用户说的
     *    「还没来得及操作就没了」。现在无条件刷新。
     * 2. 头动不再算「操作」。准星落点抖动 8px 就调这里，而手机塞在纸盒里一直在抖，
     *    结果控制台永远等不到 8 秒静默，永远挡在画面上。
     */
    fun touch() {
        val tuning = uiState.ipdTuning
        // V2.38：倒计时期间按任何键都不许把控制台点亮。那一刻用户已经收起了控制台、
        // 正对着想定的方向——一按就弹出一整块面板，等于当场把目标盖住。
        val counting = uiState.alignCountdown != null
        uiState = uiState.copy(
            // B13：调瞳距时控制台不许被任何动作点亮，否则整块面板又把视频盖住。
            consoleVisible = if (tuning || counting) false else true,
            lastActivityMs = SystemClock.elapsedRealtime()
        )
    }

    /** 控制台已经显示时，把淡出计时推后；没显示 / 调瞳距时就不动它。 */
    private fun keepConsoleAlive() {
        val state = uiState
        if (state.consoleVisible && !state.ipdTuning) {
            uiState = state.copy(lastActivityMs = SystemClock.elapsedRealtime())
        }
    }

    /**
     * B14：调节条的淡出是**交互感知**的，不是从激活那一刻起算的固定倒计时。
     *
     * 对齐 AOSP `VolumeDialogImpl`：hover 期间 16000ms、离开回落到 3000ms 量级；
     * 每次真实调节在 [showAdjust] 里重排一次。这里额外保证 hover 期间不会
     * 因为「一次长停留超过了 16 秒」而到期——每秒续一次，最多每帧一次 state copy。
     *
     * @param hoverId 当前准星压着的原始目标 id；只有 `adj*` 算「停在条上」。
     */
    fun tick(hoverId: String? = null) {
        val now = SystemClock.elapsedRealtime()
        val state = uiState
        if (state.message != null && state.messageUntilMs > 0L && now >= state.messageUntilMs) {
            uiState = state.copy(message = null, messageUntilMs = 0L)
        }

        val feedback = uiState.feedback
        if (feedback != null && !uiState.ipdTuning) {
            val hovering = hoverId?.startsWith("adj") == true
            val until = when {
                hovering && !adjustHovered -> now + ADJUST_HOVER_MS  // 刚进入 hover
                !hovering && adjustHovered -> now + FEEDBACK_MS      // 刚离开 hover
                hovering && feedback.untilMs - now < ADJUST_HOVER_MS - 1_000L ->
                    now + ADJUST_HOVER_MS                           // 持续 hover，每秒续一次
                else -> feedback.untilMs
            }
            adjustHovered = hovering
            if (until != feedback.untilMs) {
                uiState = uiState.copy(feedback = uiState.feedback?.copy(untilMs = until))
            }
            if (now >= (uiState.feedback?.untilMs ?: 0L)) {
                uiState = uiState.copy(feedback = null)
                adjustHovered = false
            }
        } else if (feedback == null) {
            adjustHovered = false
        }

        val current = uiState
        if (!current.ipdTuning &&
            current.screen == VrScreen.PLAYER && current.overlay == VrOverlay.NONE &&
            current.consoleVisible && current.settings.autoFade &&
            now - current.lastActivityMs > current.settings.consoleTimeoutMs
        ) {
            uiState = current.copy(consoleVisible = false)
        }
    }

    /** V2.4：短提示默认 2.4 秒后自动消失，不再常驻钉在画面底部。 */
    fun showMessage(message: String, durationMs: Long = 2_400L) {
        uiState = uiState.copy(
            message = message,
            messageUntilMs = SystemClock.elapsedRealtime() + durationMs
        )
    }

    /**
     * V2.46 / P0：临时离开前台（来电 / 锁屏 / 切后台 / 下拉通知栏）。
     *
     * 原来 `onPause()` 只调 `disarmGaze()`，播放照常继续：声音在后台外放、
     * 自动连播还在跑，回来后进度已经丢掉几十分钟。更糟的是 `disarmGaze` 把
     * `gazeArmed` 置 false，`VrUiLayer` 会铺一张全屏「戴稳了？」把正在看的片
     * 和进度全盖住，还要重新按触发键——文案和「刚才在看片」完全不符。
     *
     * 现在：暂停 + 落盘断点，但**不锁凝视**。准星进度由 `GazeDetector.reset()`
     * 在 `onResume` 里清掉并走 1 秒静默，这已经足够防误触，不需要整屏遮罩。
     */
    fun onLeaveForeground() {
        if (uiState.alignCountdown != null) {
            uiState = uiState.copy(alignCountdown = null)
            alignStartPose = null
            alignLastPose = null
            alignSteadyMs = 0L
            RunLog.append("posture", "重定倒计时作废（离开 VR）")
        }
        if (uiState.screen == VrScreen.PLAYER) {
            // 19:2x：看「意图」不看 `isPlaying` —— 缓冲中 isPlaying 也是 false，
            // 旧写法会放过一次暂停，用户回到前台时片子自己又放起来。
            if (uiState.wantPlay) player.pause()
            player.saveProgress()
            RunLog.append("ui", "离开前台 · 已暂停并保存断点")
        }
    }

    /** V2.46 / P0：回到前台。控制台叫回来，并用一条可见短提示说明已暂停。 */
    fun onReturnForeground() {
        uiState = uiState.copy(
            consoleVisible = true,
            lastActivityMs = SystemClock.elapsedRealtime()
        )
        // 常驻播放错误（messageUntilMs == 0）比「已暂停」重要，别用短提示盖掉它。
        val hasError = uiState.message != null && uiState.messageUntilMs == 0L
        if (!hasError && uiState.screen == VrScreen.PLAYER &&
            !uiState.wantPlay && uiState.gazeArmed
        ) {
            showMessage("已为你暂停 · 凝视「播放」继续")
        }
    }

    private fun buildRef(item: MediaItemEntity): MediaRef? {
        val source = sources[item.sourceId]
            ?: return MediaRef(item.sourceId, SourceType.LOCAL, path = item.path, mediaId = item.id)
        val type = runCatching { SourceType.valueOf(source.type) }.getOrDefault(SourceType.LOCAL)
        return when (type) {
            SourceType.SMB -> {
                val (host, share) = parseUnc(source.address)
                MediaRef(
                    sourceId = item.sourceId,
                    type = SourceType.SMB,
                    host = host,
                    share = share,
                    path = item.path,
                    credentialRef = source.credentialRef,
                    mediaId = item.id
                )
            }
            // 阶段二：网络源把 base URL 放 host、可直接访问的完整 URL 放 path，
            // HttpRandomAccessSource.networkUrl() 会直接使用后者。
            SourceType.WEBDAV, SourceType.HTTP, SourceType.DLNA -> MediaRef(
                sourceId = item.sourceId,
                type = type,
                host = source.address,
                path = item.path,
                credentialRef = source.credentialRef,
                mediaId = item.id
            )
            else -> MediaRef(item.sourceId, SourceType.LOCAL, path = item.path, mediaId = item.id)
        }
    }
}
