package app.vkturn.daemon

import app.vkturn.proxy.LogBus

/**
 * Callback that may request elevated start of `vkturnd` (Polkit on Linux),
 * then returns whether a reconnect attempt should proceed.
 */
typealias VkturndElevator = (logs: LogBus) -> Boolean
