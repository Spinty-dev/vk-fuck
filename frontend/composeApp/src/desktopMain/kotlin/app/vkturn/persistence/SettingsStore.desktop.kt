package app.vkturn.persistence

import app.vkturn.model.AppConfig
import java.io.File

/**
 * Stores the configuration tree as pretty-printed JSON under the
 * conventional per-user config dir for the host OS:
 *
 *   Linux    — $XDG_CONFIG_HOME/vkturn/config.json (fallback ~/.config)
 *   macOS    — ~/Library/Application Support/vkturn/config.json
 *   Windows  — %APPDATA%\vkturn\config.json
 *
 * An older `settings.json` layout (schema v0) is auto-migrated on load.
 */
private class DesktopSettingsStore : SettingsStore {
    private val configDir: File by lazy { resolveConfigDir() }
    private val configFile: File by lazy { File(configDir, "config.json") }
    private val legacyFile: File by lazy { File(configDir, "settings.json") }

    override fun load(): AppConfig {
        val raw = when {
            configFile.exists() -> configFile.readText()
            legacyFile.exists() -> legacyFile.readText()
            else -> ""
        }
        return Migration.load(raw)
    }

    override fun save(config: AppConfig) {
        configDir.mkdirs()
        configFile.writeText(Migration.save(config))
    }

    private fun resolveConfigDir(): File {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val home = System.getProperty("user.home") ?: "."
        return when {
            os.contains("mac") ->
                File(home, "Library/Application Support/vkturn")
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path
                File(appData, "vkturn")
            }
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    ?: File(home, ".config").path
                File(xdg, "vkturn")
            }
        }
    }
}

actual fun createSettingsStore(): SettingsStore = DesktopSettingsStore()
