package app.vkturn.daemon

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/**
 * JDK 16+ has first-class UNIX domain socket support in [SocketChannel],
 * so we don't need JNA/libc bindings. macOS is supported out of the box;
 * Windows named pipes will need a different implementation in a follow-up
 * because [StandardProtocolFamily.UNIX] on Windows requires a recent
 * build and AFUNIX.sys.
 */
private class UnixDaemonClient(private val address: DaemonAddress) : DaemonClient {
    private var channel: SocketChannel? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private val lock = Any()

    override val isConnected: Boolean
        get() = channel?.isOpen == true

    override fun connect(): DaemonResponse {
        synchronized(lock) {
            if (channel?.isOpen == true) {
                val ping = request(DaemonRequest(op = "hello"))
                if (ping.ok) return ping
                close()
                return DaemonResponse(
                    ok = false,
                    error = ping.error.orEmpty().ifBlank { "daemon hello failed" },
                )
            }
            return runCatching {
                val ch =
                    SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                        configureBlocking(true)
                        connect(UnixDomainSocketAddress.of(address.path))
                    }
                channel = ch
                reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), Charsets.UTF_8))
                writer = OutputStreamWriter(Channels.newOutputStream(ch), Charsets.UTF_8)
                request(DaemonRequest(op = "hello"))
            }.getOrElse { t ->
                close()
                DaemonResponse(ok = false, error = t.message ?: t.javaClass.simpleName ?: "connect failed")
            }
        }
    }

    override fun request(req: DaemonRequest): DaemonResponse {
        synchronized(lock) {
            val w = writer ?: return DaemonResponse(ok = false, error = "not connected")
            val r = reader ?: return DaemonResponse(ok = false, error = "not connected")
            try {
                w.write(json.encodeToString(req))
                w.write("\n")
                w.flush()
                val line = r.readLine() ?: return DaemonResponse(ok = false, error = "daemon closed connection")
                return json.decodeFromString<DaemonResponse>(line)
            } catch (t: Throwable) {
                close()
                return DaemonResponse(ok = false, error = t.message ?: t::class.simpleName.orEmpty())
            }
        }
    }

    override fun subscribeLogs(routeId: String, onLine: (String) -> Unit): () -> Unit {
        val streamChannel = runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                configureBlocking(true)
                connect(UnixDomainSocketAddress.of(address.path))
            }
        }.getOrNull() ?: return { /* noop */ }

        val streamReader = BufferedReader(InputStreamReader(Channels.newInputStream(streamChannel), Charsets.UTF_8))
        val streamWriter = OutputStreamWriter(Channels.newOutputStream(streamChannel), Charsets.UTF_8)
        val stopped = AtomicBoolean(false)

        streamWriter.write(json.encodeToString(DaemonRequest(op = "logs", id = routeId, follow = true)))
        streamWriter.write("\n")
        streamWriter.flush()

        val thread = Thread({
            try {
                while (!stopped.get()) {
                    val line = streamReader.readLine() ?: break
                    val resp = runCatching { json.decodeFromString<DaemonResponse>(line) }.getOrNull() ?: continue
                    resp.line?.let(onLine)
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { streamChannel.close() }
            }
        }, "vkturnd-logs-$routeId").apply { isDaemon = true; start() }

        return {
            stopped.set(true)
            runCatching { streamChannel.close() }
            thread.interrupt()
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { channel?.close() }
            channel = null
            reader = null
            writer = null
        }
    }
}

actual fun createDaemonClient(address: DaemonAddress): DaemonClient = UnixDaemonClient(address)

actual fun defaultDaemonAddress(): DaemonAddress {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    return when {
        os.contains("win") -> DaemonAddress("""\\.\pipe\vkturn-control""")
        else -> DaemonAddress("/run/vkturn/control.sock")
    }
}
