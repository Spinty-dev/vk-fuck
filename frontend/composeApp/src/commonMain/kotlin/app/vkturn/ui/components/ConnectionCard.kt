package app.vkturn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vkturn.proxy.RouteState
import app.vkturn.ui.theme.AmneziaColors

/**
 * Shared ring-button + status label used on both mobile dashboard and
 * desktop panel. Size and typography scale with [density]; behaviour and
 * colours are identical across platforms.
 */
@Immutable
enum class ConnectionCardDensity {
    /** 200 dp ring, tight typography — fits in a phone dashboard column. */
    Compact,

    /** 260 dp ring, larger headline — used for desktop panel card. */
    Expanded,
}

@Composable
fun ConnectionCard(
    state: RouteState,
    enabled: Boolean,
    statusTitle: String,
    statusSubtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    density: ConnectionCardDensity = ConnectionCardDensity.Compact,
) {
    val ringSize: Dp = when (density) {
        ConnectionCardDensity.Compact -> 200.dp
        ConnectionCardDensity.Expanded -> 260.dp
    }
    val baseColor = when (state) {
        RouteState.CONNECTED -> AmneziaColors.Connected
        RouteState.CONNECTING, RouteState.STARTING, RouteState.CAPTCHA, RouteState.LOCKOUT ->
            AmneziaColors.Connecting
        RouteState.ERROR -> AmneziaColors.Error
        RouteState.STOPPING, RouteState.IDLE -> AmneziaColors.Primary
    }
    val ringColor by animateColorAsState(baseColor, tween(380), label = "ring-color")
    val intensity by animateFloatAsState(
        targetValue = if (state == RouteState.CONNECTED) 1f else 0.7f,
        animationSpec = tween(500),
        label = "ring-intensity",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(ringSize)
                .drawBehind {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outerRadius = size.minDimension / 2f
                    val glow = Brush.radialGradient(
                        colors = listOf(
                            ringColor.copy(alpha = 0.32f * intensity),
                            ringColor.copy(alpha = 0.12f * intensity),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = outerRadius * 1.25f,
                    )
                    drawCircle(brush = glow, radius = outerRadius * 1.25f, center = center)
                    drawCircle(
                        color = ringColor,
                        radius = outerRadius - 10.dp.toPx(),
                        center = center,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = ringAction(state),
                    style = when (density) {
                        ConnectionCardDensity.Compact -> MaterialTheme.typography.titleMedium
                        ConnectionCardDensity.Expanded -> MaterialTheme.typography.titleLarge
                    },
                    color = ringColor,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = ringHint(state, enabled),
                    style = MaterialTheme.typography.labelMedium,
                    color = AmneziaColors.TextTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = statusTitle,
            style = when (density) {
                ConnectionCardDensity.Compact -> MaterialTheme.typography.titleMedium
                ConnectionCardDensity.Expanded -> MaterialTheme.typography.headlineMedium
            },
            color = AmneziaColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        if (!statusSubtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AmneziaColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Small colored dot + one-line state label — used in sidebars / app bars. */
@Composable
fun MiniStatusIndicator(state: RouteState, label: String, modifier: Modifier = Modifier) {
    val color = when (state) {
        RouteState.CONNECTED -> AmneziaColors.Connected
        RouteState.CONNECTING, RouteState.STARTING, RouteState.CAPTCHA, RouteState.LOCKOUT ->
            AmneziaColors.Connecting
        RouteState.ERROR -> AmneziaColors.Error
        RouteState.STOPPING, RouteState.IDLE -> AmneziaColors.TextTertiary
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AmneziaColors.TextSecondary,
        )
    }
}

private fun ringAction(state: RouteState): String = when (state) {
    RouteState.IDLE -> "Подключить"
    RouteState.STARTING -> "Запуск…"
    RouteState.CONNECTING -> "Соединение…"
    RouteState.CAPTCHA -> "Капча"
    RouteState.LOCKOUT -> "Ожидание…"
    RouteState.CONNECTED -> "Отключить"
    RouteState.STOPPING -> "Отключение…"
    RouteState.ERROR -> "Повторить"
}

private fun ringHint(state: RouteState, enabled: Boolean): String = when {
    !enabled -> "Нет активного профиля"
    state == RouteState.IDLE -> "Тап — старт VPN"
    state == RouteState.CONNECTED -> "Тап — остановить"
    state == RouteState.ERROR -> "Тап — перезапустить"
    else -> ""
}
