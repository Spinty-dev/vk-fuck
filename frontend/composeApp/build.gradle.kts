import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
    }

    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }

        val desktopMain by getting {
            resources.srcDir(layout.buildDirectory.dir("generated/vkturnClasspathEmbed"))
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.runtime)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.wireguard.tunnel)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "app.vkturn"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vkturn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Self-signed keystore committed to the repo so anyone can produce an
    // installable `-release.apk` without a separate CI pipeline. Not suitable
    // for Play distribution — rotate before publishing.
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "vkturn-release"
            keyAlias = "vkturn"
            keyPassword = "vkturn-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Keep the bundled Go binaries executable on install (they are shipped
        // as lib*.so inside jniLibs so Android extracts them with +x).
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        res.srcDirs("src/androidMain/res")
        jniLibs.srcDirs("src/androidMain/jniLibs")
    }
}

// ---------- native binary plumbing ----------
//
// We ship `vk-turn-proxy-client` and `sing-box` inside every distribution
// so the UI works out of the box. The `build-native.sh` helper actually
// cross-compiles them — here we wire the output locations so Gradle picks
// them up when present and skips silently when they're not (dev loop).

val nativeBundleDir = layout.projectDirectory.dir("native")
val jniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")

/** Compose-style folder under [nativeBundleDir], e.g. `linux-x64` (matches [BinaryResolver.desktop]). */
fun vkturnHostNativePlatformSegment(): String {
    val osName = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osTag = when {
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        osName.contains("windows") -> "windows"
        osName.contains("linux") || osName.contains("freebsd") -> "linux"
        else -> throw GradleException("vkturn classpath embed: unsupported OS: $osName")
    }
    val archTag = when (arch) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException("vkturn classpath embed: unsupported arch: $arch")
    }
    return "$osTag-$archTag"
}

val vkturnClasspathEmbedGenerated = layout.buildDirectory.dir("generated/vkturnClasspathEmbed")

/** Puts `vkturn-bundle/{vkturn-client*,sing-box*,vkturnd*}` into the desktop uber JAR. */
tasks.register("embedVkturnClasspathBinaries") {
    group = "vkturn"
    description =
        "Copy composeApp/native/<host>/ binaries into Gradle resources vkturn-bundle/ (for package*UberJar)."

    val hostNativeLeaf = vkturnHostNativePlatformSegment()

    inputs.dir(nativeBundleDir.dir(hostNativeLeaf))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()

    outputs.dir(vkturnClasspathEmbedGenerated)

    doLast {
        val embedRoot = vkturnClasspathEmbedGenerated.get().asFile
        delete(embedRoot)

        val plat = vkturnHostNativePlatformSegment()
        val srcRoot = nativeBundleDir.dir(plat).asFile
        val outBundle = embedRoot.resolve("vkturn-bundle")

        if (!srcRoot.isDirectory) {
            embedRoot.mkdirs()
            logger.lifecycle(
                "vkturn: skip uber-jar binary embed — missing directory $srcRoot (run scripts/build-native.sh desktop-…)",
            )
            return@doLast
        }

        val files = srcRoot.listFiles()?.filter { f ->
            f.isFile &&
                (
                    f.name.startsWith("vkturn-client") ||
                        f.name.startsWith("sing-box") ||
                        f.name.startsWith("vkturnd")
                    )
        }.orEmpty()

        if (files.isEmpty()) {
            embedRoot.mkdirs()
            logger.lifecycle("vkturn: skip uber-jar binary embed — no vkturn-client / sing-box in $srcRoot")
            return@doLast
        }

        outBundle.mkdirs()
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")
        for (f in files) {
            val destFile = File(outBundle, f.name)
            f.copyTo(destFile, overwrite = true)
            if (!isWindows) {
                destFile.setExecutable(true, false)
            }
        }
        logger.lifecycle("vkturn: embedded ${files.size} native binary/binaries into $outBundle for uber JAR")
    }
}

tasks.named("desktopProcessResources") {
    dependsOn("embedVkturnClasspathBinaries")
}

val buildNativeAndroid by tasks.registering(Exec::class) {
    group = "vkturn"
    description = "Cross-compile libvkturnclient.so and libsingbox.so for all Android ABIs (Go + ANDROID_NDK for armeabi-v7a/x86_64)."
    workingDir = rootProject.projectDir
    val script = rootProject.projectDir.resolve("scripts/build-native.sh")
    commandLine("bash", script.absolutePath, "android")
    onlyIf { script.exists() }
}

val buildNativeBinaries by tasks.registering(Exec::class) {
    group = "vkturn"
    description = "Cross-compile vk-turn-proxy-client, sing-box and vkturnd into the bundle trees."
    workingDir = rootProject.projectDir
    val script = rootProject.projectDir.resolve("scripts/build-native.sh")
    commandLine("bash", script.absolutePath, "all")
    isIgnoreExitValue = true
    onlyIf { script.exists() }
}

compose.desktop {
    application {
        mainClass = "app.vkturn.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }

        nativeDistributions {
            // Include a platform-specific `native/<os>-<arch>/` subdir with
            // the vk-turn-proxy client and sing-box binaries alongside the
            // packaged app — jpackage places this tree under the resources
            // dir which BinaryResolver looks up at runtime.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("native"))

            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "vkturn"
            packageVersion = "1.0.0"
            description = "VK TURN Proxy — a cross-platform GUI"
            vendor = "vkturn"

            linux {
                packageName = "vkturn"
                menuGroup = "Network"
            }
            windows {
                menuGroup = "vkturn"
                upgradeUuid = "9b1c8c2e-5a5e-4d24-9c2b-2b4d3b5a7a42"
            }
            macOS {
                bundleID = "app.vkturn"
            }
        }
    }
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        mustRunAfter(buildNativeAndroid)
    }
    tasks.register("releaseApk") {
        group = "vkturn"
        description = "Run build-native.sh android (if script present), then assembleRelease (signed APK)."
        dependsOn(buildNativeAndroid)
        dependsOn(tasks.named("assembleRelease"))
    }
}

// ---------- executable fat JAR for distribution ----------
//
// Compose Desktop plugin creates platform-specific packages (Deb/Rpm/Msi/Dmg)
// but we also need a standalone executable JAR for custom distributions.
// This task creates vkturn-gui.jar with proper Main-Class manifest.

tasks.register<Jar>("packageUberJar") {
    group = "vkturn"
    description = "Create an executable fat JAR (vkturn-gui.jar) for distribution."

    archiveFileName.set("vkturn-gui.jar")

    // Include compiled classes from desktop main compilation
    from(layout.buildDirectory.dir("classes/kotlin/desktop/main"))

    // Include generated resources (vkturn-bundle binaries)
    from(layout.buildDirectory.dir("generated/vkturnClasspathEmbed"))

    // Include regular resources
    from(layout.buildDirectory.dir("processedResources/desktop/main"))

    // Collect all runtime dependencies
    val runtimeClasspath = configurations.named("desktopRuntimeClasspath").get()
    from(runtimeClasspath.filter { it.exists() }.map { f ->
        if (f.isDirectory) f else zipTree(f)
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/INDEX.LIST")
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")

    manifest {
        attributes(
            "Main-Class" to "app.vkturn.MainKt",
            "Implementation-Title" to "vkturn",
            "Implementation-Version" to "1.0.0"
        )
    }

    dependsOn("desktopProcessResources")
    dependsOn("compileKotlinDesktop")
}

tasks.register("packageReleaseUberJar") {
    group = "vkturn"
    description = "Build native binaries then create the executable fat JAR."
    dependsOn("buildNativeBinaries")
    dependsOn("packageUberJar")
}
