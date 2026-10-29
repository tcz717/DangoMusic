package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.external.ExternalPlayback
import me.sorihiko.dango.music.external.ExternalStatus

/** Display-only metadata; an external catalog match is never sent to the audio player. */
data class PlaybackView(
    val key: String,
    val title: String,
    val artists: List<String>,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val track: NeteaseTrack?,
    val external: Boolean,
    val lyrics: Lyrics? = null,
) {
    val artist: String get() = artists.joinToString(" / ")

    companion object {
        fun snapshot(): PlaybackView? {
            if (ExternalPlayback.enabled) {
                val s = ExternalPlayback.snapshot
                if (!s.active) return null
                return PlaybackView(s.key, s.title, s.artists, s.status == ExternalStatus.PLAYING,
                    s.positionMs, s.durationMs, s.track, true, s.lyrics)
            }
            val s = MusicPlayer.snapshot() ?: return null
            return PlaybackView(s.track.key, s.track.name, s.track.artists,
                s.state == MusicPlayer.State.PLAYING, s.positionMs, s.track.durationMs, s.track, false)
        }
    }
}
