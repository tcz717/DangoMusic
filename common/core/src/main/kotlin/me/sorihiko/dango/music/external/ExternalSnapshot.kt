package me.sorihiko.dango.music.external

import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.NeteaseTrack
import java.util.Collections

enum class ExternalStatus { DISABLED, UNSUPPORTED, HELPER_MISSING, DISCONNECTED, PLAYING, PAUSED, STOPPED }

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

/** Positive offsets show lyrics earlier. Unknown durations have no upper clamp; arithmetic saturates. */
fun lyricPosition(positionMs: Long, durationMs: Long, offsetMs: Int): Long {
    val offset = offsetMs.toLong()
    val adjusted = when {
        offset > 0 && positionMs > Long.MAX_VALUE - offset -> Long.MAX_VALUE
        offset < 0 && positionMs < Long.MIN_VALUE - offset -> Long.MIN_VALUE
        else -> positionMs + offset
    }
    return adjusted.coerceIn(0L, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
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

/** Playing anchors expire after 30 seconds; paused anchors may be old but captures must stay fresh. */
internal class ExternalTimeline(private val clock: ExternalClock) {
    private var sample: ExternalSample? = null
    private var receivedNs = 0L
    private var anchorPosition = 0L
    private var anchorAgeMs = 0L

    fun accept(value: ExternalSample?): Boolean {
        if (value == null || value.sessionId.isBlank() || value.title.isBlank() ||
            value.durationMs !in 0..86_400_000L ||
            value.capturedAtUnixMs <= 0 ||
            clock.unixMs() - value.capturedAtUnixMs !in 0..5_000L ||
            value.status !in listOf(ExternalStatus.PLAYING, ExternalStatus.PAUSED, ExternalStatus.STOPPED)) {
            sample = null
            return false
        }
        val age = value.capturedAtUnixMs - value.updatedAtUnixMs
        val transportDelay = (clock.unixMs() - value.capturedAtUnixMs).coerceIn(0, 5_000)
        anchorAgeMs = age + transportDelay
        val running = value.status == ExternalStatus.PLAYING
        if (value.updatedAtUnixMs <= 0 || age < 0 ||
            (running && (anchorAgeMs > 30_000 || !value.playbackRate.isFinite() || value.playbackRate !in 0.0..8.0))) {
            sample = null
            return false
        }
        val rate = if (running) value.playbackRate else 0.0
        anchorPosition = (value.positionMs.toDouble() +
            if (running) (age + transportDelay) * rate else 0.0)
            .coerceIn(0.0, value.durationMs.toDouble()).toLong()
        sample = value.copy(artists = Collections.unmodifiableList(ArrayList(value.artists)),
            playbackRate = rate)
        receivedNs = clock.nanoTime()
        return true
    }

    fun snapshot(match: ExternalMatch?): ExternalSnapshot {
        val s = sample ?: return ExternalSnapshot(ExternalStatus.DISCONNECTED)
        val elapsed = ((clock.nanoTime() - receivedNs).coerceAtLeast(0) / 1_000_000L)
        if (elapsed > 5_000 || (s.status == ExternalStatus.PLAYING && anchorAgeMs + elapsed > 30_000))
            return ExternalSnapshot(ExternalStatus.DISCONNECTED)
        val position = (anchorPosition + elapsed * s.playbackRate).coerceIn(0.0, s.durationMs.toDouble()).toLong()
        return ExternalSnapshot(s.status, s.sessionId, s.title, s.artists, s.album, s.durationMs, position, match)
    }
}
