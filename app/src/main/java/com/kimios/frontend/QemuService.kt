package com.kimios.frontend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

class QemuService : Service() {
    companion object {
        const val ACTION_STATUS = "com.kimios.frontend.STATUS"
        const val EXTRA_MESSAGE = "message"
        private const val TAG = "KimiOS-Service"
        private const val CHANNEL_ID = "kimios-vm"
        private const val NOTIFICATION_ID = 1

        @Volatile var active = false
        @Volatile var qemuPid = -1
        @Volatile var lastStatus = "VM stopped"

        fun start(context: Context, width: Int, height: Int, ramMb: Int, vcpus: Int, diskGb: Int) {
            val intent = Intent(context, QemuService::class.java)
                .putExtra("width", width)
                .putExtra("height", height)
                .putExtra("ramMb", ramMb)
                .putExtra("vcpus", vcpus)
                .putExtra("diskGb", diskGb)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var process: Process? = null
    private var repo: RepoServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shmWatcher: Thread? = null
    private var ramFile: File? = null
    private var width = 1080
    private var height = 1920
    private var ramMb = 512
    private var vcpus = 2
    private var diskGb = 8

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active) return START_STICKY
        intent?.let {
            width = it.getIntExtra("width", width)
            height = it.getIntExtra("height", height)
            ramMb = it.getIntExtra("ramMb", ramMb)
            vcpus = it.getIntExtra("vcpus", vcpus)
            diskGb = it.getIntExtra("diskGb", diskGb)
        }
        active = true
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(lastStatus))

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kimios:qemu").apply {
            acquire(12 * 60 * 60 * 1000L)
        }

        Thread({ boot() }, "qemu-boot").start()
        return START_STICKY
    }

    private fun boot() {
        try {
            status("Extracting assets (first boot takes a while)")
            val binary = AssetInstaller.install(this)
            if (binary == null) {
                status("QEMU binary missing from the APK")
                return
            }

            val filesDir = filesDir
            val disk = File(filesDir, "kimios/disk.img")
            val desired = diskGb.toLong() * 1024 * 1024 * 1024
            if (!disk.exists() || disk.length() != desired) {
                status("Creating $diskGb GB disk (KimiFS will format it)")
                disk.delete()
                RandomAccessFile(disk, "rw").use { it.setLength(desired) }
            }

            val qmpSocket = File(filesDir, "qmp.sock")
            qmpSocket.delete()
            NativeBridge.nativeInit(qmpSocket.absolutePath)

            // Serve the bundled kpkg repo to the guest (10.0.2.2:8080).
            repo = RepoServer(this).also { it.start() }

            val serialLog = File(filesDir, "serial.log")
            val ramBytes = ramMb.toLong() * 1024 * 1024
            val ram = File(filesDir, "ram.img")
            ramFile = ram
            NativeBridge.nativeShmStop()
            ram.delete()
            RandomAccessFile(ram, "rw").use { it.setLength(ramBytes) }

            var useShm = true
            while (true) {
                serialLog.delete()
                val command = buildCommand(binary, disk, qmpSocket, serialLog, ram, useShm)
                val builder = ProcessBuilder(command)
                    .directory(filesDir)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(File(filesDir, "qemu.log")))
                builder.environment()["LD_LIBRARY_PATH"] = File(filesDir, "qemu/lib").absolutePath
                builder.environment()["TMPDIR"] = filesDir.absolutePath

                val mode = if (useShm) " (shared RAM)" else ""
                status("Starting QEMU: $vcpus vCPU, $ramMb MB RAM, ${width}x${height}$mode")
                File(filesDir, "qemu.log").appendText("\n=== boot ${System.currentTimeMillis()} ===\n")
                process = builder.start()
                qemuPid = findPid()
                if (useShm) startShmWatcher(serialLog, ram, ramBytes)
                status("KimiOS booting (pid $qemuPid) — watch the serial log")

                if (process?.waitFor(6, java.util.concurrent.TimeUnit.SECONDS) == true) {
                    stopShmWatcher()
                    if (useShm) {
                        status("Shared RAM backend rejected by QEMU — retrying with private RAM (VNC)")
                        useShm = false
                        continue
                    }
                }
                break
            }

            val code = process?.waitFor() ?: -1
            Log.i(TAG, "qemu exited with $code")
            status("QEMU exited (code $code) — see qemu.log")
        } catch (e: Exception) {
            Log.e(TAG, "boot failed", e)
            status("QEMU failed: ${e.message}")
        } finally {
            stopShmWatcher()
            qemuPid = -1
            active = false
            repo?.stop()
            wakeLock?.let { if (it.isHeld) it.release() }
            stopSelf()
        }
    }

    private fun buildCommand(
        binary: File,
        disk: File,
        qmpSocket: File,
        serialLog: File,
        ram: File,
        useShm: Boolean
    ): List<String> {
        val command = mutableListOf(
            binary.absolutePath,
            "-machine", if (useShm) "virt,gic-version=3,memory-backend=ram" else "virt,gic-version=3",
            "-cpu", "cortex-a53",
            "-smp", vcpus.toString(),
            "-m", "${ramMb}M",
            "-display", "none",
            "-vnc", "127.0.0.1:0,non-adaptive=on,lossy=off",
            "-qmp", "unix:${qmpSocket.absolutePath},server,nowait",
            "-device", "ramfb",
            "-device", "virtio-keyboard-device",
            "-device", "virtio-tablet-device",
            "-netdev", "user,id=n0",
            "-device", "virtio-net-device,netdev=n0",
            "-drive", "if=none,format=raw,file=${disk.absolutePath},id=hd0",
            "-device", "virtio-blk-device,drive=hd0",
            "-dtb", File(filesDir, "kimios/virt.dtb").absolutePath,
            "-L", File(filesDir, "qemu/share/qemu").absolutePath,
            "-rtc", "base=localtime,clock=host",
            "-semihosting-config", "enable=on,target=native",
            "-serial", "file:${serialLog.absolutePath}",
            "-append", "kimios.fb=${width}x${height}",
            "-kernel", File(filesDir, "kimios/kimios").absolutePath
        )
        if (useShm) {
            command += listOf(
                "-object",
                "memory-backend-file,id=ram,size=${ramMb}M,mem-path=${ram.absolutePath},share=on"
            )
        }
        return command
    }

    private fun startShmWatcher(serialLog: File, ram: File, ramBytes: Long) {
        val pattern = Regex("""ramfb\s*:\s*(\d+)x(\d+)\s+XRGB8888 at (0x[0-9a-fA-F]+)""")
        shmWatcher = Thread({
            // Nested TCG (QEMU inside a virtual Android device) can boot very
            // slowly; give the serial line plenty of time to appear.
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                if (process?.isAlive == false) return@Thread
                try {
                    if (serialLog.exists()) {
                        val match = pattern.find(serialLog.readText())
                        if (match != null) {
                            val w = match.groupValues[1].toInt()
                            val h = match.groupValues[2].toInt()
                            val phys = java.lang.Long.parseLong(
                                match.groupValues[3].removePrefix("0x"), 16)
                            val offset = phys - 0x40000000L
                            val stride = w * 4
                            if (offset < 0 || offset + stride.toLong() * h > ramBytes) {
                                status("Display: VNC (framebuffer outside shared RAM)")
                                return@Thread
                            }
                            Log.i(TAG, "ramfb line found: ${w}x${h} phys=0x${
                                java.lang.Long.toHexString(phys)} offset=0x${
                                java.lang.Long.toHexString(offset)}")
                            val ok = NativeBridge.nativeShmStart(
                                ram.absolutePath, ramBytes, offset, w, h, stride)
                            Log.i(TAG, if (ok) "shared-memory display active (VNC bypassed)"
                                else "shared mapping failed — staying on VNC")
                            status(
                                if (ok) "Shared-memory display active — VNC bypassed"
                                else "Display: VNC (shared mapping failed)"
                            )
                            return@Thread
                        }
                    }
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            Log.i(TAG, "no ramfb line in serial within deadline — staying on VNC")
            status("Display: VNC (no framebuffer line in serial)")
        }, "shm-watcher")
        shmWatcher?.start()
    }

    private fun stopShmWatcher() {
        shmWatcher?.interrupt()
        shmWatcher = null
    }

    private fun findPid(): Int {
        val proc = File("/proc")
        val children = proc.listFiles() ?: return -1
        for (dir in children) {
            if (!dir.isDirectory || !dir.name.all { it.isDigit() }) continue
            try {
                val cmdline = File(dir, "cmdline").readBytes()
                val text = String(cmdline, Charsets.US_ASCII)
                if (text.contains("libqemu.so")) {
                    return dir.name.toInt()
                }
            } catch (_: Exception) {
            }
        }
        return -1
    }

    private fun status(message: String) {
        lastStatus = message
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message)
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kimios)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        stopShmWatcher()
        repo?.stop()
        NativeBridge.nativeShutdown()
        ramFile?.delete()
        ramFile = null
        qemuPid = -1
        active = false
        super.onDestroy()
    }
}
