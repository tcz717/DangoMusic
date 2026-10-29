package me.sorihiko.dango.music

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.external.ExternalPlayback
import me.sorihiko.dango.music.external.ExternalStatus

fun externalPlaybackStatus(): String {
    val s = ExternalPlayback.snapshot
    return I18n.text(when (s.status) {
        ExternalStatus.UNSUPPORTED -> "Apple Music sync requires Windows 10 1809+ (x64)."
        ExternalStatus.HELPER_MISSING -> "Apple Music bridge missing. Install the Windows workflow's bundled mod jar."
        ExternalStatus.PLAYING, ExternalStatus.PAUSED -> if (s.lyrics == null)
            "Apple Music connected; no confident lyric match yet."
        else "Apple Music connected; lyrics synchronized."
        ExternalStatus.DISABLED -> "External Player is off."
        else -> "Apple Music unavailable. Check the bridge, app and timeline."
    })
}
