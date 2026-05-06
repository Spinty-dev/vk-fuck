package app.vkturn.proxy

import app.vkturn.daemon.DaemonClient
import app.vkturn.daemon.VkturndElevator

/**
 * Builds the VPN path for [Route.VkTurnProxy]: Android uses [VpnService]/GoBackend,
 * desktop uses vk-turn subprocess + privileged sing-box TUN ([DesktopVkTurnVpnRunner]).
 */
expect fun createPlatformVpnRunner(
    logs: LogBus,
    resolver: BinaryResolver,
    hostFactory: () -> ProcessHost,
    daemonClient: DaemonClient,
    vkturndElevator: VkturndElevator? = null,
): VpnRunner?
