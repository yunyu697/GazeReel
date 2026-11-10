package com.gazereel.vr.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.gazereel.vr.R
import com.gazereel.vr.core.ScreenFrame
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.databinding.ActivityAlignBinding
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2

/**
 * V2.20 / A18：点「进入 VR」之后、真正戴稳之前的那一屏。
 *
 * ## 为什么要有这一屏
 *
 * 以前点了「进入 VR」就直接跳进双屏画面：用户一只手还拿着手机，画面已经在转了。
 * 于是「戴上就正对」这件事无从谈起——他还没戴好，画面已经用「还在塞手机」的
 * 姿势定了方向。
 *
 * 这一屏把「准备」显式化，一次解决三件事：
 *
 * 1. **左右对正**：整高一道中线，穿过盒子里的隔板。这是唯一看得见的对齐依据；
 * 2. **给时间**：塞进去、手离开、头摆正确实要几秒。以前这几秒是黑屏和未知，
 *    现在是「还在动 → 放稳了」的明确过程；
 * 3. **稳了才放行**：要连续 900ms 不动才允许进入。
 *
 * ## 它不负责定「正前方」
 *
 * V2.21 曾经把「按下按键那一刻的姿势」当成基准带进 VR。那是错的，而且错得很隐蔽：
 * 用户按完按键往往还要往沙发里靠一下、把头往枕头上放，几秒之内姿势差 40°–50° 是常态。
 * 那个差异一旦被写成基准，画面就**永久**歪着——自动摆正也被我一起关掉了，
 * 于是没有任何东西能把它纠回来（真机反馈：一进去就歪 45°、怎么重定都回不来）。
 *
 * 现在的分工很干净：**这一屏只做「准备好」，方向由 VR 页自己按用户实际姿势定。**
 * 这里唯一留下来的一件事是屏上方向的观测——手机此刻正握在手里、窗口已经横过来，
 * 是整条链上最能看清「屏幕上边是哪条轴」的时刻，顺手定下来给全场用（见 [ScreenFrame]）。
 *
 * ## 不做「稳了自动进去」
 *
 * 戴着盒子时画面突然变双屏，用户没法判断是不是出了故障；
 * 而且这一屏正是他确认「手机放没放好」的最后机会。按键是刻意的停一下。
 */
class AlignActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityAlignBinding
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val pose = FloatArray(16)
    private val anchor = FloatArray(16)

    private var hasPose = false
    private var stillSince = 0L
    private var ready = false
    private var readyAt = 0L
    private var downWhileReady = false
    private var entered = false

    private val panel: Display? by lazy {
        runCatching {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        binding = ActivityAlignBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        RunLog.append("align", "入盒引导页")
    }

    override fun onResume() {
        super.onResume()
        ready = false
        readyAt = 0L
        entered = false
        hasPose = false
        stillSince = SystemClock.elapsedRealtime()
        bindReadyState(false)
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: run {
            // 没有陀螺仪就没法判稳。与其卡死在这一屏，不如直接放行。
            RunLog.append("align", "无旋转矢量传感器，跳过稳定判定")
            setReady(true)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val raw = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(raw, event.values)

        // 屏上方向是「手机怎么拿」的属性，与姿势无关，所以整个会话里任意一帧
        // 置信够高都可以定——只要求窗口已经按横屏布局（否则定出来的是竖屏的答案）。
        val frame = if (windowIsLandscape()) {
            ScreenFrame.offer(raw, "入盒引导页")
        } else {
            ScreenFrame.UNKNOWN
        }
        val effective = if (frame != ScreenFrame.UNKNOWN) frame else displayRotation()

        System.arraycopy(ScreenFrame.poseOf(raw, effective), 0, pose, 0, 16)
        updateTilt()
        updateStability(SystemClock.elapsedRealtime())
    }

    /**
     * 屏幕坐标系里的重力方向 → 倾斜角。
     *
     * 正的 `tiltDeg` 表示手机右侧偏低。用重力分量而不是「屏幕 up 轴与天顶的夹角」，
     * 因为角度本身有 ±180° 的二义性（手机倒过来也是 180°），而分量没有。
     */
    private fun updateTilt() {
        // 列 0 = 屏幕右轴、列 1 = 屏幕上轴；屏幕坐标里的重力 g = Rᵀ·(0,0,−1)，
        // 所以两个分量就是各自 z 分量取负。
        val rightZ = pose[2]
        val upZ = pose[6]
        val gx = -rightZ
        val gy = -upZ
        if (abs(gx) < 1e-5f && abs(gy) < 1e-5f) return
        // 手机几乎平放（屏幕朝天/朝地）时，屏幕面内的倾斜没有意义，直接不动。
        if (gy > -0.10f) return
        val deg = Math.toDegrees(atan2(gx.toDouble(), -gy.toDouble())).toFloat()
        binding.levelGauge.tiltDeg = deg.coerceIn(-25f, 25f)
    }

    private fun updateStability(now: Long) {
        if (!hasPose) {
            System.arraycopy(pose, 0, anchor, 0, 16)
            hasPose = true
            stillSince = now
            return
        }
        if (angleBetween(anchor, pose) > STILL_TOLERANCE_DEG) {
            System.arraycopy(pose, 0, anchor, 0, 16)
            stillSince = now
            if (ready) setReady(false)
            return
        }
        if (!ready && now - stillSince >= STILL_MS) setReady(true)
    }

    /** 两个旋转矩阵之间的夹角（度）。 */
    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        var trace = 0f
        for (col in 0..2) {
            for (row in 0..2) {
                trace += a[col * 4 + row] * b[col * 4 + row]
            }
        }
        val cos = ((trace - 1f) / 2f).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    private fun setReady(value: Boolean) {
        ready = value
        readyAt = SystemClock.elapsedRealtime()
        bindReadyState(value)
        RunLog.append("align", if (value) "姿态已稳（${STILL_MS}ms）→ 等待按键" else "姿态有变动，重新计时")
    }

    private fun bindReadyState(value: Boolean) {
        binding.alignHint.text = getString(
            if (value) R.string.align_hint_ready else R.string.align_hint_wait
        )
        binding.alignHint.setTextColor(
            getColor(if (value) R.color.vr_accent else R.color.setup_muted)
        )
        binding.alignHint.textSize = if (value) 15f else 13f
        binding.alignHintAlt.visibility = if (value) View.VISIBLE else View.GONE
        binding.levelGauge.leveled = value
        binding.centerRail.animate()
            .alpha(if (value) 1f else 0.55f)
            .setDuration(220L)
            .start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 戴着盒子时唯一会碰屏幕的就是那个实体按键，但它和手指没法区分。
                // 所以规则是：按下时若已经稳了，抬手就进入；按下本身会让稳定计时重来，
                // 避免「手上还在调位置」被当成按键。
                downWhileReady = ready &&
                    SystemClock.elapsedRealtime() - readyAt > TAP_GUARD_MS
                stillSince = SystemClock.elapsedRealtime()
                if (ready) setReady(false)
            }
            MotionEvent.ACTION_UP -> if (downWhileReady) enterVr()
            MotionEvent.ACTION_CANCEL -> downWhileReady = false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER -> {
            if (ready) enterVr()
            true
        }
        KeyEvent.KEYCODE_BACK -> {
            finish()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun enterVr() {
        if (entered) return
        entered = true
        // 只带一件事过去：屏上方向。姿势不带——带上就是「用一个刻舟求剑的基准」，
        // 见类注释那段。方向由 VR 页按他真正躺好的姿势重新定。
        RunLog.append(
            "align",
            "进入 VR · 屏上方向=${ScreenFrame.name(ScreenFrame.cached())}"
        )
        startActivity(Intent(this, VrActivity::class.java))
        finish()
    }

    /** 窗口是否已经按横屏布局。在它成立之前，屏上方向定出来的是竖屏的答案。 */
    private fun windowIsLandscape(): Boolean =
        binding.root.width > 0 && binding.root.width > binding.root.height

    /**
     * 仅在屏上方向还没定下来时给一条仪表的读数用，所以每次读——不冻结。
     * 它不影响 VR 里的姿态（那边只认 [ScreenFrame]），只影响这一屏水泡准不准。
     */
    private fun displayRotation(): Int =
        runCatching { panel?.rotation ?: Surface.ROTATION_0 }.getOrDefault(Surface.ROTATION_0)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private companion object {
        /** 连续不动多久算稳。900ms：比一次呼吸短，比手抖长。 */
        const val STILL_MS = 900L

        /** 超过这个角度就算「动过」。传感器噪声在 0.1° 量级，1.2° 足够区分。 */
        const val STILL_TOLERANCE_DEG = 1.2f

        /** 刚变亮的瞬间不接受按键，避免用户正在调整时被自己碰到。 */
        const val TAP_GUARD_MS = 250L
    }
}
