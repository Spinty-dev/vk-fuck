package app.vkturn.platform

import javax.swing.SwingUtilities

actual fun enqueueUi(block: () -> Unit) {
    SwingUtilities.invokeLater(block)
}
