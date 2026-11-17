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
    external fun nativeGetLog(): String

    // Switches the display from VNC to the shared-memory framebuffer.
    // path: file backing QEMU guest RAM (memory-backend-file,share=on).
    // fbOffset: framebuffer guest-physical address minus 0x40000000 (virt RAM base).
    // Returns false if the mapping failed; VNC then stays active.
    external fun nativeShmStart(
        path: String,
        regionSize: Long,
        fbOffset: Long,
        width: Int,
        height: Int,
        strideBytes: Int,
        instrOffset: Long
    ): Boolean

    external fun nativeShmStop()
}
