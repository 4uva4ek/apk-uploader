package apkuploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlatformTest {
    @Test
    fun externalProcessesDoNotInheritTheJpackageLauncherEnvironment() {
        if (Platform.os != Os.LINUX) return
        val previous = System.getProperty("jpackage.app-path")
        System.setProperty("jpackage.app-path", "/opt/ApkUploader/bin/ApkUploader")
        try {
            val builder = ProcessBuilder("true")
            builder.environment()["_JPACKAGE_LAUNCHER"] = "0"
            builder.environment()["LD_LIBRARY_PATH"] = ":/opt/ApkUploader/lib/app"
            val env = Platform.external(builder).environment()
            assertFalse("_JPACKAGE_LAUNCHER" in env)
            assertFalse("LD_LIBRARY_PATH" in env)

            val other = ProcessBuilder("true")
            other.environment()["LD_LIBRARY_PATH"] = "/usr/local/lib:/opt/ApkUploader/lib/app"
            assertEquals("/usr/local/lib", Platform.external(other).environment()["LD_LIBRARY_PATH"])
        } finally {
            if (previous == null) System.clearProperty("jpackage.app-path") else System.setProperty("jpackage.app-path", previous)
        }
    }
}
