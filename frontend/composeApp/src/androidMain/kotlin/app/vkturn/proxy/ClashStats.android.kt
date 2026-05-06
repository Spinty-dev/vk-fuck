package app.vkturn.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

actual fun pollClashStats(port: Int): ClashStats? {
    if (port <= 0) return null
    return runCatching {
        val conn = (URL("http://127.0.0.1:$port/connections").openConnection() as HttpURLConnection).apply {
            connectTimeout = 500
            readTimeout = 500
            requestMethod = "GET"
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(body).jsonObject
        ClashStats(
            downloadTotal = root["downloadTotal"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            uploadTotal = root["uploadTotal"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }.getOrNull()
}
