package com.gazereel.vr.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gazereel.vr.R
import com.gazereel.vr.core.ActionDispatcher
import com.gazereel.vr.core.InputChannel
import com.gazereel.vr.core.TriggerEvent
import com.gazereel.vr.core.TriggerKind
import com.gazereel.vr.core.VrAction
import com.gazereel.vr.core.VrFormat
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.data.CrashLog
import com.gazereel.vr.data.CredentialStore
import com.gazereel.vr.data.FormatProber
import com.gazereel.vr.data.LibraryRepository
import com.gazereel.vr.data.PosterRepository
import com.gazereel.vr.data.RandomAccessSourceFactory
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.data.SmbSessionPool
import com.gazereel.vr.data.UnifiedDataSourceFactory
import com.gazereel.vr.data.db.AppDatabase
import com.gazereel.vr.input.GazeDetector
import com.gazereel.vr.input.GestureRecognizer
import com.gazereel.vr.input.SettingsRepository
import com.gazereel.vr.input.TriggerDispatcher
import com.gazereel.vr.player.PlaybackService
import com.gazereel.vr.player.PlayerController
import com.gazereel.vr.player.SystemVolume
import com.gazereel.vr.player.SubtitleRepository
import com.gazereel.vr.render.SensorHeadsetProvider
import com.gazereel.vr.render.VrRenderer
import com.gazereel.vr.render.VrSurfaceView
import com.gazereel.vr.render.VrUiLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 戴镜后的唯一 Activity：GL 双屏视场 + 三条输入通道 + 前台串流服务。
 *
 * V2.7：进入 VR 是一串容易失败的初始化（数据库、GL、传感器、ExoPlayer、前台服务），
 * 任何一步抛异常，旧版都会直接闪退、用户什么都看不到。现在每一步都带阶段标记，
 * 失败时把原因写到日志并画到屏幕上——用户能看懂发生了什么，我也能据此定位。
 */
class VrActivity : AppCompatActivity() {

    private lateinit var coordinator: VrCoordinator
    private lateinit var gaze: GazeDetector
    private lateinit var trigger: TriggerDispatcher
    private lateinit var gesture: GestureRecognizer
    private lateinit var renderer: VrRenderer
    private lateinit var player: PlayerController
    private lateinit var headset: VrHeadsetProvider
    private lateinit var glView: VrSurfaceView

    private val handler = Handler(Looper.getMainLooper())
    private var downAt = 0L
    private val longPress = Runnable { injectTrigger(TriggerKind.LONG_PRESS) }

    /** 当前初始化阶段，崩溃时写进日志，用来定位是哪一步。 */
    private var bootStage = "start"

    /** 本次实际使用的头追实现，写进启动标记，供下次判断是否需要降级。 */
    private var bootMode = "pending"

    /**
     * 进入某个阶段：既更新屏幕上的失败说明，也刷新落盘标记。
     * 标记必须跟着阶段走，否则「崩在哪一步」这个信息就丢了，
     * 下次启动也就没法区分是 Cardboard native 崩还是我们自己的 bug。
     */
    private fun stage(name: String) {
        bootStage = name
        CrashLog.markBooting(this, bootMode, name)
        RunLog.append(this, "vr", "stage=$name mode=$bootMode")
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            // V2.19：姿态闭环跑在主线程，33ms 一次。缓动 0.8 秒 ≈ 24 帧，够顺。
            coordinator.updatePosture(headset.headRotation(), now)
            gaze.update(now)
            gesture.update(now)
            coordinator.tick()
            logUiIfChanged()
            handler.postDelayed(this, 33L)
        }
    }

    /** 只在界面/格式真的变化时记一行，避免每帧刷日志。 */
    private var loggedScreen: VrScreen? = null
    private var loggedOverlay: VrOverlay? = null
    private var loggedFormat: VrFormat? = null

    private fun logUiIfChanged() {
        val state = coordinator.uiState
        if (state.screen == loggedScreen && state.overlay == loggedOverlay &&
            state.format == loggedFormat
        ) {
            return
        }
        loggedScreen = state.screen
        loggedOverlay = state.overlay
        loggedFormat = state.format
        RunLog.append(
            this, "ui",
            "${state.screen}/${state.overlay} format=${state.format.label} " +
                "items=${state.items.size}"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        try {
            boot()
        } catch (t: Throwable) {
            showFailure(t)
        }
    }

    /**
     * V2.18：池子必须是字段，退出时要关。
     *
     * 真机反馈「取消勾选之后再扫描提示失败，要完全退出 APP 重开才能成功」。
     * 查下来是一次**连接泄漏**：这里原本是局部变量，`onDestroy` 够不着它，
     * 于是每进一次 VR 就在 NAS 上留下一条没关的 SMB 会话。
     * 进出几次之后 NAS 的并发连接被占满，配置页再扫描就连不上了——
     * 而重启 App 会把所有连接放掉，所以「重开就好了」。
     */
    private var pool: SmbSessionPool? = null

    private fun boot() {
        stage("database")
        val database = AppDatabase.get(this)
        val credentials = CredentialStore(this)
        val pool = SmbSessionPool().also { this.pool = it }
        val settings = SettingsRepository(this)
        val library = LibraryRepository(database)

        // V2.7.9：识别规则换代后，旧的格式记忆是有毒的。
        //
        // 19:23 那轮用户在格式确认里接受了「360 球面 Mono」，那条结论被写进覆盖表；
        // 之后无论探测到什么都不再采用，一律优先用覆盖表 ——
        // 于是左右分幅的 180 被当成 360 Mono 渲染，正中看到的就是两幅的接缝。
        // 覆盖表里存的是「旧规则 + 用户误选」的产物，一次性清掉，代价只是重选一次。
        stage("format-memory")
        runCatching {
            val prefs = getSharedPreferences("gazereel", MODE_PRIVATE)
            if (prefs.getInt(LibraryRepository.MEMORY_VERSION_KEY, 0) <
                LibraryRepository.MEMORY_VERSION
            ) {
                kotlinx.coroutines.runBlocking { library.clearFormatMemory() }
                prefs.edit()
                    .putInt(LibraryRepository.MEMORY_VERSION_KEY, LibraryRepository.MEMORY_VERSION)
                    .apply()
                RunLog.append(this, "fmt", "旧格式记忆已清空（识别规则已换代）")
            }
        }

        stage("ui-layer")
        val posters = PosterRepository(this)
        val uiLayer = VrUiLayer(posters::load)
        val sourceFactory = RandomAccessSourceFactory(pool, credentials, this)
        val dataSourceFactory = UnifiedDataSourceFactory(sourceFactory)
        val subtitles = SubtitleRepository(sourceFactory)
        // V2.7.6：播放前读一次容器里的球面声明与真实宽高，不再靠文件名猜格式。
        val prober = FormatProber(sourceFactory, this)

        // V2.7.3：暂时固定用纯传感器实现。
        //
        // Cardboard 的 HeadTracker 走 native，姿态在世界坐标系里的约定（up 轴、
        // 手性）我没法在没有真机日志的情况下验证；而「头一动画面就转」这件事
        // 必须一次修到位，不能再猜。传感器路径的姿态数学现在完全在工程里：
        // 视线取 -设备z，up 用重力钉在世界竖直上，横放竖放都能推出来。
        //
        // CardboardHeadsetProvider 保留在 src/cardboard/java，等做桶形畸变专项
        // （需要 LensDistortion 的畸变网格）时再切回来并真机校准。
        bootMode = "sensor"
        stage("headset")
        headset = SensorHeadsetProvider(this)
        stage("player")
        val dispatcher = ActionDispatcher { action, channel -> coordinator.handle(action, channel) }
        player = PlayerController(this, dataSourceFactory, library) { state ->
            coordinator.onPlaybackState(state)
        }

        stage("coordinator")
        coordinator = VrCoordinator(
            scope = lifecycleScope,
            library = library,
            player = player,
            settings = settings,
            headset = headset,
            subtitles = subtitles,
            systemVolume = SystemVolume(this),
            prober = prober,
            onExit = { finish() },
            onRecenter = { gaze.reset(); gesture.reset() }
        )

        stage("inputs")
        // V2.14：不再把头动当「操作」。准星落点抖 8px 就调 touch()，
        // 手机塞在纸盒里一直在抖，控制台因此永远等不到 8 秒静默。
        gaze = GazeDetector(
            uiLayer, headset, dispatcher, settings,
            { coordinator.uiState }
        )
        trigger = TriggerDispatcher(
            uiLayer, headset, dispatcher,
            { coordinator.uiState }, { coordinator.touch() }
        )
        gesture = GestureRecognizer(
            headset, dispatcher, settings,
            { coordinator.uiState }
        )

        stage("renderer")
        renderer = VrRenderer(
            headset, uiLayer, settings,
            { coordinator.uiState },
            { gaze.currentId to gaze.progress }
        )
        renderer.onGlReady = { surface ->
            RunLog.append(this, "gl", "surface ready")
            runOnUiThread { player.setSurface(surface) }
        }
        renderer.onGlError = { t ->
            RunLog.append(this, "gl", "失败 ${t.javaClass.simpleName}: ${t.message?.take(200)}")
            runOnUiThread { showFailure(t) }
        }
        // V2.20 / C1：抽帧判立体布局。GL 线程回调，结论要回主线程落地。
        renderer.onFrameSample = { verdict ->
            handler.post { coordinator.onFrameVerdict(verdict) }
        }

        stage("surface")
        glView = VrSurfaceView(this)
        // 必须 setRenderer 之后才能设 renderMode（GLSurfaceView 的 GLThread 此时才存在）。
        glView.bind(renderer)
        setContentView(glView)

        stage("headset-start")
        headset.attachTriggerListener { event ->
            runOnUiThread { trigger.onTrigger(event) }
        }
        headset.start()
        // 正前方由 Provider 在第一帧传感器数据到达时自动定基准（pendingInitialRecenter）。
        // 这里不能直接调 recenter()：此刻还没收到过数据，rotation 仍是单位矩阵，
        // 拿它当基准等于没设，第一帧真数据一到画面就跳到手机的绝对朝向。
        player.onEnded = { mediaId -> coordinator.autoPlayNext(mediaId) }
        player.onError = { message -> coordinator.showMessage(message) }

        lifecycleScope.launch {
            // V2.17：这里必须是「只含未隐藏的片库」，而且要和「全部片源」一起给——
            // 原来读的是 all()，隐藏对 VR 完全无效；而配置页反过来只读 enabled，
            // 隐藏之后再也显示不回来。两个查询接反了，是两个方向的死路。
            database.sourceDao().observeEnabled().collect { enabled ->
                val all = withContext(Dispatchers.IO) { database.sourceDao().all() }
                coordinator.setSources(
                    all.associateBy { it.id },
                    enabled.map { it.id }.toSet()
                )
            }
        }
        lifecycleScope.launch {
            database.mediaDao().observeAll().collect { items -> coordinator.loadItems(items) }
        }
        lifecycleScope.launch {
            database.progressDao().observeAll().collect { all -> coordinator.loadProgress(all) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }

        // 前台服务失败（权限 / 系统限制）不该让整个 VR 界面跟着挂掉。
        stage("foreground-service")
        runCatching { startForegroundService(Intent(this, PlaybackService::class.java)) }
            .onFailure { CrashLog.write(this, it, "foreground-service") }

        stage("ready")
        // 走到这里说明整套初始化都过了，撤掉「正在启动」标记。
        CrashLog.clearBootMarker(this)
        handler.post(tick)
    }

    /**
     * 把「闪退」变成一页能读的错误说明：写日志 + 在屏幕上说清哪一步失败。
     * 用户不需要连电脑，照着屏幕上的字就能反馈。
     */
    private fun showFailure(t: Throwable) {
        CrashLog.write(this, t, bootStage)
        RunLog.append(
            this,
            "FAIL",
            "$bootStage ${t.javaClass.simpleName}: ${t.message?.take(200)}"
        )
        runCatching { handler.removeCallbacks(tick) }
        // 初始化没走完时，PlayerController 的定时器仍会回调还没建好的 coordinator，
        // 不在这里停掉就会在错误页上二次崩溃。
        if (::player.isInitialized) runCatching { player.release() }
        if (::headset.isInitialized) runCatching { headset.stop() }
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.vr_bg))
        }
        val text = android.widget.TextView(this).apply {
            setTextColor(0xFFEAF2FA.toInt())
            textSize = 14f
            setPadding(56, 120, 56, 120)
            text = buildString {
                appendLine("进入 VR 失败了")
                appendLine()
                appendLine("发生在：$bootStage")
                appendLine("${t.javaClass.simpleName}: ${t.message ?: "没有更多信息"}")
                appendLine()
                appendLine("这一步已经记进日志。返回上一页会看到「上次进入 VR 失败」，")
                appendLine("点开就能把详细内容发出来。下面是技术细节：")
                appendLine()
                append(t.stackTraceToString().take(2000))
            }
        }
        scroll.addView(text)
        runCatching { setContentView(scroll) }
    }

    override fun onResume() {
        super.onResume()
        if (::glView.isInitialized) glView.onResume()
    }

    override fun onPause() {
        // V2.9：离开 VR 就把凝视重新锁上。回来时姿态基准已经变了，
        // 而且一定伴随一次明显移动，这时候自动确认任何目标都是错的。
        if (::coordinator.isInitialized) coordinator.disarmGaze()
        if (::glView.isInitialized) glView.onPause()
        super.onPause()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.elapsedRealtime()
                handler.postDelayed(longPress, TriggerDispatcher.LONG_PRESS_MS)
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (SystemClock.elapsedRealtime() - downAt < TriggerDispatcher.LONG_PRESS_MS) {
                    injectTrigger(TriggerKind.CLICK)
                }
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 无需第二台设备：系统按键与有线耳机线控作为可选备用通道。
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                coordinator.handle(VrAction.Adjust("volume", 1), InputChannel.SYSTEM); true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                coordinator.handle(VrAction.Adjust("volume", -1), InputChannel.SYSTEM); true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                injectTrigger(TriggerKind.CLICK); true
            }
            KeyEvent.KEYCODE_BACK -> {
                coordinator.handle(VrAction.Back, InputChannel.SYSTEM); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun injectTrigger(kind: TriggerKind) {
        trigger.onTrigger(TriggerEvent(kind, SystemClock.elapsedRealtime()))
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(longPress)
        if (::glView.isInitialized) glView.onPause()
        // 初始化失败时这些可能没建起来，逐个判断，避免二次崩溃。
        if (::player.isInitialized) runCatching { player.release() }
        if (::renderer.isInitialized) runCatching { renderer.release() }
        if (::headset.isInitialized) runCatching { headset.stop() }
        runCatching { stopService(Intent(this, PlaybackService::class.java)) }
        // V2.18：把 SMB 会话还给 NAS。不关就是每次进出 VR 泄漏一条连接。
        // V2.28：close() 现在与 SMB 调用共用一把锁；放到后台执行，别让一次
        // 慢网络 IO 把 onDestroy 卡在主线程上（关闭的是本页自己的池子，
        // 与配置页那条互不干扰）。
        pool?.let { closing -> Thread { runCatching { closing.close() } }.start() }
        pool = null
        super.onDestroy()
    }
}
