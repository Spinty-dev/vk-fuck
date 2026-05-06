package app.vkturn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vkturn.proxy.RouteState
import app.vkturn.ui.theme.AmneziaColors
import app.vkturn.vm.Profile

/**
 * The "who are we connecting to" card. Shows the active profile
 * (server + identity + route badge) and exposes two quick actions:
 * switch profile (opens picker sheet) and edit.
 */
@Composable
fun ActiveProfileCard(
    profile: Profile?,
    state: RouteState,
    onSwitch: () -> Unit,
    onEdit: () -> Unit,
    onClick: () -> Unit = onSwitch,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = AmneziaColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, AmneziaColors.OutlineSoft),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state = state)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (profile == null) {
                    Text(
                        "Нет активного профиля",
                        style = MaterialTheme.typography.titleMedium,
                        color = AmneziaColors.TextPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Открой «Профили» или импортируй WingsV-ссылку",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmneziaColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = "Активный профиль",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmneziaColors.TextTertiary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${profile.serverName} · ${profile.identityName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = AmneziaColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KindBadge(kind = profile.routeKind.badge)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = profile.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = AmneziaColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSwitch) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = "Переключить профиль",
                    tint = AmneziaColors.Primary,
                )
            }
            if (profile != null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Редактировать",
                        tint = AmneziaColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(state: RouteState) {
    val color = when (state) {
        RouteState.CONNECTED -> AmneziaColors.Connected
        RouteState.CONNECTING, RouteState.STARTING, RouteState.CAPTCHA, RouteState.LOCKOUT ->
            AmneziaColors.Connecting
        RouteState.ERROR -> AmneziaColors.Error
        RouteState.STOPPING, RouteState.IDLE -> AmneziaColors.TextTertiary
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun KindBadge(kind: String) {
    Text(
        text = kind,
        style = MaterialTheme.typography.labelSmall,
        color = AmneziaColors.TextTertiary,
        modifier = Modifier
            .background(AmneziaColors.SurfaceElevated, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
