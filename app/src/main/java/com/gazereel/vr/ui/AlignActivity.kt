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
 * 而 B4 的规矩是「进 VR 后第一个稳定姿态就是正前方」——**画面在动的时候，
 * 那个「稳定姿态」抓到的其实是塞手机的动作**。于是刚戴稳就发现画面偏了，
 * 要么等 40° 检测，要么去控制台手动挪。
 *
 * 这一屏把「准备」显式化，一次解决三件事：
 *
 * 1. **左右对正**：整高一道中线，穿过盒子里的隔板。这是唯一看得见的对齐依据；
 * 2. **给时间**：不算体力活，但塞进去、手离开、头摆正确实要几秒。以前这几秒是
 *    黑屏和未知，现在是「还在动 → 放稳了」的明确过程；
 * 3. **稳了才放行**：要连续 900ms 不动才允许进入。进入那一刻的姿势就是基准姿势，
 *    所以戴上之后画面**本来就是正的**，不需要任何补救。
 *
 * ## 不进 VR 的自动跳转
 *
 * 不做「稳了就自己进去」。戴着盒子时画面突然变成双屏，用户没法判断是不是出了故障；
 * 而且这一屏正是他确认「手机放没放好」的最后机会。按键是刻意的停一下。
 */
class AlignActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityAlignBinding
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val pose = FloatArray(16)
    private val anchor = FloatArray(16)

    /**
     * V2.21：把「确认那一刻」的原始姿态带进 VR。
     *
     * 只传屏幕姿态矩阵不够——VR 页还要自己算一遍屏幕映射，两边一旦不一致
     * 就是 180° 的差。传 TYPE_ROTATION_VECTOR 的原值（四元数），
     * 由 VR 页用自己的映射重建，才是同一个姿势的唯一定义。
     */
    private val quat = FloatArray(4)
    private val quatAtPress = FloatArray(4)
    private var hasQuat = false
    private var hasPressQuat = false
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
        if (event.values.size >= 4) {
            System.arraycopy(event.values, 0, quat, 0, 4)
            hasQuat = true
        }
        val raw = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(raw, event.values)
        // 与 SensorHeadsetProvider 同一套映射：设备轴要补上屏幕旋转才是屏幕轴。
        val deviceToWorld = FloatArray(16)
        for (col in 0..2) {
            for (row in 0..2) {
                deviceToWorld[col * 4 + row] = raw[row * 3 + col]
            }
        }
        val screenToDevice = screenToDevice(displayRotation())
        android.opengl.Matrix.multiplyMM(pose, 0, deviceToWorld, 0, screenToDevice, 0)

        updateTilt()
        updateStability(SystemClock.elapsedRealtime())
    }

    /**
     * 屏幕坐标系里的重力方向 → 倾斜角。
     *
     * 正的 `tiltDeg` 表示手机右侧偏低。用重力而不是「屏幕 up 轴与天顶的夹角」，
     * 是因为角度本身有 ±180° 的二义性（手机倒过来也是 180°），而分量没有。
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
        // trace(aᵀb) 的 3x3 部分；用逐元素点积算，省一次矩阵乘法。
        var trace = 0f
        for (col in 0..2) {
            for (row in 0..2) {
                trace += a[col * 4 + row] * b[col * 4 + row]
            }
        }
        val cos = ((trace - 1f) / 2f).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
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
                // 按下去的那一下会震到手机，所以基准要取「按下之前」的姿势。
                if (downWhileReady && hasQuat) {
                    System.arraycopy(quat, 0, quatAtPress, 0, 4)
                    hasPressQuat = true
                }
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
        val source = when {
            hasPressQuat -> quatAtPress
            hasQuat -> quat
            else -> null
        }
        RunLog.append(
            "align",
            if (source == null) "进入 VR（无姿态可带，交给第一帧数据定基准）"
            else "进入 VR · 带上确认时的姿势作为正前方"
        )
        val intent = Intent(this, VrActivity::class.java)
            .putExtra(VrActivity.EXTRA_ORIENTATION, source?.copyOf())
            .putExtra(VrActivity.EXTRA_SCREEN_ROTATION, displayRotation())
        startActivity(intent)
        finish()
    }

    /** V2.21：屏上方向只解析一次。它决定「屏幕右 / 上」对应设备的哪条轴，中途变会让中线跳。 */
    private var fixedRotation = Int.MIN_VALUE

    private fun displayRotation(): Int {
        if (fixedRotation == Int.MIN_VALUE) {
            fixedRotation = runCatching { panel?.rotation ?: Surface.ROTATION_0 }
                .getOrDefault(Surface.ROTATION_0)
        }
        return fixedRotation
    }

    private fun screenToDevice(screenRotation: Int): FloatArray {
        val (x, y) = when (screenRotation) {
            Surface.ROTATION_90 -> floatArrayOf(0f, 1f, 0f) to floatArrayOf(-1f, 0f, 0f)
            Surface.ROTATION_180 -> floatArrayOf(-1f, 0f, 0f) to floatArrayOf(0f, -1f, 0f)
            Surface.ROTATION_270 -> floatArrayOf(0f, -1f, 0f) to floatArrayOf(1f, 0f, 0f)
            else -> floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, 1f, 0f)
        }
        return floatArrayOf(
            x[0], x[1], x[2], 0f,
            y[0], y[1], y[2], 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

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
