package com.gazereel.vr

import android.app.Application
import com.gazereel.vr.data.CrashLog
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.data.SmbRandomAccessSource

/**
 * V2.7.4：测试版必须能「事后看到死因」。
 *
 * 真机出问题时我抓不到 logcat，用户也只能看到 App 一闪而过。
 * 这里挂一个全局未捕获异常处理器：任何线程上的 Java 崩溃都先写进
 * `last_crash.txt` 与 `run_log.txt`，再交回系统默认处理（照常崩，不改行为）。
 * 下次打开配置页 →「运行日志」，一屏截图就能定位。
 *
 * 注意：native 崩溃（SIGSEGV）接不住，那种情况只能靠崩溃前最后几条日志判断。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 输入层（手势 / 触发键 / 凝视）没有 Context，日志要能从这里拿到入口。
        RunLog.install(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                CrashLog.write(this, throwable, "uncaught:${thread.name}")
                // V2.35：崩溃那一行带上当时的内存账，OOM 才不用事后猜是谁涨的。
                val runtime = Runtime.getRuntime()
                val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
                val maxMb = runtime.maxMemory() / (1024L * 1024L)
                val smbMb = SmbRandomAccessSource.cachedBytes.get() / (1024L * 1024L)
                RunLog.append(
                    this,
                    "CRASH",
                    "${throwable.javaClass.simpleName}: ${throwable.message} @ ${thread.name}" +
                        " · 堆 ${usedMb}/${maxMb}MB · SMB 缓存 ${smbMb}MB" +
                        " · 活源 ${SmbRandomAccessSource.liveSources.get()}"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        RunLog.append(this, "app", "启动 v$version")
    }
}
