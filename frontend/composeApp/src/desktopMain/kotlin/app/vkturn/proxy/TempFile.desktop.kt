package app.vkturn.proxy

import java.io.File

actual fun writeTempFile(name: String, content: String): String {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "vkturn").apply { mkdirs() }
    val file = File(dir, name)
    file.writeText(content)
    return file.absolutePath
}
