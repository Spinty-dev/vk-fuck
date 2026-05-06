package app.vkturn.platform

/** Run [block] on the UI toolkit thread (Swing EDT / Android main looper). */
expect fun enqueueUi(block: () -> Unit)
