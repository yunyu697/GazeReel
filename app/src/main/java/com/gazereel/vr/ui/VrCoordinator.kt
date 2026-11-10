package com.gazereel.vr.ui

import android.os.SystemClock
import com.gazereel.vr.core.InputChannel
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.SourceType
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
    }

    var sources: Map<Long, SourceEntity> = emptyMap()
    private var pendingOpen: Triple<MediaItemEntity, MediaRef, com.gazereel.vr.core.VrFormat>? = null

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
        val visible = enabledSourceIds?.let { ids -> items.filter { it.sourceId in ids } } ?: items
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
            selectedIndex = index.coerceIn(0, maxOf(0, visible.size - 1))
        )
        // 页码要按「这一屏现在有几个格子」收敛：成叠之后页数会少一大截。
        uiState = next.copy(page = uiState.page.coerceIn(0, next.pageCount - 1))
    }

    /** 阶段二 FR-10：片库卡片据此显示「上次看到 mm:ss」。 */
    fun loadProgress(all: List<PlayProgressEntity>) {
        uiState = uiState.copy(progress = all.associateBy { it.mediaId })
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
        if (cached != null) return LibraryRepository.FormatResolution(cached, false)

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
            val resume = withContext(Dispatchers.IO) { library.resumePosition(item.id) }
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
        val ref = pendingOpen?.second
        if (ref != null) {
            scope.launch(Dispatchers.IO) { library.rememberFormat(ref, format) }
        }
        uiState = uiState.copy(format = format, overlay = VrOverlay.NONE)
        // 仅「首次选片」需要继续启动播放；播放中改格式不重开片源。
        if (pendingOpen != null) startPlayback()
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
        RunLog.append("ui", "进入叠 ${stack.label}（${stack.items.size} 部）")
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
