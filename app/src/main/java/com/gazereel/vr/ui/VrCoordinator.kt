package com.gazereel.vr.ui

import android.os.SystemClock
import com.gazereel.vr.core.FormatDetector
import com.gazereel.vr.core.InputChannel
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.SourceType
import com.gazereel.vr.core.StereoFrameJudge
import com.gazereel.vr.core.VrAction
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.data.FormatProber
import com.gazereel.vr.data.LibraryRepository
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.db.PlayProgressEntity
import com.gazereel.vr.data.db.SourceEntity
import com.gazereel.vr.data.parseUnc
import com.gazereel.vr.input.SettingsRepository
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

    private companion object {
        /**
         * 档位反馈的存活时间。
         *
         * 设计第 415 行写的是 1.2 秒，但真机反馈「还没来得及操作调节条就消失了」——
         * 1.2 秒够看清一个数，不够「看清 + 再按一下」。档位是连按的，
         * 每次按键都会把这个时间戳往后推，所以延长不会让它变成常驻浮层。
         */
        const val FEEDBACK_MS = 2_600L

        /**
         * 手动「正前方」低于这个偏移就直说「不用挪」。
         *
         * 2° 左右：再小的话挪过去也看不出来，弹一张卡反而像出故障了。
         */
        const val ALIGN_MIN_RAD = 0.035f
    }

    var sources: Map<Long, SourceEntity> = emptyMap()

    /** V2.19 / B4+B5：姿态闭环。它只发现「姿势变了」，动画面的是 Provider。 */
    private val posture = PostureTracker()

    /**
     * V2.19c：主动「挪正前方」时记下的方向（俯仰 + 偏航）。
     *
     * 非 null 表示这张确认卡是用户自己点出来的，不是检测到的——
     * 确认时不需要走 [PostureTracker.confirm]（没有候选姿态），只需要静默期。
     */
    private var manualPitch: Float? = null
    private var manualYaw = 0f
    private var manualUntilMs = 0L

    private fun degrees(rad: Float): Int = Math.toDegrees(rad.toDouble()).toInt()

    /**
     * V2.21：入盒引导页已经把「正前方」定好了，通知姿态闭环不要再自动定一次。
     *
     * 这条不改变任何朝向，只是把 [PostureTracker] 的首次基准标记为已建立。
     * 没有它，用户按下按键后的一两秒内会看到画面又转一下——看起来就像
     * 「重定向自己跑了」。40° 候选与手动挪正前方都不受影响。
     */
    fun onSeededBaseline() {
        posture.markBaselineFromSeed(SystemClock.elapsedRealtime())
        RunLog.append("posture", "基准来自入盒引导页 · 不再自动重定")
    }

    /** 每帧（约 60Hz）由 VrActivity 的 tick 推一次，跑在主线程，不碰 GL 线程。 */
    fun updatePosture(rotation: FloatArray, nowMs: Long) {
        // 手动卡片挂着的时候，**绝不能让自动检测覆盖它**：
        // 此刻 tracker 没有候选，下面的 copy 会把 postureCandidate 写回 null，
        // 卡片就只闪一帧。所以这里单独走一条分支，只负责倒计时。
        if (manualPitch != null) {
            val left = (manualUntilMs - nowMs).coerceAtLeast(0L)
            if (left == 0L) {
                dismissPosture()
            } else if (left != uiState.postureRemainingMs) {
                uiState = uiState.copy(postureRemainingMs = left)
            }
            return
        }

        val changed = posture.onPose(rotation, nowMs, allowBaseline = !uiState.gazeArmed)

        // ① 首次基准：进 VR 后**第一个稳定姿态就是正前方**，静默转过去，不问他。
        //
        // 这一条是 V2.19c 的核心。真实顺序是「先躺好、再塞手机」，而 Provider 的首次
        // 重定发生在第一帧传感器数据到达时——那一刻用户还在往盒子里塞手机，
        // 正前方被定成了一个随便的方向。等他躺好，画面就挂在天花板前面。
        // 与其事后问他「要不要挪」，不如把「躺好、不动」这个瞬间本身当作答案：
        // 那正是他舒服的姿势。
        posture.pendingBaseline?.let { pose ->
            headset.recenterTo(pose, PostureTracker.ANIM_MS)
            posture.baselineEstablished(nowMs)
            onRecenter()
            RunLog.append("posture", "首次基准 · 偏移 ${degrees(posture.offsetOf(pose))}° → 已定为你现在的姿势")
            return
        }

        val candidate = posture.candidate
        val yaw = posture.candidateYaw
        val remaining = if (candidate == null) 0L else posture.candidateRemainingMs(nowMs)
        if (changed || candidate != uiState.postureCandidate ||
            yaw != uiState.postureYaw || remaining != uiState.postureRemainingMs
        ) {
            // 只在候选出现 / 消失时记一行。真机若仍觉得「没反应」，
            // 日志里有没有这行就是「检测没跑」和「检测跑了但没到阈值」的分界。
            if (candidate != uiState.postureCandidate) {
                RunLog.append(
                    "posture",
                    if (candidate == null) "候选撤销" else
                        "候选出现 · 俯仰 ${degrees(candidate)}° 偏航 ${degrees(yaw)}°"
                )
            }
            uiState = uiState.copy(
                postureCandidate = candidate,
                postureYaw = yaw,
                postureRemainingMs = remaining
            )
        }
    }

    /**
     * V2.19b：用户主动点「正前方」。
     *
     * 自动检测覆盖不到「先躺好、再塞手机」——那一刻初始重定已经把正前方定成躺姿，
     * 之后永远不会有 40° 偏差。V2.19c 用「首次稳定姿态即基准」把这条补上了，
     * 但手动这条路仍然要留着：坐着看片时想微调、或者 40° 死区之内的偏移，
     * 只能靠用户自己说一句。
     */
    private fun askAlignForward() {
        val rotation = headset.headRotation()
        val offset = posture.offsetOf(rotation)
        val pitch = posture.pitchOf(rotation)
        val yaw = posture.yawOf(rotation)
        RunLog.append(
            "posture",
            "手动挪正前方 · 偏移 ${degrees(offset)}°" +
                "（俯仰 ${degrees(pitch)}° 偏航 ${degrees(yaw)}°）"
        )
        if (offset < ALIGN_MIN_RAD) {
            showMessage("已经正对着了 · 不用挪")
            return
        }
        manualPitch = pitch
        manualYaw = yaw
        manualUntilMs = SystemClock.elapsedRealtime() + PostureTracker.CANDIDATE_TTL_MS
        uiState = uiState.copy(
            postureCandidate = pitch,
            postureYaw = yaw,
            postureRemainingMs = PostureTracker.CANDIDATE_TTL_MS
        )
    }

    /**
     * 确认「把正前方挪到这里」：整个参考系 0.8 秒缓转过去。
     *
     * 为什么不是瞬跳：整个视界在一帧里平移几十度，是最典型的晕动触发源。
     * 为什么不是只挪 UI：那样视频留在原地，用户反而要仰头找画面。
     *
     * V2.19c：自动路径交给 [PostureTracker.confirm] 的**候选姿态**，不是「按下那一刻的
     * 朝向」——要按到卡片上的按钮，视线必然要移开几度，那几度不该写进新的正前方。
     */
    private fun confirmPosture() {
        val now = SystemClock.elapsedRealtime()
        val pose = if (manualPitch == null) {
            val confirmed = posture.confirm(now)
            if (confirmed == null) {
                showMessage("没有需要调整的姿势")
                return
            }
            confirmed
        } else {
            manualPitch = null
            posture.suppress(now)
            // null = 就用此刻的朝向（手动路径没有候选姿态可依）。
            null
        }
        headset.recenterTo(pose, PostureTracker.ANIM_MS)
        // 缓转期间必须把凝视停留与手势窗口清零——只暂停命中不清零，
        // 缓动结束首帧就会拿着缓动前的位置当场再触发一次。
        onRecenter()
        uiState = uiState.copy(postureCandidate = null, postureYaw = 0f, postureRemainingMs = 0L)
        showMessage("正前方已挪到你现在的姿势")
    }

    private fun dismissPosture() {
        manualPitch = null
        posture.dismiss(headset.headRotation())
        uiState = uiState.copy(postureCandidate = null, postureYaw = 0f, postureRemainingMs = 0L)
    }
    private var pendingOpen: Triple<MediaItemEntity, MediaRef, com.gazereel.vr.core.VrFormat>? = null

    /**
     * V2.20 / C1：这一次打开时保留的证据，供抽帧到位后重算一次。
     *
     * 抽帧在起播之后 ~10 帧才回来，那时候「文件名 + 宽高 + 容器」这套证据早就算过一遍了。
     * 把同样的输入补上抽帧结论再跑一次 [com.gazereel.vr.core.FormatDetector.detect]，
     * 比在几个分支里零散地改 layout 安全得多——判据只有一处，改也只改一处。
     */
    private var pendingProbe: com.gazereel.vr.core.SphericalMetadata.Probe? = null
    private var pendingItem: MediaItemEntity? = null

    /** 用户在这次播放里自己选过格式：抽帧不许覆盖他，他的话最硬。 */
    private var userPickedFormat = false

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
        rawItems = items
        // V2.17：隐藏的片库连它的片子一起不进片库页。索引与断点都还在库里，只是不显示。
        val allowed = enabledSourceIds?.let { ids -> items.filter { it.sourceId in ids } } ?: items

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
        touch()
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
            VrAction.AlignForward -> askAlignForward()
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
            is VrAction.SeekBy -> player.seekBy(action.seconds)
            is VrAction.SetFormat -> applyFormat(action.format)
            VrAction.OpenFormat -> uiState = uiState.copy(overlay = VrOverlay.FORMAT)
            VrAction.OpenSettings -> uiState = uiState.copy(overlay = VrOverlay.SETTINGS)
            VrAction.CloseOverlay -> uiState = uiState.copy(overlay = VrOverlay.NONE)
            VrAction.Back -> back()
            VrAction.Exit -> onExit()
            VrAction.ShowConsole -> uiState = uiState.copy(
                consoleVisible = true,
                lastActivityMs = SystemClock.elapsedRealtime()
            )
            VrAction.ArmGaze -> {
                // V2.9：戴稳之后的第一次触发键。这里只负责解锁凝视，
                // 不顺带确认任何目标——准星此刻压着的东西要重新「进入」才算数。
                uiState = uiState.copy(gazeArmed = true, consoleVisible = true)
                RunLog.append("ui", "凝视已上膛 via $channel")
                showMessage("已开始 · 凝视 ${settings.current.gazeDurationMs} 毫秒即确认", 2_200L)
            }
            VrAction.ResetForward -> {
                // V2.4：重定基准后清掉凝视停留与手势窗口，并给一条会消失的反馈。
                headset.recenter()
                // V2.19：基准归零了，候选与静默期也要一起清掉。
                posture.reset()
                uiState = uiState.copy(postureCandidate = null, postureYaw = 0f, postureRemainingMs = 0L)
                onRecenter()
                showMessage("正前方已重置")
            }
            VrAction.LockView -> toggleViewLock()
            is VrAction.Adjust -> adjust(action.key, action.delta)
            is VrAction.ToggleOption -> toggleOption(action.key)
            VrAction.ClearFormatMemory -> {
                scope.launch(Dispatchers.IO) { library.clearFormatMemory() }
                showMessage("格式记忆已清除")
            }
        }
    }

    private fun openSelected() {
        scope.launch {
            val item = uiState.selectedItem ?: return@launch
            val ref = buildRef(item) ?: return@launch
            userPickedFormat = false
            pendingItem = item
            val resolution = resolveFormatFor(item, ref)
            pendingOpen = Triple(item, ref, resolution.format)
            uiState = uiState.copy(
                suggestedFormat = resolution.format,
                format = resolution.format,
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
            // 命中记忆就不再探测，抽帧重算也没有输入可用——清掉上一次的残留证据。
            pendingProbe = null
            return LibraryRepository.FormatResolution(cached, false)
        }

        val probe = prober?.let {
            withContext(Dispatchers.IO) {
                runCatching { withTimeoutOrNull(8_000L) { it.probe(ref) } }.getOrNull()
            }
        }
        pendingProbe = probe
        return withContext(Dispatchers.IO) { library.resolveFormat(ref, item, probe) }
    }

    private fun startPlayback() {
        val pending = pendingOpen ?: return
        pendingOpen = null
        val (item, ref, format) = pending
        uiState = uiState.copy(
            screen = VrScreen.PLAYER,
            overlay = VrOverlay.NONE,
            format = format,
            suggestedFormat = format,
            currentMediaId = item.id,
            currentTitle = item.title,
            consoleVisible = true,
            subtitles = emptyList(),
            lastActivityMs = SystemClock.elapsedRealtime()
        )
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
        userPickedFormat = true
        val ref = pendingOpen?.second
        if (ref != null) {
            scope.launch(Dispatchers.IO) { library.rememberFormat(ref, format) }
        }
        uiState = uiState.copy(format = format, overlay = VrOverlay.NONE)
        // 仅「首次选片」需要继续启动播放；播放中改格式不重开片源。
        if (pendingOpen != null) startPlayback()
    }

    /**
     * V2.20 / C1：GL 线程抽到的布局结论（在 VrActivity 里切回主线程后调用）。
     *
     * 判据不在这里写第二遍：把抽帧结论作为又一条证据，重新跑同一个
     * [FormatDetector.detect]。这样「容器 > 抽帧 > 文件名」的优先级只有一处定义，
     * 以后调顺序不会两边打架。
     *
     * 与现有结论一致就什么都不做（绝大多数片子走这条），
     * 不一致才当场改：只换每眼采样区域，不重开片源、不打断播放。
     */
    fun onFrameVerdict(verdict: StereoFrameJudge.Verdict) {
        if (userPickedFormat) {
            RunLog.append("fmt", "抽帧结论 ${verdict.layout.name} 未采用：用户本片已手选")
            return
        }
        val item = pendingItem
            ?: uiState.items.firstOrNull { it.id == uiState.currentMediaId }
            ?: return
        val ref = buildRef(item) ?: return
        val w = pendingProbe?.width?.takeIf { it > 0 } ?: item.width
        val h = pendingProbe?.height?.takeIf { it > 0 } ?: item.height

        val redo = FormatDetector().detect(item.path, w, h, pendingProbe, verdict.evidence())
        if (redo.format == uiState.format) {
            RunLog.append("fmt", "抽帧与现有判据一致：${redo.format.label}")
            return
        }
        val before = uiState.format
        uiState = uiState.copy(
            format = redo.format,
            suggestedFormat = redo.format,
            overlay = VrOverlay.NONE
        )
        scope.launch(Dispatchers.IO) { runCatching { library.rememberFormat(ref, redo.format) } }
        RunLog.append("fmt", "抽帧校正 ${before.label} → ${redo.format.label}（${verdict.describe()}）")
        showMessage("已按画面判为 ${redo.format.label}", 2_600L)
    }

    /** 自动连播：不弹格式确认，直接套用识别结果并复用共享会话（PRD 5.2）。 */
    fun autoPlayNext(mediaId: Long) {
        if (!settings.current.autoNext) {
            uiState = uiState.copy(screen = VrScreen.LIBRARY, playing = false)
            return
        }
        scope.launch {
            val next = withContext(Dispatchers.IO) { library.nextOf(mediaId) } ?: return@launch
            val ref = buildRef(next) ?: return@launch
            val resolution = resolveFormatFor(next, ref)
            pendingOpen = Triple(next, ref, resolution.format)
            uiState = uiState.copy(
                selectedIndex = uiState.items.indexOfFirst { it.id == next.id }.coerceAtLeast(0),
                format = resolution.format,
                suggestedFormat = resolution.format,
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
            else -> uiState = state.copy(page = next)
        }
    }

    // ── V2.16 成叠（B1 的第一层）与「回到播放」 ─────────────────────────

    private fun openStack(key: String) {
        val stack = uiState.stacks.firstOrNull { it.key == key } ?: return
        uiState = uiState.copy(activeStack = key, page = 0, consoleVisible = true)
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
            consoleVisible = true,
            lastActivityMs = SystemClock.elapsedRealtime()
        )
        player.resume()
        RunLog.append("ui", "回到播放：${state.currentTitle}")
    }

    private fun back() {
        val state = uiState
        when {
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
                    consoleVisible = true,
                    selectedIndex = if (global >= 0) global else state.selectedIndex,
                    page = if (index >= 0) index / VrUiState.PAGE_SIZE else state.page
                )
            }
            // V2.18：柜子层按返回，退回片库，不要直接说「已在首页」。
            state.cabinetsOpen -> uiState = state.copy(cabinetsOpen = false)
            // V2.16：在某一叠里按返回，先退回「全部叠」，而不是直接说「已在首页」。
            state.activeStack != null -> closeStack()
            else -> showMessage("已在片库首页 · 凝视「退出」可返回配置")
        }
    }

    /**
     * V2.11：档位制调节 + 视野中央反馈。
     *
     * 设计第 392 / 415 行的口径：音量 5 档（实体侧键一次一档）、亮度 3 档、
     * 变化时在视野中央浮出大字与段条、1.2 秒淡出。
     *
     * 原来是 `±10%` 的连续值：音量要按十一次才从最小到最大，而且**视野里没有任何反馈**——
     * 戴镜后按音量键，用户看不到任何变化，只能怀疑按键坏了。
     */
    private fun adjust(key: String, delta: Int) {
        // 音量键走的是 InputChannel.SYSTEM，不经过 TriggerDispatcher，
        // 这里要自己把淡出计时推后，否则连按音量时控制台会中途消失。
        // 注意用 keepConsoleAlive 而不是 touch：按音量键不该把控制台叫出来，
        // 设计第 392 行要的是「档位在视野中央浮出来，不用低头进控制台」。
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
                uiState = uiState.copy(
                    settings = next,
                    feedback = AdjustFeedback(
                        label = "音量",
                        detail = "${level + 1} / ${steps.size}",
                        level = level + 1,
                        steps = steps.size,
                        untilMs = SystemClock.elapsedRealtime() + FEEDBACK_MS
                    )
                )
            }
            "brightness" -> {
                val steps = VrSettings.BRIGHTNESS_STEPS
                val level = (VrSettings.brightnessStep(settings.current.brightness) + delta)
                    .coerceIn(0, steps.lastIndex)
                val next = settings.update { it.copy(brightness = steps[level]) }
                uiState = uiState.copy(
                    settings = next,
                    feedback = AdjustFeedback(
                        label = "亮度",
                        detail = VrSettings.BRIGHTNESS_LABELS[level],
                        level = level + 1,
                        steps = steps.size,
                        untilMs = SystemClock.elapsedRealtime() + FEEDBACK_MS
                    )
                )
            }
            // IPD 与凝视时长不是「戴镜时频繁调节」的东西，保持连续微调。
            "ipd" -> {
                val next = settings.update {
                    it.copy(ipdMm = (it.ipdMm + delta * 2).coerceIn(50, 75))
                }
                uiState = uiState.copy(settings = next)
            }
            "gaze" -> {
                val next = settings.update {
                    it.copy(gazeDurationMs = (it.gazeDurationMs + delta * 200L).coerceIn(600L, 2_400L))
                }
                uiState = uiState.copy(settings = next)
            }
        }
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
                "gestures" -> current.copy(gesturesEnabled = !current.gesturesEnabled)
                "subtitles" -> current.copy(subtitlesEnabled = !current.subtitlesEnabled)
                "cursor" -> current.copy(cursorEnabled = !current.cursorEnabled)
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
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            message = error ?: uiState.message,
            messageUntilMs = if (error != null) 0L else uiState.messageUntilMs
        )
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
        uiState = uiState.copy(
            consoleVisible = true,
            lastActivityMs = SystemClock.elapsedRealtime()
        )
    }

    /** 控制台已经显示时，把淡出计时推后；没显示就不动它。 */
    private fun keepConsoleAlive() {
        val state = uiState
        if (state.consoleVisible) {
            uiState = state.copy(lastActivityMs = SystemClock.elapsedRealtime())
        }
    }

    fun tick() {
        val now = SystemClock.elapsedRealtime()
        val state = uiState
        if (state.message != null && state.messageUntilMs > 0L && now >= state.messageUntilMs) {
            uiState = state.copy(message = null, messageUntilMs = 0L)
        }
        // V2.11：档位反馈自己到期，不常驻。绘制层只在它存在时多画一层。
        val withFeedback = uiState
        val feedback = withFeedback.feedback
        if (feedback != null && now >= feedback.untilMs) {
            uiState = withFeedback.copy(feedback = null)
        }
        val current = uiState
        if (current.screen == VrScreen.PLAYER && current.overlay == VrOverlay.NONE &&
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
     * V2.9：重新把凝视锁上。离开 VR 时调用。
     *
     * 用户把手机从纸盒里拿出来再看一眼、或者切到别的应用，回来时姿态基准已经变了，
     * 而且一定伴随一次明显的移动。这时候如果凝视还开着，回来第一眼就可能把某张卡确认掉。
     * 所以宁可让他再按一次触发键，也不要在这种时刻自动触发任何动作。
     */
    fun disarmGaze() {
        if (uiState.gazeArmed) {
            uiState = uiState.copy(gazeArmed = false, consoleVisible = true)
            RunLog.append("ui", "凝视已锁定（离开 VR）")
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
