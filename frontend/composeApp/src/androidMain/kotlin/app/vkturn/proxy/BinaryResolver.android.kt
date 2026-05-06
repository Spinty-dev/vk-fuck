package app.vkturn.proxy

import android.content.Context
import java.io.File

/** Set by MainActivity at startup. */
internal var androidBinaryResolverContext: Context? = null

/**
 * Android shippable binaries live inside `jniLibs/<abi>/` as
 * `libvkturnclient.so` / `libsingbox.so`. They survive APK extraction
 * with `+x` and are copied to the app's `nativeLibraryDir` by the
 * installer, which is on the app's ExecutableMounted mount.
 */
private class AndroidBinaryResolver(private val ctx: Context) : BinaryResolver {
    override fun resolveVkTurnProxy(override: String): String? =
        resolve(override, libName = "libvkturnclient.so", userFileName = "vkturn-client")

    override fun resolveSingBox(override: String): String? =
        resolve(override, libName = "libsingbox.so", userFileName = "sing-box")

    private fun resolve(override: String, libName: String, userFileName: String): String? {
        if (override.isNotBlank()) {
            val f = File(override)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val shipped = File(nativeDir, libName)
        if (shipped.exists()) return shipped.absolutePath

        val user = File(ctx.filesDir, userFileName)
        return if (user.exists() && user.canExecute()) user.absolutePath else null
    }
}

actual fun createBinaryResolver(): BinaryResolver {
    val ctx = androidBinaryResolverContext
        ?: error("androidBinaryResolverContext must be set in MainActivity.onCreate")
    return AndroidBinaryResolver(ctx)
}
