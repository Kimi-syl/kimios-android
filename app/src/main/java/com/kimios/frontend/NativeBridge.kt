package com.kimios.frontend

import android.view.Surface

object NativeBridge {
    init {
        System.loadLibrary("kimios-bridge")
    }

    @Volatile
    var firstFrameListener: (() -> Unit)? = null

    @JvmStatic
    fun onFirstFrameFromNative() {
        firstFrameListener?.invoke()
    }

    external fun nativeInit(qmpSocketPath: String)
    external fun nativeSurfaceCreated(surface: Surface)
    external fun nativeSurfaceDestroyed()
    external fun nativePointer(x: Float, y: Float, state: Int)
    external fun nativeKey(qcode: String, down: Boolean)
    external fun nativeShutdown()
}
