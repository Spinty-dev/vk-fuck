package app.vkturn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.vkturn.proxy.RouteState
import app.vkturn.ui.nav.TopDest
import app.vkturn.ui.theme.AmneziaColors

/**
 * Desktop / tablet left sidebar. Shows the app mark, a list of top-level
 * destinations and a mini connection indicator at the bottom so users see
 * state from any screen.
 */
@Composable
fun SideNav(
    current: TopDest,
    onSelect: (TopDest) -> Unit,
    statusState: RouteState,
    statusLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(AmneziaColors.Surface)
            .padding(vertical = 20.dp, horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AmneziaColors.Primary),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("vkturn", style = MaterialTheme.typography.titleLarge, color = AmneziaColors.TextPrimary)
                Text("turn proxy client", style = MaterialTheme.typography.labelSmall, color = AmneziaColors.TextTertiary)
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TopDest.desktopItems.forEach { dest ->
                NavItem(
                    label = dest.labelDesktop,
                    selected = current == dest,
                    onClick = { onSelect(dest) },
                    icon = {
                        Icon(dest.iconDesktop, contentDescription = null, tint = if (current == dest) AmneziaColors.TextPrimary else AmneziaColors.TextSecondary)
                    },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AmneziaColors.SurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AmneziaColors.OutlineSoft),
        ) {
            MiniStatusIndicator(
                state = statusState,
                label = statusLabel,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val bg = if (selected) AmneziaColors.SurfaceElevated else Color.Transparent
    val border = if (selected) BorderStroke(1.dp, AmneziaColors.OutlineSoft) else null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onClick),
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) AmneziaColors.TextPrimary else AmneziaColors.TextSecondary,
            )
        }
    }
}
