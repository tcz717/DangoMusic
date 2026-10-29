package me.sorihiko.dango.music.external

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal interface ExternalSessionProvider {
    fun poll(): ExternalSample?
    fun close()
}

/** Runs only the bundled executable (or an explicitly installed sibling), never a shell or PATH command. */
internal class WindowsSessionProvider(private val launch: (() -> Process)? = null) : ExternalSessionProvider {
    private val process = AtomicReference<Process?>()
    @Volatile private var closed = false
    private var executable: File? = null

    override fun poll(): ExternalSample? {
        if (closed || (launch == null && !supported())) return null
        return try {
            val child = if (launch != null) launch.invoke() else {
                val helper = executable ?: locate()?.also { executable = it } ?: return null
                ProcessBuilder(helper.absolutePath).redirectErrorStream(true).start()
            }
            process.set(child)
            if (closed) { child.destroyForcibly(); return null }
            val bytes = ByteArrayOutputStream()
            val overflow = java.util.concurrent.atomic.AtomicBoolean(false)
            val reader = Thread({
                try {
                    child.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (bytes.size() + n > 65_536) {
                                overflow.set(true)
                                child.destroyForcibly()
                                break
                            }
                            bytes.write(buffer, 0, n)
                        }
                    }
                } catch (_: Exception) { overflow.set(true) }
            }, "Dango-AppleMusic-Output").apply { isDaemon = true; start() }
            val finished = child.waitFor(2, TimeUnit.SECONDS)
            if (!finished) child.destroyForcibly()
            reader.join(250)
            if (!finished || child.isAlive || reader.isAlive || overflow.get() || child.exitValue() != 0) null
            else parse(String(bytes.toByteArray(), Charsets.UTF_8))
        } catch (_: Exception) { null }
        finally {
            process.getAndSet(null)?.let { child ->
                if (child.isAlive) child.destroyForcibly()
                try { child.inputStream.close() } catch (_: Exception) { }
            }
        }
    }

    override fun close() {
        closed = true
        process.getAndSet(null)?.destroyForcibly()
    }

    private fun locate(): File? {
        val resource = javaClass.getResourceAsStream("/native/windows-x64/dango-apple-music.exe")
        if (resource != null) {
            resource.use { input ->
                val dir = File(System.getProperty("user.home"), ".dango/native")
                if (!dir.isDirectory && !dir.mkdirs()) return null
                val target = File(dir, "dango-apple-music.exe")
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return target
            }
        }
        val jar = File(javaClass.protectionDomain.codeSource.location.toURI())
        if (!jar.isFile) return null
        return File(jar.parentFile, "dango-apple-music.exe").takeIf { it.isFile }
    }

    companion object {
        fun supported(): Boolean = System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("windows") &&
            System.getProperty("os.arch", "").lowercase(Locale.ROOT) in listOf("amd64", "x86_64")

        internal fun parse(json: String): ExternalSample? {
            return try {
                val o = JsonParser().parse(json.trim().removePrefix("\uFEFF")).asJsonObject
                if (!o.get("available").asBoolean) null else {
                    val status = when (o.get("state").asString) {
                        "playing" -> ExternalStatus.PLAYING
                        "paused" -> ExternalStatus.PAUSED
                        "stopped" -> ExternalStatus.STOPPED
                        else -> return null
                    }
                    val artists = o.getAsJsonArray("artists").map { it.asString }
                    val session = o.get("sessionId").asString
                    val title = o.get("title").asString
                    val album = o.get("album").asString
                    if (artists.size > 12 || artists.any { it.length > 200 } ||
                        session.length > 512 || title.length > 512 || album.length > 512) null
                    else ExternalSample(session, title,
                        artists, album, o.get("durationMs").asLong,
                        o.get("positionMs").asLong, o.get("updatedAtUnixMs").asLong,
                        o.get("capturedAtUnixMs").asLong, o.get("playbackRate").asDouble, status)
                }
            } catch (_: Exception) { null }
        }
    }
}
