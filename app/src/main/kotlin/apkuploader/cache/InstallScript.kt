package apkuploader.cache

import java.io.File

/**
 * Rookie-style `install.txt`: only `adb push <local> <remote>` lines are honoured, and only when
 * the local path stays inside the dropped folder and the remote path is under /sdcard.
 * Every other line is reported and ignored.
 */
object InstallScript {
    class Push(val local: String, val remote: String)
    class Parsed(val pushes: List<Push>, val ignored: List<String>)
    class Planned(val files: List<CacheFile>, val notes: List<String>)

    private val existingRoots = setOf("/sdcard", "/sdcard/Android", "/sdcard/Android/obb", "/sdcard/Android/data")

    fun parse(text: String): Parsed {
        val pushes = mutableListOf<Push>()
        val ignored = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith("REM ", true)) continue
            val tokens = tokenize(line)
            val args = if (tokens.firstOrNull().equals("adb", true)) tokens.drop(1) else tokens
            if (args.size >= 3 && args[0].equals("push", true)) pushes += Push(args[1], args.last())
            else ignored += line
        }
        return Parsed(pushes, ignored)
    }

    fun plan(folder: File, parsed: Parsed): Planned {
        val files = mutableListOf<CacheFile>()
        val notes = parsed.ignored.map { "install.txt: ignored \"$it\"" }.toMutableList()
        val root = folder.canonicalFile
        for (push in parsed.pushes) {
            val local = File(folder, push.local.replace('\\', '/').trim('/')).canonicalFile
            if (!local.toPath().startsWith(root.toPath()) || !local.exists()) {
                notes += "install.txt: skipped push of \"${push.local}\" (not inside the folder)"
                continue
            }
            val remote = normalizeRemote(push.remote)
            if (remote == null) {
                notes += "install.txt: skipped push to \"${push.remote}\" (not under /sdcard)"
                continue
            }
            val into = push.remote.endsWith("/") || push.remote.endsWith("\\") || remote in existingRoots
            if (local.isFile) {
                val target = if (into) "$remote/${local.name}" else remote
                files += CacheFile(local, local.relativeTo(root).invariantSeparatorsPath, target)
            } else {
                val base = if (into) "$remote/${local.name}" else remote
                for (file in CacheLayout.collect(local)) {
                    files += CacheFile(file.local, file.local.relativeTo(root).invariantSeparatorsPath, "$base/${file.relativePath}")
                }
            }
        }
        return Planned(files.distinctBy { it.remote }, notes)
    }

    private fun normalizeRemote(remote: String): String? {
        var path = remote.trim().replace('\\', '/')
        path = path.replace(Regex("^/storage/emulated/0"), "/sdcard").replace(Regex("^/storage/self/primary"), "/sdcard")
        path = path.trimEnd('/')
        if (!path.startsWith("/sdcard/") && path != "/sdcard") return null
        if (path.split('/').any { it == ".." }) return null
        return path
    }

    private fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (char in line) {
            when {
                quote != null -> if (char == quote) quote = null else current.append(char)
                char == '"' || char == '\'' -> quote = char
                char.isWhitespace() -> if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }
}
