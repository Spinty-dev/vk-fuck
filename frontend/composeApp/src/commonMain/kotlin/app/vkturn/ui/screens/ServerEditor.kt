package app.vkturn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vkturn.model.Server
import app.vkturn.ui.components.AmTextField
import app.vkturn.ui.components.Slab
import app.vkturn.ui.theme.AmneziaColors

@Composable
fun ServerEditor(
    server: Server,
    onChange: (Server) -> Unit,
) {
    Text("Сервер", style = MaterialTheme.typography.displaySmall, color = AmneziaColors.TextPrimary)
    Text(
        "Один VPS с запущенным `vk-turn-proxy server`. Под ним живут identity — разные WG/AWG ключи.",
        style = MaterialTheme.typography.bodyMedium,
        color = AmneziaColors.TextSecondary,
    )

    Slab(title = "Общее") {
        AmTextField(
            value = server.name,
            onValueChange = { v -> onChange(server.copy(name = v)) },
            label = "Название",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AmTextField(
                value = server.host,
                onValueChange = { v -> onChange(server.copy(host = v)) },
                label = "Host",
                placeholder = "vpn.example.com или 1.2.3.4",
                modifier = Modifier.weight(2f),
            )
            AmTextField(
                value = server.proxyPort.toString(),
                onValueChange = { v -> onChange(server.copy(proxyPort = v.toIntOrNull() ?: server.proxyPort)) },
                label = "Port",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
        AmTextField(
            value = server.notes,
            onValueChange = { v -> onChange(server.copy(notes = v)) },
            label = "Заметки",
            singleLine = false,
        )
    }
}
