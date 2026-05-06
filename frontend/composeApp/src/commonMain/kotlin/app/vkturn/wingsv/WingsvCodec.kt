package app.vkturn.wingsv

/**
 * Platform hooks for WingsV subscription parsing:
 *   - [inflateZlib] undoes the deflate wrapper the 3x-ui panel puts on the
 *     serialized [WingsvConfig] before base64-encoding it.
 *   - [fetchSubscription] optionally performs an HTTP GET when the user
 *     pastes a URL instead of the raw `wingsv://…` token.
 */
expect fun inflateZlib(bytes: ByteArray): ByteArray

expect fun fetchSubscription(url: String): String?
