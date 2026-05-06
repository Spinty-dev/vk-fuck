package app.vkturn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vkturn.proxy.LogLevel
import app.vkturn.proxy.LogLine
import app.vkturn.proxy.LogOrigin
import app.vkturn.ui.theme.AmneziaColors

@Immutable
enum class LogFeedDensity {
    /** Full-width toolbar + comfortable padding (desktop / settings screen). */
    Comfortable,

    /** Tighter rows and toolbar for the mobile connection screen. */
    Compact,
}

fun filterLogLines(
    lines: List<LogLine>,
    routeFilter: String?,
    showDebug: Boolean,
): List<LogLine> =
    lines.asSequence()
        .filter { routeFilter == null || it.routeId == routeFilter }
        .filter { showDebug || it.level != LogLevel.DEBUG }
        .toList()

/**
 * Scrollable log output with toolbar — shared by the dedicated Logs screen and the mobile home shell.
 */
@Composable
fun LogFeedPanel(
    filteredLines: List<LogLine>,
    modifier: Modifier = Modifier,
    density: LogFeedDensity = LogFeedDensity.Comfortable,
    title: String,
    showDebug: Boolean,
    onShowDebugToggle: () -> Unit,
    autoscroll: Boolean,
    onAutoscrollToggle: () -> Unit,
    onCopyAll: () -> Unit,
    onClear: () -> Unit,
    emptyHint: String,
) {
    val listState = rememberLazyListState()
    val horizontalPad = when (density) {
        LogFeedDensity.Comfortable -> 20.dp
        LogFeedDensity.Compact -> 0.dp
    }
    val verticalPad = when (density) {
        LogFeedDensity.Comfortable -> 20.dp
        LogFeedDensity.Compact -> 0.dp
    }
    val innerPadH = when (density) {
        LogFeedDensity.Comfortable -> 12.dp
        LogFeedDensity.Compact -> 8.dp
    }
    val innerPadV = when (density) {
        LogFeedDensity.Comfortable -> 8.dp
        LogFeedDensity.Compact -> 6.dp
    }

    LaunchedEffect(filteredLines.size, autoscroll) {
        if (autoscroll && filteredLines.isNotEmpty()) {
            listState.scrollToItem(filteredLines.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (density == LogFeedDensity.Comfortable) {
                    Modifier.padding(horizontal = horizontalPad, vertical = verticalPad)
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(
            when (density) {
                LogFeedDensity.Comfortable -> 12.dp
                LogFeedDensity.Compact -> 8.dp
            },
        ),
    ) {
        val titleStyle =
            if (density == LogFeedDensity.Compact) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.displaySmall

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = titleStyle,
                color = AmneziaColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${filteredLines.size}",
                style = MaterialTheme.typography.labelMedium,
                color = AmneziaColors.TextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            ToggleChipCompact("debug", showDebug, onShowDebugToggle)
            IconButton(onClick = onAutoscrollToggle) {
                Icon(
                    Icons.Filled.VerticalAlignBottom,
                    contentDescription = if (autoscroll) "Автопрокрутка вкл" else "Автопрокрутка выкл",
                    tint = if (autoscroll) AmneziaColors.Primary else AmneziaColors.TextSecondary,
                )
            }
            IconButton(onClick = onCopyAll) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать всё", tint = AmneziaColors.TextSecondary)
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Очистить", tint = AmneziaColors.TextSecondary)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0A0A0C),
            shape =
                if (density == LogFeedDensity.Compact) RoundedCornerShape(12.dp)
                else MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, AmneziaColors.OutlineSoft),
        ) {
            if (filteredLines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        emptyHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmneziaColors.TextTertiary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = innerPadH, vertical = innerPadV),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filteredLines) { line ->
                        LogLineRow(line, density)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleChipCompact(label: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) AmneziaColors.Primary else AmneziaColors.SurfaceElevated
    val fg = if (enabled) AmneziaColors.OnPrimary else AmneziaColors.TextSecondary
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = bg,
        shape = RoundedCornerShape(6.dp),
        border = if (!enabled) BorderStroke(1.dp, AmneziaColors.OutlineSoft) else null,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
    }
}

@Composable
private fun LogLineRow(line: LogLine, density: LogFeedDensity) {
    val fontSize =
        when (density) {
            LogFeedDensity.Comfortable -> 12.sp
            LogFeedDensity.Compact -> 11.sp
        }
    val badgeWidth =
        when (density) {
            LogFeedDensity.Comfortable -> 70.dp
            LogFeedDensity.Compact -> 62.dp
        }
    val color = when (line.level) {
        LogLevel.ERROR -> AmneziaColors.Error
        LogLevel.WARN -> AmneziaColors.Connecting
        LogLevel.DEBUG -> AmneziaColors.TextTertiary
        LogLevel.INFO -> AmneziaColors.TextPrimary
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = formatClockShort(line.epochMillis),
            color = AmneziaColors.TextTertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = line.origin.badge(),
            color = AmneziaColors.TextTertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            modifier = Modifier.padding(end = 6.dp).width(badgeWidth),
        )
        Text(text = line.text, color = color, fontFamily = FontFamily.Monospace, fontSize = fontSize)
    }
}

private fun LogOrigin.badge(): String = when (this) {
    LogOrigin.PROXY -> "[proxy] "
    LogOrigin.SINGBOX -> "[sing-bx]"
    LogOrigin.WIREGUARD -> "[wg]    "
    LogOrigin.APP -> "[app]   "
}

fun LogLine.formatForCopy(): String =
    "${formatClockShort(epochMillis)}  ${origin.badge()} [${level.name}] $text"

fun formatClockShort(epochMillis: Long): String {
    val totalSec = (epochMillis / 1000) % 86_400
    val h = (totalSec / 3600).toInt()
    val m = ((totalSec % 3600) / 60).toInt()
    val s = (totalSec % 60).toInt()
    return "${h.pad2()}:${m.pad2()}:${s.pad2()}"
}

private fun Int.pad2(): String = if (this < 10) "0$this" else "$this"
