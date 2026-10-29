package me.sorihiko.dango.music.external

import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.NeteaseTrack
import org.junit.Assert.*
import org.junit.Test

class ExternalTransportTest {
    @Test fun externalModeCannotMutateInternalTransportAndDisablingRestoresSafeBehavior() {
        val type = MusicPlayer::class.java
        val names = listOf("state", "current", "queue", "index", "seekBaseMs", "pausedAtMs", "audio", "line")
        val fields = names.associateWith { type.getDeclaredField(it).apply { isAccessible = true } }
        val saved = fields.mapValues { it.value.get(null) }
        val oldEnabled = ExternalPlayback.enabled
        val oldOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Linux")
            ExternalPlayback.enabled = true
            val original = NeteaseTrack(1, "Original", listOf("Artist"), 200_000, "")
            val replacement = NeteaseTrack(2, "Replacement", listOf("Artist"), 200_000, "")
            val queue = listOf(original)
            fields.getValue("queue").set(null, queue)
            fields.getValue("current").set(null, original)
            fields.getValue("index").set(null, 0)
            fields.getValue("state").set(null, MusicPlayer.State.PLAYING)
            fields.getValue("seekBaseMs").set(null, 6_000L)
            fields.getValue("pausedAtMs").set(null, 4_000L)
            fields.getValue("audio").set(null, null)
            fields.getValue("line").set(null, null)

            MusicPlayer.playQueue(listOf(replacement), 0)
            MusicPlayer.playIndex(0)
            MusicPlayer.playTrackAt(replacement, { error("External mode must not request playback") }, false)
            MusicPlayer.next()
            MusicPlayer.prev()
            MusicPlayer.seek(90_000)
            MusicPlayer.syncTo(100_000, true)
            MusicPlayer.togglePause()
            assertSame(queue, MusicPlayer.queue)
            assertSame(original, MusicPlayer.current)
            assertEquals(0, MusicPlayer.index)
            assertEquals(MusicPlayer.State.PLAYING, MusicPlayer.state)
            assertEquals(6_000L, MusicPlayer.positionMs)
            assertEquals(4_000L, fields.getValue("pausedAtMs").getLong(null))

            ExternalPlayback.enabled = false
            MusicPlayer.togglePause() // null audio line: state transition only, no device or decoder
            assertEquals(MusicPlayer.State.PAUSED, MusicPlayer.state)
            assertEquals(6_000L, MusicPlayer.positionMs)
            fields.getValue("queue").set(null, emptyList<NeteaseTrack>())
            fields.getValue("current").set(null, null)
            fields.getValue("state").set(null, MusicPlayer.State.STOPPED)
            MusicPlayer.playQueue(emptyList(), 0)
            MusicPlayer.playIndex(0)
            MusicPlayer.next()
            MusicPlayer.prev()
            MusicPlayer.seek(1_000)
            MusicPlayer.syncTo(1_000, true)
            MusicPlayer.togglePause()
            assertTrue(MusicPlayer.queue.isEmpty())
            assertNull(MusicPlayer.current)
            assertEquals(MusicPlayer.State.STOPPED, MusicPlayer.state)
        } finally {
            fields.forEach { (name, field) -> field.set(null, saved[name]) }
            System.setProperty("os.name", oldOs)
            ExternalPlayback.enabled = oldEnabled
        }
    }

    @Test fun missingHelperIsDistinctFromNoAppleMusicSession() {
        val provider = WindowsSessionProvider(locateHelper = { null })
        assertNull(provider.poll())
        assertEquals(ExternalStatus.HELPER_MISSING, provider.failure)
        val clock = object : ExternalClock {
            override fun nanoTime() = 0L
            override fun unixMs() = 1_800_000_000_000L
        }
        val engine = ExternalEngine(clock, java.util.concurrent.Executor { it.run() }, { error("No source I/O") })
        engine.reset(ExternalStatus.HELPER_MISSING)
        assertEquals(ExternalStatus.HELPER_MISSING, engine.snapshot().status)
        assertEquals("", engine.snapshot().key)
        assertNull(engine.snapshot().lyrics)
        engine.accept(null)
        assertEquals(ExternalStatus.DISCONNECTED, engine.snapshot().status)
    }
}
