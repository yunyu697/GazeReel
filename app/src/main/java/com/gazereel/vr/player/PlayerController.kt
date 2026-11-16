@file:Suppress("UnsafeOptInUsageError")

package com.gazereel.vr.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.data.LibraryRepository
import com.gazereel.vr.data.MediaRefRegistry
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.data.SmbRandomAccessSource
import com.gazereel.vr.data.UnifiedDataSourceFactory
import com.gazereel.vr.data.db.MediaItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.gazereel.vr.data.db.mediaKeyOf

/**
 * PRD 5.2 播放启动链路：
 * MediaRef → 自定义 DataSource → ProgressiveMediaSource → ExoPlayer → SurfaceTexture。
 * 播放结束时由 onEnded 回调交给 UI 层做目录内自动连播（复用共享会话）。
 */
class PlayerController(
    context: Context,
    private val dataSourceFactory: UnifiedDataSourceFactory,
    private val library: LibraryRepository,
    private val onStateChanged: (PlaybackState) -> Unit
) {
    private val appContext = context.applicationContext

    /**
     * 2.1.0：**记录设备最终挑了哪个解码器**。
     *
     * 4001 只说「解码器初始化失败」，但一台机器上同一个编码往往有好几个解码器
     * （硬解 `c2.qti.*` / 软解 `c2.android.*` / 厂商私有），失败的是哪一个、
     * 还有没有备选，日志里完全没有。这个选择器把 Media3 的候选链原样抄一份到
     * `RunLog`，出事时能直接看出「只剩一个解码器，且它不支持 5400×2700」。
     *
     * 它**不改变选择结果**：先问 `MediaCodecSelector.DEFAULT`，再把它的答案原样返回。
     * 选择逻辑仍归 Media3，我们只旁听。
     */
    private val recordingSelector = object : MediaCodecSelector {
        private val loggedCodecChains = java.util.Collections.synchronizedSet(HashSet<String>())

        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            // 2.1.0-alpha3：同一组候选一次播放里会被问 4~6 次（每个渲染器/每条轨都问），
            // 真机日志里就是 6 行一模一样的 `候选 …`，把真正有用的东西挤没了。
            // 按「mime + 候选链」去重，一次会话只记一行。
            val key = mimeType + "|" + infos.joinToString(",") { it.name }
            if (loggedCodecChains.add(key)) {
                RunLog.append(
                    appContext, "codec",
                    "$mimeType 候选 " + infos.joinToString(", ") { it.name }.ifEmpty { "（无）" }
                )
            }
            return infos
        }
    }

    /**
     * 2.1.0：`setEnableDecoderFallback(true)` 是 4001 唯一能自救的一步。
     *
     * 默认值是 false —— Media3 只试候选链里的第一个解码器，`configure()` 一抛
     * 异常就整体失败（真机日志：`code=4001` → `state=IDLE`）。打开之后它会依次
     * 尝试后面的解码器，厂商机上「主解码器不支持、软解支持」这类组合才可能播出来。
     *
     * 它救不了「所有解码器都不支持 5400×2700 VP9」——那种情况只能靠
     * [DecoderCaps] 把原因说清楚，换片源。不要把它当成万能降级。
     */
    private val renderersFactory = DefaultRenderersFactory(appContext)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector(recordingSelector)

    // 只显式调缓冲策略：不设 Surface 链路 → 仍是 MediaCodec 硬解。
    // 网络片源（SMB/WebDAV/HTTP）抖动大：min/max 缓冲调大做 cushion，起播门槛保持低值，
    // prioritizeTimeOverSizeThresholds 让高码率 4K 不被字节上限提前掐断，backBuffer 便于回退 seek。
    private val player: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                // V2.35：改成**按字节封顶**。
                //
                // 旧版是 prioritizeTimeOverSizeThresholds(true) + 90 秒上限：等于告诉
                // 播放器「不用管内存，堆满 90 秒再说」。8K 片源 90 秒就是 1GB 数量级，
                // 真机 OOM 的日志里堆已经顶到 growth limit（256MB），SMB 那次 1MB 的
                // 读响应成了最后一根稻草。现在字节上限 96MB 优先，时间上限只是兜底；
                // 4K / 8K 都能垫 5–10 秒，足够吃掉 SMB 的抖动，又不会把内存吃到爆。
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(BACK_BUFFER_MS, true)
                .build()
        )
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val ticker = object : Runnable {
        override fun run() {
            // 位置真的开始走，才说明解码出帧了——比「首帧回调」更贴近用户看到的画面，
            // 也跨版本稳定。只记一次，用来判断崩溃发生在起播前还是起播后。
            if (!loggedFirstFrame && player.isPlaying && player.currentPosition > 200L) {
                loggedFirstFrame = true
                RunLog.append(
                    appContext, "play",
                    "开始出帧 pos=${player.currentPosition}ms"
                )
            }
            logMemoryIfDue()
            emitState()
            handler.postDelayed(this, 500L)
        }
    }

    /**
     * V2.35：每 10 秒记一行内存。
     *
     * 上一次 OOM 只能看到「1MB 分配失败、堆顶到 256MB」，却不知道是解码缓冲、
     * SMB 缓存还是别的东西在长。这一行把三者摆在一起（Java 堆 / SMB 缓存 / 活着的源数），
     * 下次再崩就能直接看出是谁涨的，不用再靠猜。
     */
    private var lastMemLogMs = 0L
    private fun logMemoryIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastMemLogMs < 10_000L) return
        lastMemLogMs = now
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMb = runtime.maxMemory() / (1024L * 1024L)
        val smbCacheMb = SmbRandomAccessSource.cachedBytes.get() / (1024L * 1024L)
        RunLog.append(
            appContext, "mem",
            "堆 ${usedMb}/${maxMb}MB · SMB 缓存 ${smbCacheMb}MB · 活源 " +
                "${SmbRandomAccessSource.liveSources.get()} · 缓冲 " +
                "${player.bufferedPercentage}%"
        )
    }

    private var loggedFirstFrame = false

    private var currentItem: MediaItemEntity? = null

    /** 播完时把当前 mediaId 交给 UI 决定是否自动连播。 */
    var onEnded: ((Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                RunLog.append(appContext, "play", "state=${stateName(playbackState)}")
                if (playbackState == Player.STATE_ENDED) handleEnded()
                emitState()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 2.1.0-alpha3：**格式从异常本身拿，不再问 player.currentTracks**。
                //
                // alpha1/alpha2 读 `currentTracks`，真机上拿到的是空：日志里
                // open → BUFFERING → ERROR 只隔 300ms，错误派发时播放器已经滑到
                // IDLE、轨道表被清掉，于是 `诊断` 那一行根本没打出来，
                // 用户看到的仍是「换 4K 以内」那句兜底话 —— 费力写的判据一次没用上。
                //
                // `ExoPlaybackException.rendererFormat` 就是**失败的那条 Format 本体**
                // （下面日志里 `[5400, 2700, 59.94006]` 就是它），连平台的判定
                // `rendererFormatSupport` 一起给了；`onTracksChanged` 暂存的那份做第二来源。
                val exo = error as? ExoPlaybackException
                val track = exo?.rendererFormat?.let { trackOf(it) } ?: lastVideoTrack
                val verdict = track?.let {
                    DecoderCaps.diagnose(it.mime, it.width, it.height, it.fps)
                }
                val label = track?.label ?: "这条视频轨"
                // 4001（解码器初始化失败）在能力超限时也会出现：`configure()` 被拒 →
                // Media3 报 init failed。但**平台其实已经说了原因**（EXCEEDS），
                // 所以标题按平台的判定走，别让用户以为是解码器坏了。
                val exceeds = exo?.rendererFormatSupport == C.FORMAT_EXCEEDS_CAPABILITIES
                val (message, hint) = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                        (if (exceeds) "画面超出本机解码上限 · $label"
                        else "解码器启动失败 · $label") to (verdict?.hint
                            ?: "设备的这个解码器起不来；换分辨率更小的版本，或转 H.265")
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                        "找不到能解 $label 的解码器" to (verdict?.hint
                            ?: "本机没有这个编码的解码器；用 H.264 / H.265 重压一遍")
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                        "画面超出本机解码上限 · $label" to (verdict?.hint
                            ?: "换分辨率更小的版本，或转成 H.264 / H.265")
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        "本机不支持这个编码 · $label" to (verdict?.hint
                            ?: "常见于 10bit / VP9 Profile 2；用 H.264 / H.265 重压一遍")
                    PlaybackException.ERROR_CODE_DECODING_FAILED ->
                        "解码中断 · $label" to "文件可能缺片段或码率过高；换一个版本试试"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "共享已断开，正在重试" to "检查共享是否在线、账号是否过期；也可以回片库重连"
                    else -> (error.message ?: "播放失败") to null
                }
                RunLog.append(
                    appContext, "play",
                    "ERROR code=${error.errorCode} " +
                        "formatSupport=${supportName(exo?.rendererFormatSupport)} " +
                        "${error.message?.take(160)}"
                )
                // 这一行把「此片规格 / 平台怎么判 / 用的哪个解码器 / 它上限多少」
                // 钉在同一行 —— 下一次不用再让用户跑 dumpsys。
                verdict?.let { RunLog.append(appContext, "play", "诊断 ${it.logLine}") }
                // 2.1.0-alpha2：**不再写 CrashLog**。
                // ExoPlaybackException 是被 onPlayerError 捕获的播放错误，不是应用崩溃；
                // 写进 CrashLog 会让日志页把它标成「— 上次崩溃 —」，用户以为应用不稳定，
                // 而且真正让进程死掉的记录会被这条挤掉。播放错误留在 RunLog 就够。
                onError?.invoke(message)
                emitState(error = message, hint = hint)
            }

            override fun onTracksChanged(tracks: Tracks) {
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until group.length) {
                        val format = runCatching { group.getTrackFormat(i) }.getOrNull() ?: continue
                        trackOf(format)?.let { lastVideoTrack = it }
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                RunLog.append(
                    appContext, "play",
                    "尺寸 ${videoSize.width}x${videoSize.height} " +
                        "DAR ${videoSize.pixelWidthHeightRatio}"
                )
            }
        })
        handler.post(ticker)
        // 2.1.0：把本机硬解上限记一行。4001 的判读全靠它 —— 日志里既有
        // 「片子 5400×2700」，也有「本机 VP9 上限 4096×2176」，不用再让用户
        // 去跑 dumpsys。放 IO 线程：枚举 MediaCodecList 有几十毫秒开销，
        // 不该压在进 VR 的那一帧上。
        ioScope.launch {
            RunLog.append(appContext, "codec", "硬解能力 " + DecoderCaps.summary())
        }
    }

    /**
     * 2.1.0：一条视频轨的编码 / 尺寸 / 帧率。
     *
     * 为什么不用 `onVideoSizeChanged`：4001 发生在解码器 `configure()` 就失败，
     * 那一枪根本不会回调，`VideoSize` 永远是 0（真机日志里就是 `尺寸 0x0`）。
     */
    private data class VideoTrack(
        val mime: String?,
        val width: Int,
        val height: Int,
        val fps: Float,
        val label: String
    )

    /**
     * 2.1.0-alpha3：**轨道表还在的时候**先存一份。
     *
     * `onPlayerError` 触发时播放器可能已经回到 IDLE、`currentTracks` 被清空，
     * 所以不能等到出错再读。轨道回调给的 Format 与异常里的 `rendererFormat`
     * 是同一个对象，这里只是抢先留档。
     */
    private var lastVideoTrack: VideoTrack? = null

    private fun trackOf(format: Format): VideoTrack? {
        if (format.width <= 0 || format.height <= 0) return null
        val fps = if (format.frameRate > 0f) format.frameRate else -1f
        val label = buildString {
            append(codecLabel(format.codecs)).append(' ')
            append(format.width).append('×').append(format.height)
            if (fps > 0f) append('@').append(fps.roundToInt())
        }
        // `sampleMimeType` 是解析器直接给的 MIME，比从 `codecs` 前缀猜准；
        // 老容器里它可能为空，才退回按前缀映射。
        return VideoTrack(
            format.sampleMimeType ?: mimeOf(format.codecs),
            format.width, format.height, fps, label
        )
    }

    /** 平台对这条轨的判定，进日志用。 */
    private fun supportName(support: Int?): String = when (support) {
        C.FORMAT_EXCEEDS_CAPABILITIES -> "EXCEEDS"
        C.FORMAT_UNSUPPORTED_SUBTYPE -> "SUBTYPE"
        C.FORMAT_UNSUPPORTED_TYPE -> "TYPE"
        C.FORMAT_UNSUPPORTED_DRM -> "DRM"
        C.FORMAT_HANDLED -> "OK"
        else -> "?"
    }

    /** RFC 6381 的 `codecs` 前缀 → 给人看的编码名。 */
    private fun codecLabel(codecs: String?): String = when {
        codecs == null -> "编码未知"
        codecs.startsWith("avc") -> "H.264"
        codecs.startsWith("hvc") || codecs.startsWith("hev") -> "H.265"
        codecs.startsWith("vp09") || codecs.startsWith("vp9") -> "VP9"
        codecs.startsWith("av01") -> "AV1"
        else -> codecs.substringBefore('.')
    }

    /** 同一前缀 → 能力查询用的 MIME。查不到对应 MIME 时返回 null，诊断降级为「尺寸未知」。 */
    private fun mimeOf(codecs: String?): String? = when {
        codecs == null -> null
        codecs.startsWith("avc") -> "video/avc"
        codecs.startsWith("hvc") || codecs.startsWith("hev") -> "video/hevc"
        codecs.startsWith("vp09") || codecs.startsWith("vp9") -> "video/x-vnd.on2.vp9"
        codecs.startsWith("av01") -> "video/av01"
        else -> null
    }

    /**
     * V2.7.8：区分「真的播完」和「片源提前读到头」。
     *
     * 旧实现在 STATE_ENDED 里无条件自动连播，于是片源一断，用户看到的是
     * 「播几秒就跳到下一个」，日志里也没有崩溃可看——最难受的一种失败：
     * 出了问题，但产品装作没事发生。
     * 现在：只有位置真的走到接近结尾才连播；提前结束一律停下来并给出原因。
     */
    private fun handleEnded() {
        val item = currentItem ?: return
        val duration = player.duration
        val position = player.currentPosition
        val normalEnd = duration == C.TIME_UNSET || duration <= 0L ||
            position >= duration - 3_000L
        if (normalEnd) {
            onEnded?.invoke(item.id)
            return
        }
        val detail = "提前结束 pos=${position}ms dur=${duration}ms 已拦下自动连播"
        RunLog.append(appContext, "play", detail)
        // 同上：片源提前读到尾巴是**被处理掉**的情况，不是崩溃，别占用「上次崩溃」那一格。
        val message = "这个文件只读到 ${position / 1000} 秒就断了，共享可能中断，回片库重试一次"
        onError?.invoke(message)
        emitState(error = message)
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    fun setSurface(surface: Surface) {
        onMain { player.setVideoSurface(surface) }
    }

    /**
     * V2.7.7：ExoPlayer 只能在创建它的线程（这里就是主线程）上访问，
     * 否则直接抛 IllegalStateException 把进程带走。
     *
     * 但凝视、触发键、手势都跑在 GL 线程上（VrRenderer.onDrawFrame → ActionDispatcher），
     * 于是 handle() 里的 togglePlay / pause / saveProgress 全是跨线程调用。
     * 真机已经因此崩过一次（saveProgress 在 Dispatchers.IO 里读 currentPosition）。
     * 与其在每个调用点记得切线程，不如把「必须回主线程」这条规则收在播放器这一层。
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    fun open(item: MediaItemEntity, ref: MediaRef, startPositionMs: Long = 0L) {
        onMain {
            currentItem = item
            loggedFirstFrame = false
            // 换片时清掉上一部的轨道留档，否则「打不开的那部」会拿上一部的格式去诊断。
            lastVideoTrack = null
            RunLog.append(
                appContext, "play",
                "open ${ref.type} ${item.title.take(60)} " +
                    "path=${item.path.takeLast(60)} ${item.width}x${item.height} " +
                    "from=${startPositionMs}ms"
            )
            val uri = MediaRefRegistry.register(ref)
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.Builder().setUri(uri).build())
            player.stop()
            player.setMediaSource(mediaSource)
            player.prepare()
            if (startPositionMs > 0L) player.seekTo(startPositionMs)
            player.playWhenReady = true
            emitState()
        }
    }

    fun togglePlay() {
        onMain {
            player.playWhenReady = !player.playWhenReady
            emitState()
        }
    }

    fun pause() {
        onMain {
            player.playWhenReady = false
            emitState()
        }
    }

    fun resume() {
        onMain {
            player.playWhenReady = true
            emitState()
        }
    }

    fun seekToFraction(fraction: Float) {
        onMain {
            val duration = player.duration
            if (duration == C.TIME_UNSET || duration <= 0L) return@onMain
            player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
            emitState()
        }
    }

    fun seekBy(seconds: Int) {
        onMain {
            val target = (player.currentPosition + seconds * 1_000L).coerceAtLeast(0L)
            player.seekTo(target)
            emitState()
        }
    }

    fun setVolume(percent: Int) {
        onMain { player.volume = percent.coerceIn(0, 100) / 100f }
    }

    val positionMs: Long get() = player.currentPosition.coerceAtLeast(0L)
    val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
    val mediaId: Long get() = currentItem?.id ?: 0L
    val isPlaying: Boolean get() = player.isPlaying

    /**
     * 断点续播：位置必须在主线程读出来再交给 IO 去写盘。
     * 旧实现在 Dispatchers.IO 里读 currentPosition —— 就是这次闪退的现场。
     */
    fun saveProgress() {
        onMain {
            val item = currentItem ?: return@onMain
            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            ioScope.launch { library.saveProgress(mediaKeyOf(item), position, duration) }
        }
    }

    fun release() {
        onMain {
            handler.removeCallbacks(ticker)
            player.setVideoSurface(null)
            player.release()
        }
    }

    private fun emitState(error: String? = null, hint: String? = null) {
        onStateChanged(
            PlaybackState(
                mediaId = currentItem?.id ?: 0L,
                playing = player.isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                // 19:2x：缓冲中 `isPlaying` 是 false，光看它会把「等数据」当成「暂停」。
                playWhenReady = player.playWhenReady,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                error = error,
                // 2.1.0：错误卡第二行。只在报错那一次带值，其余帧为 null ——
                // 消费端（VrCoordinator）沿用「有错就保留」的口径，见那边的注释。
                errorHint = hint
            )
        )
    }

    companion object {
        // Media3 1.5.1 对非本地 scheme（本工程是 vrplayer://，按 streaming 处理）的默认值：
        // min/max=50s、起播=1s、rebuffer 后=2s、backBuffer=0、prioritizeTime=false。
        //
        // V2.35：**回到按字节封顶**。上一版为了治卡顿把时间阈值拉到 60/90 秒并打开
        // prioritizeTimeOverSizeThresholds(true)，等于取消字节上限——8K 片源会把
        // 90 秒的画面往内存里堆，真机直接 OOM。现在 96MB 字节上限优先，时间值只兜底。
        private const val MIN_BUFFER_MS = 15_000                        // 15s 常驻 cushion
        private const val MAX_BUFFER_MS = 30_000                        // 30s 上限（字节先到就停）
        private const val BUFFER_FOR_PLAYBACK_MS = 1_000                // 1s 低值快速起播
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000 // 卡顿后垫 4s 再恢复
        private const val BACK_BUFFER_MS = 10_000                       // 回退 seek 不重连
        private const val TARGET_BUFFER_BYTES = 96 * 1024 * 1024        // 96MB 硬顶
    }
}
