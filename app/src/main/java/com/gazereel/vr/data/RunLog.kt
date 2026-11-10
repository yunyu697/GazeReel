package com.gazereel.vr.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V2.7.4：给测试版用的运行日志。
 *
 * 真机出问题时，用户能截图比「描述现象」有效得多；而我在电脑上抓不到 logcat，
 * 只能靠屏幕上写出来的字。所以这里只记**关键节点**，不记每帧：
 * 进 VR 的阶段、打开哪个文件、用什么格式、播放器状态怎么变、GL 有没有失败。
 *
 * 落盘在应用私有目录，配置页「运行日志」里可以直接看、复制、清空。
 */
object RunLog {

    private const val FILE_NAME = "run_log.txt"

    /** 超过这个长度就整体清空重来：测试期宁可丢旧记录，也不能让文件无限长。 */
    private const val MAX_BYTES = 160_000L

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 输入层（手势、触发键、凝视）没有 Context，靠这里拿一个应用级引用。 */
    private var installed: Context? = null

    fun install(context: Context) {
        installed = context.applicationContext
    }

    fun append(tag: String, message: String) {
        installed?.let { append(it, tag, message) }
    }

    @Synchronized
    fun append(context: Context, tag: String, message: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) file.writeText("")
            file.appendText("${stamp.format(Date())} [$tag] $message\n")
        }
    }

    fun read(context: Context): String = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText().orEmpty()
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /** 日志页顶部那几行：机型信息往往比堆栈更能解释问题。 */
    fun deviceHeader(): String = buildString {
        appendLine("机型 ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统 Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("ABI ${Build.SUPPORTED_ABIS.joinToString(",")}")
    }
}
