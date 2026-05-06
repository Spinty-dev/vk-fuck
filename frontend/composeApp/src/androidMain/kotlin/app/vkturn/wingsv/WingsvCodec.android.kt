package app.vkturn.wingsv

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Inflater

actual fun inflateZlib(bytes: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(bytes)
    val buf = ByteArray(8 * 1024)
    val out = ByteArrayOutputStream(bytes.size * 3)
    try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buf, 0, n)
        }
    } finally {
        inflater.end()
    }
    return out.toByteArray()
}

actual fun fetchSubscription(url: String): String? = runCatching {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 10_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "vkturn/1.0")
        setRequestProperty("Accept", "*/*")
    }
    conn.inputStream.bufferedReader().use { it.readText() }
}.getOrNull()
