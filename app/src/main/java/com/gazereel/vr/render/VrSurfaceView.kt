package com.gazereel.vr.render

import android.content.Context
import android.opengl.GLSurfaceView

/**
 * ES 3.0 上下文；头追需要连续渲染，不使用 WHEN_DIRTY。
 *
 * V2.7 修：`renderMode` 不能写在构造函数里。GLSurfaceView 内部是用
 * `mGLThread.setRenderMode()` 实现的，而 `mGLThread` 只在 `setRenderer()` 里创建，
 * 构造期调用必然抛 NullPointerException（真机堆栈：VrSurfaceView.<init> 第 11 行）。
 * 顺序必须是先 setRenderer 再设 renderMode，所以这里收成一个方法。
 */
class VrSurfaceView(context: Context) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setPreserveEGLContextOnPause(true)
        keepScreenOn = true
    }

    /** 先建渲染线程，再决定渲染模式。顺序不能反。 */
    fun bind(renderer: Renderer) {
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
