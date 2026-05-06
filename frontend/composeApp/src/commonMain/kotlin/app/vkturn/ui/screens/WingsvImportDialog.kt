package app.vkturn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import app.vkturn.ui.components.StatusBanner
import app.vkturn.ui.components.StatusBannerKind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.vkturn.platform.enqueueUi
import app.vkturn.ui.components.AmTextField
import app.vkturn.ui.theme.AmneziaColors
import app.vkturn.vm.AppViewModel
import app.vkturn.wingsv.WingsvImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun WingsvImportDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onImported: (serverId: String, identityId: String, routeId: String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    notes = emptyList()
                    // Run import off the UI thread; apply state on the toolkit main thread directly.
                    // Release desktop uber JARs can leave Dispatchers.Main broken (ProGuard / ServiceLoader),
                    // and withContext(Default) resumes on Main — avoid that entirely.
                    scope.launch(Dispatchers.Default) {
                        val current = viewModel.config.value
                        val outcome = WingsvImporter.import(input, current)
                        if (!isActive) return@launch
                        enqueueUi {
                            busy = false
                            when (outcome) {
                                is WingsvImporter.Outcome.Fail -> error = outcome.message
                                is WingsvImporter.Outcome.Ok -> {
                                    viewModel.updateConfig { outcome.result.config }
                                    notes = outcome.result.notes
                                    error = null
                                    onImported(
                                        outcome.result.serverId,
                                        outcome.result.identityId,
                                        outcome.result.routeId,
                                    )
                                }
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmneziaColors.Primary,
                    contentColor = AmneziaColors.OnPrimary,
                ),
            ) { Text(if (busy) "Импорт…" else "Импортировать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
        },
        title = { Text("Импорт WingsV-подписки", color = AmneziaColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Вставь `wingsv://…` токен или URL подписки из 3x-ui. " +
                        "Будет создан новый Server с одной Identity и одним vk-turn-proxy роутом.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmneziaColors.TextSecondary,
                )
                AmTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "wingsv:// или http(s)://…",
                    placeholder = "wingsv://EvcD...",
                    singleLine = false,
                )
                if (error != null) {
                    StatusBanner(
                        kind = StatusBannerKind.Error,
                        title = "Ошибка импорта",
                        subtitle = error,
                        onDismiss = { error = null },
                    )
                }
                notes.forEach { note ->
                    Text(note, color = AmneziaColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        containerColor = AmneziaColors.Surface,
    )
}
