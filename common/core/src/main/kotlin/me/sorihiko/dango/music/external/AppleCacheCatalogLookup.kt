package me.sorihiko.dango.music.external

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.logging.Logger

/** Verifies supplied cache IDs against public catalog metadata; never searches by title. */
internal class AppleCacheCatalogLookup(
    private val fetcher: (String) -> String? = { fetch(it) },
    private val nanoTime: () -> Long = System::nanoTime,
) : AppleCatalogLookup {
    private data class Key(val identity: List<String>, val catalogIds: List<Long>)
    private data class CacheEntry(val result: Long?, val resolvedNs: Long) {
        fun expired(nowNs: Long) =
            nowNs - resolvedNs >= if (result == null) NEGATIVE_TTL_NS else SUCCESS_TTL_NS
    }

    private val cache = object : LinkedHashMap<Key, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CacheEntry>?) =
            size > CACHE_LIMIT
    }

    override fun resolve(sample: ExternalSample, catalogIds: List<Long>, active: () -> Boolean): Long? {
        if (!active()) return null
        val ids = catalogIds.asSequence().filter { it > 0 }.distinct()
            .take(CANDIDATE_LIMIT + 1).sorted().toList()
        if (ids.isEmpty()) return null
        // Truncation could hide a second matching ID and turn ambiguity into a false match.
        if (ids.size > CANDIDATE_LIMIT) {
            LOGGER.warning("Apple catalog lookup rejected too many candidate IDs")
            return null
        }
        val key = Key(sample.identity(), ids)
        val cached = synchronized(cache) { cache[key]?.takeUnless { it.expired(nanoTime()) } }
        if (cached != null) return if (active()) cached.result else null

        val requested = ids.toSet()
        val joinedIds = ids.joinToString(",")
        for (country in COUNTRIES) {
            if (!active()) return null
            val body = try {
                fetcher("$LOOKUP_URL?id=$joinedIds&entity=song&country=$country&limit=$CANDIDATE_LIMIT")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (e: Exception) {
                LOGGER.warning("Apple catalog lookup request failed (${e.javaClass.simpleName})")
                null
            }
            if (!active()) return null
            val results = body?.let(::parseResults) ?: continue
            val verified = mutableSetOf<Long>()
            for (value in results) {
                if (!active()) return null
                val track = value as? JsonObject ?: continue
                val id = number(track, "trackId")?.takeIf { it in requested } ?: continue
                val title = text(track, "trackName") ?: continue
                val artist = text(track, "artistName") ?: continue
                val album = text(track, "collectionName") ?: continue
                val duration = number(track, "trackTimeMillis") ?: continue
                if (AppleCatalogMetadata.matches(sample, title, artist, album, duration)) verified.add(id)
            }
            if (!active()) return null
            if (verified.isNotEmpty()) return remember(key, verified.singleOrNull(), active)
        }
        return remember(key, null, active)
    }

    private fun remember(key: Key, result: Long?, active: () -> Boolean): Long? {
        val entry = CacheEntry(result, nanoTime())
        synchronized(cache) {
            if (!active()) return null
            cache[key] = entry
        }
        return result
    }

    private fun parseResults(body: String): JsonArray? {
        if (body.length > MAX_RESPONSE_BYTES || !BoundedJson.hasSafeDepth(body)) {
            LOGGER.warning("Apple catalog lookup response exceeds size limit")
            return null
        }
        return try {
            val root = JsonParser().parse(body) as? JsonObject
            (root?.get("results") as? JsonArray).also {
                if (it == null) LOGGER.warning("Apple catalog lookup returned an invalid response shape")
            }
        } catch (_: JsonParseException) {
            LOGGER.warning("Apple catalog lookup returned invalid JSON")
            null
        }
    }

    private fun text(track: JsonObject, name: String): String? =
        (track.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.asString

    private fun number(track: JsonObject, name: String): Long? =
        (track.get(name) as? JsonPrimitive)?.takeIf { it.isNumber }?.asString?.toLongOrNull()

    private companion object {
        const val LOOKUP_URL = "https://itunes.apple.com/lookup"
        val COUNTRIES = listOf("cn", "us", "jp", "gb")
        const val CANDIDATE_LIMIT = 128
        const val CACHE_LIMIT = 96
        const val SUCCESS_TTL_NS = 600_000_000_000L
        const val NEGATIVE_TTL_NS = 15_000_000_000L
        const val MAX_RESPONSE_BYTES = 512 * 1024
        val LOGGER: Logger = Logger.getLogger(AppleCacheCatalogLookup::class.java.name)

        fun fetch(url: String): String? {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.doOutput = false
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 2_500
                connection.readTimeout = 3_500
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    LOGGER.warning("Apple catalog lookup HTTP status $status")
                    return null
                }
                if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                    throw IOException("Apple catalog lookup response exceeds size limit")
                }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) {
                            throw IOException("Apple catalog lookup response exceeds size limit")
                        }
                        output.write(buffer, 0, count)
                    }
                }
                return String(output.toByteArray(), Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }
    }
}
