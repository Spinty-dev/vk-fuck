package app.vkturn.platform

import android.os.Handler
import android.os.Looper

actual fun enqueueUi(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}
