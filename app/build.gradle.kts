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
val platformToolsVersion = "37.0.1"
val platformToolsUrl = "https://dl.google.com/android/repository/platform-tools_r$platformToolsVersion-win.zip"
val platformToolsSha256 = "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99"
val platformToolsFiles = listOf("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "libwinpthread-1.dll", "NOTICE.txt", "source.properties")
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
    val archive = layout.buildDirectory.file("downloads/platform-tools_r$platformToolsVersion-win.zip")
    val output = platformToolsRoot.map { it.dir("windows") }
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
        File(target, "PLATFORM-TOOLS-VERSION.txt").writeText(
            "Android SDK Platform-Tools $platformToolsVersion\n$platformToolsUrl\nSHA-256 $platformToolsSha256\n",
        )
    }
}

tasks.matching { it.name.startsWith("prepareAppResources") }.configureEach { dependsOn(fetchPlatformTools) }

compose.desktop {
    application {
        mainClass = "apkuploader.MainKt"

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
        }
    }
}
