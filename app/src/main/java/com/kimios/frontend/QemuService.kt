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

class QemuService : Service() {
    companion object {
        const val ACTION_STATUS = "com.kimios.frontend.STATUS"
        const val EXTRA_MESSAGE = "message"
        private const val TAG = "KimiOS-Service"
        private const val CHANNEL_ID = "kimios-vm"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, QemuService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_text)))

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kimios:qemu").apply {
            acquire(12 * 60 * 60 * 1000L)
        }

        Thread({ boot() }, "qemu-boot").start()
        return START_STICKY
    }

    private fun boot() {
        try {
            val binary = AssetInstaller.install(this)
            if (binary == null) {
                status("QEMU binary missing: run scripts/install-qemu.sh and rebuild")
                stopSelf()
                return
            }

            val filesDir = filesDir
            val qmpSocket = File(filesDir, "qmp.sock")
            qmpSocket.delete()
            NativeBridge.nativeInit(qmpSocket.absolutePath)

            val command = listOf(
                binary.absolutePath,
                "-machine", "virt,gic-version=3",
                "-cpu", "cortex-a53",
                "-m", "128M",
                "-display", "vnc=127.0.0.1:5900",
                "-qmp", "unix:${qmpSocket.absolutePath},server,nowait",
                "-device", "ramfb",
                "-device", "virtio-keyboard-device",
                "-device", "virtio-tablet-device",
                "-drive", "if=none,format=raw,file=${File(filesDir, "kimios/disk.img").absolutePath},id=hd0",
                "-device", "virtio-blk-device,drive=hd0",
                "-dtb", File(filesDir, "kimios/virt.dtb").absolutePath,
                "-L", File(filesDir, "qemu/share/qemu").absolutePath,
                "-semihosting-config", "enable=on,target=native",
                "-serial", "file:${File(filesDir, "serial.log").absolutePath}",
                "-kernel", File(filesDir, "kimios/kimios").absolutePath
            )

            val builder = ProcessBuilder(command)
                .directory(filesDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(File(filesDir, "qemu.log")))
            builder.environment()["LD_LIBRARY_PATH"] = File(filesDir, "qemu/lib").absolutePath
            builder.environment()["TMPDIR"] = filesDir.absolutePath

            status("Starting QEMU")
            process = builder.start()
            status("KimiOS booting")

            val code = process?.waitFor() ?: -1
            Log.i(TAG, "qemu exited with $code")
            status("QEMU exited (code $code); see qemu.log")
        } catch (e: Exception) {
            Log.e(TAG, "boot failed", e)
            status("QEMU failed: ${e.message}")
        } finally {
            stopSelf()
        }
    }

    private fun status(message: String) {
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
        running = false
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        NativeBridge.nativeShutdown()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }
}
