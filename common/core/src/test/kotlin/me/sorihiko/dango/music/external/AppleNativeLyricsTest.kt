package me.sorihiko.dango.music.external

import com.google.gson.Gson
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.MusicSourceApi
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.NeteaseTrack
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class AppleNativeLyricsTest {
    @Rule @JvmField val temp = TemporaryFolder()

    private val timedTtml = """
        <t:tt xmlns:t="http://www.w3.org/ns/ttml" xmlns:meta="urn:apple:test">
          <t:body><t:div><t:p begin="00:01.250" end="00:03.250">
            <t:span begin="0ms" dur="450ms">Hello</t:span><t:span begin="750ms" dur="1000ms"> world</t:span>
          </t:p></t:div></t:body>
        </t:tt>
    """.trimIndent()

    private fun sample(title: String = "Sample", position: Long = 10_000L,
                       state: ExternalStatus = ExternalStatus.PLAYING,
                       captured: Long = 1_800_000_000_000L) =
        ExternalSample("apple-session", title, listOf("Artist"), "Album", 200_000L, position,
            captured, captured, 1.0, state)

    private fun writeCache(root: java.nio.file.Path, name: String, id: String = "AP_123",
                           status: String = "success", ttml: String = timedTtml) {
        val json = Gson().toJson(mapOf("lyricsId" to id, "status" to status, "ttml" to ttml))
        Files.write(root.resolve(name), json.toByteArray(StandardCharsets.UTF_8))
    }

    @Test fun catalogLyricsIdsAcceptNumericIdsAndSafeVariantSuffixesOnly() {
        assertEquals(123L, CatalogLyricsId.parse("AP_123"))
        assertEquals(123L, CatalogLyricsId.parse("ap_123-variant-2"))
        assertNull(CatalogLyricsId.parse("AP_0"))
        assertNull(CatalogLyricsId.parse("AP_12anything"))
        assertNull(CatalogLyricsId.parse("AP_123-"))
        assertNull(CatalogLyricsId.parse("AP_-123"))
    }

    @Test fun cacheScannerFindsNestedSuccessDocumentsAndRejectsBadStatusAndIds() {
        val root = temp.newFolder().toPath()
        val nested = Files.createDirectories(root.resolve("resource/cache"))
        writeCache(nested, "ttmlLyrics-good.json", "AP_123-variant", "success")
        writeCache(root, "ttmlLyrics-pending.json", "AP_124", "pending")
        writeCache(root, "ttmlLyrics-bad-id.json", "NOT_A_CATALOG_ID")
        Files.write(root.resolve("ttmlLyrics-partial.json"), "{".toByteArray(StandardCharsets.UTF_8))
        val (documents, diagnostics) = AppleLyricsCacheScanner(listOf(root)).scan()
        assertEquals("diagnostics=$diagnostics", listOf(123L), documents.map { it.catalogId })
        assertEquals(4, diagnostics.filesFound)
        assertEquals(1, diagnostics.validDocuments)
        assertEquals(3, diagnostics.rejectedDocuments)
    }

    @Test fun cacheJsonNestingIsRejectedBeforeGsonParsing() {
        val root = temp.newFolder().toPath()
        val nested = "[".repeat(100) + "0" + "]".repeat(100)
        val deep = """{"nested":$nested,"lyricsId":"AP_123","status":"success","ttml":"<tt/>"}"""
        assertFalse(BoundedJson.hasSafeDepth(deep))
        assertTrue(BoundedJson.hasSafeDepth("""{"text":"[not nesting]","value":[]}"""))
        Files.write(root.resolve("ttmlLyrics-deep.json"), deep.toByteArray(StandardCharsets.UTF_8))
        val (documents, diagnostics) = AppleLyricsCacheScanner(listOf(root)).scan()
        assertTrue(documents.isEmpty())
        assertEquals(1, diagnostics.rejectedDocuments)
    }

    @Test fun cacheScannerDoesNotFollowLinksOutsideTheCacheRoot() {
        val root = temp.newFolder().toPath()
        val outside = temp.newFolder().toPath()
        writeCache(outside, "ttmlLyrics-outside.json")
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: SecurityException) {
            return
        }
        val (documents, _) = AppleLyricsCacheScanner(listOf(root)).scan()
        assertTrue(documents.isEmpty())
    }

    @Test fun parsesNestedNamespaceAliasedWordSpansAndAbsoluteTimes() {
        val lines = SecureTtmlParser.parse(timedTtml)!!
        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals(1250L, line.timeMs)
        assertEquals("Hello world", line.text)
        assertEquals(listOf(1250L, 2000L), line.words!!.map { it.startMs })
        assertEquals(listOf(450L, 1000L), line.words!!.map { it.durMs })
        assertEquals("Hello world", line.words!!.joinToString("") { it.text })
    }

    @Test fun nestedSpansKeepEachTextRunOnceAndRespectInheritedTiming() {
        val ttml = """
            <tt xmlns="urn:ttml"><body><div><p begin="10.000" end="12.000">
              <span begin="0s" dur="1.5s">Hello <span begin="500ms" dur="200ms">world</span></span>
            </p></div></body></tt>
        """.trimIndent()
        val line = SecureTtmlParser.parse(ttml)!!.single()
        assertEquals("Hello world", line.text)
        assertEquals("Hello world", line.words!!.joinToString("") { it.text })
        assertEquals(listOf(10_000L, 10_500L), line.words!!.map { it.startMs })
        assertEquals(listOf(1_500L, 200L), line.words!!.map { it.durMs })
    }

    @Test fun AppleClockTimesInSpansAreMediaAbsoluteNotAddedToParagraphBegin() {
        val ttml = """
            <tt xmlns="urn:ttml"><body><p begin="00:10.000" end="00:12.000"><span begin="00:10.500" end="00:10.800">Hello </span><span begin="00:11.000" dur="250ms">world</span></p></body></tt>
        """.trimIndent()
        val line = SecureTtmlParser.parse(ttml)!!.single()
        assertEquals(10_000L, line.timeMs)
        assertEquals("Hello world", line.text)
        assertEquals(listOf(10_500L, 11_000L), line.words!!.map { it.startMs })
        assertEquals(listOf(300L, 250L), line.words!!.map { it.durMs })
    }

    @Test fun lineOnlyLyricsPreserveAppleMetadataOffsetsWithoutApplyingThem() {
        val ttml = """
            <tt xmlns="urn:ttml" xmlns:i="urn:itunes">
              <head><metadata><i:iTunesMetadata leadingSilence="0.120">
                <i:audio lyricOffset="-0.090"/>
              </i:iTunesMetadata></metadata></head>
              <body><div><p begin="1.160" end="2.000">Line only</p></div></body>
            </tt>
        """.trimIndent()
        val line = SecureTtmlParser.parse(ttml)!!.single()
        assertEquals(1160L, line.timeMs)
        assertNull(line.words)
        assertEquals("Line only", line.text)
    }

    @Test fun xmlSpaceAndNonForegroundRolesDoNotCorruptForegroundWordTiming() {
        val ttml = """
            <tt xmlns="urn:ttml"><body><p begin="0s" end="3s"><span begin="0s" dur="1s" xml:space="preserve">  Hello  </span><span begin="0s" dur="1s" role="x-translation">bonjour</span><span begin="1s" dur="1s" role="x-lyrics">world</span></p></body></tt>
        """.trimIndent()
        val line = SecureTtmlParser.parse(ttml)!!.single()
        assertEquals("  Hello  world", line.text)
        assertNotNull("text=${line.text}", line.words)
        assertEquals(line.text, line.words!!.joinToString("") { it.text })
        assertEquals(listOf(0L, 1000L), line.words!!.map { it.startMs })
    }

    @Test fun parsesClockTimesAndKeepsLineOnlyDocumentsLineSynced() {
        val ttml = """
            <tt xmlns="urn:ttml"><body><p begin="01:02:03.500" end="01:02:05.000">No word spans</p></body></tt>
        """.trimIndent()
        val line = SecureTtmlParser.parse(ttml)!!.single()
        assertEquals(3_723_500L, line.timeMs)
        assertNull(line.words)
    }

    @Test fun iTunesCatalogMatchingRejectsWrongTitleArtistAlbumOrDuration() {
        val lookup = ITunesCatalogLookup()
        val player = sample()
        assertTrue(lookup.metadataMatches(player, "Ｓａｍｐｌｅ", "Artist", "Album", 201_000))
        assertFalse(lookup.metadataMatches(player, "Sample (Live)", "Artist", "Album", 200_000))
        assertFalse(lookup.metadataMatches(player, "Sample", "Another Artist", "Album", 200_000))
        assertFalse(lookup.metadataMatches(player, "Sample", "Artist", "Another Album", 200_000))
        assertFalse(lookup.metadataMatches(player, "Sample", "Artist", "Album", 202_001))
    }

    @Test fun decoratedSmtcArtistMustMatchCatalogArtistAndAlbumExactly() {
        val player = ExternalSample("apple-session", "在银河中孤独摇摆",
            listOf("知更鸟, HOYO-MiX & Chevy — 空气蛹 - EP"), "", 165_000L, 0,
            1_800_000_000_000L, 1_800_000_000_000L, 0.0, ExternalStatus.PAUSED)
        val lookup = ITunesCatalogLookup()
        assertTrue(lookup.metadataMatches(player, "在银河中孤独摇摆",
            "知更鸟, HOYO-MiX & Chevy", "空气蛹 - EP", 165_463))
        assertFalse(lookup.metadataMatches(player, "在银河中孤独摇摆",
            "知更鸟", "空气蛹 - EP", 165_463))
        assertFalse(lookup.metadataMatches(player, "在银河中孤独摇摆",
            "知更鸟, HOYO-MiX & Chevy", "Other EP", 165_463))
        assertFalse(lookup.metadataMatches(player, "在银河中孤独摇摆",
            "知更鸟, HOYO-MiX & Chevy", "空气蛹 - EP", 170_000))
    }

    @Test fun rejectsExternalEntitiesAndOversizedXml() {
        val xxe = """<!DOCTYPE tt [<!ENTITY lyric SYSTEM "file:///etc/passwd">]>
            <tt xmlns="urn:ttml"><body><p begin="0s" end="1s">&lyric;</p></body></tt>"""
        assertNull(SecureTtmlParser.parse(xxe))
        assertNull(SecureTtmlParser.parse("x".repeat(AppleLyricsCacheScanner.MAX_TTML_CHARS + 1)))
        val deep = "<tt>" + "<span>".repeat(100) + "</span>".repeat(100) + "</tt>"
        assertNull(SecureTtmlParser.parse(deep))
    }

    @Test fun catalogIdMustResolveUniquelyAndDuplicateDocumentsMustAgree() {
        val root = temp.newFolder().toPath()
        writeCache(root, "ttmlLyrics-a.json")
        val lookup = object : AppleCatalogLookup {
            override fun resolve(sample: ExternalSample, active: () -> Boolean) = 123L
        }
        val provider = AppleNativeLyricsResolver(AppleLyricsCacheScanner(listOf(root)), lookup) { true }
        val resolved = provider.resolve(sample()) { true }
        assertEquals(AppleNativeLyricsStatus.NATIVE_WORD_SYNCED, resolved.status)
        assertEquals("Apple Music (native cache)", resolved.lyrics?.sourceName)
        assertEquals(1250L, resolved.lyrics?.unsplitLines?.single()?.words?.first()?.startMs)

        writeCache(root, "ttmlLyrics-b.json", ttml = timedTtml.replace("Hello", "Goodbye"))
        val ambiguous = provider.resolve(sample()) { true }
        assertEquals(AppleNativeLyricsStatus.AMBIGUOUS, ambiguous.status)
        assertNull(ambiguous.lyrics)
    }

    @Test fun lineOnlyNativeDocumentStaysNativeAndMalformedCacheIsExplicitlyInvalid() {
        val root = temp.newFolder().toPath()
        val lineOnly = """<tt xmlns="urn:ttml"><body><p begin="0s" end="2s">line only</p></body></tt>"""
        writeCache(root, "ttmlLyrics-line.json", ttml = lineOnly)
        val lookup = object : AppleCatalogLookup {
            override fun resolve(sample: ExternalSample, active: () -> Boolean) = 123L
        }
        val resolver = AppleNativeLyricsResolver(AppleLyricsCacheScanner(listOf(root)), lookup) { true }
        val resolved = resolver.resolve(sample()) { true }
        assertEquals(AppleNativeLyricsStatus.NATIVE_LINE_SYNCED, resolved.status)
        assertEquals("line only", resolved.lyrics?.unsplitLines?.single()?.text)
        assertNull(resolved.lyrics?.unsplitLines?.single()?.words)

        val invalidRoot = temp.newFolder().toPath()
        writeCache(invalidRoot, "ttmlLyrics-invalid.json", ttml = "<!DOCTYPE tt><tt/>")
        val invalid = AppleNativeLyricsResolver(
            AppleLyricsCacheScanner(listOf(invalidRoot)), lookup,
        ) { true }.resolve(sample()) { true }
        assertEquals(AppleNativeLyricsStatus.INVALID, invalid.status)
        assertNull(invalid.lyrics)
    }

    @Test fun nativeLyricsCanUpgradeAnAlreadySuccessfulFallback() {
        val clock = FakeClock()
        val tasks = TaskQueue()
        val api = FakeApi()
        val native = FakeNativeProvider()
        val engine = ExternalEngine(clock, tasks, { api }, nativeLyrics = native)
        engine.accept(clock.sample())
        tasks.run()
        assertEquals("fallback", engine.snapshot().lyrics?.sourceName)
        assertEquals(1, api.searches)

        native.result = AppleNativeLyricsResult(
            AppleNativeLyricsStatus.NATIVE_WORD_SYNCED,
            Lyrics(listOf(LyricLine(1250, "native", listOf(me.sorihiko.dango.lyrics.LyricWord(1250, 400, "native")))),
                "Apple Music (native cache)").immutablePreparedCopy(),
            AppleLyricsDiagnostics(1, 1, 0),
        )
        clock.advance(5_000)
        engine.accept(clock.sample(position = 15_000))
        assertEquals(1, tasks.queue.size)
        tasks.run()
        assertEquals("Apple Music (native cache)", engine.snapshot().lyrics?.sourceName)
        assertEquals(AppleNativeLyricsStatus.NATIVE_WORD_SYNCED, engine.snapshot().nativeLyricsStatus)
        assertEquals(2, native.calls)
        assertEquals(1, api.searches)
    }

    @Test fun pauseAndSeekInvalidatePendingNativePublication() {
        val clock = FakeClock()
        val tasks = TaskQueue()
        val native = FakeNativeProvider().apply {
            result = AppleNativeLyricsResult(
                AppleNativeLyricsStatus.NATIVE_LINE_SYNCED,
                Lyrics(listOf(LyricLine(0, "native")), "native"),
            )
        }
        val api = FakeApi()
        val engine = ExternalEngine(clock, tasks, { api }, nativeLyrics = native)
        engine.accept(clock.sample())
        engine.accept(clock.sample(state = ExternalStatus.PAUSED))
        tasks.run()
        assertNull(engine.snapshot().lyrics)
        tasks.run()
        assertEquals("native", engine.snapshot().lyrics?.sourceName)
        assertEquals(0, api.searches)

        clock.advance(500)
        engine.accept(clock.sample(position = 90_000, state = ExternalStatus.PAUSED))
        tasks.run()
        assertEquals("native", engine.snapshot().lyrics?.sourceName)
    }

    @Test fun workerFailureCannotLeaveTheTrackPermanentlyPending() {
        val clock = FakeClock()
        val tasks = TaskQueue()
        val api = FakeApi()
        val failingNative = object : AppleNativeLyricsProvider {
            override fun resolve(sample: ExternalSample, active: () -> Boolean): AppleNativeLyricsResult {
                throw AssertionError("simulated parser failure")
            }
        }
        val engine = ExternalEngine(clock, tasks, { api }, nativeLyrics = failingNative)
        engine.accept(clock.sample())
        var threw = false
        try {
            tasks.run()
        } catch (_: AssertionError) {
            threw = true
        }
        assertTrue(threw)
        engine.accept(clock.sample())
        assertEquals(1, tasks.queue.size)
        tasks.run()
        assertEquals("fallback", engine.snapshot().lyrics?.sourceName)
    }

    private class FakeClock : ExternalClock {
        var wall = 1_800_000_000_000L
        var nano = 0L
        override fun nanoTime() = nano
        override fun unixMs() = wall
        fun advance(ms: Long) { wall += ms; nano += ms * 1_000_000L }
        fun sample(position: Long = 10_000, state: ExternalStatus = ExternalStatus.PLAYING) =
            ExternalSample("apple-session", "Sample", listOf("Artist"), "Album", 200_000,
                position, wall, wall, if (state == ExternalStatus.PLAYING) 1.0 else 0.0, state)
    }

    private class TaskQueue : java.util.concurrent.Executor {
        val queue = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { queue += command }
        fun run() = queue.removeAt(0).run()
    }

    private class FakeApi : MusicSourceApi {
        override val source = MusicSource.NETEASE
        var searches = 0
        override fun search(query: String): List<NeteaseTrack> {
            searches++
            return listOf(NeteaseTrack(1, "Sample", listOf("Artist"), 200_000, "cover"))
        }
        override fun lyrics(track: NeteaseTrack) = Lyrics(listOf(LyricLine(0, "fallback")), "fallback")
        override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = error("Native lyrics must not request audio")
    }

    private class FakeNativeProvider : AppleNativeLyricsProvider {
        var calls = 0
        var result = AppleNativeLyricsResult(AppleNativeLyricsStatus.CACHE_MISSING)
        override fun resolve(sample: ExternalSample, active: () -> Boolean): AppleNativeLyricsResult {
            calls++
            return if (active()) result else AppleNativeLyricsResult()
        }
    }
}
