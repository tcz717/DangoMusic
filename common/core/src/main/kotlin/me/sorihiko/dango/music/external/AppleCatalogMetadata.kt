package me.sorihiko.dango.music.external

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal object AppleCatalogMetadata {
    fun matches(
        sample: ExternalSample,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
    ): Boolean {
        if (sample.artists.size != 1 ||
            sample.durationMs !in 1..86_400_000L || durationMs !in 1..86_400_000L ||
            abs(sample.durationMs - durationMs) > 2_000) return false

        val normalizedTitle = normalize(title)
        val normalizedArtist = normalize(artist)
        val displayArtist = sample.artists.single()
        val normalizedDisplayArtist = normalize(displayArtist)
        if (normalizedTitle.isEmpty() || normalize(sample.title) != normalizedTitle ||
            normalizedArtist.isEmpty() || normalizedDisplayArtist.isEmpty()) return false

        val normalizedAlbum = normalize(album)
        if (sample.album.isNotBlank() && normalize(sample.album) != normalizedAlbum) return false
        if (normalizedDisplayArtist == normalizedArtist &&
            displayArtist.count { it == '\u2014' } == artist.count { it == '\u2014' }) return true
        if (sample.album.isNotBlank() || normalizedAlbum.isEmpty()) return false

        // Dashes can belong to either name; accept only one catalog-verified boundary.
        return displayArtist.indices.count { index ->
            displayArtist[index] == '\u2014' &&
                normalize(displayArtist.substring(0, index)) == normalizedArtist &&
                normalize(displayArtist.substring(index + 1)) == normalizedAlbum
        } == 1
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
