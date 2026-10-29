package me.sorihiko.dango.music.external

import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.LyricWord
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.MusicSourceApi
import me.sorihiko.dango.music.NeteaseTrack
import java.text.Normalizer
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

internal class ExternalMatch(track: NeteaseTrack, lyrics: Lyrics?) {
    private val track = copy(track)
    private val storedLyrics = lyrics?.takeIf { bounded(it.lines) }
    val lyrics: Lyrics? get() = storedLyrics?.let(::copyLyrics)

    private fun copyLyrics(original: Lyrics): Lyrics? {
        val lines = original.lines
        if (!bounded(lines)) return null
        return Lyrics(Collections.unmodifiableList(lines.map { line ->
            LyricLine(line.timeMs, line.text, line.words?.let { words ->
                Collections.unmodifiableList(words.map { LyricWord(it.startMs, it.durMs, it.text) })
            })
        }), original.sourceName)
    }
    private fun bounded(lines: List<LyricLine>) = lines.size <= 10_000 &&
        lines.sumOf { it.text.length.toLong() } <= 1_000_000 &&
        lines.sumOf { it.words?.size?.toLong() ?: 0L } <= 20_000
    fun copyTrack() = copy(track)

    private fun copy(t: NeteaseTrack) = NeteaseTrack(t.id, t.name,
        Collections.unmodifiableList(ArrayList(t.artists)), t.durationMs, t.coverUrl,
        t.source, t.nativeId, t.mediaId, t.albumId)
}

/** Intentionally prefers no lyrics over lyrics from a different recording. */
internal class ExternalMatcher {
    private data class Key(val source: MusicSource, val title: String, val artists: List<String>, val duration: Long)
    private val cache = object : LinkedHashMap<Key, ExternalMatch?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ExternalMatch?>?) = size > 64
    }

    fun resolve(sample: ExternalSample, api: MusicSourceApi, active: () -> Boolean = { true }): ExternalMatch? {
        if (!active()) return null
        val key = Key(api.source, sample.title, sample.artists.toList(), sample.durationMs)
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val result = try {
            if (sample.title.length > 300 || sample.artists.isEmpty() || sample.artists.size > 12 ||
                sample.artists.any { it.isBlank() || it.length > 200 } || sample.durationMs <= 0) null
            else {
                val candidates = api.search("${sample.title} ${sample.artists.joinToString(" ")}".take(512))
                    .take(40).filter { matches(sample, it) }.distinctBy { it.key }
                if (!active()) return null
                if (candidates.size == 1) {
                    val track = candidates.single()
                    ExternalMatch(track, api.lyrics(track))
                } else null
            }
        } catch (_: Exception) { null }
        if (!active()) return null
        synchronized(cache) { cache[key] = result }
        return result
    }

    internal fun matches(sample: ExternalSample, track: NeteaseTrack): Boolean {
        if (normalize(sample.title).isEmpty() || normalize(sample.title) != normalize(track.name)) return false
        val expected = sample.artists.map(::normalize).toSet()
        if (expected.isEmpty() || "" in expected || expected != track.artists.map(::normalize).toSet()) return false
        if (sample.durationMs <= 0 || track.durationMs <= 0) return false
        val tolerance = (sample.durationMs / 100).coerceIn(1_000, 3_000)
        return kotlin.math.abs(sample.durationMs - track.durationMs) <= tolerance
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
