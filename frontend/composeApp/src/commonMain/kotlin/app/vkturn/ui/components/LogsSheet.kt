package app.vkturn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.vkturn.proxy.LogBus
import app.vkturn.ui.theme.AmneziaColors

/**
 * Bottom sheet with the full log feed. On mobile this is opened from the
 * top-app-bar icon — keeps the dashboard uncluttered while making the log
 * one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsSheet(
    logs: LogBus,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lines by logs.lines.collectAsState()
    val clipboard = LocalClipboardManager.current
    var autoscroll by remember { mutableStateOf(true) }
    var showDebug by remember { mutableStateOf(false) }
    val filtered = remember(lines, showDebug) { filterLogLines(lines, null, showDebug) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AmneziaColors.Surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 720.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            LogFeedPanel(
                filteredLines = filtered,
                modifier = Modifier.fillMaxSize(),
                density = LogFeedDensity.Compact,
                title = "Журнал",
                showDebug = showDebug,
                onShowDebugToggle = { showDebug = !showDebug },
                autoscroll = autoscroll,
                onAutoscrollToggle = { autoscroll = !autoscroll },
                onCopyAll = {
                    clipboard.setText(AnnotatedString(filtered.joinToString("\n") { it.formatForCopy() }))
                },
                onClear = onClear,
                emptyHint = "Сообщения появятся, когда маршрут запустится.",
            )
        }
    }
}
