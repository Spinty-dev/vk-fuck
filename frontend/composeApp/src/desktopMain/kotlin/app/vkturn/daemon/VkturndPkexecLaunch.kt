package app.vkturn.daemon

import app.vkturn.proxy.BinaryResolver
import app.vkturn.proxy.LogBus
import app.vkturn.proxy.LogLevel
import java.io.File
import java.util.concurrent.TimeUnit

private val elevateLock = Any()

/** Wrap for `sh`, so paths with spaces survive a single `-c` string. */
private fun posixSingleQuoted(raw: String): String =
    "'" + raw.replace("'", "'\\''") + "'"

private fun resolveSocketGroup(logs: LogBus): String {
    // If user already belongs to vkturn group, keep service default.
    val allGroups =
        runCatching {
            ProcessBuilder("id", "-nG").start().inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()?.trim().orEmpty()
    if (allGroups.split(Regex("\\s+")).any { it == "vkturn" }) return "vkturn"

    // Otherwise bind socket to current primary group so GUI can connect.
    val primary =
        runCatching {
            ProcessBuilder("id", "-gn").start().inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()?.trim().orEmpty()
    if (primary.isNotBlank()) {
        logs.appendApp("vkturnd socket group: $primary (fallback, no vkturn membership)", LogLevel.INFO)
        return primary
    }
    logs.appendApp("Не удалось определить группу пользователя, используем vkturn.", LogLevel.WARN)
    return "vkturn"
}

/**
 * Linux: показывает диалог Polkit через `pkexec` и стартует `vkturnd` «в отрыве» через `sh … &`,
 * чтобы наш процесс не блокировался на долгоживущем демоне.
 * На других ОС возвращает `false`.
 */
internal fun tryLaunchPrivilegedVkturnd(
    client: DaemonClient,
    address: DaemonAddress,
    resolver: BinaryResolver,
    logs: LogBus,
): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if (!os.contains("linux")) {
        logs.appendApp("Автоподъём vkturnd через pkexec поддержан только в Linux.", LogLevel.INFO)
        return false
    }
    val pkexec = File("/usr/bin/pkexec")
    if (!pkexec.canExecute()) {
        logs.appendApp(
            "Не найден /usr/bin/pkexec — нужен Polkit-agent или systemd‑установленный vkturnd.",
            LogLevel.WARN,
        )
        return false
    }

    val binary =
        resolver.resolveVkturnd("") ?: run {
            logs.appendApp(
                "Не найден vkturnd (переменная VKTURND_BINARY, JAR-bundle, см. также /usr/lib/vkturn/).",
                LogLevel.WARN,
            )
            return false
        }

    synchronized(elevateLock) {
        val probe = client.connect()
        if (probe.ok) {
            return true
        }
        client.close()

        logs.appendApp("Запрос Polkit для запуска привилегированного демона vkturnd…", LogLevel.INFO)

        val socketGroup = resolveSocketGroup(logs)
        val demonLine =
            listOf(
                posixSingleQuoted(binary),
                "-socket",
                posixSingleQuoted(address.path),
                "-group",
                posixSingleQuoted(socketGroup),
            ).joinToString(" ")

        val script = "nohup $demonLine </dev/null >/dev/null 2>&1 &"
        val proc =
            try {
                ProcessBuilder(
                    pkexec.absolutePath,
                    "/bin/sh",
                    "-c",
                    script,
                ).start()
            } catch (t: Throwable) {
                logs.appendApp("Не удалось вызвать pkexec (${t.message ?: t.javaClass.simpleName})", LogLevel.ERROR)
                return false
            }

        // If Polkit dialog is available, pkexec normally returns quickly.
        // While it is running, we proactively probe the socket so we can proceed
        // as soon as daemon is ready instead of blocking for a fixed timeout.
        val launchWindowMs = 20_000L
        val deadline = System.currentTimeMillis() + launchWindowMs
        var connected = false
        while (!connected && System.currentTimeMillis() < deadline) {
            if (client.connect().ok) {
                connected = true
                break
            }
            client.close()
            if (proc.waitFor(250, TimeUnit.MILLISECONDS)) break
            if (Thread.currentThread().isInterrupted) break
        }

        if (connected) {
            logs.appendApp("vkturnd запущен через Polkit, подключение восстановлено.", LogLevel.INFO)
            return true
        }

        if (!proc.waitFor(150, TimeUnit.MILLISECONDS) && !connected && System.currentTimeMillis() >= deadline) {
            proc.destroyForcibly()
            logs.appendApp(
                "pkexec не ответил за ${launchWindowMs / 1000} с. Проверьте, что запущен Polkit-agent (в DE/WM) или установите daemon/install.sh.",
                LogLevel.ERROR,
            )
            return false
        }

        val lastErr = client.connect().error.orEmpty()
        val code = if (proc.isAlive) 0 else proc.exitValue()
        if (code != 0 || !connected) {
            val detail = if (lastErr.contains("Permission denied")) {
                "Ошибка доступа к сокету (Permission denied). Если вы только что добавились в группу vkturn, ПЕРЕЗАЙДИТЕ В СИСТЕМУ (logout/login)."
            } else {
                "Часто это отказ/таймаут Polkit или отсутствие Polkit-agent."
            }
            logs.appendApp(
                "pkexec завершился без запуска vkturnd (код=$code). $detail",
                LogLevel.WARN,
            )
            return false
        }
        return connected
    }
}
