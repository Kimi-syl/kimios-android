package com.kimios.frontend

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(QemuService.EXTRA_MESSAGE) ?: return
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        registerReceiver(
            statusReceiver,
            IntentFilter(QemuService.ACTION_STATUS),
            RECEIVER_NOT_EXPORTED
        )
        QemuService.start(this)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        NativeBridge.nativeSurfaceDestroyed()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.nativeSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeBridge.nativeSurfaceDestroyed()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val width = surfaceView.width.coerceAtLeast(1)
        val height = surfaceView.height.coerceAtLeast(1)
        val x = (event.x / width).coerceIn(0f, 1f)
        val y = (event.y / height).coerceIn(0f, 1f)
        val state = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> 2
            else -> 0
        }
        NativeBridge.nativePointer(x, y, state)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val qcode = qcodeFor(keyCode) ?: return super.onKeyDown(keyCode, event)
        NativeBridge.nativeKey(qcode, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val qcode = qcodeFor(keyCode) ?: return super.onKeyUp(keyCode, event)
        NativeBridge.nativeKey(qcode, false)
        return true
    }

    private fun qcodeFor(keyCode: Int): String? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "ret"
        KeyEvent.KEYCODE_SPACE -> "spc"
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> "esc"
        KeyEvent.KEYCODE_DEL -> "backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
        KeyEvent.KEYCODE_TAB -> "tab"
        KeyEvent.KEYCODE_PERIOD -> "dot"
        KeyEvent.KEYCODE_COMMA -> "comma"
        KeyEvent.KEYCODE_MINUS -> "minus"
        KeyEvent.KEYCODE_EQUALS -> "equal"
        KeyEvent.KEYCODE_SLASH -> "slash"
        KeyEvent.KEYCODE_BACKSLASH -> "backslash"
        KeyEvent.KEYCODE_SEMICOLON -> "semicolon"
        KeyEvent.KEYCODE_APOSTROPHE -> "apostrophe"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "leftbracket"
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "rightbracket"
        KeyEvent.KEYCODE_GRAVE -> "grave"
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_SHIFT_LEFT -> "shift"
        KeyEvent.KEYCODE_SHIFT_RIGHT -> "shift_r"
        KeyEvent.KEYCODE_CTRL_LEFT -> "ctrl"
        KeyEvent.KEYCODE_CTRL_RIGHT -> "ctrl_r"
        KeyEvent.KEYCODE_ALT_LEFT -> "alt"
        KeyEvent.KEYCODE_ALT_RIGHT -> "alt_r"
        KeyEvent.KEYCODE_HOME -> "home"
        KeyEvent.KEYCODE_MOVE_END -> "end"
        KeyEvent.KEYCODE_PAGE_UP -> "pgup"
        KeyEvent.KEYCODE_PAGE_DOWN -> "pgdn"
        else -> null
    }
}
