package app.vkturn.persistence

import android.content.Context
import app.vkturn.model.AppConfig
import java.io.File

/** Set by MainActivity at startup. */
internal var androidPersistenceContext: Context? = null

private class AndroidSettingsStore(private val ctx: Context) : SettingsStore {
    private val configFile by lazy { File(ctx.filesDir, "config.json") }
    private val legacyFile by lazy { File(ctx.filesDir, "settings.json") }

    override fun load(): AppConfig {
        val raw = when {
            configFile.exists() -> configFile.readText()
            legacyFile.exists() -> legacyFile.readText()
            else -> ""
        }
        return Migration.load(raw)
    }

    override fun save(config: AppConfig) {
        configFile.writeText(Migration.save(config))
    }
}

actual fun createSettingsStore(): SettingsStore {
    val ctx = androidPersistenceContext
        ?: error("androidPersistenceContext must be set in MainActivity.onCreate")
    return AndroidSettingsStore(ctx)
}
