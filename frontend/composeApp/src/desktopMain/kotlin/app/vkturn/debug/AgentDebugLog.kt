package app.vkturn.debug

import java.io.File

//region agent log
internal object AgentDebugLog {
    private const val sessionId = "5a1a62"
    private const val logPath = "/home/spinty/projects/new_vk/.cursor/debug-5a1a62.log"

    fun log(hypothesisId: String, location: String, message: String, data: Map<String, Any?> = emptyMap(), runId: String = "pre-fix") {
        runCatching {
            val ts = System.currentTimeMillis()
            val payload =
                buildString {
                    append('{')
                    append("\"sessionId\":\"").append(sessionId).append("\",")
                    append("\"runId\":\"").append(runId).append("\",")
                    append("\"hypothesisId\":\"").append(escape(hypothesisId)).append("\",")
                    append("\"location\":\"").append(escape(location)).append("\",")
                    append("\"message\":\"").append(escape(message)).append("\",")
                    append("\"timestamp\":").append(ts).append(',')
                    append("\"data\":").append(toJson(data))
                    append('}')
                }
            File(logPath).appendText(payload + "\n")
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun toJson(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(k)).append('"').append(':').append(valueToJson(v))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun valueToJson(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is Number -> v.toString()
        is String -> "\"" + escape(v) + "\""
        else -> "\"" + escape(v.toString()) + "\""
    }
}
//endregion agent log

