package com.gazereel.vr.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.gazereel.vr.core.ClipRequest
import com.gazereel.vr.data.RunLog

/**
 * 真机验收的入口跳板：把 adb 点名的片名原样带进 [VrActivity] 后立刻退出。
 *
 * 为什么要单独一个 Activity，而不是把 extras 直接发给 VrActivity：
 * `VrActivity` 的 `exported=false` 是有意的 —— 它是整块 GL 场景，不该被其他 App 拉起。
 * 验收脚本用的是 `am start`，身份是 shell、不是本 App，只能进导出组件。
 * 于是开这一个**只做转发、不画任何东西**的导出壳，其余逻辑一行不改。
 *
 * 主题用系统半透明，避免跳板本身闪一帧白屏（戴着头显时那一下很刺眼）。
 * 这也意味着：**不带 extra 时它就是空转**，不需要额外的开关或调试构建。
 */
class VerifyLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clip = intent?.getStringExtra(ClipRequest.EXTRA_CLIP)
        val item = intent?.getStringExtra(ClipRequest.EXTRA_ITEM)
        RunLog.append(this, "verify", "入口 ${ClipRequest.describe(item, clip)}")
        startActivity(
            Intent(this, VrActivity::class.java)
                .putExtra(ClipRequest.EXTRA_CLIP, clip)
                .putExtra(ClipRequest.EXTRA_ITEM, item)
        )
        finish()
    }
}
