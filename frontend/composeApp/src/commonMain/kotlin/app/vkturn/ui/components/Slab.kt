package app.vkturn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vkturn.ui.theme.AmneziaColors

/**
 * Rectangular "slab" container — the primary structural element of Amnezia-
 * style UI. Dark elevated surface with a hairline outline, a title and an
 * optional subtitle, then free-form content.
 */
@Composable
fun Slab(
    title: String? = null,
    subtitle: String? = null,
    padding: PaddingValues = PaddingValues(20.dp),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AmneziaColors.Surface,
        border = BorderStroke(1.dp, AmneziaColors.OutlineSoft),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AmneziaColors.TextPrimary,
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmneziaColors.TextSecondary,
                )
            }
            content()
        }
    }
}
