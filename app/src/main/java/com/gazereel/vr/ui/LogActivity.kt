package com.gazereel.vr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.gazereel.vr.R
import com.gazereel.vr.core.PoseTrace
import com.gazereel.vr.data.CrashLog
import com.gazereel.vr.data.RunLog

/**
 * V2.7.4 测试版：一页能直接截图的运行日志。
 *
 * 用户不需要连电脑、不需要懂 adb——进 VR 出了问题，回到这一页截图发我，
 * 上面有时间、阶段、播放器状态和异常类型。三件事比任何描述都准：
 * 崩在哪一步、打开的是哪个文件、最后一帧之前发生了什么。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.setup_bg))
            fitsSystemWindows = true
        }

        // ── 顶栏：返回 / 复制 / 清空 ──────────────────────────────────
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(12), dp(10))
        }
        bar.addView(
            TextView(this).apply {
                text = getString(R.string.log_title)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.setup_text))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(action(R.string.log_copy) { copyToClipboard() })
        bar.addView(action(R.string.log_clear) {
            RunLog.clear(this)
            CrashLog.clear(this)
            refresh()
        })
        bar.addView(action(R.string.log_back) { finish() })
        root.addView(bar)

        root.addView(
            TextView(this).apply {
                text = getString(R.string.log_hint)
                textSize = 11f
                setPadding(dp(20), 0, dp(20), dp(10))
                setTextColor(getColor(R.color.setup_muted))
            }
        )

        body = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.setup_text))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(4), dp(20), dp(40))
            setLineSpacing(0f, 1.15f)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val run = RunLog.read(this)
        val crash = CrashLog.read(this)
        body.text = buildString {
            appendLine(RunLog.deviceHeader())
            appendLine("— 姿态诊断（V2.24 第 0 步 · 只读）—")
            append(PoseTrace.summary())
            appendLine()
            appendLine("— 上次崩溃 —")
            appendLine(crash?.takeIf { it.isNotBlank() } ?: "（没有记录）")
            appendLine()
            appendLine("— 运行日志（新 → 旧顺序倒着看更省事）—")
            append(run.takeIf { it.isNotBlank() } ?: "（还没有记录。进一次 VR 再回来看）")
        }
    }

    private fun copyToClipboard() {
        val text = body.text?.toString().orEmpty()
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("GazeReel 运行日志", text))
        body.text = text // 保持内容不变，只给一条反馈
        android.widget.Toast.makeText(this, R.string.log_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun action(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = getString(labelRes)
            textSize = 13f
            setTextColor(getColor(R.color.setup_cyan))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
