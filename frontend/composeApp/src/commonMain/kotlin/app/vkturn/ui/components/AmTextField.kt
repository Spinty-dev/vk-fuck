package app.vkturn.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import app.vkturn.ui.theme.AmneziaColors

/** Outlined text field wired to the Amnezia palette. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = AmneziaColors.TextTertiary) } },
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AmneziaColors.SurfaceElevated,
            unfocusedContainerColor = AmneziaColors.SurfaceElevated,
            disabledContainerColor = AmneziaColors.SurfaceElevated,
            focusedBorderColor = AmneziaColors.Primary,
            unfocusedBorderColor = AmneziaColors.Outline,
            focusedLabelColor = AmneziaColors.Primary,
            unfocusedLabelColor = AmneziaColors.TextSecondary,
            cursorColor = AmneziaColors.Primary,
            focusedTextColor = AmneziaColors.TextPrimary,
            unfocusedTextColor = AmneziaColors.TextPrimary,
        ),
    )
}
