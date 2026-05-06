package app.vkturn.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

enum class LogOrigin { PROXY, SINGBOX, WIREGUARD, APP }

data class LogLine(
    val epochMillis: Long,
    val level: LogLevel,
    val origin: LogOrigin,
    val routeId: String,
    val routeLabel: String,
    val stream: String,
    val text: String,
)

/**
 * Ring-buffer of log lines shared across the entire app. Each line carries
 * its originating route id so the Logs screen can filter by route.
 */
class LogBus(private val capacity: Int = 8000) {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    fun append(line: LogLine) {
        _lines.update { current ->
            val next = if (current.size >= capacity) current.drop(current.size - capacity + 1) else current
            next + line
        }
    }

    fun appendApp(message: String, level: LogLevel = LogLevel.INFO) {
        append(
            LogLine(
                epochMillis = System.currentTimeMillis(),
                level = level,
                origin = LogOrigin.APP,
                routeId = "app",
                routeLabel = "app",
                stream = "app",
                text = message,
            ),
        )
    }

    fun clear() { _lines.value = emptyList() }
}

object LogClassifier {
    private val errorMarkers = listOf("panic", "failed", "error", "fatal")
    private val warnMarkers = listOf("warn", "retry", "timeout", "reconnect")

    fun classify(stream: String, raw: String): LogLevel {
        val lower = raw.lowercase()
        if (stream == "stderr") return LogLevel.ERROR
        if (errorMarkers.any { lower.contains(it) }) return LogLevel.ERROR
        if (warnMarkers.any { lower.contains(it) }) return LogLevel.WARN
        if (lower.startsWith("debug") || lower.contains("[debug]")) return LogLevel.DEBUG
        return LogLevel.INFO
    }
}
