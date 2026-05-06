package app.vkturn.proxy

import app.vkturn.model.Route
import app.vkturn.model.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the Go `vk-turn-proxy` client for a single [Route.VkTurnProxy]
 * route. All state transitions come from the structured event stream on
 * stdout — no regex scraping required.
 */
class VkTurnRunner(
    private val logs: LogBus,
    private val resolver: BinaryResolver,
    private val hostFactory: () -> ProcessHost,
) {
    private val _status = MutableStateFlow(RouteStatus(routeId = "", routeLabel = ""))
    val status: StateFlow<RouteStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var host: ProcessHost? = null
    private var collectors: Job? = null
    private var currentRoute: Route.VkTurnProxy? = null

    fun start(
        server: Server,
        route: Route.VkTurnProxy,
        binaryOverride: String,
        protectSocketPath: String? = null,
    ) {
        if (host?.isRunning == true) return

        currentRoute = route
        val initial = RouteStatus(
            routeId = route.id,
            routeLabel = route.label,
            state = RouteState.STARTING,
            totalStreams = route.effectiveStreams,
        )
        _status.value = initial

        val binary = resolver.resolveVkTurnProxy(binaryOverride)
        if (binary == null) {
            _status.value = initial.copy(state = RouteState.ERROR, message = "vk-turn-proxy бинарник не найден")
            logs.appendApp("Cannot resolve vk-turn-proxy binary", LogLevel.ERROR)
            return
        }

        val argv = ProxyArgs.build(server, route, protectSocketPath)
        logs.append(
            LogLine(
                epochMillis = System.currentTimeMillis(),
                level = LogLevel.INFO,
                origin = LogOrigin.APP,
                routeId = route.id,
                routeLabel = route.label,
                stream = "app",
                text = "$ $binary ${argv.joinToString(" ") { if (' ' in it) "\"$it\"" else it }}",
            ),
        )

        val h = hostFactory().also { host = it }
        collectors = Job(scope.coroutineContext[Job])
        attachCollectors(h, route, collectors!!)

        try {
            h.start(binary, argv)
            _status.update { it.copy(state = RouteState.CONNECTING) }
        } catch (t: Throwable) {
            _status.value = initial.copy(state = RouteState.ERROR, message = "Не удалось запустить: ${t.message}")
            logs.appendApp("failed to spawn vk-turn-proxy: ${t.message}", LogLevel.ERROR)
        }
    }

    fun stop() {
        val h = host ?: return
        _status.update { it.copy(state = RouteState.STOPPING) }
        h.stop(graceSeconds = 5)
    }

    fun dispose() {
        stop()
        collectors?.cancel()
        scope.cancel()
    }

    private fun attachCollectors(h: ProcessHost, route: Route.VkTurnProxy, parent: Job) {
        scope.launch(parent) { h.stdout.collect { consume(it, route) } }
        scope.launch(parent) { h.stderr.collect { consume(it, route, isStderr = true) } }
        scope.launch(parent) {
            h.exits.collect { exit ->
                val next = when {
                    exit.killed -> RouteState.IDLE
                    exit.code == 0 -> RouteState.IDLE
                    else -> RouteState.ERROR
                }
                _status.update {
                    it.copy(
                        state = next,
                        message = if (next == RouteState.ERROR) "Процесс завершён (exit=${exit.code})" else "",
                    )
                }
                logs.append(
                    LogLine(
                        epochMillis = System.currentTimeMillis(),
                        level = if (next == RouteState.ERROR) LogLevel.ERROR else LogLevel.INFO,
                        origin = LogOrigin.APP,
                        routeId = route.id,
                        routeLabel = route.label,
                        stream = "exit",
                        text = "process exited with code=${exit.code} killed=${exit.killed}",
                    ),
                )
            }
        }
    }

    private fun consume(line: ProcessHost.Line, route: Route.VkTurnProxy, isStderr: Boolean = false) {
        val streamTag = if (isStderr) "stderr" else "stdout"

        val parsed = ProxyEvents.parse(line.text, streamTag)
        when (parsed) {
            is ProxyEvent.Caps -> _status.update {
                it.copy(capsVersion = parsed.version, capabilities = parsed.capabilities)
            }
            is ProxyEvent.StatusPhase -> applyPhase(parsed.phase)
            is ProxyEvent.Lockout -> _status.update {
                it.copy(state = RouteState.LOCKOUT, lockoutSecondsRemaining = parsed.seconds,
                    message = "Captcha cooldown: ${parsed.seconds}s")
            }
            is ProxyEvent.CaptchaPrompt -> applyCaptcha(parsed)
            is ProxyEvent.Telemetry -> _status.update {
                it.copy(
                    sessionMode = parsed.sessionMode.ifEmpty { it.sessionMode },
                    activeStreams = parsed.activeStreams,
                    connectedStreams = parsed.connectedStreams,
                    streams = parsed.streams,
                    totalStreams = maxOf(it.totalStreams, parsed.activeStreams, route.effectiveStreams),
                    outBytes = parsed.streams.sumOf { s -> s.outBytes },
                    inBytes = parsed.streams.sumOf { s -> s.inBytes },
                )
            }
            is ProxyEvent.Raw, is ProxyEvent.Unknown -> {
                // nothing state-wise, fall through to log record
            }
        }

        // Always record the original line in the shared log bus. Tagged lines
        // (PROXY_EVENT, PROXY_STATUS, PROXY_CAPS) are collapsed into the DEBUG
        // level to keep the main view readable.
        val isControlLine = line.text.startsWith("PROXY_EVENT:") ||
                line.text.startsWith("PROXY_STATUS:") ||
                line.text.startsWith("PROXY_CAPS:")
        val level = if (isControlLine) LogLevel.DEBUG
                    else LogClassifier.classify(streamTag, line.text)
        logs.append(
            LogLine(
                epochMillis = System.currentTimeMillis(),
                level = level,
                origin = LogOrigin.PROXY,
                routeId = route.id,
                routeLabel = route.label,
                stream = streamTag,
                text = line.text,
            ),
        )
    }

    private fun applyPhase(phase: String) {
        _status.update { current ->
            val nextState = when (phase) {
                "auth_ready" -> RouteState.CONNECTING
                "turn_ready" -> RouteState.CONNECTING
                "dtls_ready", "ok", "dtls_alive" -> RouteState.CONNECTED
                else -> current.state
            }
            current.copy(
                state = nextState,
                message = if (nextState == RouteState.CONNECTED) "" else current.message,
            )
        }
    }

    private fun applyCaptcha(prompt: ProxyEvent.CaptchaPrompt) {
        when (prompt.state) {
            "required", "prompt" -> _status.update {
                it.copy(
                    state = RouteState.CAPTCHA,
                    captchaUrl = prompt.url.ifEmpty { "http://localhost:8765" },
                    captchaUserAgent = prompt.userAgent.takeIf { ua -> ua.isNotBlank() },
                    message = "Нужно решить капчу",
                )
            }
            "solved", "done", "cleared" -> _status.update {
                it.copy(captchaUrl = null, captchaUserAgent = null)
            }
            "cancelled", "aborted" -> _status.update {
                it.copy(
                    state = RouteState.ERROR,
                    captchaUrl = null,
                    message = "Капча отменена",
                )
            }
        }
    }
}
