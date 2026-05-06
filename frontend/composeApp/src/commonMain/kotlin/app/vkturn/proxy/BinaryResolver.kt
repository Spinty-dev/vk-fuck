package app.vkturn.proxy

/**
 * Locates platform-specific executables for the proxy client and sing-box.
 * Lookup priority on every target:
 *   1. Explicit override (from [AppConfig]).
 *   2. Platform bundle (nativeLibraryDir on Android, app resources dir on
 *      desktop — populated by the packaging script).
 *   3. Common user-install locations (only on desktop).
 */
interface BinaryResolver {
    /** Returns an absolute path, or null if nothing usable is found. */
    fun resolveVkTurnProxy(override: String): String?
    fun resolveSingBox(override: String): String?
    /** Путь к привилегированному демону TUN (`vkturnd`); на Android не используется. */
    fun resolveVkturnd(override: String): String? = null
}

expect fun createBinaryResolver(): BinaryResolver
