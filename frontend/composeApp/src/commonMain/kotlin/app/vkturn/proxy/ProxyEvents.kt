package app.vkturn.proxy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured events emitted by the Go `vk-turn-proxy` client over stdout.
 *
 * The client announces its capabilities on startup:
 *   PROXY_CAPS: version=1 caps=auth_ready,captcha_lockout,...
 *   PROXY_EVENT: {"type":"caps","version":1,"capabilities":[...]}
 *
 * and then emits a series of line-prefixed events:
 *   PROXY_STATUS: auth_ready             (legacy text marker)
 *   PROXY_EVENT:  {"type":"status","phase":"dtls_ready"}
 *   PROXY_EVENT:  {"type":"captcha","state":"required","url":"..."}
 *   PROXY_EVENT:  {"type":"lockout","seconds":30}
 *   PROXY_EVENT:  {"type":"telemetry","activeStreams":10,
 *                  "connectedStreams":7,"streams":[...]}
 *
 * We read both forms — JSON events drive the state machine, text markers
 * act as a belt-and-braces fallback for legacy builds.
 */
sealed class ProxyEvent {
    data class Raw(val line: String, val stream: String) : ProxyEvent()

    data class Caps(val version: Int, val capabilities: List<String>) : ProxyEvent()

    data class StatusPhase(val phase: String) : ProxyEvent()

    data class Lockout(val seconds: Int) : ProxyEvent()

    data class CaptchaPrompt(
        val state: String,
        val source: String,
        val url: String,
        val userAgent: String,
    ) : ProxyEvent()

    data class Telemetry(
        val timestampMs: Long,
        val sessionMode: String,
        val activeStreams: Int,
        val connectedStreams: Int,
        val leaderStream: Int?,
        val primaryStream: Int?,
        val streams: List<StreamTelemetry>,
    ) : ProxyEvent()

    data class Unknown(val type: String, val raw: String) : ProxyEvent()
}

@Serializable
data class StreamTelemetry(
    val id: Int,
    val leader: Boolean = false,
    val primary: Boolean = false,
    val turnReady: Boolean = false,
    val dtlsReady: Boolean = false,
    val lastAliveMs: Long = 0,
    val queueDepth: Int = 0,
    val queueCapacity: Int = 0,
    val queueFillPercent: Int = 0,
    val outPackets: Long = 0,
    val outBytes: Long = 0,
    val inPackets: Long = 0,
    val inBytes: Long = 0,
)

object ProxyEvents {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Classify a single line into a structured event. */
    fun parse(rawLine: String, defaultStream: String = "stdout"): ProxyEvent {
        val line = rawLine.trimEnd()
        if (line.isEmpty()) return ProxyEvent.Raw(line, defaultStream)

        val statusPrefix = "PROXY_STATUS:"
        if (line.startsWith(statusPrefix)) {
            val marker = line.substring(statusPrefix.length).trim()
            val phase = marker.substringBefore(' ').trim()
            return ProxyEvent.StatusPhase(phase)
        }

        val capsPrefix = "PROXY_CAPS:"
        if (line.startsWith(capsPrefix)) {
            val body = line.substring(capsPrefix.length).trim()
            // Format: "version=1 caps=a,b,c"
            var version = 0
            var caps = emptyList<String>()
            body.split(' ').forEach { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) return@forEach
                val key = token.substring(0, idx)
                val value = token.substring(idx + 1)
                when (key) {
                    "version" -> version = value.toIntOrNull() ?: 0
                    "caps" -> caps = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            return ProxyEvent.Caps(version, caps)
        }

        val eventPrefix = "PROXY_EVENT:"
        if (line.startsWith(eventPrefix)) {
            val payload = line.substring(eventPrefix.length).trim()
            return parseJson(payload)
        }

        return ProxyEvent.Raw(line, defaultStream)
    }

    private fun parseJson(payload: String): ProxyEvent {
        if (!payload.startsWith("{")) return ProxyEvent.Raw(payload, "stdout")
        val root: JsonObject = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { return ProxyEvent.Raw(payload, "stdout") }

        val type = root["type"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        return when (type) {
            "caps" -> {
                val version = root["version"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0
                val caps = (root["capabilities"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe() }
                    .orEmpty()
                ProxyEvent.Caps(version, caps)
            }
            "status" -> ProxyEvent.StatusPhase(
                root["phase"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
            )
            "lockout" -> ProxyEvent.Lockout(
                root["seconds"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0,
            )
            "captcha" -> ProxyEvent.CaptchaPrompt(
                state = root["state"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                source = root["source"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                url = root["url"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                userAgent = root["userAgent"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
            )
            "telemetry" -> {
                val streams = (root["streams"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { element ->
                        runCatching { json.decodeFromJsonElement(StreamTelemetry.serializer(), element) }
                            .getOrNull()
                    }
                    .orEmpty()
                ProxyEvent.Telemetry(
                    timestampMs = root["timestampMs"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: 0L,
                    sessionMode = root["sessionMode"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                    activeStreams = root["activeStreams"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0,
                    connectedStreams = root["connectedStreams"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0,
                    leaderStream = root["leaderStream"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull(),
                    primaryStream = root["primaryStream"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull(),
                    streams = streams,
                )
            }
            else -> ProxyEvent.Unknown(type, payload)
        }
    }

    /**
     * Shortened Kotlin Serialization API compat — the stable `contentOrNull`
     * on [JsonPrimitive] lives in kotlinx.serialization.json but gets
     * renamed across versions; we wrap it once to keep call sites tidy.
     */
    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()
}
