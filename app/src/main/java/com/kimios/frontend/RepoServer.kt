package com.kimios.frontend

import android.content.Context
import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny static HTTP server that serves the bundled kpkg repository
 * (assets/kpkg/) on 127.0.0.1:8080. QEMU user-mode NAT maps the guest's
 * 10.0.2.2 to the host loopback, so `kpkg update` / `kpkg install` inside
 * KimiOS reach this server out of the box — no external network needed.
 */
class RepoServer(private val context: Context) {
    companion object {
        private const val TAG = "KimiOS-Repo"
        private const val PORT = 8080
    }

    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "kpkg-repo") {
            try {
                val s = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                server = s
                Log.i(TAG, "kpkg repo serving on 127.0.0.1:$PORT")
                while (running) {
                    val client = try {
                        s.accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread { handle(client) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "repo server not started: ${e.message}")
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 5000
                val reader = c.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    respond(c, 405, ByteArray(0))
                    return
                }
                var path = parts[1].substringBefore('?')
                if (path.startsWith("/")) path = path.substring(1)
                // Drain headers.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                if (path.isEmpty() || path.contains("..") || path.contains('/')) {
                    respond(c, 404, ByteArray(0))
                    return
                }
                val data = try {
                    context.assets.open("kpkg/$path").use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
                if (data == null) {
                    respond(c, 404, ByteArray(0))
                    return
                }
                respond(c, 200, data)
            } catch (e: Exception) {
                Log.d(TAG, "request failed: ${e.message}")
            }
        }
    }

    private fun respond(c: Socket, code: Int, body: ByteArray) {
        val reason = if (code == 200) "OK" else "Error"
        val header = "HTTP/1.0 $code $reason\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }
}