package app.vkturn.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.vkturn.model.AppConfig
import app.vkturn.ui.components.AmTextField
import app.vkturn.ui.components.Slab
import app.vkturn.ui.theme.AmneziaColors
import app.vkturn.vm.AppViewModel

/**
 * Consolidated settings screen — WingsV import, binary overrides, About.
 *
 * On mobile, we also expose a shortcut to the full Logs screen here
 * (bottom sheet is the primary entry, but this tile is another way in
 * for users who look in "Ещё" first).
 */
@Composable
fun SettingsScreen(
    config: AppConfig,
    viewModel: AppViewModel,
    onOpenImport: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.headlineMedium,
            color = AmneziaColors.TextPrimary,
        )
        Text(
            "Импорт подписок, пути к бинарям и сведения о приложении",
            style = MaterialTheme.typography.bodyMedium,
            color = AmneziaColors.TextSecondary,
        )

        HubTile(
            title = "Импорт WingsV",
            subtitle = "Вставь wingsv:// или ссылку на 3x-ui подписку — создаст сервер + identity + маршрут.",
            icon = Icons.Filled.Download,
            onClick = onOpenImport,
        )

        HubTile(
            title = "Полный журнал",
            subtitle = "События с фильтрами по маршруту и экспортом. Быстрый просмотр — кнопкой «Журнал» в шапке.",
            icon = Icons.AutoMirrored.Filled.Article,
            onClick = onOpenLogs,
        )

        Slab(title = "Путь к бинарям") {
            Text(
                "По умолчанию приложение ищет vk-turn-proxy и sing-box в ресурсах. " +
                    "Если у тебя своя сборка — укажи путь вручную.",
                style = MaterialTheme.typography.bodySmall,
                color = AmneziaColors.TextSecondary,
            )
            AmTextField(
                value = config.proxyBinaryOverride,
                onValueChange = { v ->
                    viewModel.updateConfig { cfg -> cfg.copy(proxyBinaryOverride = v) }
                },
                label = "vk-turn-proxy (полный путь к бинарю)",
                placeholder = "/usr/local/bin/vk-turn-proxy-client",
            )
            AmTextField(
                value = config.singBoxBinaryOverride,
                onValueChange = { v ->
                    viewModel.updateConfig { cfg -> cfg.copy(singBoxBinaryOverride = v) }
                },
                label = "sing-box (полный путь к бинарю)",
                placeholder = "/usr/local/bin/sing-box",
            )
        }

        Slab(title = "О программе") {
            Text(
                "vkturn — кроссплатформенный GUI над vk-turn-proxy и sing-box. " +
                    "Один Kotlin-кодабейс (Compose Multiplatform) для Android, Linux, Windows и macOS.",
                style = MaterialTheme.typography.bodyMedium,
                color = AmneziaColors.TextSecondary,
            )
            Text(
                "Модель: Server → Identity → Route. Сервер — один VPS, identity — пара WG-ключей, " +
                    "маршрут — способ достучаться (vk-turn-proxy / sing-box VLESS / direct).",
                style = MaterialTheme.typography.bodySmall,
                color = AmneziaColors.TextSecondary,
            )
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = AmneziaColors.TextTertiary)
            Spacer(Modifier.width(8.dp))
            Text(
                "Схема конфига v${AppConfig.CURRENT_SCHEMA_VERSION}",
                style = MaterialTheme.typography.labelMedium,
                color = AmneziaColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun HubTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = AmneziaColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AmneziaColors.OutlineSoft),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, contentDescription = null, tint = AmneziaColors.Primary)
                Text(title, style = MaterialTheme.typography.titleMedium, color = AmneziaColors.TextPrimary, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AmneziaColors.TextTertiary)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AmneziaColors.TextSecondary)
        }
    }
}
