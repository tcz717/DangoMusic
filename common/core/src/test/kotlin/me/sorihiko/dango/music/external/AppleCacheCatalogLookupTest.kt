package me.sorihiko.dango.music.external

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder

class AppleCacheCatalogLookupTest {
    private fun sample() = ExternalSample(
        sessionId = "synthetic:cache-catalog",
        title = "Clockwork Kites",
        artists = listOf("Lyra, Harbor & Mica \u2014 Paper Orbits - EP"),
        album = "",
        durationMs = 218_000,
        positionMs = 0,
        updatedAtUnixMs = 1,
        capturedAtUnixMs = 1,
        playbackRate = 1.0,
        status = ExternalStatus.PLAYING,
    )

    private fun track(
        id: Long = 42,
        title: String = "Clockwork Kites",
        artist: String = "Lyra, Harbor & Mica",
        album: String = "Paper Orbits - EP",
        durationMs: Long = 218_000,
    ) = JsonObject().apply {
        addProperty("trackId", id)
        addProperty("trackName", title)
        addProperty("artistName", artist)
        addProperty("collectionName", album)
        addProperty("trackTimeMillis", durationMs)
    }

    private fun response(vararg tracks: JsonElement) = JsonObject().apply {
        addProperty("resultCount", tracks.size)
        add("results", JsonArray().apply { tracks.forEach { add(it) } })
    }.toString()

    private class Fetcher(var reply: (String) -> String?) {
        val urls = mutableListOf<String>()
        fun fetch(url: String): String? {
            urls.add(url)
            return reply(url)
        }
    }

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }

    @Test fun decoratedArtistVerifiesCacheIdUsingOnlyPositiveIdsAndCnLookup() {
        val fetcher = Fetcher { response(track(), track(id = 99, artist = "Quartz Ensemble")) }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch)
        assertEquals(42L, lookup.resolve(sample(), listOf(99, 0, 42, -7, 42)) { true })
        assertEquals(1, fetcher.urls.size)
        val url = fetcher.urls.single()
        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertEquals("itunes.apple.com", uri.host)
        assertEquals("/lookup", uri.path)
        assertNull(uri.userInfo)
        assertNull(uri.fragment)
        assertEquals(mapOf("id" to "42,99", "country" to "cn", "entity" to "song", "limit" to "128"), query(url))
        assertFalse(url.contains("Clockwork"))
        assertFalse(url.contains("search"))
    }

    @Test fun cnMetadataMissFallsThroughToUsAndStopsAtFirstVerifiedCountry() {
        val fetcher = Fetcher { url ->
            if (query(url).getValue("country") == "cn") response(track(title = "Copper Lanterns"))
            else response(track())
        }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch)
        assertEquals(42L, lookup.resolve(sample(), listOf(42)) { true })
        assertEquals(listOf("cn", "us"), fetcher.urls.map { query(it).getValue("country") })
    }

    @Test fun completeMissQueriesEachCountryOnceInOrderWithAllIds() {
        val fetcher = Fetcher { response() }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch)
        assertNull(lookup.resolve(sample(), listOf(43, 42)) { true })
        assertEquals(listOf("cn", "us", "jp", "gb"), fetcher.urls.map { query(it).getValue("country") })
        assertTrue(fetcher.urls.all { query(it).getValue("id") == "42,43" })
    }

    @Test fun unrequestedIdCannotMatchEvenWhenItsMetadataIsCorrect() {
        val fetcher = Fetcher { response(track(id = 99), track(artist = "Quartz Ensemble")) }
        assertNull(AppleCacheCatalogLookup(fetcher::fetch).resolve(sample(), listOf(42)) { true })
        assertEquals(4, fetcher.urls.size)
    }

    @Test fun everyMetadataFieldMustAgreeRatherThanChoosingByDuration() {
        val wrongTracks = listOf(
            track(title = "Clockwork Kites (Live)"),
            track(artist = "Quartz Ensemble"),
            track(album = "Glass Orbits - EP"),
            track(durationMs = 220_001),
            track(durationMs = 0),
        )
        for (wrong in wrongTracks) {
            val fetcher = Fetcher { response(wrong) }
            assertNull(wrong.toString(), AppleCacheCatalogLookup(fetcher::fetch)
                .resolve(sample(), listOf(42)) { true })
            assertEquals(4, fetcher.urls.size)
        }
    }

    @Test fun twoVerifiedIdsAreAmbiguousEvenWhenOneHasAnExactDuration() {
        val fetcher = Fetcher { url ->
            if (query(url).getValue("country") == "cn") response(track(), track(id = 43, durationMs = 219_999))
            else response(track())
        }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch)
        assertNull(lookup.resolve(sample(), listOf(42, 43)) { true })
        assertEquals(1, fetcher.urls.size)
        assertNull(lookup.resolve(sample(), listOf(43, 42)) { true })
        assertEquals(1, fetcher.urls.size)
    }

    @Test fun duplicateRowsForOneVerifiedIdAreNotAmbiguous() {
        val fetcher = Fetcher { response(track(), track(), track(id = 43, title = "Copper Lanterns")) }
        assertEquals(42L, AppleCacheCatalogLookup(fetcher::fetch).resolve(sample(), listOf(42, 43)) { true })
        assertEquals(1, fetcher.urls.size)
    }

    @Test fun aCnMatchDoesNotConsultOtherCountries() {
        val fetcher = Fetcher { url ->
            if (query(url).getValue("country") == "cn") response(track())
            else response(track(), track(id = 43))
        }
        assertEquals(42L, AppleCacheCatalogLookup(fetcher::fetch).resolve(sample(), listOf(42, 43)) { true })
        assertEquals(1, fetcher.urls.size)
    }

    @Test fun all128UniquePositiveCandidatesFitOneRequest() {
        val ids = (1L..128L).reversed().toList() + listOf(0L, -1L, 42L)
        val fetcher = Fetcher { response(track(id = 128)) }
        assertEquals(128L, AppleCacheCatalogLookup(fetcher::fetch).resolve(sample(), ids) { true })
        assertEquals(1, fetcher.urls.size)
        assertEquals((1L..128L).toList(), query(fetcher.urls.single()).getValue("id").split(',').map { it.toLong() })
    }

    @Test fun moreThan128CandidatesAreRejectedWithoutTruncationOrIo() {
        val fetcher = Fetcher { response(track(id = 129)) }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch)
        assertNull(lookup.resolve(sample(), (1L..129L).toList()) { true })
        assertTrue(fetcher.urls.isEmpty())
        assertEquals(129L, lookup.resolve(sample(), listOf(129)) { true })
    }

    @Test fun noPositiveCandidatesMeansNoIo() {
        val fetcher = Fetcher { error("No request expected") }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch)
        for (ids in listOf(emptyList(), listOf(0L, -1L, Long.MIN_VALUE))) {
            assertNull(lookup.resolve(sample(), ids) { true })
        }
        assertTrue(fetcher.urls.isEmpty())
    }

    @Test fun cancellationBeforeLookupPreventsIoAndOverridesCachedSuccess() {
        val fetcher = Fetcher { response(track()) }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch)
        assertNull(lookup.resolve(sample(), listOf(42)) { false })
        assertTrue(fetcher.urls.isEmpty())
        assertEquals(42L, lookup.resolve(sample(), listOf(42)) { true })
        assertNull(lookup.resolve(sample(), listOf(42)) { false })
        assertEquals(1, fetcher.urls.size)
        assertEquals(42L, lookup.resolve(sample(), listOf(42)) { true })
        assertEquals(1, fetcher.urls.size)
    }

    @Test fun cancellationDuringFetchStopsFutureIoAndCachesNeitherSuccessNorMiss() {
        for (body in listOf(response(track()), response(), null)) {
            var active = true
            val fetcher = Fetcher { active = false; body }
            val lookup = AppleCacheCatalogLookup(fetcher::fetch)
            assertNull(lookup.resolve(sample(), listOf(42)) { active })
            assertEquals(1, fetcher.urls.size)
            assertNull(lookup.resolve(sample(), listOf(42)) { active })
            assertEquals(1, fetcher.urls.size)
            active = true
            fetcher.reply = { response(track()) }
            assertEquals(42L, lookup.resolve(sample(), listOf(42)) { active })
            assertEquals(2, fetcher.urls.size)
        }
    }

    @Test fun cancellationImmediatelyBeforeCacheWriteDoesNotCache() {
        var active = true
        var cancelAtClockRead = true
        val fetcher = Fetcher { response(track()) }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch, nanoTime = {
            if (cancelAtClockRead) active = false
            0L
        })
        assertNull(lookup.resolve(sample(), listOf(42)) { active })
        assertEquals(1, fetcher.urls.size)
        active = true
        cancelAtClockRead = false
        assertEquals(42L, lookup.resolve(sample(), listOf(42)) { active })
        assertEquals(2, fetcher.urls.size)
    }

    @Test fun successExpiresAtTenMinutesAndCacheHitsDoNotRefreshTtl() {
        var now = -1_000_000L
        val fetcher = Fetcher { response(track()) }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch, nanoTime = { now })
        assertEquals(42L, lookup.resolve(sample(), listOf(43, 42)) { true })
        now += 600_000_000_000L - 1
        assertEquals(42L, lookup.resolve(sample(), listOf(42, 43, 42, 0)) { true })
        assertEquals(1, fetcher.urls.size)
        now++
        assertEquals(42L, lookup.resolve(sample(), listOf(42, 43)) { true })
        assertEquals(2, fetcher.urls.size)
    }

    @Test fun negativeExpiresAtFifteenSecondsAndCacheHitsDoNotRefreshTtl() {
        var now = 0L
        val fetcher = Fetcher { response() }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch, nanoTime = { now })
        assertNull(lookup.resolve(sample(), listOf(42)) { true })
        assertEquals(4, fetcher.urls.size)
        now = 15_000_000_000L - 1
        fetcher.reply = { response(track()) }
        assertNull(lookup.resolve(sample(), listOf(42)) { true })
        assertEquals(4, fetcher.urls.size)
        now++
        assertEquals(42L, lookup.resolve(sample(), listOf(42)) { true })
        assertEquals(5, fetcher.urls.size)
    }

    @Test fun ttlStartsWhenLookupFinishesRatherThanBeforeFetch() {
        var now = 0L
        val fetcher = Fetcher { now += 7_000_000_000L; response(track()) }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch, nanoTime = { now })
        assertEquals(42L, lookup.resolve(sample(), listOf(42)) { true })
        now += 600_000_000_000L - 1
        assertEquals(42L, lookup.resolve(sample(), listOf(42)) { true })
        assertEquals(1, fetcher.urls.size)
    }

    @Test fun cacheKeyUsesSampleIdentityAndSortedCandidatesButNotPlaybackUpdates() {
        val fetcher = Fetcher { response() }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch)
        val original = sample()
        assertNull(lookup.resolve(original, listOf(43, 42)) { true })
        assertNull(lookup.resolve(original.copy(
            positionMs = 30_000,
            updatedAtUnixMs = 3,
            capturedAtUnixMs = 4,
            playbackRate = 0.0,
            status = ExternalStatus.PAUSED,
        ), listOf(42, 43, 43, -1)) { true })
        assertEquals(4, fetcher.urls.size)
        val changedSamples = listOf(
            original.copy(sessionId = "synthetic:another-session"),
            original.copy(title = "Copper Lanterns"),
            original.copy(artists = listOf("Quartz Ensemble")),
            original.copy(album = "Paper Orbits - EP"),
            original.copy(durationMs = 218_001),
        )
        for ((index, changed) in changedSamples.withIndex()) {
            assertNull(lookup.resolve(changed, listOf(42, 43)) { true })
            assertEquals(4 * (index + 2), fetcher.urls.size)
        }
        assertNull(lookup.resolve(original, listOf(42, 44)) { true })
        assertEquals(4 * (changedSamples.size + 2), fetcher.urls.size)
    }

    @Test fun cacheRetainsAtMost96EntriesAndEvictsLeastRecentlyUsed() {
        val fetcher = Fetcher { response(track()) }
        val lookup = AppleCacheCatalogLookup(fetcher::fetch, nanoTime = { 0L })
        fun resolve(index: Int) = lookup.resolve(sample().copy(sessionId = "synthetic:$index"), listOf(42)) { true }
        for (index in 1..96) assertEquals(42L, resolve(index))
        assertEquals(96, fetcher.urls.size)
        assertEquals(42L, resolve(1))
        assertEquals(96, fetcher.urls.size)
        assertEquals(42L, resolve(97))
        assertEquals(97, fetcher.urls.size)
        assertEquals(42L, resolve(1))
        assertEquals(97, fetcher.urls.size)
        assertEquals(42L, resolve(2))
        assertEquals(98, fetcher.urls.size)
        assertEquals(42L, resolve(96))
        assertEquals(98, fetcher.urls.size)
    }

    @Test fun malformedResponsesAndFetchFailuresFallThroughWithoutThrowing() {
        val invalidBodies = listOf(
            null, "", "not json", "{", "null", "[]", "{}",
            """{"results":null}""", """{"results":{}}""",
            response(JsonNull.INSTANCE, JsonPrimitive(3), JsonObject()),
            " ".repeat(512 * 1024 + 1),
        )
        for (body in invalidBodies) {
            val fetcher = Fetcher { url ->
                if (query(url).getValue("country") == "cn") body else response(track())
            }
            assertEquals(42L, AppleCacheCatalogLookup(fetcher::fetch).resolve(sample(), listOf(42)) { true })
            assertEquals(listOf("cn", "us"), fetcher.urls.map { query(it).getValue("country") })
        }
        for (failure in listOf(IOException("synthetic transport failure"), IllegalStateException("synthetic failure"))) {
            val fetcher = Fetcher { url ->
                if (query(url).getValue("country") == "cn") throw failure else response(track())
            }
            assertEquals(42L, AppleCacheCatalogLookup(fetcher::fetch).resolve(sample(), listOf(42)) { true })
            assertEquals(2, fetcher.urls.size)
        }
    }

    @Test fun malformedTrackFieldsAreNotCoercedIntoMatchingMetadata() {
        val invalidTracks = listOf(
            track().apply { remove("trackId") },
            track().apply { addProperty("trackId", "42") },
            track().apply { addProperty("trackId", 42.75) },
            track().apply { addProperty("trackId", BigDecimal("18446744073709551658")) },
            track().apply { addProperty("trackId", true) },
            track().apply { remove("trackName") },
            track().apply { add("artistName", JsonArray()) },
            track().apply { add("collectionName", JsonNull.INSTANCE) },
            track().apply { remove("trackTimeMillis") },
            track().apply { addProperty("trackTimeMillis", "218000") },
            track().apply { addProperty("trackTimeMillis", 218000.5) },
            track().apply { addProperty("trackTimeMillis", BigDecimal("18446744073709769616")) },
        )
        for (invalid in invalidTracks) {
            val fetcher = Fetcher { response(invalid) }
            assertNull(AppleCacheCatalogLookup(fetcher::fetch).resolve(sample(), listOf(42)) { true })
            assertEquals(4, fetcher.urls.size)
        }
    }
}
