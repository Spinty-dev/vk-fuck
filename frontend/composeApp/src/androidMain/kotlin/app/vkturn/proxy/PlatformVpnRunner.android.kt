package app.vkturn.proxy

import app.vkturn.daemon.DaemonClient
import app.vkturn.daemon.VkturndElevator

@Suppress("UNUSED_PARAMETER")
actual fun createPlatformVpnRunner(
    logs: LogBus,
    resolver: BinaryResolver,
    hostFactory: () -> ProcessHost,
    daemonClient: DaemonClient,
    vkturndElevator: VkturndElevator?,
): VpnRunner? = createVpnRunner(logs)
