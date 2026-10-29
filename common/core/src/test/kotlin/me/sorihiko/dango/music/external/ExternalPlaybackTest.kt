package me.sorihiko.dango.music.external

import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.LyricSplit
import me.sorihiko.dango.lyrics.LyricWord
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.MusicSourceApi
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.NeteaseTrack
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExternalPlaybackTest {
    private class Clock : ExternalClock {
        var wall = 1_800_000_000_000L
        var nano = 0L
        override fun nanoTime() = nano
        override fun unixMs() = wall
        fun advance(ms: Long) { wall += ms; nano += ms * 1_000_000 }
    }

    private class Tasks : Executor {
        val queue = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { queue += command }
        fun run(index: Int = 0) { queue.removeAt(index).run() }
    }

    private class Source : MusicSourceApi {
        override val source = MusicSource.NETEASE
        var candidates = emptyList<NeteaseTrack>()
        var searches = 0
        var lyricRequests = 0
        var suppliedLyrics: Lyrics? = null
        override fun search(query: String): List<NeteaseTrack> { searches++; return candidates }
        override fun lyrics(track: NeteaseTrack): Lyrics {
            lyricRequests++
            return suppliedLyrics ?: Lyrics(listOf(LyricLine(0, track.name)), "fake")
        }
        override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = error("Must never request audio")
    }

    private fun sample(clock: Clock, title: String = "Song", session: String = "apple:1",
                       position: Long = 10_000, status: ExternalStatus = ExternalStatus.PLAYING,
                       rate: Double = 1.0, age: Long = 0) =
        ExternalSample(session, title, listOf("Artist"), "Album", 200_000, position,
            clock.wall - age, clock.wall, rate, status)
    private fun track(name: String = "Song", id: Long = 1, duration: Long = 200_000,
                      artists: List<String> = listOf("Artist")) =
        NeteaseTrack(id, name, artists, duration, "cover")

    @Test fun anchorsRateMonotonicAndClamping() {
        val clock = Clock()
        val timeline = ExternalTimeline(clock)
        assertTrue(timeline.accept(sample(clock, age = 2_000, rate = 1.5)))
        assertEquals(13_000L, timeline.snapshot(null).positionMs)
        clock.advance(1_000)
        clock.wall -= 90_000 // wall-clock corrections do not change extrapolation
        assertEquals(14_500L, timeline.snapshot(null).positionMs)
        clock.wall += 90_000
        timeline.accept(sample(clock, position = 199_000, rate = 2.0))
        clock.advance(2_000)
        assertEquals(200_000L, timeline.snapshot(null).positionMs)
        timeline.accept(sample(clock, position = -100, status = ExternalStatus.PAUSED))
        assertEquals(0L, timeline.snapshot(null).positionMs)
    }

    @Test fun pauseResumeAndSeekBothDirections() {
        val clock = Clock()
        val timeline = ExternalTimeline(clock)
        timeline.accept(sample(clock))
        clock.advance(1_000)
        assertEquals(11_000L, timeline.snapshot(null).positionMs)
        timeline.accept(sample(clock, position = 11_000, status = ExternalStatus.PAUSED, age = 80_000))
        clock.advance(2_000)
        assertEquals(11_000L, timeline.snapshot(null).positionMs)
        timeline.accept(sample(clock, position = 70_000))
        assertEquals(70_000L, timeline.snapshot(null).positionMs)
        timeline.accept(sample(clock, position = 3_000))
        clock.advance(500)
        assertEquals(3_500L, timeline.snapshot(null).positionMs)
    }

    @Test fun invalidAnchorsClearMetadataAndStaleFeedDisconnects() {
        val clock = Clock()
        val timeline = ExternalTimeline(clock)
        timeline.accept(sample(clock).copy(updatedAtUnixMs = 0))
        clock.advance(1_000)
        assertEquals(ExternalStatus.DISCONNECTED, timeline.snapshot(null).status)
        assertEquals("", timeline.snapshot(null).title)
        timeline.accept(sample(clock, age = -500))
        clock.advance(1_000)
        assertEquals(ExternalStatus.DISCONNECTED, timeline.snapshot(null).status)
        timeline.accept(sample(clock, rate = Double.NaN))
        clock.advance(1_000)
        assertEquals(ExternalStatus.DISCONNECTED, timeline.snapshot(null).status)
        clock.advance(5_001)
        assertEquals(ExternalStatus.DISCONNECTED, timeline.snapshot(null).status)
        assertNull(timeline.snapshot(null).track)
        timeline.accept(sample(clock))
        assertEquals(ExternalStatus.PLAYING, timeline.snapshot(null).status)
        assertFalse(timeline.accept(sample(clock).copy(capturedAtUnixMs = clock.wall - 20_000)))
        assertEquals(ExternalStatus.DISCONNECTED, timeline.snapshot(null).status)
    }

    @Test fun rejectsWrongVersionsArtistsDurationUnknownAndAmbiguity() {
        val clock = Clock()
        val matcher = ExternalMatcher()
        val s = sample(clock)
        assertTrue(matcher.matches(s, track(name = "Ｓｏｎｇ")))
        assertFalse(matcher.matches(s, track(name = "Song (Live)")))
        assertFalse(matcher.matches(s, track(artists = listOf("Other"))))
        assertFalse(matcher.matches(s, track(duration = 220_000)))
        assertFalse(matcher.matches(s, track(duration = 0)))
        val api = Source()
        api.candidates = listOf(track(), track(id = 2))
        assertNull(matcher.resolve(s, api))
        assertEquals(0, api.lyricRequests)
        api.candidates = listOf(track())
        assertNull(matcher.resolve(s, api)) // cached rejection
        assertEquals(1, api.searches)
    }

    @Test fun scansOnlyBoundedCandidatesAndCacheIsBounded() {
        val clock = Clock()
        val matcher = ExternalMatcher()
        val api = Source()
        api.candidates = (1..40).map { track("Wrong", it.toLong()) } + track()
        assertNull(matcher.resolve(sample(clock), api))
        api.candidates = emptyList()
        repeat(70) { matcher.resolve(sample(clock, title = "Other $it"), api) }
        matcher.resolve(sample(clock), api)
        assertEquals(72, api.searches)
    }

    @Test fun lateMatchesCannotCrossTracksSessionsDisconnectsOrStops() {
        val clock = Clock()
        val tasks = Tasks()
        val api = Source()
        val engine = ExternalEngine(clock, tasks, { api })
        api.candidates = listOf(track(), track("New", 2))
        engine.accept(sample(clock))
        engine.accept(sample(clock, title = "New"))
        tasks.run(1)
        assertEquals("New", engine.snapshot().track?.name)
        tasks.run()
        assertEquals("New", engine.snapshot().track?.name)
        engine.accept(sample(clock, session = "apple:2"))
        assertNull(engine.snapshot().track)
        assertEquals("apple:2", engine.snapshot().sessionId)
        engine.accept(null)
        tasks.run()
        assertEquals(ExternalStatus.DISCONNECTED, engine.snapshot().status)
        engine.accept(sample(clock))
        engine.reset(ExternalStatus.DISABLED)
        tasks.run()
        assertNull(engine.snapshot().track)
        assertEquals(ExternalStatus.DISABLED, engine.snapshot().status)
        engine.accept(sample(clock))
        tasks.run()
        assertEquals("Song", engine.snapshot().track?.name)
    }

    @Test fun metadataPublishesBeforeMatchingAndSnapshotsDoNotChange() {
        val clock = Clock()
        val tasks = Tasks()
        val api = Source().apply { candidates = listOf(track()) }
        val engine = ExternalEngine(clock, tasks, { api })
        engine.accept(sample(clock))
        val before = engine.snapshot()
        assertEquals("Song", before.title)
        assertNull(before.track)
        tasks.run()
        val after = engine.snapshot()
        assertEquals("Song", after.track?.name)
        assertNotNull(after.lyrics)
        after.track!!.coverUrl = "changed"
        assertEquals("cover", after.track!!.coverUrl)
        clock.advance(1_000)
        assertEquals(10_000L, before.positionMs)
        assertEquals(11_000L, engine.snapshot().positionMs)
        assertEquals(0f, after.energy)
        try {
            (after.artists as MutableList).add("changed")
            fail("artists must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun sourceChangesInvalidatePendingMatches() {
        val clock = Clock()
        val tasks = Tasks()
        val first = Source().apply { candidates = listOf(track()) }
        val second = object : MusicSourceApi {
            override val source = MusicSource.QQ
            override fun search(query: String) = emptyList<NeteaseTrack>()
            override fun lyrics(track: NeteaseTrack): Lyrics? = null
            override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = error("audio")
        }
        var selected: MusicSourceApi = first
        val engine = ExternalEngine(clock, tasks, { selected })
        engine.accept(sample(clock))
        selected = second
        engine.accept(sample(clock))
        tasks.run()
        assertNull(engine.snapshot().track)
        tasks.run()
        assertNull(engine.snapshot().track)
    }

    @Test fun parsesOnlyValidProtocolAndUnsupportedPlatformIsLazy() {
        assertNull(WindowsSessionProvider.parse("""{"available":false}"""))
        assertNull(WindowsSessionProvider.parse("not json"))
        assertNull(WindowsSessionProvider.parse("""{"available":true,"state":"buffering"}"""))
        val json = """{"available":true,"sessionId":"apple:1","title":"Song","artists":["Artist"],
            "album":"Album","durationMs":200000,"positionMs":10000,"updatedAtUnixMs":1800000000000,
            "capturedAtUnixMs":1800000000000,"playbackRate":1,"state":"playing"}"""
        assertEquals("Song", WindowsSessionProvider.parse(json)?.title)
        val oldOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Linux")
            ExternalPlayback.enabled = true
            assertEquals(ExternalStatus.UNSUPPORTED, ExternalPlayback.snapshot.status)
            ExternalPlayback.enabled = false
            assertEquals(ExternalStatus.DISABLED, ExternalPlayback.snapshot.status)
        } finally {
            System.setProperty("os.name", oldOs)
            ExternalPlayback.enabled = false
        }
    }

    private class Child(output: String, private val finishes: Boolean = true) : Process() {
        private val input = ByteArrayInputStream(output.toByteArray(Charsets.UTF_8))
        var destroyed = false
        var timeoutMs = 0L
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timeoutMs = unit.toMillis(timeout)
            return finishes
        }
        override fun exitValue() = 0
        override fun isAlive() = !finishes && !destroyed
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }

    @Test fun helperTimeoutOutputBoundAndShutdown() {
        val timeout = Child("""{"available":false}""", finishes = false)
        val timed = WindowsSessionProvider { timeout }
        assertNull(timed.poll())
        assertTrue(timeout.destroyed)
        assertEquals(2_000L, timeout.timeoutMs)
        val oversized = Child("x".repeat(70_000))
        assertNull(WindowsSessionProvider { oversized }.poll())
        assertTrue(oversized.destroyed)
        var launches = 0
        val closed = WindowsSessionProvider { launches++; Child("""{"available":false}""") }
        closed.close()
        assertNull(closed.poll())
        assertEquals(0, launches)
    }

    @Test fun returnedLyricsCannotMutateCacheAndDuplicateIdsAreNotAmbiguous() {
        val clock = Clock()
        val source = Source().apply { candidates = listOf(track(), track()) }
        val result = ExternalMatcher().resolve(sample(clock), source)!!
        assertEquals(1, source.lyricRequests)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.lyrics!!.lines as MutableList).clear()
        }
        assertEquals(1, result.lyrics!!.lines.size)
    }

    @Test fun shutdownDestroysAnActiveHelperWithoutWaitingForTimeout() {
        val started = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val child = object : Process() {
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun waitFor() = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                started.countDown()
                destroyed.await(1, TimeUnit.SECONDS)
                return false
            }
            override fun exitValue() = 1
            override fun isAlive() = destroyed.count != 0L
            override fun destroy() { destroyed.countDown() }
            override fun destroyForcibly(): Process { destroy(); return this }
        }
        val provider = WindowsSessionProvider { child }
        val worker = Thread { assertNull(provider.poll()) }
        worker.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        provider.close()
        worker.join(1_000)
        assertFalse(worker.isAlive)
        assertEquals(0L, destroyed.count)
    }

    @Test fun lyricOffsetsClampForBothClockDirectionsAndOverflow() {
        assertEquals(12_000L, lyricPosition(10_000, 20_000, 2_000))
        assertEquals(8_000L, lyricPosition(10_000, 20_000, -2_000))
        assertEquals(0L, lyricPosition(1_000, 20_000, -2_000))
        assertEquals(20_000L, lyricPosition(19_000, 20_000, 2_000))
        assertEquals(1_000L, lyricPosition(1_000, 0, 0))
        assertEquals(1_000L, lyricPosition(1_000, -1, 0))
        assertEquals(20_000L, lyricPosition(Long.MAX_VALUE, 20_000, 1))
        assertEquals(0L, lyricPosition(Long.MIN_VALUE, 20_000, -1))
        assertEquals(12_000L, ExternalPlayback.lyricPosition(10_000, 20_000, 2_000))
    }

    @Test fun unknownDurationPreservesAdvancingPositionAndOffset() {
        assertEquals(12_000L, lyricPosition(10_000, 0, 2_000))
        assertEquals(13_000L, lyricPosition(11_000, 0, 2_000))
        assertEquals(8_000L, lyricPosition(10_000, 0, -2_000))
        assertEquals(0L, lyricPosition(1_000, 0, -2_000))
        assertEquals(Long.MAX_VALUE, lyricPosition(Long.MAX_VALUE, 0, 2_000))
        assertEquals(0L, lyricPosition(Long.MIN_VALUE, 0, -2_000))
    }

    @Test fun snapshotKeyTracksSessionAndSongButNotPlaybackPosition() {
        val clock = Clock()
        val timeline = ExternalTimeline(clock)
        timeline.accept(sample(clock))
        val first = timeline.snapshot(null)
        clock.advance(100)
        assertEquals(first.key, timeline.snapshot(null).key)
        timeline.accept(sample(clock, status = ExternalStatus.PAUSED, position = 90_000))
        assertEquals(first.key, timeline.snapshot(null).key)
        timeline.accept(sample(clock, session = "apple:2"))
        assertNotEquals(first.key, timeline.snapshot(null).key)
        timeline.accept(sample(clock, title = "Other"))
        assertNotEquals(first.key, timeline.snapshot(null).key)
        timeline.accept(null)
        assertEquals("", timeline.snapshot(null).key)
        assertNull(timeline.snapshot(null).lyrics)
    }

    @Test fun missingDurationKeepsMetadataButDoesNotGuessLyricsOrProgress() {
        val clock = Clock()
        val timeline = ExternalTimeline(clock)
        val sample = sample(clock).copy(durationMs = 0)
        assertTrue(timeline.accept(sample))
        clock.advance(1_000)
        assertEquals("Song", timeline.snapshot(null).title)
        assertEquals(ExternalStatus.PLAYING, timeline.snapshot(null).status)
        assertEquals(0L, timeline.snapshot(null).positionMs)
        assertNull(ExternalMatcher().resolve(sample, Source().apply { candidates = listOf(track()) }))
    }

    @Test fun cachedLyricsKeepSplitSettingReversible() {
        val before = LyricSplit.enabled
        try {
            LyricSplit.enabled = true
            val result = ExternalMatch(track(), Lyrics(listOf(LyricLine(0, "你好 世界"), LyricLine(5_000, "再见")), "fake"))
            val lyrics = result.lyrics!!
            val split = lyrics.lines
            assertEquals(listOf(0L, 2_125L, 5_000L), split.map { it.timeMs })
            LyricSplit.enabled = false
            val raw = lyrics.lines
            assertEquals(listOf("你好 世界", "再见"), raw.map { it.text })
            assertEquals(listOf(0L, 5_000L), raw.map { it.timeMs })
            repeat(128) {
                LyricSplit.enabled = true
                assertSame(lyrics, result.lyrics)
                assertSame(split, result.lyrics!!.lines)
                LyricSplit.enabled = false
                assertSame(raw, result.lyrics!!.lines)
            }
        } finally { LyricSplit.enabled = before }
    }

    @Test fun snapshotsReusePreparedImmutableLyricsAndBothWordViews() {
        val before = LyricSplit.enabled
        try {
            for (prepareSplit in listOf(true, false)) {
                LyricSplit.enabled = prepareSplit
                val words = mutableListOf(
                    LyricWord(1_000, 600, "你好"),
                    LyricWord(1_600, 0, " "),
                    LyricWord(2_600, 800, "世界"),
                )
                val lastWords = mutableListOf(LyricWord(5_000, 900, "再见"))
                val providerLines = mutableListOf(
                    LyricLine(1_000, "你好 世界", words),
                    LyricLine(5_000, "再见", lastWords),
                )
                val original = Lyrics(providerLines, "fake")
                val clock = Clock()
                val tasks = Tasks()
                val api = Source().apply {
                    candidates = listOf(track())
                    suppliedLyrics = original
                }
                val engine = ExternalEngine(clock, tasks, { api })
                engine.accept(sample(clock))
                tasks.run()
                val snapshot = engine.snapshot()
                val lyrics = snapshot.lyrics!!
                assertNotSame(original, lyrics)
                words.clear()
                lastWords.clear()
                providerLines.clear()
                (original.unsplitLines as MutableList).clear()

                LyricSplit.enabled = false
                val raw = lyrics.lines
                assertEquals(listOf("你好 世界", "再见"), raw.map { it.text })
                assertEquals(listOf(1_000L, 5_000L), raw.map { it.timeMs })
                assertEquals(listOf(1_000L, 1_600L, 2_600L), raw[0].words!!.map { it.startMs })
                assertEquals(listOf(600L, 0L, 800L), raw[0].words!!.map { it.durMs })
                LyricSplit.enabled = true
                val split = lyrics.lines
                assertEquals(listOf("你好", "世界", "再见"), split.map { it.text })
                assertEquals(listOf(1_000L, 2_600L, 5_000L), split.map { it.timeMs })
                assertEquals(listOf(2_600L), split[1].words!!.map { it.startMs })
                assertEquals(listOf(800L), split[1].words!!.map { it.durMs })

                for (view in listOf(raw, split)) {
                    assertThrows(UnsupportedOperationException::class.java) { (view as MutableList).clear() }
                    for (line in view) {
                        assertThrows(UnsupportedOperationException::class.java) {
                            (line.words as MutableList).clear()
                        }
                    }
                }
                repeat(128) {
                    for (enabled in listOf(false, true)) {
                        LyricSplit.enabled = enabled
                        val expected = if (enabled) split else raw
                        val next = engine.snapshot()
                        assertSame(lyrics, snapshot.lyrics)
                        assertSame(lyrics, next.lyrics)
                        assertSame(expected, next.lyrics!!.lines)
                        for (i in expected.indices) {
                            assertSame(expected[i], next.lyrics!!.lines[i])
                            assertSame(expected[i].words, next.lyrics!!.lines[i].words)
                            assertSame(expected[i].words!![0], next.lyrics!!.lines[i].words!![0])
                        }
                    }
                }
                assertEquals(1, api.lyricRequests)
            }
        } finally { LyricSplit.enabled = before }
    }

    @Test fun lyricBoundsUseRawInputRegardlessOfSplitSetting() {
        val before = LyricSplit.enabled
        try {
            val expanding = Lyrics(List(6_000) { LyricLine(it * 5_000L, "你好 世界") }, "fake")
            val oversized = Lyrics(listOf(LyricLine(0, "你好" + " ".repeat(1_000_000) + "世界")), "fake")
            for (enabled in listOf(true, false)) {
                LyricSplit.enabled = enabled
                val match = ExternalMatch(track(), expanding)
                assertTrue(match.hasLyrics)
                assertNotNull(match.lyrics)
                assertFalse(ExternalMatch(track(), oversized).hasLyrics)
                LyricSplit.enabled = true
                assertEquals(12_000, match.lyrics!!.lines.size)
                LyricSplit.enabled = false
                assertEquals(6_000, match.lyrics!!.lines.size)
            }
        } finally { LyricSplit.enabled = before }
    }

    @Test fun cancellationDuringSearchDoesNotStartALyricRequest() {
        val clock = Clock()
        val tasks = Tasks()
        var lyricRequests = 0
        lateinit var engine: ExternalEngine
        val source = object : MusicSourceApi {
            override val source = MusicSource.NETEASE
            override fun search(query: String): List<NeteaseTrack> {
                engine.reset(ExternalStatus.DISABLED)
                return listOf(track())
            }
            override fun lyrics(track: NeteaseTrack): Lyrics? { lyricRequests++; return null }
            override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = error("audio")
        }
        engine = ExternalEngine(clock, tasks, { source })
        engine.accept(sample(clock))
        tasks.run()
        assertEquals(0, lyricRequests)
        assertEquals(ExternalStatus.DISABLED, engine.snapshot().status)
        assertNull(engine.snapshot().lyrics)
    }

    @Test fun repeatedFreshCapturesDoNotKeepAnOldPlayingAnchorAlive() {
        val clock = Clock()
        val timeline = ExternalTimeline(clock)
        val anchor = sample(clock)
        val match = ExternalMatch(track(), Lyrics(listOf(LyricLine(0, "lyrics")), "fake"))
        timeline.accept(anchor)
        clock.advance(29_000)
        assertTrue(timeline.accept(anchor.copy(capturedAtUnixMs = clock.wall)))
        assertEquals(39_000L, timeline.snapshot(match).positionMs)
        assertNotNull(timeline.snapshot(match).lyrics)
        clock.advance(1_001)
        val stale = timeline.snapshot(match)
        assertEquals(ExternalStatus.DISCONNECTED, stale.status)
        assertEquals("", stale.key)
        assertNull(stale.lyrics)
        assertFalse(timeline.accept(anchor.copy(capturedAtUnixMs = clock.wall)))
        assertEquals(ExternalStatus.DISCONNECTED, timeline.snapshot(match).status)
        assertTrue(timeline.accept(sample(clock, position = 2_000)))
        assertEquals(2_000L, timeline.snapshot(null).positionMs)
    }

    @Test fun oldPausedAnchorsRemainFixedOnlyWhileCaptureIsFresh() {
        val clock = Clock()
        val timeline = ExternalTimeline(clock)
        val paused = sample(clock, status = ExternalStatus.PAUSED, age = 172_800_000)
        assertTrue(timeline.accept(paused))
        clock.advance(4_000)
        assertEquals(10_000L, timeline.snapshot(null).positionMs)
        assertTrue(timeline.accept(paused.copy(capturedAtUnixMs = clock.wall)))
        clock.advance(4_000)
        assertEquals(ExternalStatus.PAUSED, timeline.snapshot(null).status)
        clock.advance(1_001)
        assertEquals(ExternalStatus.DISCONNECTED, timeline.snapshot(null).status)
        assertFalse(timeline.accept(paused.copy(capturedAtUnixMs = clock.wall, updatedAtUnixMs = 0)))
    }

    @Test fun searchExceptionsRetryAfterMonotonicDeadlineWithoutPerPollJobs() {
        val clock = Clock()
        val tasks = Tasks()
        var searches = 0
        val source = object : MusicSourceApi {
            override val source = MusicSource.NETEASE
            override fun search(query: String): List<NeteaseTrack> {
                searches++
                if (searches == 1) error("Offline")
                return listOf(track())
            }
            override fun lyrics(track: NeteaseTrack) = Lyrics(listOf(LyricLine(0, "lyrics")), "fake")
            override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = error("audio")
        }
        val engine = ExternalEngine(clock, tasks, { source })
        engine.accept(sample(clock))
        repeat(20) { engine.accept(sample(clock)) }
        assertEquals(1, tasks.queue.size)
        tasks.run()
        assertEquals(1, searches)
        assertNull(engine.snapshot().lyrics)
        clock.wall += 1_000_000 // wall jumps cannot bypass the retry backoff
        repeat(20) { engine.accept(sample(clock)) }
        assertTrue(tasks.queue.isEmpty())
        clock.advance(29_999)
        engine.accept(sample(clock))
        assertTrue(tasks.queue.isEmpty())
        clock.advance(1)
        repeat(20) { engine.accept(sample(clock)) }
        assertEquals(1, tasks.queue.size)
        assertEquals(1, searches)
        tasks.run()
        assertEquals(2, searches)
        assertNotNull(engine.snapshot().lyrics)
        clock.advance(60_000)
        engine.accept(sample(clock))
        assertTrue(tasks.queue.isEmpty())
        assertEquals(2, searches)
    }

    @Test fun nullLyricsRetryAndDisableReenableCannotPermanentlyPoisonCache() {
        val clock = Clock()
        val tasks = Tasks()
        var searches = 0
        var lyricRequests = 0
        val source = object : MusicSourceApi {
            override val source = MusicSource.NETEASE
            override fun search(query: String): List<NeteaseTrack> { searches++; return listOf(track()) }
            override fun lyrics(track: NeteaseTrack): Lyrics? {
                lyricRequests++
                return if (lyricRequests == 1) null else Lyrics(listOf(LyricLine(0, "lyrics")), "fake")
            }
            override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = error("audio")
        }
        val engine = ExternalEngine(clock, tasks, { source })
        engine.accept(sample(clock))
        tasks.run()
        assertNotNull(engine.snapshot().track)
        assertNull(engine.snapshot().lyrics)
        engine.reset(ExternalStatus.DISABLED)
        engine.accept(sample(clock))
        tasks.run() // cached miss: toggling does not hammer the provider
        assertEquals(1, searches)
        repeat(20) { engine.accept(sample(clock)) }
        assertTrue(tasks.queue.isEmpty())
        clock.advance(30_000)
        repeat(20) { engine.accept(sample(clock)) }
        assertEquals(1, tasks.queue.size)
        tasks.run()
        assertEquals(2, searches)
        assertEquals(2, lyricRequests)
        assertNotNull(engine.snapshot().lyrics)
    }

    @Test fun unsuccessfulCandidateLookupExpiresButRemainsBoundedBetweenPolls() {
        val clock = Clock()
        val source = Source()
        val matcher = ExternalMatcher(clock)
        assertNull(matcher.resolve(sample(clock), source))
        assertFalse(matcher.retryDue(sample(clock), source.source))
        repeat(20) { assertNull(matcher.resolve(sample(clock), source)) }
        assertEquals(1, source.searches)
        clock.advance(30_000)
        assertTrue(matcher.retryDue(sample(clock), source.source))
        source.candidates = listOf(track())
        assertNotNull(matcher.resolve(sample(clock), source)?.lyrics)
        assertEquals(2, source.searches)
        assertFalse(matcher.retryDue(sample(clock), source.source))
    }
}
