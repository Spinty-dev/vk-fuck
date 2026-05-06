package app.vkturn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vkturn.model.CaptchaMode
import app.vkturn.model.CaptchaSolver
import app.vkturn.model.Identity
import app.vkturn.model.LinkKind
import app.vkturn.model.Route
import app.vkturn.model.Server
import app.vkturn.model.SessionMode
import app.vkturn.model.TcpFlavor
import app.vkturn.model.TransportMode
import app.vkturn.model.TurnTransport
import app.vkturn.proxy.RouteStatus
import app.vkturn.ui.components.AmSwitch
import app.vkturn.ui.components.AmTextField
import app.vkturn.ui.components.RadioRow
import app.vkturn.ui.components.SegmentedPicker
import app.vkturn.ui.components.SettingRow
import app.vkturn.ui.components.Slab
import app.vkturn.ui.theme.AmneziaColors

@Composable
fun RouteEditor(
    server: Server,
    identity: Identity,
    route: Route,
    status: RouteStatus?,
    onChange: (Route) -> Unit,
) {
    Text("Route · ${route.typeBadgeLabel()}", style = MaterialTheme.typography.displaySmall, color = AmneziaColors.TextPrimary)
    Text(
        "Как identity «${identity.name}» достучится до сервера «${server.name}». Параметры применяются при следующем запуске роута.",
        style = MaterialTheme.typography.bodyMedium,
        color = AmneziaColors.TextSecondary,
    )

    Slab(title = "Общее") {
        AmTextField(
            value = route.label,
            onValueChange = { v -> onChange(route.withLabel(v)) },
            label = "Название",
        )
        SettingRow(
            title = "Активен",
            description = "Выключенный роут не запускается супервизором.",
            trailing = { AmSwitch(route.enabled) { v -> onChange(route.withEnabled(v)) } },
        )
    }

    when (route) {
        is Route.VkTurnProxy -> VkTurnEditor(route, status) { onChange(it) }
        is Route.SingBox -> SingBoxEditor(identity, route) { onChange(it) }
        is Route.Direct -> DirectEditor(route) { onChange(it) }
    }
}

@Composable
private fun VkTurnEditor(
    route: Route.VkTurnProxy,
    status: RouteStatus?,
    onChange: (Route.VkTurnProxy) -> Unit,
) {
    Slab(title = "Link") {
        SegmentedPicker(
            options = LinkKind.entries,
            selected = route.linkKind,
            onSelect = { v -> onChange(route.copy(linkKind = v)) },
            label = {
                when (it) {
                    LinkKind.VK -> "VK"
                    LinkKind.YANDEX -> "Яндекс Телемост"
                }
            },
        )
        AmTextField(
            value = route.primaryLinks.joinToString(","),
            onValueChange = { v ->
                val items = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                onChange(route.copy(primaryLinks = items))
            },
            label = if (route.linkKind == LinkKind.VK) "VK links (через запятую — порядок = приоритет)" else "Yandex link",
            placeholder = if (route.linkKind == LinkKind.VK) "https://vk.com/call/join/..." else "https://telemost.yandex.ru/j/...",
        )
        if (route.linkKind == LinkKind.VK) {
            AmTextField(
                value = route.secondaryLink,
                onValueChange = { v -> onChange(route.copy(secondaryLink = v)) },
                label = "Secondary link (фолбэк при cooldown)",
                placeholder = "https://vk.com/call/join/...",
            )
        }
    }

    Slab(title = "Listen") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AmTextField(
                value = route.listenHost,
                onValueChange = { v -> onChange(route.copy(listenHost = v)) },
                label = "Host",
                modifier = Modifier.weight(2f),
            )
            AmTextField(
                value = route.listenPort.toString(),
                onValueChange = { v -> onChange(route.copy(listenPort = v.toIntOrNull() ?: route.listenPort)) },
                label = "Port",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
    }

    Slab(title = "Transport") {
        SegmentedPicker(
            options = TransportMode.entries,
            selected = route.transport,
            onSelect = { v -> onChange(route.copy(transport = v)) },
            label = { it.label },
        )
        if (route.transport == TransportMode.TCP) {
            SegmentedPicker(
                options = TcpFlavor.entries,
                selected = route.tcpFlavor,
                onSelect = { v -> onChange(route.copy(tcpFlavor = v)) },
                label = { it.label },
            )
        }
        SegmentedPicker(
            options = TurnTransport.entries,
            selected = route.turnTransport,
            onSelect = { v -> onChange(route.copy(turnTransport = v)) },
            label = { "TURN: ${it.label}" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AmTextField(
                value = if (route.streams == 0) "" else route.streams.toString(),
                onValueChange = { v -> onChange(route.copy(streams = v.toIntOrNull() ?: 0)) },
                label = "Потоки (-n)",
                placeholder = "auto",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            AmTextField(
                value = route.credsGroupSize.toString(),
                onValueChange = { v -> onChange(route.copy(credsGroupSize = v.toIntOrNull() ?: route.credsGroupSize)) },
                label = "creds-group-size",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AmTextField(
                value = route.overrideTurnHost,
                onValueChange = { v -> onChange(route.copy(overrideTurnHost = v)) },
                label = "TURN host (override)",
                modifier = Modifier.weight(1f),
            )
            AmTextField(
                value = route.overrideTurnPort,
                onValueChange = { v -> onChange(route.copy(overrideTurnPort = v)) },
                label = "TURN port",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
    }

    Slab(title = "Session (-session-mode)") {
        SegmentedPicker(
            options = SessionMode.entries,
            selected = route.sessionMode,
            onSelect = { v -> onChange(route.copy(sessionMode = v)) },
            label = { it.label },
        )
        AmTextField(
            value = route.sessionId,
            onValueChange = { v -> onChange(route.copy(sessionId = v)) },
            label = "Session ID (hex, 32 chars, для mu)",
        )
        if (route.sessionMode == SessionMode.MU) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmTextField(
                    value = route.adaptivePoolMin.toString(),
                    onValueChange = { v -> onChange(route.copy(adaptivePoolMin = v.toIntOrNull() ?: 1)) },
                    label = "pool-min",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                AmTextField(
                    value = route.adaptivePoolMax.toString(),
                    onValueChange = { v -> onChange(route.copy(adaptivePoolMax = v.toIntOrNull() ?: 0)) },
                    label = "pool-max",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                AmTextField(
                    value = route.adaptivePoolStreamsPerIdentity.toString(),
                    onValueChange = { v ->
                        onChange(route.copy(adaptivePoolStreamsPerIdentity = v.toIntOrNull() ?: 0))
                    },
                    label = "streams/id",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    Slab(title = "Captcha") {
        Text(
            "Солвер — `-captcha-solver v2` (улучшенный) по умолчанию, можно откатиться на v1. " +
                    "Стратегия «Только вручную» эквивалентна `-manual-captcha`.",
            style = MaterialTheme.typography.bodySmall,
            color = AmneziaColors.TextSecondary,
        )
        SegmentedPicker(
            options = CaptchaSolver.entries,
            selected = route.captchaSolver,
            onSelect = { v -> onChange(route.copy(captchaSolver = v)) },
            label = { it.label },
        )
        Spacer(Modifier.height(4.dp))
        CaptchaMode.entries.forEach { mode ->
            RadioRow(
                title = mode.label,
                description = mode.description,
                selected = route.captchaMode == mode,
                onSelect = { onChange(route.copy(captchaMode = mode)) },
            )
            if (mode != CaptchaMode.entries.last()) HorizontalDivider(color = AmneziaColors.OutlineSoft)
        }
        if (status?.captchaUrl != null) {
            Text(
                "Клиент просит открыть: ${status.captchaUrl}",
                style = MaterialTheme.typography.labelLarge,
                color = AmneziaColors.Connecting,
            )
        }
    }

    Slab(title = "Дополнительно") {
        SettingRow(
            title = "Direct mode (-no-dtls)",
            description = "Без DTLS-обфускации. Высокий риск бана.",
            trailing = { AmSwitch(route.directNoDtls) { v -> onChange(route.copy(directNoDtls = v)) } },
        )
        HorizontalDivider(color = AmneziaColors.OutlineSoft)
        SettingRow(
            title = "Подробные логи (-debug)",
            description = "Включить debug-логи прокси.",
            trailing = { AmSwitch(route.debug) { v -> onChange(route.copy(debug = v)) } },
        )
    }

    Slab(title = "WB Stream (LiveKit)") {
        SettingRow(
            title = "Использовать WB Stream",
            description = "Туннелирование через LiveKit-комнату вместо прямого TURN.",
            trailing = {
                AmSwitch(route.wbStream.enabled) { v ->
                    onChange(route.copy(wbStream = route.wbStream.copy(enabled = v)))
                }
            },
        )
        if (route.wbStream.enabled) {
            AmTextField(
                value = route.wbStream.roomId,
                onValueChange = { v -> onChange(route.copy(wbStream = route.wbStream.copy(roomId = v))) },
                label = "Room ID (\"any\" = создать новую)",
            )
            AmTextField(
                value = route.wbStream.displayName,
                onValueChange = { v -> onChange(route.copy(wbStream = route.wbStream.copy(displayName = v))) },
                label = "Display name",
            )
            AmTextField(
                value = route.wbStream.e2eSecretB64,
                onValueChange = { v -> onChange(route.copy(wbStream = route.wbStream.copy(e2eSecretB64 = v))) },
                label = "E2E secret (base64 chacha20-poly1305 key, опц.)",
            )
        }
    }
}

@Composable
private fun SingBoxEditor(
    identity: Identity,
    route: Route.SingBox,
    onChange: (Route.SingBox) -> Unit,
) {
    Slab(title = "VLESS") {
        AmTextField(
            value = route.vlessLink,
            onValueChange = { v -> onChange(route.copy(vlessLink = v)) },
            label = "vless:// URI",
            placeholder = "vless://uuid@host:443?security=reality&pbk=...&sni=...&sid=...",
            singleLine = false,
        )
    }

    Slab(title = "Local SOCKS / HTTP") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AmTextField(
                value = route.socksListenHost,
                onValueChange = { v -> onChange(route.copy(socksListenHost = v)) },
                label = "SOCKS host",
                modifier = Modifier.weight(2f),
            )
            AmTextField(
                value = route.socksListenPort.toString(),
                onValueChange = { v -> onChange(route.copy(socksListenPort = v.toIntOrNull() ?: route.socksListenPort)) },
                label = "SOCKS port",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            AmTextField(
                value = if (route.httpListenPort == 0) "" else route.httpListenPort.toString(),
                onValueChange = { v -> onChange(route.copy(httpListenPort = v.toIntOrNull() ?: 0)) },
                label = "HTTP port (0 = off)",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
    }

    val vkRoutes = identity.routes.filterIsInstance<Route.VkTurnProxy>()
    Slab(title = "Chain through vk-turn-proxy") {
        Text(
            "Если выбран vk-turn-proxy роут, sing-box будет ходить через его TCP listen " +
                    "(VLESS → SOCKS → vk-turn-proxy → TURN → VPS).",
            style = MaterialTheme.typography.bodySmall,
            color = AmneziaColors.TextSecondary,
        )
        RadioRow(
            title = "Без цепочки (прямой выход)",
            description = null,
            selected = route.chainedThroughProxyRouteId == null,
            onSelect = { onChange(route.copy(chainedThroughProxyRouteId = null)) },
        )
        vkRoutes.forEach { r ->
            HorizontalDivider(color = AmneziaColors.OutlineSoft)
            RadioRow(
                title = r.label,
                description = "${r.listenHost}:${r.listenPort} · ${r.transport.label}",
                selected = route.chainedThroughProxyRouteId == r.id,
                onSelect = { onChange(route.copy(chainedThroughProxyRouteId = r.id)) },
            )
        }
    }

    Slab(title = "TUN") {
        SettingRow(
            title = "TUN (требует демона)",
            description = "Поднятие TUN пока недоступно на десктопе без системного demon-а. " +
                    "На Android — через VpnService (следующая итерация).",
            trailing = {
                AmSwitch(route.tunEnabled) { v -> onChange(route.copy(tunEnabled = v)) }
            },
        )
    }
}

@Composable
private fun DirectEditor(
    route: Route.Direct,
    onChange: (Route.Direct) -> Unit,
) {
    Slab(title = "Direct") {
        Text(
            "Никаких прокси — WireGuard/AmneziaWG ходит прямо на VPS. Используется для проверки " +
                    "связности или на чистых серверах.",
            style = MaterialTheme.typography.bodyMedium,
            color = AmneziaColors.TextSecondary,
        )
        AmTextField(
            value = route.endpoint,
            onValueChange = { v -> onChange(route.copy(endpoint = v)) },
            label = "Endpoint (override)",
            placeholder = "1.2.3.4:51820 (пусто = сервер из родителя)",
        )
    }
}

private fun Route.typeBadgeLabel(): String = when (this) {
    is Route.VkTurnProxy -> "vk-turn-proxy"
    is Route.SingBox -> "sing-box"
    is Route.Direct -> "direct"
}

private fun Route.withLabel(label: String): Route = when (this) {
    is Route.VkTurnProxy -> copy(label = label)
    is Route.SingBox -> copy(label = label)
    is Route.Direct -> copy(label = label)
}

private fun Route.withEnabled(enabled: Boolean): Route = when (this) {
    is Route.VkTurnProxy -> copy(enabled = enabled)
    is Route.SingBox -> copy(enabled = enabled)
    is Route.Direct -> copy(enabled = enabled)
}
