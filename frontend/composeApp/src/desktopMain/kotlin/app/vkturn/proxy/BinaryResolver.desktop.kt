package app.vkturn.proxy

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val VKTURN_CLASSPATH_BUNDLE_CACHE = ".cache/vkturn/classpath-bundle"

/**
 * Locates native executables on the desktop. Order of resolution:
 *   1. User override (absolute path).
 *   2. `VKTURN_CLIENT` / `SINGBOX_BINARY` environment variables.
 *   3. Binaries unpacked from the classpath (resources under vkturn-bundle/ inside the uber JAR).
 *   4. App bundle — unpacked resources (`compose.application.resources.dir`, …).
 *   5. Common user-install paths (`~/.local/bin`, `/usr/local/bin`, ...).
 *   6. Working directory lookup.
 */
private class DesktopBinaryResolver : BinaryResolver {
    override fun resolveVkTurnProxy(override: String): String? =
        resolve(override, "vkturn-client", "VKTURN_CLIENT", listOf("vkturn-client", "client"))

    override fun resolveSingBox(override: String): String? =
        resolve(override, "sing-box", "SINGBOX_BINARY", listOf("sing-box"))

    override fun resolveVkturnd(override: String): String? =
        resolve(override, "vkturnd", "VKTURND_BINARY", listOf("vkturnd"))

    private fun resolve(
        override: String,
        friendlyName: String,
        envKey: String,
        bareNames: List<String>,
    ): String? {
        if (override.isNotBlank()) {
            val f = File(override)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        System.getenv(envKey)?.takeIf { it.isNotBlank() }?.let {
            val f = File(it)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }

        val home = System.getProperty("user.home") ?: "."
        val candidates = listOf(
            File(home, ".local/bin/$friendlyName"),
            File("/usr/local/bin/$friendlyName"),
            File("/usr/bin/$friendlyName"),
            File("/usr/lib/vkturn/$friendlyName"),
            File("/opt/homebrew/bin/$friendlyName"),
        ) + bareNames.map { File(System.getProperty("user.dir"), it) }

        candidates.firstOrNull { it.exists() && it.canExecute() }?.absolutePath?.let { return it }

        classpathBundledExtractedPath(friendlyName)?.let { return it }

        // Bundled binaries shipped next to the JAR — we look for a
        // `native/<os>-<arch>/<name>` tree relative to the app's class
        // path root; jpackage places resources there.
        bundledBinary(friendlyName)?.let { return it }

        return null
    }

    /** Extracts classpath entries under vkturn-bundle using a SHA-256 prefix dir so swapping JARs never reuses stale files. */
    private fun classpathBundledExtractedPath(nameStem: String): String? {
        val fileName = "$nameStem${execSuffix()}"
        val resourcePath = "vkturn-bundle/$fileName"
        val loader = Thread.currentThread().contextClassLoader
            ?: DesktopBinaryResolver::class.java.classLoader
        val inp = loader.getResourceAsStream(resourcePath) ?: return null
        inp.use { stream ->
            val cacheRoot = File(
                System.getProperty("user.home") ?: ".",
                VKTURN_CLASSPATH_BUNDLE_CACHE,
            )
            cacheRoot.mkdirs()

            val md = MessageDigest.getInstance("SHA-256")
            val tmp = Files.createTempFile(cacheRoot.toPath(), "${fileName}-", ".tmp").toFile()
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val digestBytes = md.digest()
            val hc = "0123456789abcdef"
            val hex = digestBytes.take(12).joinToString("") { b ->
                val i = b.toInt() and 0xff
                "${hc[i shr 4]}${hc[i and 15]}"
            }

            val versionedDir = File(cacheRoot, hex)
            versionedDir.mkdirs()
            val dest = File(versionedDir, fileName)
            try {
                if (!dest.exists() || dest.length() != tmp.length()) {
                    Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } else {
                    tmp.delete()
                }
            } catch (_: Exception) {
                Files.copy(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                tmp.delete()
            }
            if (!windowsHost()) {
                runCatching { dest.setExecutable(true, false) }
            }
            return dest.absolutePath.takeIf { it.isNotBlank() }
        }
    }

    private fun bundledBinary(name: String): String? {
        val fileName = "$name${execSuffix()}"

        // Compose MP's `appResourcesRootDir` picks the platform subdir and
        // merges it into `compose.application.resources.dir`, so the binary
        // ends up at the top level of that directory at runtime.
        val roots = listOfNotNull(
            System.getProperty("compose.application.resources.dir")?.let { File(it) },
            File(System.getProperty("user.dir"), "resources"),
            File(System.getProperty("user.dir"), "app/resources"),
        )
        for (root in roots) {
            val exe = File(root, fileName)
            if (exe.exists()) {
                // Make sure the file is executable — jpackage preserves +x
                // on Linux/macOS but MSI/Docker layouts may not.
                if (!exe.canExecute()) runCatching { exe.setExecutable(true) }
                return exe.absolutePath
            }
        }

        // Dev-loop fallback: look in the source tree under
        // composeApp/native/<os-arch>/ when running from Gradle.
        val sourcePlatform = composePlatformDir() ?: return null
        val sourceRoots = listOf(
            File(System.getProperty("user.dir"), "composeApp/native/$sourcePlatform"),
            File(System.getProperty("user.dir"), "native/$sourcePlatform"),
        )
        for (root in sourceRoots) {
            val exe = File(root, fileName)
            if (exe.exists()) {
                if (!exe.canExecute()) runCatching { exe.setExecutable(true) }
                return exe.absolutePath
            }
        }
        return null
    }

    private fun composePlatformDir(): String? {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty()
        val osTag = when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("win") -> "windows"
            os.contains("linux") -> "linux"
            else -> return null
        }
        val archTag = when (arch) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return "$osTag-$archTag"
    }

    private fun execSuffix(): String = if (windowsHost()) ".exe" else ""

    private fun windowsHost(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}

actual fun createBinaryResolver(): BinaryResolver = DesktopBinaryResolver()
