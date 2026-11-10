package com.gazereel.vr.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V2.7：不让用户面对一块黑屏和「应用已停止」。
 *
 * 进 VR 失败时把原因写到应用私有目录，并让界面把话说出来；
 * 下次打开配置页还能看到上一次的失败记录，用户可以直接把它发出来。
 * 对用户来说这是「有人告诉他发生了什么」，对开发来说这是唯一可靠的真机线索
 * ——没有日志就只能猜。
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val MARKER = "boot_in_progress"
    private const val BLOCKED = "cardboard_blocked"

    /** 降级判据版本：判据一改就换标签，旧的封锁自动失效。 */
    private const val BLOCK_TAG = "stage-aware-v1"

    /** 上次启动留下的痕迹：用哪种头追、死在哪一步。 */
    data class BootMarker(val mode: String, val stage: String)

    /**
     * 进 VR 前先立一个「正在启动」标记，正常走完再删掉。
     * 下次启动如果还看到它，说明上次初始化把进程带崩了——native 崩溃（SIGSEGV）
     * Java 层的 try-catch 根本接不住，只有这种落盘标记能留下来。
     *
     * V2.7 修：标记里必须带上阶段。之前只记模式，结果崩在 `surface`（我们自己的
     * 空指针）也会被当成 Cardboard native 崩溃，把官方头追白白禁掉。
     */
    fun markBooting(context: Context, mode: String, stage: String) {
        runCatching { File(context.filesDir, MARKER).writeText("$mode|$stage") }
    }

    fun bootMarker(context: Context): BootMarker? = runCatching {
        val raw = File(context.filesDir, MARKER).takeIf { it.exists() }?.readText()?.trim()
            ?: return@runCatching null
        val parts = raw.split('|', limit = 2)
        BootMarker(parts[0], parts.getOrElse(1) { "" })
    }.getOrNull()

    fun clearBootMarker(context: Context) {
        runCatching { File(context.filesDir, MARKER).delete() }
    }

    /**
     * 崩过一次之后不再赌 Cardboard，改用纯传感器实现，先保证能进去。
     * 封锁带上判据版本：判据一改（比如这次的 V2.7 修），旧封锁自动作废重试，
     * 不让用户背着一个早就修好的问题一直用降级实现。
     */
    fun blockCardboard(context: Context) {
        runCatching { File(context.filesDir, BLOCKED).writeText(BLOCK_TAG) }
    }

    fun isCardboardBlocked(context: Context): Boolean = runCatching {
        val file = File(context.filesDir, BLOCKED)
        if (!file.exists()) return@runCatching false
        if (file.readText().trim() != BLOCK_TAG) {
            file.delete()
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    fun unblockCardboard(context: Context) {
        runCatching { File(context.filesDir, BLOCKED).delete() }
    }

    fun write(context: Context, throwable: Throwable, stage: String = "") {
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val body = buildString {
                appendLine("time: $time")
                if (stage.isNotBlank()) appendLine("stage: $stage")
                appendLine(
                    "device: ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}"
                )
                appendLine(throwable.stackTraceToString().take(6000))
            }
            File(context.filesDir, FILE_NAME).writeText(body)
        }
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /** 给用户看的一行原因：异常类型 + 消息，不带堆栈。 */
    fun headline(raw: String?): String? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        val line = text.lineSequence().firstOrNull {
            it.contains("Exception") || it.contains("Error")
        } ?: text.lineSequence().firstOrNull { it.contains("stage:") }
        return line?.trim()?.take(140)
    }
}
