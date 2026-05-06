package app.vkturn.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vkturn.ui.theme.AmneziaColors

/**
 * Two-column row: label/description on the left, a trailing control on the
 * right. Used for every binary/enum toggle in Settings.
 */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AmneziaColors.TextPrimary)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = AmneziaColors.TextSecondary)
            }
        }
        trailing()
    }
}

@Composable
fun AmSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = AmneziaColors.OnPrimary,
            checkedTrackColor = AmneziaColors.Primary,
            checkedBorderColor = AmneziaColors.Primary,
            uncheckedThumbColor = AmneziaColors.TextSecondary,
            uncheckedTrackColor = AmneziaColors.SurfaceElevated,
            uncheckedBorderColor = AmneziaColors.Outline,
        ),
    )
}
