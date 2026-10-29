package me.sorihiko.dango.music

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.external.ExternalPlayback
import me.sorihiko.dango.music.external.AppleNativeLyricsStatus
import me.sorihiko.dango.music.external.ExternalStatus

fun externalPlaybackStatus(): String {
    val s = ExternalPlayback.snapshot
    return I18n.text(when (s.status) {
        ExternalStatus.UNSUPPORTED -> "Apple Music sync requires Windows 10 1809+ (x64)."
        ExternalStatus.HELPER_MISSING -> "Apple Music bridge missing. Install the Windows workflow's bundled mod jar."
        ExternalStatus.PLAYING, ExternalStatus.PAUSED -> when (s.nativeLyricsStatus) {
            AppleNativeLyricsStatus.NATIVE_WORD_SYNCED ->
                I18n.format("Apple native cache · %d/%d valid · word-synced.", s.nativeValidDocuments, s.nativeCacheFiles)
            AppleNativeLyricsStatus.NATIVE_PARTIAL_WORD_SYNCED ->
                I18n.format("Apple native cache · %d/%d valid · partial word sync.", s.nativeValidDocuments, s.nativeCacheFiles)
            AppleNativeLyricsStatus.NATIVE_LINE_SYNCED ->
                I18n.format("Apple native cache · %d/%d valid · line-synced.", s.nativeValidDocuments, s.nativeCacheFiles)
            AppleNativeLyricsStatus.CACHE_MISSING -> if (s.lyrics == null)
                I18n.format("Apple cache: %d files, %d rejected; no fallback lyrics yet.",
                    s.nativeCacheFiles, s.nativeRejectedDocuments)
            else I18n.format("Apple cache: %d files, %d rejected; using %s fallback.",
                s.nativeCacheFiles, s.nativeRejectedDocuments, s.lyricSource ?: "unknown")
            AppleNativeLyricsStatus.AMBIGUOUS ->
                if (s.lyrics == null) "Apple native lyric match is ambiguous; no fallback lyrics yet."
                else I18n.format("Apple native lyric match is ambiguous; using %s fallback.", s.lyricSource ?: "unknown")
            AppleNativeLyricsStatus.INVALID ->
                if (s.lyrics == null) "Apple native lyric document is invalid; no fallback lyrics yet."
                else I18n.format("Apple native lyric document is invalid; using %s fallback.", s.lyricSource ?: "unknown")
            AppleNativeLyricsStatus.NO_CATALOG_MATCH ->
                if (s.lyrics == null) "No verified Apple catalog match or fallback lyrics."
                else I18n.format("No verified Apple catalog match; using %s fallback.", s.lyricSource ?: "unknown")
            AppleNativeLyricsStatus.UNSUPPORTED ->
                if (s.lyrics == null) "Apple native lyrics require Windows; no fallback lyrics yet."
                else I18n.format("Apple native lyrics unavailable; using %s fallback.", s.lyricSource ?: "unknown")
            else -> if (s.lyrics == null)
                "Apple Music connected; no confident lyric match yet."
            else I18n.format("Apple Music connected; using %s lyrics.", s.lyricSource ?: "unknown")
        }
        ExternalStatus.DISABLED -> "External Player is off."
        else -> "Apple Music unavailable. Check the bridge, app and timeline."
    })
}
