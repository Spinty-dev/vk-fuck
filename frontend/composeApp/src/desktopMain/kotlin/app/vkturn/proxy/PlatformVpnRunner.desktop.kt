package app.vkturn.proxy

import app.vkturn.daemon.DaemonClient
import app.vkturn.daemon.VkturndElevator
import app.vkturn.debug.AgentDebugLog

actual fun createPlatformVpnRunner(
    logs: LogBus,
    resolver: BinaryResolver,
    hostFactory: () -> ProcessHost,
    daemonClient: DaemonClient,
    vkturndElevator: VkturndElevator?,
): VpnRunner? {
    // Desktop "system VPN" path requires the privileged vkturnd daemon.
    // If it's not available, try to elevate (Polkit) when possible; otherwise
    // fall back to the plain user-mode vk-turn-proxy runner so the app still
    // works (just without TUN/system routing).
    AgentDebugLog.log(
        hypothesisId = "A",
        location = "PlatformVpnRunner.desktop.kt:createPlatformVpnRunner",
        message = "enter",
        data = mapOf("hasElevator" to (vkturndElevator != null)),
    )

    var hello = daemonClient.connect()
    daemonClient.close()
    AgentDebugLog.log(
        hypothesisId = "A",
        location = "PlatformVpnRunner.desktop.kt:createPlatformVpnRunner",
        message = "daemon connect probe",
        data = mapOf("ok" to hello.ok, "err" to hello.error?.take(120)),
    )
    if (!hello.ok && vkturndElevator != null) {
        logs.appendApp("vkturnd недоступен (${hello.error}); пробуем запустить через Polkit…", LogLevel.INFO)
        // The elevator is responsible for pkexec invocation and logging.
        vkturndElevator(logs)
        hello = daemonClient.connect()
        daemonClient.close()
        AgentDebugLog.log(
            hypothesisId = "A",
            location = "PlatformVpnRunner.desktop.kt:createPlatformVpnRunner",
            message = "after elevator connect probe",
            data = mapOf("ok" to hello.ok, "err" to hello.error?.take(120)),
        )
    }
    if (!hello.ok) {
        logs.appendApp(
            "vkturnd недоступен (${hello.error}); запускаем vk-turn без системного VPN (user-mode).",
            LogLevel.WARN,
        )
        AgentDebugLog.log(
            hypothesisId = "A",
            location = "PlatformVpnRunner.desktop.kt:createPlatformVpnRunner",
            message = "fallback to null vpnRunner",
            data = mapOf("err" to hello.error?.take(160)),
        )
        return null
    }
    AgentDebugLog.log(
        hypothesisId = "A",
        location = "PlatformVpnRunner.desktop.kt:createPlatformVpnRunner",
        message = "return DesktopVkTurnVpnRunner",
    )
    return DesktopVkTurnVpnRunner(logs, resolver, hostFactory, daemonClient, vkturndElevator)
}
