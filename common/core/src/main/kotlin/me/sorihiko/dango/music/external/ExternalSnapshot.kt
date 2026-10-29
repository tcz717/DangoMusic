package me.sorihiko.dango.music.external

import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.NeteaseTrack
import java.util.Collections

enum class ExternalStatus { DISABLED, UNSUPPORTED, DISCONNECTED, PLAYING, PAUSED, STOPPED }

/** One coherent read; position is fixed at the instant this snapshot was obtained. */
class ExternalSnapshot internal constructor(
    val status: ExternalStatus,
    val sessionId: String = "",
    val title: String = "",
    artists: List<String> = emptyList(),
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    private val match: ExternalMatch? = null,
) {
    val artists: List<String> = Collections.unmodifiableList(ArrayList(artists))
    val key: String = if (sessionId.isEmpty()) "" else
        (listOf(sessionId, title, album, durationMs.toString(), artists.size.toString()) + artists)
            .joinToString("|") { "${it.length}:$it" }
    // The historical track model has a mutable coverUrl, so never expose our stored instance.
    val track: NeteaseTrack? get() = match?.copyTrack()
    val lyrics: Lyrics? get() = match?.lyrics
    val energy: Float get() = 0f
    val active: Boolean get() = status == ExternalStatus.PLAYING || status == ExternalStatus.PAUSED
}

/** Positive offsets show lyrics earlier; saturating arithmetic also handles untrusted positions. */
fun lyricPosition(positionMs: Long, durationMs: Long, offsetMs: Int): Long {
    val offset = offsetMs.toLong()
    val adjusted = when {
        offset > 0 && positionMs > Long.MAX_VALUE - offset -> Long.MAX_VALUE
        offset < 0 && positionMs < Long.MIN_VALUE - offset -> Long.MIN_VALUE
        else -> positionMs + offset
    }
    return adjusted.coerceIn(0L, durationMs.coerceAtLeast(0L))
}

internal interface ExternalClock {
    fun nanoTime(): Long
    fun unixMs(): Long
}

internal object SystemExternalClock : ExternalClock {
    override fun nanoTime() = System.nanoTime()
    override fun unixMs() = System.currentTimeMillis()
}

internal data class ExternalSample(
    val sessionId: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val updatedAtUnixMs: Long,
    val capturedAtUnixMs: Long,
    val playbackRate: Double,
    val status: ExternalStatus,
) {
    fun identity() = listOf(sessionId, title, album, durationMs.toString(), artists.size.toString()) + artists
}

internal class ExternalTimeline(private val clock: ExternalClock) {
    private var sample: ExternalSample? = null
    private var receivedNs = 0L
    private var anchorPosition = 0L

    fun accept(value: ExternalSample?): Boolean {
        if (value == null || value.sessionId.isBlank() || value.title.isBlank() ||
            value.durationMs !in 0..86_400_000L ||
            value.capturedAtUnixMs <= 0 ||
            kotlin.math.abs(clock.unixMs() - value.capturedAtUnixMs) > 10_000 ||
            value.status !in listOf(ExternalStatus.PLAYING, ExternalStatus.PAUSED, ExternalStatus.STOPPED)) {
            sample = null
            return false
        }
        val age = value.capturedAtUnixMs - value.updatedAtUnixMs
        val validAnchor = value.updatedAtUnixMs > 0 && age in 0..86_400_000L
        val rate = value.playbackRate.takeIf { it.isFinite() && it in 0.0..8.0 } ?: 0.0
        val running = value.status == ExternalStatus.PLAYING && validAnchor
        val transportDelay = (clock.unixMs() - value.capturedAtUnixMs).coerceIn(0, 10_000)
        anchorPosition = (value.positionMs.toDouble() +
            if (running) (age + transportDelay) * rate else 0.0)
            .coerceIn(0.0, value.durationMs.toDouble()).toLong()
        sample = value.copy(artists = Collections.unmodifiableList(ArrayList(value.artists)),
            playbackRate = if (running) rate else 0.0)
        receivedNs = clock.nanoTime()
        return true
    }

    fun snapshot(match: ExternalMatch?): ExternalSnapshot {
        val s = sample ?: return ExternalSnapshot(ExternalStatus.DISCONNECTED)
        val elapsed = ((clock.nanoTime() - receivedNs).coerceAtLeast(0) / 1_000_000L)
        if (elapsed > 5_000) return ExternalSnapshot(ExternalStatus.DISCONNECTED)
        val position = (anchorPosition + elapsed * s.playbackRate).coerceIn(0.0, s.durationMs.toDouble()).toLong()
        return ExternalSnapshot(s.status, s.sessionId, s.title, s.artists, s.album, s.durationMs, position, match)
    }
}
