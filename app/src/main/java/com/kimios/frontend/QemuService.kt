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
    private var wakeLock: PowerManager.WakeLock? = null
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

            val command = listOf(
                binary.absolutePath,
                "-machine", "virt,gic-version=3",
                "-cpu", "cortex-a53",
                "-smp", vcpus.toString(),
                "-m", "${ramMb}M",
                "-display", "none",
                "-vnc", "127.0.0.1:0",
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
                "-serial", "file:${File(filesDir, "serial.log").absolutePath}",
                "-append", "kimios.fb=${width}x${height}",
                "-kernel", File(filesDir, "kimios/kimios").absolutePath
            )

            val builder = ProcessBuilder(command)
                .directory(filesDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(File(filesDir, "qemu.log")))
            builder.environment()["LD_LIBRARY_PATH"] = File(filesDir, "qemu/lib").absolutePath
            builder.environment()["TMPDIR"] = filesDir.absolutePath

            status("Starting QEMU: $vcpus vCPU, $ramMb MB RAM, ${width}x${height}")
            File(filesDir, "qemu.log").appendText("\n=== boot ${System.currentTimeMillis()} ===\n")
            process = builder.start()
            qemuPid = findPid()
            status("KimiOS booting (pid $qemuPid) — watch the serial log")

            val code = process?.waitFor() ?: -1
            Log.i(TAG, "qemu exited with $code")
            status("QEMU exited (code $code) — see qemu.log")
        } catch (e: Exception) {
            Log.e(TAG, "boot failed", e)
            status("QEMU failed: ${e.message}")
        } finally {
            qemuPid = -1
            active = false
            wakeLock?.let { if (it.isHeld) it.release() }
            stopSelf()
        }
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
        NativeBridge.nativeShutdown()
        qemuPid = -1
        active = false
        super.onDestroy()
    }
}
