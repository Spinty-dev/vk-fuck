package app.vkturn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.vkturn.model.AppConfig
import app.vkturn.proxy.LogBus
import app.vkturn.ui.CompactBreakpoint
import app.vkturn.ui.components.LogFeedDensity
import app.vkturn.ui.components.LogFeedPanel
import app.vkturn.ui.components.filterLogLines
import app.vkturn.ui.components.formatForCopy
import app.vkturn.ui.theme.AmneziaColors

@Composable
fun LogsScreen(
    logs: LogBus,
    config: AppConfig,
    onClear: () -> Unit,
) {
    val lines by logs.lines.collectAsState()
    val clipboard = LocalClipboardManager.current

    var routeFilter by remember { mutableStateOf<String?>(null) }
    var autoscroll by remember { mutableStateOf(true) }
    var showDebug by remember { mutableStateOf(false) }

    val filtered = remember(lines, routeFilter, showDebug) {
        filterLogLines(lines, routeFilter, showDebug)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < CompactBreakpoint
        Row(modifier = Modifier.fillMaxSize()) {
            if (!isCompact) {
                Column(
                    modifier = Modifier
                        .width(260.dp)
                        .background(AmneziaColors.Surface)
                        .padding(vertical = 20.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Фильтр", style = MaterialTheme.typography.labelMedium, color = AmneziaColors.TextTertiary)
                    FilterRow("Все", routeFilter == null, "app") { routeFilter = null }
                    config.servers.forEach { server ->
                        server.identities.forEach { id ->
                            id.routes.forEach { r ->
                                FilterRow(
                                    label = "${id.name} · ${r.label}",
                                    selected = routeFilter == r.id,
                                    badge = when (r) {
                                        is app.vkturn.model.Route.VkTurnProxy -> "vk-turn"
                                        is app.vkturn.model.Route.SingBox -> "sing-box"
                                        is app.vkturn.model.Route.Direct -> "direct"
                                    },
                                    onClick = { routeFilter = r.id },
                                )
                            }
                        }
                    }
                }
            }

            LogFeedPanel(
                filteredLines = filtered,
                modifier = Modifier.weight(1f),
                density = LogFeedDensity.Comfortable,
                title = "Логи",
                showDebug = showDebug,
                onShowDebugToggle = { showDebug = !showDebug },
                autoscroll = autoscroll,
                onAutoscrollToggle = { autoscroll = !autoscroll },
                onCopyAll = {
                    clipboard.setText(AnnotatedString(filtered.joinToString("\n") { it.formatForCopy() }))
                },
                onClear = onClear,
                emptyHint = "Логи появятся здесь, когда роут запустится.",
            )
        }
    }
}

@Composable
private fun FilterRow(label: String, selected: Boolean, badge: String, onClick: () -> Unit) {
    val bg = if (selected) AmneziaColors.SurfaceElevated else Color.Transparent
    val fg = if (selected) AmneziaColors.TextPrimary else AmneziaColors.TextSecondary
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = bg,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.weight(1f))
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = AmneziaColors.TextTertiary,
                modifier = Modifier
                    .background(AmneziaColors.SurfaceElevated, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
