package app.vkturn.tunnel

import android.content.Context
import android.util.Log
import app.vkturn.proxy.LogLevel
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Glue between [VkturnVpnService] and `com.wireguard.android:tunnel`'s
 * [GoBackend].
 *
 * Important: [GoBackend] always brings up **its own** [GoBackend.VpnService]
 * tunnel via [VpnService.Builder.establish] — it never attaches to an fd we
 * opened separately. Previously we called [Builder.establish] in
 * [VkturnVpnService] and then started GoBackend anyway; that fights for the
 * single VPN slot and wireguard-go never owned the live tun, so vk-turn-proxy
 * could show `dtls_ready` while user traffic never rode WireGuard.
 *
 * Correct flow: [VkturnVpnService] must **not** call establish(); orchestration
 * matches WINGSV — warm up [GoBackend], start [GoBackend.VpnService] before the
 * vk-turn `-protect-sock` bridge, then [setState]. `-protect-sock` must call
 * [VpnService.protect] on [GoBackend.VpnService] ([GoBackendVpnAccess]), not on
 * [VkturnVpnService].
 *
 * wireguard-android doesn't ship AmneziaWG support — identities that use the
 * AmneziaWG flavor need a different backend later; here we only gate on
 * whether [Config.parse] accepts the body.
 */
object WgBackendBridge {
    private val backend = AtomicReference<Backend?>(null)
    private val activeTunnel = AtomicReference<VkturnTunnel?>(null)

    /** Construct [GoBackend] before starting [GoBackend.VpnService] / protect bridge (WINGSV order). */
    fun warmUp(ctx: Context) {
        if (backend.get() != null) return
        synchronized(this) {
            if (backend.get() == null) {
                backend.set(GoBackend(ctx.applicationContext))
            }
        }
    }

    fun start(ctx: Context, wgConfig: String): Boolean {
        warmUp(ctx)
        val cfg = try {
            Config.parse(ByteArrayInputStream(wgConfig.toByteArray(Charsets.UTF_8)))
        } catch (t: Throwable) {
            Log.e(TAG, "WG config parse failed", t)
            TunnelLogSink.bus?.appendApp(
                "WG: ошибка разбора конфига: ${t.message?.take(160) ?: t.javaClass.simpleName}",
                LogLevel.ERROR,
            )
            return false
        }
        val be = backend.get() ?: run {
            TunnelLogSink.bus?.appendApp("WG: GoBackend не инициализирован", LogLevel.ERROR)
            return false
        }
        val tun = VkturnTunnel("vkturn")
        activeTunnel.set(tun)

        TunnelLogSink.bus?.appendApp("WG: вызываем GoBackend.setState(UP)…", LogLevel.INFO)

        val err = AtomicReference<Throwable?>(null)
        val ok = AtomicReference(false)
        val worker = Thread({
            try {
                be.setState(tun, Tunnel.State.UP, cfg)
                ok.set(true)
                Log.i(TAG, "GoBackend started for tunnel ${tun.name}")
            } catch (t: Throwable) {
                err.set(t)
                Log.e(TAG, "GoBackend.setState(UP) failed", t)
            }
        }, "vkturn-wg-setstate")
        worker.start()
        worker.join(SET_STATE_JOIN_MS)
        if (worker.isAlive) {
            TunnelLogSink.bus?.appendApp(
                "WG: таймаут ${SET_STATE_JOIN_MS / 1000}s — GoBackend.setState не вернулся (проверьте Endpoint/DNS и что vk-turn слушает)",
                LogLevel.ERROR,
            )
            return false
        }
        if (!ok.get()) {
            val t = err.get()
            TunnelLogSink.bus?.appendApp(
                "WG: ${t?.message?.take(200) ?: "ошибка поднятия туннеля"}",
                LogLevel.ERROR,
            )
            return false
        }
        TunnelLogSink.bus?.appendApp("WG: setState(UP) выполнен", LogLevel.INFO)
        return true
    }

    fun stopAll() {
        activeTunnel.getAndSet(null)?.let { tun ->
            runCatching { backend.get()?.setState(tun, Tunnel.State.DOWN, null) }
        }
    }

    private class VkturnTunnel(private val tunName: String) : Tunnel {
        override fun getName(): String = tunName
        override fun onStateChange(newState: Tunnel.State) {
            Log.i(TAG, "tunnel state -> $newState")
            TunnelLogSink.bus?.appendApp("WG: туннель → $newState", LogLevel.INFO)
        }
    }

    private const val TAG = "vkturn/wg-backend"
    private const val SET_STATE_JOIN_MS = 90_000L
}
