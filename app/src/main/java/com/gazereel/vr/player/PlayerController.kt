@file:Suppress("UnsafeOptInUsageError")

package com.gazereel.vr.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.data.CrashLog
import com.gazereel.vr.data.LibraryRepository
import com.gazereel.vr.data.MediaRefRegistry
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.data.UnifiedDataSourceFactory
import com.gazereel.vr.data.db.MediaItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val player: ExoPlayer = ExoPlayer.Builder(appContext).build()
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
            emitState()
            handler.postDelayed(this, 500L)
        }
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
                val message = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        "此编码当前设备不支持"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "共享已断开，正在重试"
                    else -> error.message ?: "播放失败"
                }
                RunLog.append(
                    appContext, "play",
                    "ERROR code=${error.errorCode} ${error.message?.take(160)}"
                )
                CrashLog.write(appContext, error, "playback")
                onError?.invoke(message)
                emitState(error = message)
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
        CrashLog.write(
            appContext,
            IllegalStateException(detail),
            "playback-ended"
        )
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

    private fun emitState(error: String? = null) {
        onStateChanged(
            PlaybackState(
                mediaId = currentItem?.id ?: 0L,
                playing = player.isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                error = error
            )
        )
    }
}
