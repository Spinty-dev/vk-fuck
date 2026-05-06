package app.vkturn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vkturn.model.Route as ModelRoute
import app.vkturn.proxy.RouteStatus
import app.vkturn.ui.theme.AmneziaColors
import app.vkturn.vm.RouteKind

/**
 * Read-only metrics panel for the desktop Dashboard — streams, bytes in/out,
 * session mode, captcha URL. Values are derived from the route status.
 */
@Composable
fun MetricsCard(
    status: RouteStatus?,
    route: ModelRoute?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AmneziaColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, AmneziaColors.OutlineSoft),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Метрики",
                style = MaterialTheme.typography.titleMedium,
                color = AmneziaColors.TextPrimary,
            )
            if (status == null || route == null) {
                Text(
                    "Пока не к чему подключаться.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmneziaColors.TextTertiary,
                )
                return@Column
            }
            val cells = metricsFor(status, route)
            val rows = cells.chunked(2)
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    row.forEach { (label, value) ->
                        MetricCell(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (status.message.isNotBlank()) {
                Text(
                    status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = AmneziaColors.TextSecondary,
                )
            }
            status.captchaUrl?.takeIf { it.isNotBlank() }?.let { url ->
                Text(
                    "Капча: $url",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmneziaColors.Connecting,
                )
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AmneziaColors.TextTertiary,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = AmneziaColors.TextPrimary,
        )
    }
}

private fun metricsFor(status: RouteStatus, route: ModelRoute): List<Pair<String, String>> {
    val kind = RouteKind.of(route)
    val streams = when {
        status.totalStreams > 0 -> "${status.connectedStreams} / ${status.totalStreams}"
        status.activeStreams > 0 -> "${status.connectedStreams} / ${status.activeStreams}"
        else -> "—"
    }
    val session = status.sessionMode.ifBlank {
        when (route) {
            is ModelRoute.VkTurnProxy -> route.sessionMode.flag
            else -> "—"
        }
    }
    val rxTx = "${fmtBytes(status.inBytes)} / ${fmtBytes(status.outBytes)}"
    val endpoint = when (route) {
        is ModelRoute.VkTurnProxy -> "${route.listenHost}:${route.listenPort}"
        is ModelRoute.SingBox -> "${route.socksListenHost}:${route.socksListenPort}"
        is ModelRoute.Direct -> route.endpoint.ifBlank { "—" }
    }
    return buildList {
        add("Бэкенд" to kind.badge)
        add("Endpoint" to endpoint)
        add("Потоки" to streams)
        add("RX / TX" to rxTx)
        add("Session" to session)
    }
}

private fun fmtBytes(b: Long): String {
    if (b < 1024) return "${b}B"
    val kb = b.toDouble() / 1024
    if (kb < 1024) return "${oneDecimal(kb)}KB"
    val mb = kb / 1024
    if (mb < 1024) return "${oneDecimal(mb)}MB"
    return "${oneDecimal(mb / 1024)}GB"
}

private fun oneDecimal(value: Double): String {
    val whole = value.toLong()
    val frac = ((value - whole) * 10).toLong().coerceIn(0, 9)
    return "$whole.$frac"
}
