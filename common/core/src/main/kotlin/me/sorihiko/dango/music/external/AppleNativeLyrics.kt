package me.sorihiko.dango.music.external

import com.google.gson.JsonParser
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.LyricWord
import me.sorihiko.dango.lyrics.Lyrics
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.io.StringReader
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

enum class AppleNativeLyricsStatus {
    UNCHECKED, UNSUPPORTED, CACHE_MISSING, NO_CATALOG_MATCH, AMBIGUOUS, INVALID,
    NATIVE_WORD_SYNCED, NATIVE_PARTIAL_WORD_SYNCED, NATIVE_LINE_SYNCED
}

internal data class AppleLyricsDiagnostics(
    val filesFound: Int = 0,
    val validDocuments: Int = 0,
    val rejectedDocuments: Int = 0,
)

internal data class AppleNativeLyricsResult(
    val status: AppleNativeLyricsStatus = AppleNativeLyricsStatus.UNCHECKED,
    val lyrics: Lyrics? = null,
    val diagnostics: AppleLyricsDiagnostics = AppleLyricsDiagnostics(),
)

internal interface AppleNativeLyricsProvider {
    fun resolve(sample: ExternalSample, active: () -> Boolean): AppleNativeLyricsResult
}

internal data class AppleCachedDocument(val catalogId: Long, val ttml: String)

internal object BoundedJson {
    fun hasSafeDepth(json: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in json) {
            if (quoted) {
                if (escaped) escaped = false
                else when (char) {
                    '\\' -> escaped = true
                    '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> if (++depth > MAX_DEPTH) return false
                    '}', ']' -> if (--depth < 0) return false
                }
            }
        }
        return !quoted && depth == 0
    }

    private const val MAX_DEPTH = 64
}

internal class AppleLyricsCacheScanner(private val fixedRoots: List<Path>? = null) {
    fun scan(): Pair<List<AppleCachedDocument>, AppleLyricsDiagnostics> {
        if (fixedRoots == null && !System.getProperty("os.name", "").startsWith("Windows", true)) {
            return emptyList<AppleCachedDocument>() to AppleLyricsDiagnostics()
        }
        val roots = fixedRoots ?: cacheRoots()
        val trustedBase = if (fixedRoots == null) {
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: return emptyList<AppleCachedDocument>() to AppleLyricsDiagnostics()
            try { java.nio.file.Paths.get(local, "Packages").toRealPath() }
            catch (_: Exception) { return emptyList<AppleCachedDocument>() to AppleLyricsDiagnostics() }
        } else null
        val documents = mutableListOf<AppleCachedDocument>()
        var filesFound = 0
        var rejected = 0
        var visitedDirectories = 0
        var visitedEntries = 0
        var totalTtmlChars = 0
        for (root in roots.take(MAX_PACKAGE_ROOTS)) {
            if (documents.size >= MAX_DOCUMENTS) continue
            val rootAttributes = try {
                Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) { continue }
            if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) continue
            val realRoot = try { root.toRealPath() } catch (_: Exception) { continue }
            if (trustedBase != null && !realRoot.startsWith(trustedBase)) continue
            try {
                val pending = java.util.ArrayDeque<Pair<Path, Int>>()
                pending.add(root to 0)
                while (!pending.isEmpty() && documents.size < MAX_DOCUMENTS && filesFound < MAX_CANDIDATE_FILES &&
                    visitedDirectories < MAX_DIRECTORIES && visitedEntries < MAX_ENTRIES) {
                    val (directory, depth) = pending.removeFirst()
                    val attributes = try {
                        Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: Exception) {
                        rejected++
                        continue
                    }
                    visitedDirectories++
                    if (!attributes.isDirectory || attributes.isSymbolicLink) continue
                    val realDirectory = try { directory.toRealPath() } catch (_: Exception) {
                        rejected++
                        continue
                    }
                    if (!realDirectory.startsWith(realRoot) || depth >= MAX_DEPTH) continue
                    Files.newDirectoryStream(directory).use { children ->
                        for (child in children) {
                            if (visitedEntries >= MAX_ENTRIES) break
                            visitedEntries++
                            if (filesFound >= MAX_CANDIDATE_FILES) break
                            val name = child.fileName?.toString()?.lowercase(Locale.ROOT) ?: continue
                            if (name.startsWith("ttmllyrics") && name.endsWith(".json")) {
                                filesFound++
                                val attrs = try {
                                    Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                                } catch (_: Exception) {
                                    rejected++
                                    continue
                                }
                                if (documents.size >= MAX_DOCUMENTS || filesFound > MAX_CANDIDATE_FILES ||
                                    !attrs.isRegularFile || attrs.isSymbolicLink) {
                                    rejected++
                                    continue
                                }
                                val realFile = try { child.toRealPath() } catch (_: Exception) {
                                    rejected++
                                    continue
                                }
                                if (!realFile.startsWith(realRoot)) {
                                    rejected++
                                    continue
                                }
                                val parsed = parseCacheFile(child)
                                if (parsed == null || totalTtmlChars + parsed.ttml.length > MAX_TOTAL_TTML_CHARS) {
                                    rejected++
                                } else {
                                    documents += parsed
                                    totalTtmlChars += parsed.ttml.length
                                }
                            } else if (depth + 1 < MAX_DEPTH && pending.size < MAX_DIRECTORIES) {
                                val attrs = try {
                                    Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                                } catch (_: Exception) {
                                    rejected++
                                    continue
                                }
                                if (attrs.isDirectory && !attrs.isSymbolicLink) pending.add(child to (depth + 1))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                rejected++
            }
        }
        return documents to AppleLyricsDiagnostics(filesFound, documents.size, rejected)
    }

    private fun cacheRoots(): List<Path> {
        val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val packages = java.nio.file.Paths.get(local, "Packages")
        if (!Files.isDirectory(packages)) return emptyList()
        val realPackages = try { packages.toRealPath() } catch (_: Exception) { return emptyList() }
        val roots = mutableListOf<Path>()
        try {
            Files.newDirectoryStream(packages).use { entries ->
                var inspected = 0
                for (entry in entries) {
                    if (roots.size >= MAX_PACKAGE_ROOTS || inspected++ >= MAX_PACKAGE_ENTRIES) break
                    if (!entry.fileName.toString().startsWith("AppleInc.AppleMusicWin_")) continue
                    val appAttrs = try {
                        Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: Exception) { continue }
                    if (!appAttrs.isDirectory || appAttrs.isSymbolicLink) continue
                    val realApp = try { entry.toRealPath() } catch (_: Exception) { continue }
                    if (!realApp.startsWith(realPackages)) continue
                    val cache = entry.resolve("AC").resolve("INetCache")
                    val cacheAttrs = try {
                        Files.readAttributes(cache, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: Exception) { continue }
                    if (!cacheAttrs.isDirectory || cacheAttrs.isSymbolicLink) continue
                    val realCache = try { cache.toRealPath() } catch (_: Exception) { continue }
                    if (realCache.startsWith(realApp) && realCache.startsWith(realPackages)) roots.add(cache)
                }
            }
        } catch (_: Exception) {
        }
        return roots
    }

    private fun parseCacheFile(path: Path): AppleCachedDocument? {
        return try {
            val size = Files.size(path)
            if (size !in 1..MAX_JSON_BYTES.toLong()) return null
            val bytes = ByteArrayOutputStream(size.toInt())
            Channels.newInputStream(Files.newByteChannel(path,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))).use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_JSON_BYTES) return null
                    bytes.write(buffer, 0, read)
                }
            }
            if (Files.size(path) != size) return null
            val json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            if (!BoundedJson.hasSafeDepth(json)) return null
            val root = JsonParser().parse(json)
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject
            val status = obj.get("status")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (!status.equals("success", ignoreCase = true)) return null
            val idText = obj.get("lyricsId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: return null
            val ttml = obj.get("ttml")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: return null
            val id = CatalogLyricsId.parse(idText) ?: return null
            if (ttml.isBlank() || ttml.length > MAX_TTML_CHARS) return null
            AppleCachedDocument(id, ttml)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        internal const val MAX_JSON_BYTES = 384 * 1024
        internal const val MAX_TTML_CHARS = 256 * 1024
        private const val MAX_PACKAGE_ROOTS = 8
        private const val MAX_PACKAGE_ENTRIES = 8192
        private const val MAX_DOCUMENTS = 64
        private const val MAX_CANDIDATE_FILES = 64
        private const val MAX_TOTAL_TTML_CHARS = 2 * 1024 * 1024
        private const val MAX_DIRECTORIES = 1024
        private const val MAX_ENTRIES = 8192
        private const val MAX_DEPTH = 8
    }
}

internal object CatalogLyricsId {
    private val pattern = Regex("""(?i)^AP_(\d{1,18})(?:-([A-Za-z0-9-]+))?$""")

    fun parse(value: String): Long? {
        val match = pattern.matchEntire(value) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
    }
}

internal class AppleNativeLyricsResolver(
    private val scanner: AppleLyricsCacheScanner = AppleLyricsCacheScanner(),
    private val catalogLookup: AppleCatalogLookup = AppleCacheCatalogLookup(),
    private val windowsSupported: () -> Boolean = {
        System.getProperty("os.name", "").startsWith("Windows", true)
    },
) : AppleNativeLyricsProvider {
    override fun resolve(sample: ExternalSample, active: () -> Boolean): AppleNativeLyricsResult {
        if (!active()) return AppleNativeLyricsResult()
        if (!windowsSupported()) {
            return AppleNativeLyricsResult(AppleNativeLyricsStatus.UNSUPPORTED)
        }
        val (documents, diagnostics) = scanner.scan()
        if (!active()) return AppleNativeLyricsResult(diagnostics = diagnostics)
        if (documents.isEmpty()) {
            val status = if (diagnostics.filesFound > 0 && diagnostics.rejectedDocuments > 0)
                AppleNativeLyricsStatus.INVALID else AppleNativeLyricsStatus.CACHE_MISSING
            return AppleNativeLyricsResult(status, diagnostics = diagnostics)
        }
        val catalogId = catalogLookup.resolve(sample, documents.map { it.catalogId }.distinct(), active)
            ?: return AppleNativeLyricsResult(AppleNativeLyricsStatus.NO_CATALOG_MATCH, diagnostics = diagnostics)
        if (!active()) return AppleNativeLyricsResult(diagnostics = diagnostics)
        val candidates = documents.filter { it.catalogId == catalogId }
        if (candidates.isEmpty()) {
            return AppleNativeLyricsResult(AppleNativeLyricsStatus.NO_CATALOG_MATCH, diagnostics = diagnostics)
        }
        val unique = candidates.distinctBy { it.ttml }
        if (unique.size != 1) {
            return AppleNativeLyricsResult(AppleNativeLyricsStatus.AMBIGUOUS, diagnostics = diagnostics)
        }
        val lines = SecureTtmlParser.parse(unique.single().ttml)
            ?: return AppleNativeLyricsResult(AppleNativeLyricsStatus.INVALID, diagnostics = diagnostics)
        if (!active()) return AppleNativeLyricsResult(diagnostics = diagnostics)
        val timedLines = lines.count { it.words?.isNotEmpty() == true }
        val lyrics = Lyrics(lines, "Apple Music (native cache)").immutablePreparedCopy()
        return AppleNativeLyricsResult(
            when {
                timedLines == 0 -> AppleNativeLyricsStatus.NATIVE_LINE_SYNCED
                timedLines == lines.size -> AppleNativeLyricsStatus.NATIVE_WORD_SYNCED
                else -> AppleNativeLyricsStatus.NATIVE_PARTIAL_WORD_SYNCED
            },
            lyrics,
            diagnostics,
        )
    }
}

internal interface AppleCatalogLookup {
    fun resolve(sample: ExternalSample, catalogIds: List<Long>, active: () -> Boolean): Long?
}

internal object SecureTtmlParser {
    private data class Timing(val startMs: Long, val endMs: Long?)
    private data class Piece(val text: String, val timing: Timing?, val preserve: Boolean)
    private data class WordBuilder(val timing: Timing, val pieces: MutableList<Piece> = mutableListOf())

    fun parse(ttml: String): List<LyricLine>? {
        if (ttml.isBlank() || ttml.length > AppleLyricsCacheScanner.MAX_TTML_CHARS || !hasBoundedXmlDepth(ttml))
            return null
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document = builder.parse(InputSource(StringReader(ttml)))
            if (!withinXmlLimits(document.documentElement)) return null
            val paragraphs = mutableListOf<Element>()
            collectParagraphs(document.documentElement, false, paragraphs)
            if (paragraphs.size > MAX_LINES) return null
            val lines = paragraphs.mapNotNull { parseParagraph(it) }
            lines.takeIf { it.isNotEmpty() && it.size <= MAX_LINES }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasBoundedXmlDepth(xml: String): Boolean {
        var index = 0
        var depth = 0
        var elements = 0
        while (index < xml.length) {
            if (xml[index] != '<') {
                index++
                continue
            }
            when {
                xml.startsWith("<!--", index) -> {
                    val end = xml.indexOf("-->", index + 4)
                    if (end < 0) return false
                    index = end + 3
                }
                xml.startsWith("<![CDATA[", index) -> {
                    val end = xml.indexOf("]]>", index + 9)
                    if (end < 0) return false
                    index = end + 3
                }
                xml.startsWith("<?", index) -> {
                    val end = xml.indexOf("?>", index + 2)
                    if (end < 0) return false
                    index = end + 2
                }
                xml.startsWith("<!", index) -> return false
                else -> {
                    var end = index + 1
                    var quote: Char? = null
                    while (end < xml.length) {
                        val char = xml[end]
                        if (quote != null) {
                            if (char == quote) quote = null
                        } else when (char) {
                            '\'', '"' -> quote = char
                            '>' -> break
                        }
                        end++
                    }
                    if (end >= xml.length) return false
                    val closing = xml.getOrNull(index + 1) == '/'
                    val selfClosing = xml.getOrNull(end - 1) == '/'
                    elements++
                    if (elements > MAX_NODES) return false
                    if (closing) {
                        if (--depth < 0) return false
                    } else if (!selfClosing && ++depth > MAX_DEPTH) {
                        return false
                    }
                    index = end + 1
                }
            }
        }
        return depth == 0
    }

    private fun withinXmlLimits(root: Node): Boolean {
        var count = 0
        fun visit(node: Node, depth: Int): Boolean {
            if (++count > MAX_NODES || depth > MAX_DEPTH) return false
            var child = node.firstChild
            while (child != null) {
                if (!visit(child, depth + 1)) return false
                child = child.nextSibling
            }
            return true
        }
        return visit(root, 0)
    }

    private fun collectParagraphs(node: Node, excluded: Boolean, out: MutableList<Element>) {
        if (out.size > MAX_LINES) return
        val nowExcluded = excluded || (node.nodeType == Node.ELEMENT_NODE && isExcluded(node as Element))
        if (nowExcluded) return
        if (node.nodeType == Node.ELEMENT_NODE && localName(node) == "p") {
            out += node as Element
            return
        }
        var child = node.firstChild
        while (child != null) {
            collectParagraphs(child, false, out)
            child = child.nextSibling
        }
    }

    private fun parseParagraph(paragraph: Element): LyricLine? {
        val timing = timingTo(paragraph) ?: return null
        val end = timing.endMs ?: return null
        if (end <= timing.startMs) return null
        val pieces = mutableListOf<Piece>()
        var child = paragraph.firstChild
        while (child != null) {
            collectPieces(child, timing, null, false, pieces)
            child = child.nextSibling
        }
        if (pieces.sumOf { it.text.length.toLong() } > MAX_TEXT_CHARS) return null
        val text = renderPieces(pieces)
        if (text.isBlank()) return null
        val words = timedWords(pieces, text)
        return LyricLine(timing.startMs, text, words)
    }

    private fun collectPieces(
        node: Node,
        parentTiming: Timing,
        owner: Timing?,
        preserve: Boolean,
        pieces: MutableList<Piece>,
    ) {
        if (pieces.size > MAX_WORDS * 4) return
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
            val value = node.nodeValue.orEmpty()
            if (value.isNotEmpty()) pieces += Piece(value, owner, preserve)
            return
        }
        if (node.nodeType != Node.ELEMENT_NODE) return
        val element = node as Element
        if (isExcluded(element)) return
        val name = localName(element)
        val space = attr(element, "space")?.lowercase(Locale.ROOT)
        val preserveSpace = when (space) {
            "preserve" -> true
            "default" -> false
            else -> preserve
        }
        val ownTiming = resolveTiming(element, parentTiming)
        val hasOwnTime = attr(element, "begin") != null || attr(element, "end") != null || attr(element, "dur") != null
        val nextOwner = if (name == "span" && hasOwnTime) {
            ownTiming?.takeIf { it.endMs != null }
        } else owner
        var child = element.firstChild
        while (child != null) {
            collectPieces(child, ownTiming ?: parentTiming, nextOwner, preserveSpace, pieces)
            child = child.nextSibling
        }
    }

    private fun timedWords(pieces: List<Piece>, text: String): List<LyricWord>? {
        val significantOutsideSpan = pieces.any { it.timing == null && it.text.any { c -> !c.isWhitespace() } }
        if (significantOutsideSpan) return null
        val builders = mutableListOf<WordBuilder>()
        var previous: WordBuilder? = null
        for ((index, piece) in pieces.withIndex()) {
            val timing = piece.timing
            if (timing != null) {
                val last = builders.lastOrNull()
                val builder = if (last != null && last.timing == timing) last else WordBuilder(timing).also(builders::add)
                builder.pieces += piece
                previous = builder
            } else if (previous != null) {
                previous.pieces += piece
            } else {
                val nextTiming = pieces.drop(index + 1).firstOrNull { it.timing != null }?.timing ?: return null
                val next = WordBuilder(nextTiming)
                next.pieces += piece
                builders.add(next)
                previous = next
            }
        }
        if (builders.isEmpty() || builders.size > MAX_WORDS) return null
        val rawTexts = builders.map { it.pieces.joinToString("") { piece -> piece.text } }
        val normalizedTexts = builders.map { renderPieces(it.pieces) }.toMutableList()
        for (index in 0 until normalizedTexts.lastIndex) {
            val separated = rawTexts[index].lastOrNull()?.isWhitespace() == true ||
                rawTexts[index + 1].firstOrNull()?.isWhitespace() == true
            if (separated && normalizedTexts[index].isNotEmpty() && normalizedTexts[index + 1].isNotEmpty() &&
                !normalizedTexts[index].last().isWhitespace() && !normalizedTexts[index + 1].first().isWhitespace()) {
                normalizedTexts[index] += " "
            }
        }
        val words = builders.mapIndexedNotNull { index, builder ->
            val wordText = normalizedTexts[index]
            val duration = (builder.timing.endMs ?: return@mapIndexedNotNull null) - builder.timing.startMs
            if (wordText.isEmpty() || duration < 0L) null
            else LyricWord(builder.timing.startMs, duration, wordText)
        }
        return words.takeIf {
            it.isNotEmpty() && it.joinToString("") { word -> word.text } == text
        }
    }

    private fun timingTo(element: Element): Timing? {
        val chain = mutableListOf<Element>()
        var parent = element.parentNode
        while (parent != null) {
            if (parent.nodeType == Node.ELEMENT_NODE) chain += parent as Element
            parent = parent.parentNode
        }
        var timing = Timing(0, null)
        for (ancestor in chain.asReversed()) timing = resolveTiming(ancestor, timing) ?: return null
        return resolveTiming(element, timing)
    }

    private fun resolveTiming(element: Element, parent: Timing): Timing? {
        val begin = attr(element, "begin")?.let(::parseTime)
        val end = attr(element, "end")?.let(::parseTime)
        val duration = attr(element, "dur")?.let(::parseTime)
        if ((attr(element, "begin") != null && begin == null) ||
            (attr(element, "end") != null && end == null) ||
            (attr(element, "dur") != null && duration == null)) return null
        // Apple clock/bare-second timestamps are media-absolute; unit offsets are parent-relative.
        val start = begin?.let {
            if (attr(element, "begin")!!.trim().endsWith("s")) safeAdd(parent.startMs, it) else it
        } ?: parent.startMs
        val finish = when {
            duration != null -> safeAdd(start, duration)
            end != null -> if (attr(element, "end")!!.trim().endsWith("s"))
                safeAdd(parent.startMs, end) else end
            else -> parent.endMs
        }
        if (start !in 0..MAX_TIME_MS || finish != null && finish !in start..MAX_TIME_MS) return null
        return Timing(start, finish)
    }

    private fun parseTime(value: String): Long? {
        val raw = value.trim()
        val offset = Regex("""^([+-]?\d+(?:\.\d+)?)(ms|s)$""").matchEntire(raw)
        if (offset != null) {
            val n = offset.groupValues[1].toBigDecimalOrNull() ?: return null
            val millis = if (offset.groupValues[2] == "s") n.multiply(BigDecimal(1000)) else n
            return try { millis.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }
            catch (_: Exception) { null }
        }
        val bareSeconds = raw.toBigDecimalOrNull()
        if (bareSeconds != null) {
            if (bareSeconds < BigDecimal.ZERO) return null
            return try {
                bareSeconds.multiply(BigDecimal(1000)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
            } catch (_: Exception) { null }
        }
        val parts = raw.split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toBigDecimalOrNull() ?: return null
        if (seconds < BigDecimal.ZERO || seconds >= BigDecimal(60)) return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        if (minutes !in 0..59 && parts.size == 3 || minutes < 0) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null else 0L
        val total = BigDecimal(hours).multiply(BigDecimal(3_600_000))
            .add(BigDecimal(minutes).multiply(BigDecimal(60_000)))
            .add(seconds.multiply(BigDecimal(1000)))
        return try { total.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }
        catch (_: Exception) { null }
    }

    private fun safeAdd(left: Long, right: Long): Long? =
        if (right > 0 && left > Long.MAX_VALUE - right || right < 0 && left < Long.MIN_VALUE - right) null
        else left + right

    private fun attr(element: Element, name: String): String? {
        val attrs = element.attributes
        for (i in 0 until attrs.length) {
            val item = attrs.item(i)
            val local = item.localName ?: item.nodeName.substringAfter(':')
            if (local.equals(name, ignoreCase = true)) return item.nodeValue
        }
        return null
    }

    private fun isExcluded(element: Element): Boolean {
        val role = attr(element, "role")?.lowercase(Locale.ROOT) ?: return false
        return role.split(Regex("""\s+""")).any { token ->
            token == "x-bg" || token.contains("background") || token.contains("translation") || token.contains("agent")
        }
    }

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName.substringAfter(':')).lowercase(Locale.ROOT)

    private fun renderPieces(pieces: List<Piece>): String {
        val output = StringBuilder()
        var pendingSpace = false
        for (piece in pieces) {
            if (piece.preserve) {
                if (pendingSpace && output.isNotEmpty()) output.append(' ')
                pendingSpace = false
                output.append(piece.text)
                continue
            }
            for (char in piece.text) {
                if (char.isWhitespace()) {
                    pendingSpace = output.isNotEmpty()
                } else {
                    if (pendingSpace) output.append(' ')
                    output.append(char)
                    pendingSpace = false
                }
            }
        }
        return output.toString()
    }

    private const val MAX_LINES = 5000
    private const val MAX_WORDS = 20_000
    private const val MAX_NODES = 30_000
    private const val MAX_DEPTH = 64
    private const val MAX_TEXT_CHARS = 256 * 1024
    private const val MAX_TIME_MS = 86_400_000L
}
