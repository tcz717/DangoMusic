package me.sorihiko.dango.music.external

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleCatalogMetadataTest {
    private fun sample(
        title: String = "Clockwork Kites",
        artists: List<String> = listOf("Lyra, Harbor & Mica"),
        album: String = "",
        durationMs: Long = 218_000,
    ) = ExternalSample(
        sessionId = "synthetic:catalog",
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
        positionMs = 0,
        updatedAtUnixMs = 1,
        capturedAtUnixMs = 1,
        playbackRate = 1.0,
        status = ExternalStatus.PLAYING,
    )

    private fun matches(
        sample: ExternalSample = sample(),
        title: String = "Clockwork Kites",
        artist: String = "Lyra, Harbor & Mica",
        album: String = "Paper Orbits - EP",
        durationMs: Long = 218_000,
    ) = AppleCatalogMetadata.matches(sample, title, artist, album, durationMs)

    @Test fun decoratedMultiArtistMatchesNormalizedArtistAndAlbumTogether() {
        val observed = sample(
            title = "\uFF23LOCKWORK\u00A0Kites!",
            artists = listOf("\uFF2Cyra, HARBOR \uFF06 Mica \u2014 PAPER Orbits \uFF0D ep"),
        )
        assertTrue(matches(observed))
        assertTrue(matches(
            sample(artists = listOf("Lyra, Harbor & Mica \u2014 Paper Orbits - EP")),
            title = "\uFF23LOCKWORK\u00A0Kites!",
            artist = "\uFF2Cyra, HARBOR \uFF06 Mica",
            album = "\uFF30APER Orbits \uFF0D ep",
        ))
    }

    @Test fun combinedArtistIsNeverSplitAtCommasOrAmpersands() {
        val observed = sample(artists = listOf("Lyra, Harbor & Mica \u2014 Paper Orbits - EP"))
        for (artist in listOf("Lyra", "Harbor", "Mica", "Harbor & Mica", "Lyra, Harbor")) {
            assertFalse(artist, matches(observed, artist = artist))
            assertFalse(artist, matches(sample(), artist = artist))
        }
        assertTrue(matches(sample()))
    }

    @Test fun decoratedArtistRejectsWrongMissingOrVersionedAlbums() {
        val observed = sample(artists = listOf("Lyra, Harbor & Mica \u2014 Paper Orbits - EP"))
        for (album in listOf("Glass Orbits - EP", "Paper Orbits", "Paper Orbits - Live", "", " ", "---")) {
            assertFalse(album, matches(observed, album = album))
        }
        assertFalse(matches(sample(artists = listOf("Lyra, Harbor & Mica \u2014 Glass Orbits - EP"))))
    }

    @Test fun explicitAlbumMustAgreeAndDisablesDisplayFallback() {
        assertTrue(matches(sample(album = " PAPER ORBITS (ep)! ")))
        assertTrue(matches(sample(album = "Paper Orbits - EP"), album = "\uFF30APER Orbits (ep)!"))
        assertFalse(matches(sample(album = "Glass Orbits - EP")))
        assertFalse(matches(sample(album = "Paper Orbits - EP"), album = ""))
        assertTrue(matches(sample(album = ""), album = ""))

        val decorated = sample(artists = listOf("Lyra, Harbor & Mica \u2014 Paper Orbits - EP"))
        assertFalse(matches(decorated.copy(album = "Glass Orbits - EP")))
        assertFalse(matches(decorated.copy(album = "Paper Orbits - EP")))
        assertFalse(matches(decorated.copy(album = "---")))
        assertTrue(matches(decorated.copy(album = " \t")))
    }

    @Test fun literalArtistWithEmdashesIsAcceptedBeforeDisplayParsing() {
        for (artist in listOf("Lyra \u2014 Harbor", "Lyra \u2014 \u2014 Harbor")) {
            val observed = sample(artists = listOf(artist))
            assertTrue(matches(observed, artist = artist))
            assertTrue(matches(observed, artist = artist, album = ""))
            assertFalse(matches(observed.copy(album = "Glass Orbits - EP"), artist = artist))
        }
    }

    @Test fun multipleEmdashesAreAllowedOnlyWithOneVerifiedBoundary() {
        assertTrue(matches(
            sample(artists = listOf("Lyra \u2014 Harbor \u2014 Paper Orbits - EP")),
            artist = "Lyra \u2014 Harbor",
        ))
        assertTrue(matches(
            sample(artists = listOf("Lyra, Harbor & Mica \u2014 Paper \u2014 Orbits - EP")),
            album = "Paper \u2014 Orbits - EP",
        ))
        assertTrue(matches(
            sample(artists = listOf("Lyra \u2014 Harbor \u2014 Paper \u2014 Orbits - EP")),
            artist = "Lyra \u2014 Harbor",
            album = "Paper \u2014 Orbits - EP",
        ))
    }

    @Test fun malformedOrAmbiguousDisplaySeparatorsAreRejected() {
        val malformed = listOf(
            "Lyra, Harbor & Mica \u2014 \u2014 Paper Orbits - EP",
            "Lyra, Harbor & Mica \u2014 - \u2014 Paper Orbits - EP",
            "Lyra, Harbor & Mica \u2014",
            "Lyra, Harbor & Mica \u2014 ---",
            "\u2014 Paper Orbits - EP",
            "Lyra, Harbor & Mica - Paper Orbits - EP",
            "Lyra, Harbor & Mica \u2013 Paper Orbits - EP",
            "Lyra, Harbor & Mica \u2014 Glass \u2014 Paper Orbits - EP",
        )
        for (artist in malformed) {
            assertFalse(artist, matches(sample(artists = listOf(artist))))
        }
    }

    @Test fun titleMustMatchExactlyAfterNormalization() {
        assertTrue(matches(sample(title = "\uFF23LOCKWORK\u00A0Kites!")))
        for (title in listOf("Clockwork Kites (Live)", "Clockwork Kites II", "Kites")) {
            assertFalse(title, matches(sample(title = title)))
            assertFalse(title, matches(title = title))
        }
    }

    @Test fun matchingTitleOrDurationAloneCannotMatch() {
        assertFalse(matches(sample(artists = listOf("Quartz Ensemble"))))
        assertFalse(matches(sample(title = "Copper Lanterns")))
        assertFalse(matches(sample(title = "Copper Lanterns", artists = listOf("Quartz Ensemble"))))
        assertFalse(matches(sample(artists = listOf("Lyra, Harbor & Mica \u2014 Paper Orbits - EP")),
            artist = "Quartz Ensemble"))
    }

    @Test fun blankOrPunctuationOnlyTitlesAndArtistsAreRejected() {
        for (blank in listOf("", " \t\r\n", "... \u2014 &")) {
            assertFalse(matches(sample(title = blank)))
            assertFalse(matches(title = blank))
            assertFalse(matches(sample(title = blank), title = blank))
            assertFalse(matches(sample(artists = listOf(blank))))
            assertFalse(matches(artist = blank))
            assertFalse(matches(sample(artists = listOf(blank)), artist = blank))
        }
    }

    @Test fun sampleMustContainExactlyOneArtist() {
        for (artists in listOf(
            emptyList(),
            listOf("Lyra, Harbor & Mica", "Lyra, Harbor & Mica"),
            listOf("Lyra", "Harbor", "Mica"),
            listOf("Lyra, Harbor & Mica", ""),
        )) {
            assertFalse(artists.toString(), matches(sample(artists = artists)))
        }
    }

    @Test fun durationBoundsAreInclusiveForBothSources() {
        for (duration in listOf(1L, 86_400_000L)) {
            assertTrue(matches(sample(durationMs = duration), durationMs = duration))
        }
        for (duration in listOf(Long.MIN_VALUE, -1L, 0L, 86_400_001L, Long.MAX_VALUE)) {
            assertFalse(duration.toString(), matches(sample(durationMs = duration)))
            assertFalse(duration.toString(), matches(durationMs = duration))
            assertFalse(duration.toString(), matches(sample(durationMs = duration), durationMs = duration))
        }
    }

    @Test fun durationToleranceIsExactlyTwoSecondsInEitherDirection() {
        for (delta in listOf(-2_000L, -1_999L, 0L, 1_999L, 2_000L)) {
            assertTrue(delta.toString(), matches(durationMs = 218_000 + delta))
            assertTrue(delta.toString(), matches(sample(durationMs = 218_000 + delta)))
        }
        for (delta in listOf(-2_001L, 2_001L)) {
            assertFalse(delta.toString(), matches(durationMs = 218_000 + delta))
            assertFalse(delta.toString(), matches(sample(durationMs = 218_000 + delta)))
        }
        assertTrue(matches(sample(durationMs = 1), durationMs = 2_001))
        assertFalse(matches(sample(durationMs = 1), durationMs = 2_002))
        assertTrue(matches(sample(durationMs = 86_400_000), durationMs = 86_398_000))
        assertFalse(matches(sample(durationMs = 86_400_000), durationMs = 86_397_999))
    }
}
