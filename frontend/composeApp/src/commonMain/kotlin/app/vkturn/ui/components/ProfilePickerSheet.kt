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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vkturn.proxy.RouteState
import app.vkturn.proxy.RouteStatus
import app.vkturn.ui.theme.AmneziaColors
import app.vkturn.vm.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePickerSheet(
    profiles: List<Profile>,
    activeProfileId: String?,
    statuses: Map<String, RouteStatus>,
    onPick: (Profile) -> Unit,
    onOpenImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AmneziaColors.Surface,
        dragHandle = null,
    ) {
        ProfilePickerContent(
            profiles = profiles,
            activeProfileId = activeProfileId,
            statuses = statuses,
            onPick = onPick,
            onOpenImport = onOpenImport,
        )
    }
}

/** Same content as the modal sheet, but embeddable (used as a dropdown on desktop). */
@Composable
fun ProfilePickerContent(
    profiles: List<Profile>,
    activeProfileId: String?,
    statuses: Map<String, RouteStatus>,
    onPick: (Profile) -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Выбрать профиль",
            style = MaterialTheme.typography.titleMedium,
            color = AmneziaColors.TextPrimary,
        )
        Text(
            "Профиль — это сочетание сервера, identity и маршрута. " +
                "Один тап — и он становится активным.",
            style = MaterialTheme.typography.bodySmall,
            color = AmneziaColors.TextSecondary,
        )
        if (profiles.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AmneziaColors.SurfaceElevated,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AmneziaColors.OutlineSoft),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Нет ни одного профиля",
                        style = MaterialTheme.typography.titleSmall,
                        color = AmneziaColors.TextPrimary,
                    )
                    Text(
                        "Вставь WingsV-ссылку или добавь сервер вручную.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmneziaColors.TextSecondary,
                    )
                    Text(
                        "Импорт WingsV",
                        modifier = Modifier
                            .clickable(onClick = onOpenImport)
                            .padding(vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = AmneziaColors.Primary,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val grouped = profiles.groupBy { it.serverName }
                grouped.forEach { (serverName, list) ->
                    item("group-$serverName") {
                        Text(
                            serverName,
                            style = MaterialTheme.typography.labelMedium,
                            color = AmneziaColors.TextTertiary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(list, key = { it.routeId }) { profile ->
                        ProfileRow(
                            profile = profile,
                            isActive = profile.routeId == activeProfileId,
                            status = statuses[profile.routeId],
                            onClick = { onPick(profile) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: Profile,
    isActive: Boolean,
    status: RouteStatus?,
    onClick: () -> Unit,
) {
    val bg = if (isActive) AmneziaColors.SurfaceElevated else Color.Transparent
    val border = if (isActive) BorderStroke(1.dp, AmneziaColors.Primary) else BorderStroke(1.dp, AmneziaColors.OutlineSoft)
    val state = status?.state ?: RouteState.IDLE
    val dotColor = when (state) {
        RouteState.CONNECTED -> AmneziaColors.Connected
        RouteState.CONNECTING, RouteState.STARTING, RouteState.CAPTCHA, RouteState.LOCKOUT ->
            AmneziaColors.Connecting
        RouteState.ERROR -> AmneziaColors.Error
        RouteState.STOPPING, RouteState.IDLE -> AmneziaColors.TextTertiary
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = bg,
        shape = RoundedCornerShape(10.dp),
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.identityName,
                    style = MaterialTheme.typography.titleSmall,
                    color = AmneziaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profile.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = AmneziaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                profile.routeKind.badge,
                style = MaterialTheme.typography.labelSmall,
                color = AmneziaColors.TextTertiary,
                modifier = Modifier
                    .background(AmneziaColors.SurfaceElevated, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
