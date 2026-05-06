package app.vkturn.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vkturn.model.Identity
import app.vkturn.model.Server
import app.vkturn.model.WgFlavor
import app.vkturn.ui.components.AmTextField
import app.vkturn.ui.components.SegmentedPicker
import app.vkturn.ui.components.Slab
import app.vkturn.ui.theme.AmneziaColors
import app.vkturn.wireguard.WgConfigGenerator
import app.vkturn.wireguard.WgKeyGen

@Composable
fun IdentityEditor(
    server: Server,
    identity: Identity,
    onChange: (Identity) -> Unit,
    onSaveConfig: (String, String) -> Unit,
) {
    val wg = identity.wg

    Text("Identity", style = MaterialTheme.typography.displaySmall, color = AmneziaColors.TextPrimary)
    Text(
        "Пара WireGuard/AmneziaWG-ключей. Под identity создаются vk-turn-proxy / sing-box / direct роуты.",
        style = MaterialTheme.typography.bodyMedium,
        color = AmneziaColors.TextSecondary,
    )

    Slab(title = "Общее") {
        AmTextField(
            value = identity.name,
            onValueChange = { v -> onChange(identity.copy(name = v)) },
            label = "Имя identity",
        )
        SegmentedPicker(
            options = WgFlavor.entries,
            selected = wg.flavor,
            onSelect = { v -> onChange(identity.copy(wg = wg.copy(flavor = v))) },
            label = { it.label },
        )
        Text(
            text = when (wg.flavor) {
                WgFlavor.WIREGUARD -> "Стандартный WireGuard."
                WgFlavor.AMNEZIA -> "AmneziaWG: добавляет junk-пакеты и обфускацию хендшейка."
            },
            style = MaterialTheme.typography.bodySmall,
            color = AmneziaColors.TextSecondary,
        )
    }

    Slab(title = "Клиент") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AmTextField(
                value = wg.privateKey,
                onValueChange = { v -> onChange(identity.copy(wg = wg.copy(privateKey = v))) },
                label = "Private key",
                placeholder = "base64(32 bytes)",
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val kp = WgKeyGen.generate()
                    onChange(identity.copy(wg = wg.copy(privateKey = kp.privateKey)))
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AmneziaColors.Primary),
                border = BorderStroke(1.dp, AmneziaColors.Primary),
            ) {
                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Сгенерировать")
            }
        }
        AmTextField(
            value = wg.address,
            onValueChange = { v -> onChange(identity.copy(wg = wg.copy(address = v))) },
            label = "Address",
            placeholder = "10.8.0.2/24",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AmTextField(
                value = wg.dns,
                onValueChange = { v -> onChange(identity.copy(wg = wg.copy(dns = v))) },
                label = "DNS",
                modifier = Modifier.weight(2f),
            )
            AmTextField(
                value = wg.mtu.toString(),
                onValueChange = { v -> onChange(identity.copy(wg = wg.copy(mtu = v.toIntOrNull() ?: wg.mtu))) },
                label = "MTU",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
    }

    Slab(title = "Сервер (peer)") {
        AmTextField(
            value = wg.peerPublicKey,
            onValueChange = { v -> onChange(identity.copy(wg = wg.copy(peerPublicKey = v))) },
            label = "Server public key",
        )
        AmTextField(
            value = wg.peerPresharedKey,
            onValueChange = { v -> onChange(identity.copy(wg = wg.copy(peerPresharedKey = v))) },
            label = "Preshared key (опц.)",
        )
        AmTextField(
            value = wg.allowedIps,
            onValueChange = { v -> onChange(identity.copy(wg = wg.copy(allowedIps = v))) },
            label = "AllowedIPs",
            singleLine = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AmTextField(
                value = wg.persistentKeepalive.toString(),
                onValueChange = { v ->
                    onChange(identity.copy(wg = wg.copy(persistentKeepalive = v.toIntOrNull() ?: wg.persistentKeepalive)))
                },
                label = "PersistentKeepalive",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (wg.flavor == WgFlavor.AMNEZIA) {
        Slab(title = "AmneziaWG — обфускация") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("Jc", wg.awgJc, Modifier.weight(1f)) { v -> onChange(identity.copy(wg = wg.copy(awgJc = v))) }
                IntField("Jmin", wg.awgJmin, Modifier.weight(1f)) { v -> onChange(identity.copy(wg = wg.copy(awgJmin = v))) }
                IntField("Jmax", wg.awgJmax, Modifier.weight(1f)) { v -> onChange(identity.copy(wg = wg.copy(awgJmax = v))) }
                IntField("S1", wg.awgS1, Modifier.weight(1f)) { v -> onChange(identity.copy(wg = wg.copy(awgS1 = v))) }
                IntField("S2", wg.awgS2, Modifier.weight(1f)) { v -> onChange(identity.copy(wg = wg.copy(awgS2 = v))) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LongField("H1", wg.awgH1, Modifier.weight(1f)) { v -> onChange(identity.copy(wg = wg.copy(awgH1 = v))) }
                LongField("H2", wg.awgH2, Modifier.weight(1f)) { v -> onChange(identity.copy(wg = wg.copy(awgH2 = v))) }
                LongField("H3", wg.awgH3, Modifier.weight(1f)) { v -> onChange(identity.copy(wg = wg.copy(awgH3 = v))) }
                LongField("H4", wg.awgH4, Modifier.weight(1f)) { v -> onChange(identity.copy(wg = wg.copy(awgH4 = v))) }
            }
        }
    }

    Slab(title = "Экспорт") {
        val vkRoute = identity.routes.firstOrNull { it is app.vkturn.model.Route.VkTurnProxy } as? app.vkturn.model.Route.VkTurnProxy
        val endpoint = vkRoute?.let { "${it.listenHost}:${it.listenPort}" } ?: "${server.host}:${server.proxyPort}"
        Text(
            "Endpoint для .conf: $endpoint",
            style = MaterialTheme.typography.bodySmall,
            color = AmneziaColors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val name = if (wg.flavor == WgFlavor.AMNEZIA) "${identity.name}.awg.conf" else "${identity.name}.wg.conf"
                    onSaveConfig(name, WgConfigGenerator.render(wg, endpoint))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmneziaColors.Primary,
                    contentColor = AmneziaColors.OnPrimary,
                ),
            ) {
                Icon(Icons.Filled.Save, null); Spacer(Modifier.width(6.dp)); Text("Сохранить .conf")
            }
        }
    }
}

@Composable
private fun IntField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    AmTextField(
        value = value.toString(),
        onValueChange = { v -> onChange(v.toIntOrNull() ?: value) },
        label = label,
        keyboardType = KeyboardType.Number,
        modifier = modifier,
    )
}

@Composable
private fun LongField(label: String, value: Long, modifier: Modifier, onChange: (Long) -> Unit) {
    AmTextField(
        value = value.toString(),
        onValueChange = { v -> onChange(v.toLongOrNull() ?: value) },
        label = label,
        keyboardType = KeyboardType.Number,
        modifier = modifier,
    )
}
