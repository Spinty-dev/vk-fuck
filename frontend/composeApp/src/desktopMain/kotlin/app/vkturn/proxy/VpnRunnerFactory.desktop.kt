package app.vkturn.proxy

/** Desktop doesn't own a VpnService — TUN flow runs through `vkturnd`. */
actual fun createVpnRunner(logs: LogBus): VpnRunner? = null
