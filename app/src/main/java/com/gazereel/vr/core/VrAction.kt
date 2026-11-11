package com.gazereel.vr.core

import android.graphics.RectF

enum class InputChannel { GAZE, TRIGGER, GESTURE, SYSTEM }

/** PRD 6.5：三个输入模块产出统一的 VrAction，由 ActionDispatcher 分发。 */
sealed class VrAction {

    /** 数值越大越优先；播放状态相关动作高于设置类动作。 */
    open val priority: Int get() = 1
    open val isPlaybackRelated: Boolean get() = false

    data class Confirm(val targetId: String) : VrAction()
    data class OpenMedia(val mediaId: Long) : VrAction() {
        override val priority = 3
        override val isPlaybackRelated = true
    }
    data class SeekTo(val fraction: Float) : VrAction() {
        override val priority = 2
        override val isPlaybackRelated = true
    }
    data class SeekBy(val seconds: Int) : VrAction() {
        override val priority = 2
        override val isPlaybackRelated = true
    }
    data class SetFormat(val format: VrFormat) : VrAction() {
        override val priority = 3
        override val isPlaybackRelated = true
    }
    data class Adjust(val key: String, val delta: Int) : VrAction()
    data class ToggleOption(val key: String) : VrAction()

    /**
     * V2.8：片库整页翻。
     *
     * 设计文档第 528 行：「摇头翻页从 1 部改为 10 部一页」。这里不把翻页塞进
     * [Next] / [Previous]，是因为那两个是「换一部片」，属于播放相关动作，
     * 在片库里被执行就会直接开始播放——翻页不能有这种副作用。
     */
    data class PageFlip(val delta: Int) : VrAction()

    /**
     * V2.16：片库成叠视图（B1 的第一层：柜子 → **叠** → 片）。
     *
     * 832 部平铺是 104 页，翻页翻到手酸。按「一次拍摄 / 一个文件夹」先聚成叠，
     * 一屏就能看到全部拍摄批次；点进某一叠才看那一批的片子。
     */
    data class OpenStack(val key: String) : VrAction()

    object CloseStack : VrAction()

    /**
     * V2.18：换柜子整层（B2）。
     *
     * 「柜子」= 一台设备或一个片源。戴镜后不取手机，所以要换片源只能在这里换：
     * 一屏摆出全部柜子（含「全部」），凝视一次就换整柜，不弹菜单、不重新扫描。
     * 这是设计 08.2 的三层里唯一还空着的一层。
     */
    object OpenCabinets : VrAction()

    object CloseCabinets : VrAction()

    /** `sourceId = 0` 表示「全部片库」。 */
    data class SwitchCabinet(val sourceId: Long) : VrAction()

    /**
     * V2.19 / B4：姿态闭环的确认。
     *
     * 检测到「换了姿势」只标记候选、画面一帧不动；凝视这个目标才用 0.8s 缓转过去。
     * 不确认就什么都不发生——3DOF 分不清「换姿势」和「头转了一下」，
     * 自动重定会抢走用户正在看的内容。
     */
    object ConfirmPosture : VrAction() { override val priority = 2 }

    object DismissPosture : VrAction()

    /**
     * V2.19 修订：**主动**把正前方挪到现在的姿势。
     *
     * 真机反馈「B4 完全没功能」——查下来是设计假设错了：
     * 检测「换了姿势」要求用户先戴着看一会儿再换姿势，而真实场景是
     * **先躺好、再把手机塞进纸盒**。那一刻初始重定已经把「正前方」
     * 定成了躺姿，之后永远不会有 40° 的偏差，检测一辈子不触发。
     *
     * 所以必须有这条明路：控制台上的「正前方」。
     *
     * V2.38 改掉了它原来的后半段：以前是「凝视一次就按那一刻的朝向重定」，
     * 而用户为了点这个按钮，头已经转到按钮那边去了——被记下的其实是按钮的位置。
     * 现在它只**开始**一次倒计时，真正被记下的是倒计时结束时头朝的方向。
     */
    object AlignForward : VrAction() { override val priority = 2 }

    /**
     * V2.38：重定「正前方」的两条路，互不冲突。
     *
     * 1. [AlignForward] → 跟着视线走的倒计时，归零那一刻头朝哪儿，哪儿就是正前方；
     * 2. [RecenterNow]（双击触发键）→ 此刻就对齐，不要倒计时。
     *
     * 两条都**不需要瞄准任何按钮**——旧版「凝视按钮再凝视确认」之所以一直差几度，
     * 就是因为瞄准动作本身把视线从「想要的正前方」挪开了（见 VrCoordinator 的注释）。
     * 这条语义下，被记下的永远是用户**看向的方向**，不是按钮的位置。
     */
    object RecenterNow : VrAction() { override val priority = 3 }

    /** 倒计时里单击 = 「现在就定在这里」，不用等它走完。 */
    object ConfirmAlign : VrAction() { override val priority = 3 }

    /** 倒计时里长按 / 返回 = 取消，正前方一点不动。 */
    object CancelAlign : VrAction() { override val priority = 2 }

    /**
     * V2.16：播放中调出片库之后，一个动作回到正在看的那一部。
     *
     * 原来从播放页按「片库」只有长按触发键或重新凝视那一格才能回去，
     * 而「我就想看一眼片库、然后接着看」是高频动作，必须有个明摆着的出口。
     */
    object ResumePlayback : VrAction() {
        override val priority = 3
        override val isPlaybackRelated = true
    }

    object TogglePlay : VrAction() {
        override val priority = 3
        override val isPlaybackRelated = true
    }
    object Next : VrAction() {
        override val priority = 3
        override val isPlaybackRelated = true
    }
    object Previous : VrAction() {
        override val priority = 3
        override val isPlaybackRelated = true
    }
    object Back : VrAction() { override val priority = 2 }
    object Exit : VrAction() { override val priority = 4 }
    object OpenFormat : VrAction()
    object OpenSettings : VrAction()
    object CloseOverlay : VrAction()
    object ShowConsole : VrAction()

    /**
     * V2.9：把凝视「上膛」。
     *
     * 只由触发键单击发出。它是进 VR 之后的第一个动作，在此之前准星不确认任何目标，
     * 所以配戴过程中的晃动不会误开视频。
     */
    object ArmGaze : VrAction() { override val priority = 4 }
    // V2.28：ResetForward 已移除。按钮在面板下方，凝视它时视线必然离开舒适中心，
    // 而它又是瞬跳 re-center，等于把「低头看按钮」写成了新的正前方。
    // 现在正前方只在进 VR 时定一次，需要微调请用 AlignForward（带确认卡）。
    object LockView : VrAction()
    object ClearFormatMemory : VrAction()

    /**
     * V2.33 / B13：设置浮层「瞳距 · 边看边调」。
     *
     * 进这个态时视频不暂停、不重开、不铺压暗层——瞳距要看着左右画面能不能合上才调得准，
     * 而原来的设置浮层用 0xEE 面板铺满画布，一进去画面就被盖住了。
     */
    object StartIpdTuning : VrAction()

    /** 退出「边看边调」：立刻把音量键还给音量，调节条淡出。 */
    object StopIpdTuning : VrAction()

    /** 粗调 2.0mm ↔ 微调 0.5mm；两者叠加到同一个 0.5mm 单位的值上。 */
    object ToggleIpdMode : VrAction()

    /** B14：音量 / 亮度调节条上的「完成」——主动收起，不等它自己淡出。 */
    object DismissAdjust : VrAction()
}

/** 世界锁定 UI 上的可凝视区域；坐标用 UI 画布像素，左右眼共用同一份布局。 */
data class GazeTarget(
    val id: String,
    val label: String,
    val rect: RectF,
    val action: VrAction
)
