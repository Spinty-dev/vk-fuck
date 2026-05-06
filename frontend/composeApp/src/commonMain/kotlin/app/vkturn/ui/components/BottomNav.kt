package app.vkturn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vkturn.ui.nav.TopDest
import app.vkturn.ui.theme.AmneziaColors

/**
 * Mobile bottom bar. Three entries (Dashboard / Profiles / Settings) with
 * textual labels — no more guess-the-icon. Logs are reached via the
 * top-app-bar sheet, not a dedicated tab.
 */
@Composable
fun BottomNav(
    current: TopDest,
    onSelect: (TopDest) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = AmneziaColors.Surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopDest.mobileTabs.forEach { dest ->
                val selected = dest == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = { onSelect(dest) })
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = dest.iconMobile,
                            contentDescription = dest.labelMobile,
                            tint = if (selected) AmneziaColors.Primary else AmneziaColors.TextSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(AmneziaColors.Primary),
                            )
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Text(
                        text = dest.labelMobile,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) AmneziaColors.Primary else AmneziaColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
