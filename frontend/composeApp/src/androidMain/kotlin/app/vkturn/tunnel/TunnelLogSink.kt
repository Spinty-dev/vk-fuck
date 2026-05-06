package app.vkturn.tunnel

import app.vkturn.proxy.LogBus

/** Filled from [app.vkturn.MainActivity] so [VkturnVpnService] can append proxy logs. */
object TunnelLogSink {
    @Volatile
    var bus: LogBus? = null
}
