package me.sorihiko.dango.music.external

import me.sorihiko.dango.music.MusicSourceApi
import me.sorihiko.dango.music.MusicSources
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Read-only Apple Music companion. It never starts local audio or requests a playable song URL.
 * Enabling starts polling; disabling/stop clears metadata immediately. Unsupported systems do no I/O.
 */
object ExternalPlayback {
    private val matches = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { r -> Thread(r, "Dango-AppleMusic-Lyrics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private val engine = ExternalEngine(SystemExternalClock, matches, { MusicSources.api() })
    private var worker: ScheduledExecutorService? = null
    private var provider: ExternalSessionProvider? = null
    private var epoch = 0L

    @set:Synchronized
    @Volatile var enabled = false
        set(value) {
            field = value
            if (value) start() else stop()
        }

    @Synchronized fun start() {
        if (!enabled || worker != null) return
        if (!WindowsSessionProvider.supported()) {
            engine.reset(ExternalStatus.UNSUPPORTED)
            return
        }
        val generation = ++epoch
        val sessionProvider = WindowsSessionProvider()
        provider = sessionProvider
        engine.reset(ExternalStatus.DISCONNECTED)
        worker = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Dango-AppleMusic-Poll").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({
                val value = sessionProvider.poll()
                synchronized(this) {
                    if (generation == epoch && enabled) engine.accept(value)
                }
            }, 0, 500, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized fun stop() {
        epoch++
        provider?.close()
        provider = null
        worker?.shutdownNow()
        worker = null
        matches.queue.clear()
        engine.reset(ExternalStatus.DISABLED)
    }

    val snapshot: ExternalSnapshot get() = snapshot()
    fun snapshot(): ExternalSnapshot = engine.snapshot()
}

/** Serialized publication, with generation checks across session, track, provider and lifecycle changes. */
internal class ExternalEngine(
    clock: ExternalClock,
    private val executor: Executor,
    private val api: () -> MusicSourceApi,
    private val matcher: ExternalMatcher = ExternalMatcher(),
) {
    private val timeline = ExternalTimeline(clock)
    private var status = ExternalStatus.DISCONNECTED
    private var identity: List<String>? = null
    private var generation = 0L
    private var match: ExternalMatch? = null

    @Synchronized fun reset(value: ExternalStatus) {
        generation++
        identity = null
        match = null
        timeline.accept(null)
        status = value
    }

    @Synchronized fun accept(sample: ExternalSample?) {
        if (!timeline.accept(sample) || sample == null) {
            reset(ExternalStatus.DISCONNECTED)
            return
        }
        status = sample.status
        val source = api()
        val key = sample.identity() + source.source.key
        if (key == identity) return
        val token = ++generation
        identity = key
        match = null
        executor.execute {
            val result = matcher.resolve(sample, source)
            synchronized(this) {
                if (generation == token && identity == key) match = result
            }
        }
    }

    @Synchronized fun snapshot(): ExternalSnapshot =
        if (status in listOf(ExternalStatus.DISABLED, ExternalStatus.UNSUPPORTED, ExternalStatus.DISCONNECTED))
            ExternalSnapshot(status)
        else timeline.snapshot(match)
}
