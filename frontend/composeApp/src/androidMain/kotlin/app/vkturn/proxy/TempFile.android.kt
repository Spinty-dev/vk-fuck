package app.vkturn.proxy

import java.io.File

actual fun writeTempFile(name: String, content: String): String {
    val ctx = androidBinaryResolverContext
        ?: error("androidBinaryResolverContext must be set in MainActivity.onCreate")
    val dir = File(ctx.cacheDir, "vkturn").apply { mkdirs() }
    val file = File(dir, name)
    file.writeText(content)
    return file.absolutePath
}
