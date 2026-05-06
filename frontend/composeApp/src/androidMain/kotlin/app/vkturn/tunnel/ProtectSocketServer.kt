package app.vkturn.tunnel

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.VpnService
import android.util.Log
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge that implements the `-protect-sock` contract expected by the Go
 * `vk-turn-proxy` client: the child connects to an abstract-namespace
 * UNIX socket and sends one file descriptor per message; we hand it to
 * [VpnService.protect] so the descriptor bypasses the **WireGuard** VPN
 * session (see [GoBackendVpnAccess] — must be the same [VpnService] that
 * owns the established TUN).
 *
 * The Go client always prepends `@` to the passed-in name
 * (see `protect_bridge.go`), so the name we register here is the raw
 * identifier the client was launched with — abstract namespace matches
 * [LocalServerSocket]'s default without any extra config.
 */
class ProtectSocketServer(
    /** Same contract as WINGSV `ProxyProtectBridgeServer.VpnServiceProvider`. */
    private val vpnProvider: () -> VpnService?,
    /** Abstract-namespace name; must match `-protect-sock` argv value. */
    private val name: String,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var server: LocalServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::runLoop, "vkturn-protect-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        thread?.interrupt()
    }

    private fun runLoop() {
        try {
            server = LocalServerSocket(name)
            while (running.get()) {
                val client = server!!.accept() ?: break
                handle(client)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "protect-sock loop exiting", t)
        }
    }

    private fun handle(client: LocalSocket) {
        client.use { c ->
            val buf = ByteArray(16)
            while (running.get()) {
                val read = try { c.inputStream.read(buf) } catch (_: Throwable) { -1 }
                if (read <= 0) break
                val fds = c.ancillaryFileDescriptors ?: continue
                for (fd in fds) {
                    if (fd == null) continue
                    val native = extractNativeFd(fd)
                    if (native >= 0) {
                        vpnProvider()?.protect(native)
                    }
                    runCatching { closeFd(fd) }
                }
                // ack
                runCatching { c.outputStream.write(byteArrayOf(1)) }
            }
        }
    }

    /**
     * [FileDescriptor] hides its native int. Reflection into `getInt$`
     * is the only stable way — AOSP has kept that name since API 1. If
     * it ever disappears we'll surface a clear error rather than mis-behave.
     */
    private fun extractNativeFd(fd: FileDescriptor): Int {
        return try {
            val m: Method = FileDescriptor::class.java.getDeclaredMethod("getInt\$").apply { isAccessible = true }
            m.invoke(fd) as Int
        } catch (t: Throwable) {
            Log.e(TAG, "cannot read native fd", t); -1
        }
    }

    private fun closeFd(fd: FileDescriptor) {
        runCatching {
            val m = FileDescriptor::class.java.getDeclaredMethod("close").apply { isAccessible = true }
            m.invoke(fd)
        }
    }

    companion object { private const val TAG = "vkturn/protect" }
}
