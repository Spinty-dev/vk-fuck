package app.vkturn.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Two-bucket size classifier good enough for our needs — a phone is
 * Compact, anything wider (tablet landscape / desktop window) is
 * Expanded. We deliberately avoid `material3-adaptive` to keep the
 * dep surface tiny.
 */
enum class WindowSize { Compact, Expanded }

val CompactBreakpoint: Dp = 600.dp

fun windowSizeFor(widthDp: Dp): WindowSize =
    if (widthDp < CompactBreakpoint) WindowSize.Compact else WindowSize.Expanded
