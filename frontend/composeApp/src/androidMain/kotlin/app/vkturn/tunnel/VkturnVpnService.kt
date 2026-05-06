package app.vkturn.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import app.vkturn.MainActivity
import app.vkturn.proxy.LogClassifier
import app.vkturn.proxy.LogLevel
import app.vkturn.proxy.LogLine
import app.vkturn.proxy.LogOrigin
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Foreground orchestrator for vk-turn-proxy + WireGuard on Android.
 *
 * Same invariants as WINGSV `ProxyTunnelService` + userspace WG path:
 * we never call [Builder.establish] here; [GoBackend.VpnService] owns the TUN.
 * The vk-turn `-protect-sock` bridge must call [VpnService.protect] on that same
 * inner service ([GoBackendVpnAccess]), not on [VkturnVpnService].
 *
 * Startup order when proxy + WG: warmUp [GoBackend] → start inner VpnService →
 * protect bridge → vk-turn-proxy → wait for `-listen` TCP + `dtls_ready` →
 * [WgBackendBridge.start] → verify public IP via VPN network.
 *
 * The service publishes each phase into [TunnelStatusBus]; the UI runner
 * ([app.vkturn.proxy.AndroidVpnRunner]) maps these into a [RouteStatus] and
 * shows **CONNECTED** only after `dtls_ready` + successful WG handshake + IP
 * probe via VPN network differing from the baseline.
 *
 * Bootstrap runs on a background thread: blocking on `GoBackend`'s
 * [CompletableFuture] from [onStartCommand] would deadlock the main thread,
 * because `GoBackend.VpnService.onCreate` completes that future on the main looper.
 */
class VkturnVpnService : VpnService() {

    private var proxy: Process? = null
    private var protectServer: ProtectSocketServer? = null
    private val bootstrapAborted = AtomicBoolean(false)
    private val dtlsReady = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent, startId)
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handleStop()
    }

    private fun handleStart(intent: Intent, startId: Int) {
        bootstrapAborted.set(false)
        dtlsReady.set(false)
        startForegroundOngoing()

        Thread({
            try {
                runBootstrap(intent, startId)
            } catch (t: Throwable) {
                Log.e("VkturnVpnService", "VPN bootstrap failed", t)
                val msg = t.message?.take(200) ?: t.javaClass.simpleName
                TunnelLogSink.bus?.appendApp("VPN: bootstrap: $msg", LogLevel.ERROR)
                TunnelStatusBus.error("Ошибка запуска: $msg")
                stopSelf()
            }
        }, "vkturn-vpn-bootstrap").start()
    }

    private fun runBootstrap(intent: Intent, startId: Int) {
        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty().ifBlank { "route" }
        val routeLabel = intent.getStringExtra(EXTRA_ROUTE_LABEL).orEmpty().ifBlank { "vpn" }

        // Baseline IP must run off the main thread — HTTP here avoids NetworkOnMainThreadException
        // and matches «до VPN всё ещё дефолтный uplink».
        val baselineIp = PublicIpProbe.fetch(network = null, timeoutMs = 4000)
        TunnelStatusBus.beginSession(routeId, routeLabel, baselineIp)
        TunnelLogSink.bus?.appendApp(
            "VPN: baseline IP=${baselineIp ?: "не определён"}",
            LogLevel.INFO,
        )

        val proxyBin = intent.getStringExtra(EXTRA_PROXY_BIN)
        val proxyArgs = intent.getStringArrayExtra(EXTRA_PROXY_ARGS)
        val wgConf = intent.getStringExtra(EXTRA_WG_CONF)
        val needsProxy = !proxyBin.isNullOrBlank()
        val needsWg = !wgConf.isNullOrBlank()

        if (bootstrapAborted.get()) return

        if (needsProxy || needsWg) {
            WgBackendBridge.warmUp(applicationContext)
        }

        if (needsProxy) {
            TunnelStatusBus.phase(TunnelPhase.STARTING, "Поднимаем внутренний GoBackend.VpnService…")
            val inner = GoBackendVpnAccess.ensureServiceStarted(applicationContext)
            if (bootstrapAborted.get()) return
            if (inner == null) {
                TunnelLogSink.bus?.appendApp(
                    "VPN: не удалось поднять GoBackend.VpnService — без него protect(fd) бессмысленен",
                    LogLevel.ERROR,
                )
                TunnelStatusBus.error("Не удалось поднять внутренний VpnService")
                stopSelf()
                return
            }
            GoBackendVpnAccess.promoteServiceForeground(
                inner,
                FOREGROUND_ID_GO_BACKEND,
                buildOngoingNotification(),
            )

            val protectName = "vkturn-protect-$startId"
            protectServer = ProtectSocketServer(
                vpnProvider = { GoBackendVpnAccess.getServiceNow() },
                name = protectName,
            ).also { it.start() }

            val args = (proxyArgs?.toList().orEmpty()) + listOf("-protect-sock", protectName)
            TunnelStatusBus.phase(TunnelPhase.PROXY_STARTING, "Запуск vk-turn-proxy…")
            runCatching {
                val proc = ProcessBuilder(listOf(proxyBin!!) + args)
                    .redirectErrorStream(false)
                    .start()
                proxy = proc
                pumpProxyLogs(proc, routeId, routeLabel)
            }.onFailure { e ->
                TunnelLogSink.bus?.appendApp(
                    "proxy: не удалось запустить процесс: ${e.message}",
                    LogLevel.ERROR,
                )
                TunnelStatusBus.error("Не удалось запустить vk-turn-proxy: ${e.message.orEmpty()}")
                stopSelf()
                return
            }

            if (!waitForListenTcpOpen(proxyArgs)) {
                TunnelStatusBus.error("vk-turn-proxy не открыл `-listen`")
                stopSelf()
                return
            }
            TunnelStatusBus.phase(TunnelPhase.PROXY_LISTEN, "Ожидаем DTLS…")

            val dtlsOk = waitForDtlsReady()
            if (!dtlsOk) {
                TunnelStatusBus.error("vk-turn-proxy не достиг dtls_ready")
                stopSelf()
                return
            }
            TunnelLogSink.bus?.appendApp(
                "VPN: dtls_ready подтверждён — запускаем WireGuard…",
                LogLevel.INFO,
            )
            TunnelStatusBus.phase(TunnelPhase.DTLS_READY, "DTLS готов, поднимаем WireGuard…")
        }

        if (abortIfStopped()) return

        wgConf?.let { conf ->
            File(filesDir, "current.wg.conf").writeText(conf)
            TunnelLogSink.bus?.appendApp(
                "VPN: поднимаем WireGuard (peer Endpoint из конфига → локальный vk-turn)…",
                LogLevel.INFO,
            )
            val ok = WgBackendBridge.start(applicationContext, conf)
            if (!ok) {
                TunnelStatusBus.error("WireGuard не поднялся — см. строки WG: в логе")
                stopSelf()
                return
            }
            TunnelStatusBus.phase(TunnelPhase.WG_UP, "WireGuard поднят, проверяем маршрут…")
            verifyTunnelIp()
        } ?: run {
            // Pure proxy-only mode (no WG config): promote to CONNECTED once
            // DTLS is healthy — there's no tunnel to route via.
            if (needsProxy) TunnelStatusBus.phase(TunnelPhase.CONNECTED, "Прокси готов")
        }

        val parts = buildList {
            if (needsProxy) add("vk-turn-proxy")
            if (needsWg) add("WireGuard (GoBackend)")
        }
        TunnelLogSink.bus?.appendApp(
            "VPN: ${if (parts.isEmpty()) "ожидание конфигурации" else parts.joinToString(" + ")}",
            LogLevel.INFO,
        )
    }

    private fun abortIfStopped(): Boolean {
        if (!bootstrapAborted.get()) return false
        runCatching {
            proxy?.destroy()
            proxy = null
            protectServer?.stop()
            protectServer = null
        }
        return true
    }

    private fun verifyTunnelIp() {
        TunnelStatusBus.phase(TunnelPhase.VERIFYING_IP, "Ждём VPN-сеть и проверяем внешний IP…")
        val baseline = TunnelStatusBus.state.value.baselineIp
        val vpnReady = awaitVpnNetwork(VPN_NETWORK_WAIT_MS)
        if (vpnReady == null) {
            TunnelLogSink.bus?.appendApp(
                "VPN: TRANSPORT_VPN не появился за ${VPN_NETWORK_WAIT_MS / 1000}s — проверка IP может быть неточной",
                LogLevel.WARN,
            )
        }
        val deadline = SystemClock.elapsedRealtime() + IP_VERIFY_TIMEOUT_MS
        var lastIp: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (bootstrapAborted.get()) return
            val vpnNetwork = findVpnNetwork()
            val ip = PublicIpProbe.fetch(network = vpnNetwork, timeoutMs = 4000)
            if (ip != null) {
                lastIp = ip
                TunnelStatusBus.setTunnelIp(ip)
                if (baseline == null || ip != baseline) {
                    TunnelStatusBus.phase(TunnelPhase.CONNECTED, "Внешний IP: $ip")
                    TunnelLogSink.bus?.appendApp(
                        "VPN: внешний IP через туннель — $ip (baseline=${baseline ?: "n/a"})",
                        LogLevel.INFO,
                    )
                    return
                }
            }
            Thread.sleep(IP_VERIFY_POLL_MS)
        }
        val msg = if (lastIp != null) {
            "Туннель не маршрутизирует трафик (IP=$lastIp совпадает с baseline)"
        } else {
            "Не удалось получить внешний IP через туннель за ${IP_VERIFY_TIMEOUT_MS / 1000}c"
        }
        TunnelLogSink.bus?.appendApp("VPN: $msg", LogLevel.ERROR)
        TunnelStatusBus.error(msg)
    }

    private fun awaitVpnNetwork(timeoutMs: Long): Network? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (bootstrapAborted.get()) return null
            findVpnNetwork()?.let { return it }
            Thread.sleep(150)
        }
        return findVpnNetwork()
    }

    private fun findVpnNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = cm.allNetworks
        // Prefer a VPN that also has INTERNET capability — WG passes both.
        candidates.forEach { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@forEach
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return n
            }
        }
        return candidates.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    /** Matches [app.vkturn.proxy.ProxyArgs] `-transport` value (`datagram` | `tcp`). */
    private fun vkTurnTransportFlag(proxyArgs: Array<String>?): String {
        val list = proxyArgs?.toList().orEmpty()
        val idx = list.indexOf("-transport")
        if (idx < 0 || idx + 1 >= list.size) return "datagram"
        return list[idx + 1].trim().lowercase(Locale.US).ifBlank { "datagram" }
    }

    private fun parseListenEndpoint(args: List<String>): Pair<String, Int>? {
        val i = args.indexOf("-listen")
        if (i < 0 || i + 1 >= args.size) return null
        val raw = args[i + 1].trim()
        val colon = raw.lastIndexOf(':')
        if (colon <= 0 || colon >= raw.length - 1) return null
        val host = raw.substring(0, colon).trim()
        val port = raw.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return host to port
    }

    /** Rough parity with WINGSV `waitForProxyWarmup`: WG peers localhost after relay listens. */
    private fun waitForListenTcpOpen(proxyArgs: Array<String>?): Boolean {
        val transport = vkTurnTransportFlag(proxyArgs)
        // Datagram mode binds UDP on -listen (see vk-turn-proxy client main.go ListenPacket).
        // A TCP Socket.connect probe never succeeds against a UDP port — skip to avoid ~120s false timeout.
        if (transport != "tcp") {
            TunnelLogSink.bus?.appendApp(
                "VPN: транспорт «$transport» — слушатель `-listen` на UDP; TCP-проверка пропускается",
                LogLevel.INFO,
            )
            return true
        }
        val ep = parseListenEndpoint(proxyArgs?.toList().orEmpty()) ?: return true
        val deadline = SystemClock.elapsedRealtime() + LISTEN_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (bootstrapAborted.get()) return false
            val p = proxy
            if (p != null && !p.isAlive) {
                TunnelLogSink.bus?.appendApp(
                    "VPN: vk-turn-proxy завершился до открытия `-listen`",
                    LogLevel.WARN,
                )
                return false
            }
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ep.first, ep.second), CONNECT_PROBE_MS)
                }
                return true
            } catch (_: Exception) {
                Thread.sleep(LISTEN_POLL_MS)
            }
        }
        TunnelLogSink.bus?.appendApp(
            "VPN: долго не открывалось TCP-соединение к `-listen` (${ep.first}:${ep.second}); для профилей с транспортом TCP проверьте порт и лог vk-turn-proxy",
            LogLevel.WARN,
        )
        return false
    }

    private fun waitForDtlsReady(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + DTLS_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (bootstrapAborted.get()) return false
            if (dtlsReady.get()) return true
            val p = proxy
            if (p != null && !p.isAlive) {
                TunnelLogSink.bus?.appendApp(
                    "VPN: vk-turn-proxy завершился до dtls_ready",
                    LogLevel.WARN,
                )
                return false
            }
            Thread.sleep(DTLS_POLL_MS)
        }
        return false
    }

    private fun pumpProxyLogs(proc: Process, routeId: String, routeLabel: String) {
        fun pump(stream: java.io.InputStream, streamName: String) {
            Thread({
                try {
                    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            ingestProxyDtlsSignal(line)
                            TunnelLogSink.bus?.append(
                                LogLine(
                                    epochMillis = System.currentTimeMillis(),
                                    level = LogClassifier.classify(streamName, line),
                                    origin = LogOrigin.PROXY,
                                    routeId = routeId,
                                    routeLabel = routeLabel,
                                    stream = streamName,
                                    text = line,
                                ),
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }, "vkturn-proxy-$streamName").start()
        }
        pump(proc.inputStream, "stdout")
        pump(proc.errorStream, "stderr")
    }

    /**
     * Mirrors WINGSV `handleProxyEventLine`: markers are line-oriented (`PROXY_STATUS:` /
     * `PROXY_EVENT:`). Stderr lines may carry `log` timestamps before the marker — strip those first.
     */
    private fun ingestProxyDtlsSignal(raw: String) {
        if (dtlsReady.get()) return
        var s = raw.trim().trimStart('\uFEFF')
        for (i in 0 until 3) {
            val stripped = stripLeadingGoLogTimestamp(s)
            if (stripped == s) break
            s = stripped
        }
        val t = s.trimStart()
        // session-mode auto can take well over 60s before JSON/PROXY markers; vk-turn always logs these.
        run {
            val tl = t.lowercase(Locale.US)
            if (!tl.contains("probe connection") &&
                (
                    tl.contains("established dtls connection!") ||
                        tl.contains("completed mu negotiation")
                    )
            ) {
                dtlsReady.set(true)
                return
            }
        }
        // Anchor on markers (not first ':'), so lines like `17:45:13 [proxy] [INFO] PROXY_STATUS: …`
        // still parse — WINGSV expects `startsWith("PROXY_STATUS:")` on the logical payload.
        val statusKey = "PROXY_STATUS:"
        val eventKey = "PROXY_EVENT:"
        val lower = t.lowercase(Locale.US)
        val si = lower.indexOf(statusKey)
        if (si >= 0) {
            val marker = t.substring(si + statusKey.length).trim().lowercase(Locale.US)
            if (marker == "dtls_ready" || marker == "ok") dtlsReady.set(true)
            return
        }
        val ei = lower.indexOf(eventKey)
        if (ei >= 0) {
            val jsonPayload = t.substring(ei + eventKey.length).trim()
            runCatching {
                val phase = JSONObject(jsonPayload).optString("phase", "").trim().lowercase(Locale.US)
                if (phase == "dtls_ready" || phase == "ok") dtlsReady.set(true)
            }.onFailure {
                if (jsonPayload.contains("dtls_ready")) dtlsReady.set(true)
            }
        }
    }

    /** Go `log` package prefix: `2026/04/30 17:45:13 message` (optional fractional seconds). */
    private fun stripLeadingGoLogTimestamp(s: String): String {
        val m = GO_STD_LOG_PREFIX.find(s) ?: return s
        if (m.range.first != 0) return s
        return s.substring(m.range.last + 1).trimStart()
    }

    private fun handleStop() {
        bootstrapAborted.set(true)
        WgBackendBridge.stopAll()
        GoBackendVpnAccess.stopService(applicationContext)
        proxy?.let { p ->
            proxy = null
            runCatching { p.destroy() }
        }
        protectServer?.stop()
        protectServer = null
        TunnelStatusBus.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildOngoingNotification(): Notification {
        val channelId = "vkturn-tunnel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "vkturn tunnel", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("vkturn")
            .setContentText("Туннель активен")
            .setOngoing(true)
            .setContentIntent(pending)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    private fun startForegroundOngoing() {
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(FOREGROUND_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "app.vkturn.tunnel.START"
        const val ACTION_STOP = "app.vkturn.tunnel.STOP"

        const val EXTRA_IFACE_ADDR = "ifaceAddr"
        const val EXTRA_DNS = "dns"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_WG_CONF = "wgConf"
        const val EXTRA_PROXY_BIN = "proxyBin"
        const val EXTRA_PROXY_ARGS = "proxyArgs"
        const val EXTRA_ROUTE_ID = "routeId"
        const val EXTRA_ROUTE_LABEL = "routeLabel"

        private const val FOREGROUND_ID = 1042
        private const val FOREGROUND_ID_GO_BACKEND = 1043
        private const val LISTEN_WAIT_MS = 120_000L
        private const val LISTEN_POLL_MS = 50L
        private const val CONNECT_PROBE_MS = 300
        /** auto→mu handshake + vk auth can exceed 60s on mobile networks. */
        private const val DTLS_WAIT_MS = 180_000L
        private const val DTLS_POLL_MS = 50L
        private const val VPN_NETWORK_WAIT_MS = 20_000L
        private const val IP_VERIFY_TIMEOUT_MS = 60_000L
        private const val IP_VERIFY_POLL_MS = 1_500L

        /** Prefix printed by Go `log` before the actual message. */
        private val GO_STD_LOG_PREFIX =
            Regex("^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s+")
    }
}
