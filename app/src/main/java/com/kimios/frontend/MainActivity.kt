package com.kimios.frontend

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

class MainActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        val RESOLUTIONS = listOf("480 x 800", "720 x 1280", "1080 x 1920", "1440 x 2560")
        val CPUS = listOf(1, 2, 4)
        const val RAM_MIN = 128
        const val RAM_STEP = 128
        const val RAM_MAX = 4096
        const val DISK_MIN = 8
        const val DISK_STEP = 4
        const val DISK_MAX = 64
        private const val PICK_FILE = 41
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var sidebar: ScrollView
    private lateinit var toggleButton: Button
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var bridgeView: TextView
    private lateinit var memoryView: TextView
    private lateinit var diskInfoView: TextView
    private lateinit var diskFilesView: TextView
    private lateinit var bootButton: Button
    private lateinit var resolutionSpinner: Spinner
    private lateinit var cpuSpinner: Spinner
    private lateinit var ramBar: SeekBar
    private lateinit var ramLabel: TextView
    private lateinit var diskBar: SeekBar
    private lateinit var diskLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("kimios", MODE_PRIVATE) }
    private var sidebarOpen = true
    private var vmRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(QemuService.EXTRA_MESSAGE) ?: return
            statusView.text = message
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            if (sidebarOpen) {
                refreshLogs()
                refreshMemory()
            }
            if (vmRunning && !QemuService.active) {
                vmRunning = false
                bootButton.text = "Boot KimiOS"
                showSidebar(true)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        sidebar = buildSidebar()
        root.addView(sidebar, FrameLayout.LayoutParams(dp(340), -1))

        toggleButton = Button(this).apply {
            text = "◀"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(140, 20, 20, 26))
            setOnClickListener { showSidebar(!sidebarOpen) }
        }
        root.addView(
            toggleButton,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(6), dp(6), 0, 0)
            }
        )
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(QemuService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        NativeBridge.firstFrameListener = {
            handler.post {
                statusView.text = "KimiOS is alive — first frame rendered"
                if (vmRunning) showSidebar(false)
            }
        }

        loadSettings()
        refreshDisk()
        handler.post(poller)
        hideSystemBars()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(statusReceiver)
        NativeBridge.firstFrameListener = null
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
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun buildSidebar(): ScrollView {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.argb(235, 12, 12, 17))
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(column, ViewGroup.LayoutParams(-1, -2))

        column.addView(title("KimiOS"))
        statusView = body("VM stopped")
        column.addView(statusView)

        column.addView(title("Virtual machine"))
        column.addView(label("Resolution (framebuffer)"))
        resolutionSpinner = Spinner(this)
        resolutionSpinner.adapter = darkAdapter(RESOLUTIONS)
        column.addView(resolutionSpinner)

        column.addView(label("vCPUs"))
        cpuSpinner = Spinner(this)
        cpuSpinner.adapter = darkAdapter(CPUS.map { "$it core${if (it > 1) "s" else ""}" })
        column.addView(cpuSpinner)

        column.addView(label("RAM"))
        ramLabel = body("512 MB")
        column.addView(ramLabel)
        ramBar = SeekBar(this)
        ramBar.max = (RAM_MAX - RAM_MIN) / RAM_STEP
        ramBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                ramLabel.text = "${ramValue()} MB"
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        column.addView(ramBar)

        column.addView(label("Disk size"))
        diskLabel = body("8 GB")
        column.addView(diskLabel)
        diskBar = SeekBar(this)
        diskBar.max = (DISK_MAX - DISK_MIN) / DISK_STEP
        diskBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                diskLabel.text = "${diskValue()} GB"
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        column.addView(diskBar)
        column.addView(hint("Changing disk size recreates the disk and erases its files."))

        bootButton = Button(this).apply {
            text = "Boot KimiOS"
            setOnClickListener { if (vmRunning) stopVm() else bootVm() }
        }
        column.addView(bootButton)

        column.addView(title("Boot log"))
        logView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(170, 200, 170))
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        column.addView(logView, LinearLayout.LayoutParams(-1, dp(220)))

        column.addView(title("Display bridge"))
        bridgeView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(150, 190, 220))
        }
        column.addView(bridgeView)

        column.addView(title("Memory"))
        memoryView = body("—")
        column.addView(memoryView)

        column.addView(title("Guest disk"))
        diskInfoView = body("—")
        column.addView(diskInfoView)
        diskFilesView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(200, 200, 210))
        }
        column.addView(diskFilesView)
        val insertButton = Button(this).apply {
            text = "Insert file (.exe or any)"
            setOnClickListener { pickFile() }
        }
        column.addView(insertButton)
        column.addView(hint("The VM is stopped while inserting. Files land in /disk — run them in the KimiOS Terminal, e.g. run /disk/game.exe"))

        return scroll
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.rgb(224, 53, 43))
        setPadding(0, dp(18), 0, dp(6))
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.rgb(150, 150, 165))
        setPadding(0, dp(8), 0, 0)
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(230, 230, 240))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.rgb(120, 120, 135))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun darkAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).also {
                    (it as TextView).setTextColor(Color.WHITE)
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).also {
                    (it as TextView).apply {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.rgb(25, 25, 32))
                    }
                }
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun ramValue(): Int = RAM_MIN + ramBar.progress * RAM_STEP
    private fun diskValue(): Int = DISK_MIN + diskBar.progress * DISK_STEP

    private fun loadSettings() {
        resolutionSpinner.setSelection(prefs.getInt("resolution", 2))
        cpuSpinner.setSelection(prefs.getInt("cpu", 1))
        ramBar.progress = (prefs.getInt("ram", 512) - RAM_MIN) / RAM_STEP
        diskBar.progress = (prefs.getInt("disk", DISK_MIN) - DISK_MIN) / DISK_STEP
        ramLabel.text = "${ramValue()} MB"
        diskLabel.text = "${diskValue()} GB"
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("resolution", resolutionSpinner.selectedItemPosition)
            .putInt("cpu", cpuSpinner.selectedItemPosition)
            .putInt("ram", ramValue())
            .putInt("disk", diskValue())
            .apply()
    }

    private fun bootVm() {
        saveSettings()
        val (w, h) = RESOLUTIONS[resolutionSpinner.selectedItemPosition]
            .split(" x ").let { it[0].toInt() to it[1].toInt() }
        vmRunning = true
        bootButton.text = "Stop VM"
        statusView.text = "Starting…"
        logView.text = ""
        QemuService.start(this, w, h, ramValue(), CPUS[cpuSpinner.selectedItemPosition], diskValue())
        NativeBridge.nativeSurfaceCreated(surfaceView.holder.surface)
        refreshDisk()
    }

    private fun stopVm() {
        stopService(Intent(this, QemuService::class.java))
        vmRunning = false
        bootButton.text = "Boot KimiOS"
        statusView.text = "VM stopped"
        refreshDisk()
    }

    private fun showSidebar(open: Boolean) {
        sidebarOpen = open
        sidebar.visibility = if (open) View.VISIBLE else View.GONE
        toggleButton.text = if (open) "◀" else "☰"
        if (open) {
            refreshLogs()
            refreshMemory()
            refreshDisk()
        }
    }

    private fun tail(file: File, bytes: Int): String {
        if (!file.exists()) return ""
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = maxOf(0, length - bytes)
                raf.seek(start)
                val buffer = ByteArray((length - start).toInt())
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun refreshLogs() {
        bridgeView.text = try {
            NativeBridge.nativeGetLog().ifEmpty { "waiting for native bridge…" }
        } catch (e: Throwable) {
            "bridge error: ${e.message}"
        }
        val qemu = tail(File(filesDir, "qemu.log"), 4000)
        val serial = tail(File(filesDir, "serial.log"), 3000)
        val text = "── QEMU ──\n$qemu\n── KimiOS serial ──\n$serial"
        if (logView.text.toString() != text) {
            logView.text = text
            logView.post {
                val layout = logView.layout ?: return@post
                val scroll = layout.getLineTop(logView.lineCount) - logView.height
                logView.scrollTo(0, maxOf(0, scroll))
            }
        }
        statusView.text = QemuService.lastStatus
    }

    private fun refreshMemory() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val gb = 1024.0 * 1024 * 1024
        val builder = StringBuilder()
        builder.append("Device RAM: %.1f / %.1f GB free\n".format(info.availMem / gb, info.totalMem / gb))
        builder.append("Guest RAM: ${ramValue()} MB\n")
        val pid = QemuService.qemuPid
        if (pid > 0) {
            val rss = try {
                File("/proc/$pid/status").readLines()
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.replace(Regex("[^0-9]"), "")?.toLongOrNull()
            } catch (e: Exception) {
                null
            }
            if (rss != null) {
                builder.append("QEMU process RSS: ${rss / 1024} MB (pid $pid)")
            } else {
                builder.append("QEMU process: pid $pid")
            }
        } else {
            builder.append("QEMU process: not running")
        }
        memoryView.text = builder.toString()
    }

    private fun refreshDisk() {
        Thread {
            val disk = File(filesDir, "kimios/disk.img")
            val text = try {
                KimiFs(disk).use { fs ->
                    val entries = fs.list()
                    val info = "Disk: %.1f GB, %.1f GB free, %d file(s)".format(
                        fs.totalSectors * 512 / 1e9, fs.freeBytes() / 1e9, entries.size
                    )
                    val lines = entries.joinToString("\n") { e ->
                        val size = if (e.isDir) "<dir>" else humanSize(e.size)
                        "  ${e.name}  ($size)"
                    }
                    info + "\n" + if (lines.isEmpty()) "  (empty)" else lines
                }
            } catch (e: Exception) {
                "Disk not ready: ${e.message}"
            }
            runOnUiThread {
                diskInfoView.text = text.substringBefore('\n')
                diskFilesView.text = text.substringAfter('\n', "")
            }
        }.start()
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(intent, PICK_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { insertUri(it) }
        }
    }

    private fun documentName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "file.bin"
    }

    private fun documentSize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
        return -1
    }

    private fun insertUri(uri: Uri) {
        statusView.text = "Inserting file…"
        Thread {
            var temp: File? = null
            try {
                if (QemuService.active) {
                    runOnUiThread { stopVm() }
                    var waited = 0
                    while (QemuService.active && waited < 15000) {
                        Thread.sleep(200)
                        waited += 200
                    }
                    if (QemuService.active) error("VM did not stop in time")
                }

                val name = documentName(uri)
                var size = documentSize(uri)
                val stream: InputStream
                if (size < 0) {
                    val copy = File.createTempFile("kimios-insert", null, cacheDir)
                    temp = copy
                    contentResolver.openInputStream(uri)!!.use { input ->
                        copy.outputStream().use { input.copyTo(it) }
                    }
                    size = copy.length()
                    stream = FileInputStream(copy)
                } else {
                    stream = contentResolver.openInputStream(uri)!!
                }

                val disk = File(filesDir, "kimios/disk.img")
                val entry = KimiFs(disk).use { it.insert(name, stream, size) }
                runOnUiThread {
                    Toast.makeText(this, "Inserted /disk/${entry.name}", Toast.LENGTH_LONG).show()
                    statusView.text = "Inserted /disk/${entry.name} — boot and run it from the Terminal"
                    refreshDisk()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Insert failed: ${e.message}", Toast.LENGTH_LONG).show()
                    statusView.text = "Insert failed: ${e.message}"
                }
            } finally {
                temp?.delete()
            }
        }.start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.nativeSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeBridge.nativeSurfaceDestroyed()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sidebarOpen) return super.onTouchEvent(event)
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
        val qcode = qcodeFor(keyCode)
        if (qcode != null && !sidebarOpen) {
            NativeBridge.nativeKey(qcode, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val qcode = qcodeFor(keyCode)
        if (qcode != null && !sidebarOpen) {
            NativeBridge.nativeKey(qcode, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
