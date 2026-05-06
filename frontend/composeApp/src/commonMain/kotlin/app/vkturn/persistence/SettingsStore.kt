package app.vkturn.persistence

import app.vkturn.model.AppConfig

/** Minimal cross-platform KV store for a serialized [AppConfig] tree. */
interface SettingsStore {
    fun load(): AppConfig
    fun save(config: AppConfig)
}

expect fun createSettingsStore(): SettingsStore
