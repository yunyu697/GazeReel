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
    /** V2.7.6：播放前从容器读真实格式；为 null 时退回宽高比 + 文件名。 */
    private val prober: FormatProber? = null,
    private val onExit: () -> Unit,
    /** V2.4：重定正前方后由 VrActivity 清掉凝视 / 手势窗口，避免首帧误触发。 */
    private val onRecenter: () -> Unit = {}
) {
    @Volatile
    var uiState: VrUiState = VrUiState()
        private set

    var sources: Map<Long, SourceEntity> = emptyMap()
    private var pendingOpen: Triple<MediaItemEntity, MediaRef, com.gazereel.vr.core.VrFormat>? = null

    init {
        // 上次退出时若锁定视角，重建后仍保持一致。
        headset.setViewLocked(settings.current.lockView)
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
        val keepId = uiState.selectedItem?.id
        val index = items.indexOfFirst { it.id == keepId }
            .takeIf { it >= 0 }
            ?: 0
        val pageCount = ((items.size + VrUiState.PAGE_SIZE - 1) / VrUiState.PAGE_SIZE)
            .coerceAtLeast(1)
        uiState = uiState.copy(
            items = items,
            selectedIndex = index.coerceIn(0, maxOf(0, items.size - 1)),
            page = uiState.page.coerceIn(0, pageCount - 1)
        )
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
            VrAction.ShowConsole -> uiState = uiState.copy(consoleVisible = true)
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

    private fun back() {
        val state = uiState
        when {
            state.overlay != VrOverlay.NONE -> uiState = state.copy(overlay = VrOverlay.NONE)
            state.screen == VrScreen.PLAYER -> {
                player.saveProgress()
                player.pause()
                // V2.8：回到正在看的这一部所在的页，而不是第 1 页。
                // 「播放中按片库」十有八九是接着翻同一批，弹回第一页等于让人重新翻一遍。
                val index = state.items.indexOfFirst { it.id == state.currentMediaId }
                uiState = state.copy(
                    screen = VrScreen.LIBRARY,
                    playing = false,
                    consoleVisible = true,
                    selectedIndex = if (index >= 0) index else state.selectedIndex,
                    page = if (index >= 0) index / VrUiState.PAGE_SIZE else state.page
                )
            }
            else -> showMessage("已在片库首页 · 凝视「退出」可返回配置")
        }
    }

    private fun adjust(key: String, delta: Int) {
        val next = settings.update { current ->
            when (key) {
                "brightness" -> current.copy(brightness = (current.brightness + delta * 10).coerceIn(10, 100))
                "volume" -> current.copy(volume = (current.volume + delta * 10).coerceIn(0, 100))
                "ipd" -> current.copy(ipdMm = (current.ipdMm + delta * 2).coerceIn(50, 75))
                "gaze" -> current.copy(
                    gazeDurationMs = (current.gazeDurationMs + delta * 200L).coerceIn(600L, 2_400L)
                )
                else -> current
            }
        }
        if (key == "volume") player.setVolume(next.volume)
        uiState = uiState.copy(settings = next)
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

    /** 任意输入通道的活动都刷新时间戳并恢复控制台。 */
    fun touch() {
        val now = SystemClock.elapsedRealtime()
        val state = uiState
        if (!state.consoleVisible || now - state.lastActivityMs > 1_000L) {
            uiState = state.copy(consoleVisible = true, lastActivityMs = now)
        }
    }

    fun tick() {
        val now = SystemClock.elapsedRealtime()
        val state = uiState
        if (state.message != null && state.messageUntilMs > 0L && now >= state.messageUntilMs) {
            uiState = state.copy(message = null, messageUntilMs = 0L)
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
