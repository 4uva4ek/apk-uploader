import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val appProperties = Properties().apply { rootProject.file("app.properties").inputStream().use { load(it) } }
val appVersion: String = appProperties.getProperty("version").trim()

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        resources.srcDir("icons")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.flatlaf)
    implementation(libs.arsclib)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Bundled Android SDK Platform-Tools (adb): a pinned release from Google's SDK repository,
// verified by SHA-256 before anything is extracted. Only the adb client files are shipped.
// Each host OS packages its own adb, staged as the Compose resource directory of that OS.
class PlatformTools(val archive: String, val sha256: String, val files: List<String>)

val hostOs = System.getProperty("os.name").lowercase()
val resourceOs = when {
    hostOs.startsWith("windows") -> "windows"
    hostOs.startsWith("mac") -> "macos"
    else -> "linux"
}
val platformToolsVersion = "37.0.1"
val platformTools = mapOf(
    "windows" to PlatformTools(
        "win", "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99",
        listOf("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "libwinpthread-1.dll", "NOTICE.txt", "source.properties"),
    ),
    "linux" to PlatformTools(
        "linux", "d230f13842f60f782a8645f9c813f8f845bf36089ea7289f28c48f17979313f1",
        listOf("adb", "NOTICE.txt", "source.properties"),
    ),
    "macos" to PlatformTools(
        "darwin", "ee39ad5967e95c2a07f04dbcbde96b1a0c916ba376096db5d2f498b7727a5d1d",
        listOf("adb", "NOTICE.txt", "source.properties"),
    ),
).getValue(resourceOs)
val platformToolsUrl = "https://dl.google.com/android/repository/platform-tools_r$platformToolsVersion-${platformTools.archive}.zip"
val platformToolsSha256 = platformTools.sha256
val platformToolsFiles = platformTools.files
val platformToolsRoot = layout.buildDirectory.dir("platform-tools")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val fetchPlatformTools by tasks.registering {
    description = "Downloads the pinned Android platform-tools and stages adb for packaging."
    val archive = layout.buildDirectory.file("downloads/platform-tools_r$platformToolsVersion-${platformTools.archive}.zip")
    val output = platformToolsRoot.map { it.dir(resourceOs) }
    inputs.property("sha256", platformToolsSha256)
    inputs.property("files", platformToolsFiles)
    outputs.dir(output)
    doLast {
        val zip = archive.get().asFile
        if (!zip.isFile || sha256(zip) != platformToolsSha256) {
            zip.parentFile.mkdirs()
            logger.lifecycle("Downloading $platformToolsUrl")
            URI(platformToolsUrl).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
            val actual = sha256(zip)
            if (actual != platformToolsSha256) {
                zip.delete()
                throw GradleException("platform-tools checksum mismatch: $actual (expected $platformToolsSha256)")
            }
        }
        val target = output.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        val remaining = platformToolsFiles.toMutableSet()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && entry.name.startsWith("platform-tools/") && name in remaining) {
                    File(target, name).outputStream().use { input.copyTo(it) }
                    remaining -= name
                }
            }
        }
        if (remaining.isNotEmpty()) throw GradleException("platform-tools archive is missing: $remaining")
        // ZipInputStream drops Unix permissions.
        File(target, "adb").takeIf { it.isFile }?.setExecutable(true, false)
        File(target, "PLATFORM-TOOLS-VERSION.txt").writeText(
            "Android SDK Platform-Tools $platformToolsVersion\n$platformToolsUrl\nSHA-256 $platformToolsSha256\n",
        )
    }
}

tasks.matching { it.name.startsWith("prepareAppResources") }.configureEach { dependsOn(fetchPlatformTools) }

compose.desktop {
    application {
        mainClass = "apkuploader.MainKt"
        // LinuxDesktopEntry sets the X11 window class through the AWT toolkit.
        jvmArgs("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")

        buildTypes {
            release {
                proguard {
                    isEnabled = false
                }
            }
        }

        nativeDistributions {
            // Runtime modules beyond the Compose defaults: from `suggestRuntimeModules` (jdeps),
            // plus jdk.crypto.ec so HTTPS to GitHub negotiates EC cipher suites on JDK 21.
            modules("java.instrument", "jdk.unsupported", "jdk.crypto.ec")
            targetFormats(TargetFormat.Msi)
            packageName = "ApkUploader"
            packageVersion = appVersion
            description = "Installs APK files and game caches on Samsung Galaxy XR over ADB"
            vendor = "4uva4ek"
            appResourcesRootDir.set(platformToolsRoot)

            windows {
                shortcut = true
                perUserInstall = true
                menuGroup = "APK Uploader"
                iconFile.set(project.file("icons/app-icon.ico"))
            }
            linux {
                iconFile.set(project.file("icons/app-icon.png"))
            }
            macOS {
                bundleID = "io.github.4uva4ek.apkuploader"
                iconFile.set(project.file("icons/app-icon.icns"))
            }
        }
    }
}

// Compose copies app resources without Unix permissions; the bundled adb must stay executable.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    doLast {
        layout.buildDirectory.dir("compose/binaries").get().asFile.walk()
            .filter { it.name == "adb" && it.parentFile.name == "resources" }
            .forEach { it.setExecutable(true, false) }
    }
}
