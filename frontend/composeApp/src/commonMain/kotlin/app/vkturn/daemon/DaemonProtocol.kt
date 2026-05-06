package app.vkturn.daemon

import kotlinx.serialization.Serializable

/**
 * Shape of the JSON messages exchanged with the `vkturnd` helper.
 * Mirrors `daemon/control/protocol.go`.
 */
@Serializable
data class DaemonRequest(
    val op: String,
    val id: String? = null,
    val kind: String? = null,
    val configJson: String? = null,
    val args: List<String>? = null,
    val protectSocket: String? = null,
    val follow: Boolean? = null,
)

@Serializable
data class DaemonResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val version: String? = null,
    val caps: List<String>? = null,
    val id: String? = null,
    val state: String? = null,
    val routes: Map<String, DaemonStatus>? = null,
    val line: String? = null,
)

@Serializable
data class DaemonStatus(
    val kind: String,
    val state: String,
    val message: String = "",
    val pid: Int = 0,
    val inBytes: Long = 0,
    val outBytes: Long = 0,
    val uptimeMs: Long = 0,
)
