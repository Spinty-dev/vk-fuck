package app.vkturn.proxy

import app.vkturn.model.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs an embedded sing-box instance for a [Route.SingBox] route. The
 * config is serialized as JSON on stdin so we can stay stateless on disk.
 *
 * User-mode only for now: inbound is a local SOCKS listener plus an
 * optional HTTP port. TUN mode needs the privileged helper daemon —
 * see the roadmap in README.
 */
class SingBoxRunner(
    private val logs: LogBus,
    private val resolver: BinaryResolver,
    private val hostFactory: () -> ProcessHost,
) {
    private val _status = MutableStateFlow(RouteStatus(routeId = "", routeLabel = ""))
    val status: StateFlow<RouteStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var host: ProcessHost? = null
    private var collectors: Job? = null

    fun start(route: Route.SingBox, binaryOverride: String, configJson: String) {
        if (host?.isRunning == true) return

        val initial = RouteStatus(
            routeId = route.id,
            routeLabel = route.label,
            state = RouteState.STARTING,
        )
        _status.value = initial

        val binary = resolver.resolveSingBox(binaryOverride)
        if (binary == null) {
            _status.value = initial.copy(
                state = RouteState.ERROR,
                message = "sing-box бинарник не найден",
            )
            logs.appendApp("Cannot resolve sing-box binary", LogLevel.ERROR)
            return
        }

        // sing-box expects a file path via `-c`. We stage the generated
        // config into the platform temp dir before spawning so restarts
        // overwrite the same path.
        val configPath = writeTempFile("singbox-${route.id}.json", configJson)
        val args = listOf("run", "-c", configPath)
        logs.append(
            LogLine(
                epochMillis = System.currentTimeMillis(),
                level = LogLevel.INFO,
                origin = LogOrigin.APP,
                routeId = route.id,
                routeLabel = route.label,
                stream = "app",
                text = "$ $binary ${args.joinToString(" ")}",
            ),
        )

        val h = hostFactory().also { host = it }
        collectors = Job(scope.coroutineContext[Job])
        attachCollectors(h, route, collectors!!)

        try {
            h.start(binary, args)
            _status.update { it.copy(state = RouteState.CONNECTING) }
        } catch (t: Throwable) {
            _status.value = initial.copy(state = RouteState.ERROR, message = "Не удалось запустить: ${t.message}")
            logs.appendApp("failed to spawn sing-box: ${t.message}", LogLevel.ERROR)
        }
    }

    fun stop() {
        val h = host ?: return
        _status.update { it.copy(state = RouteState.STOPPING) }
        h.stop(graceSeconds = 3)
    }

    fun dispose() {
        stop()
        collectors?.cancel()
        scope.cancel()
    }

    private fun attachCollectors(h: ProcessHost, route: Route.SingBox, parent: Job) {
        scope.launch(parent) {
            h.stdout.collect { line -> logLine(route, line, "stdout") }
        }
        scope.launch(parent) {
            h.stderr.collect { line -> logLine(route, line, "stderr") }
        }
        scope.launch(parent) {
            h.exits.collect { exit ->
                val next = if (exit.killed || exit.code == 0) RouteState.IDLE else RouteState.ERROR
                _status.update {
                    it.copy(
                        state = next,
                        message = if (next == RouteState.ERROR) "sing-box вышел с exit=${exit.code}" else "",
                    )
                }
            }
        }
        // Periodically sample clash-api traffic counters so the UI can show
        // RX/TX totals. Stops automatically when the parent job is cancelled.
        scope.launch(parent) {
            val port = clashApiPortFor(route)
            while (isActive) {
                delay(2_000)
                val stats = pollClashStats(port) ?: continue
                _status.update {
                    it.copy(inBytes = stats.downloadTotal, outBytes = stats.uploadTotal)
                }
            }
        }
    }

    /** Derive a deterministic clash-api port from the route's SOCKS port so
     *  multiple concurrent sing-box runners don't collide. */
    private fun clashApiPortFor(route: Route.SingBox): Int {
        val base = route.socksListenPort.coerceIn(1024, 60000)
        return ((base + 11111) % 60000).coerceAtLeast(10000)
    }

    private fun logLine(route: Route.SingBox, raw: ProcessHost.Line, tag: String) {
        // Heuristic: sing-box's `started` banner flips us to CONNECTED.
        val lower = raw.text.lowercase()
        when {
            lower.contains("sing-box started") || lower.contains("started successfully") ->
                _status.update { it.copy(state = RouteState.CONNECTED, message = "") }
            lower.contains("fatal") || lower.contains("panic") ->
                _status.update {
                    it.copy(state = RouteState.ERROR, message = raw.text.take(200))
                }
        }
        logs.append(
            LogLine(
                epochMillis = System.currentTimeMillis(),
                level = LogClassifier.classify(tag, raw.text),
                origin = LogOrigin.SINGBOX,
                routeId = route.id,
                routeLabel = route.label,
                stream = tag,
                text = raw.text,
            ),
        )
    }
}
