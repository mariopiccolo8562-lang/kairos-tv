package com.kairos.tv

import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

object EpgName {
    private val country = Regex("^[\\s|\\[\\](){}#*.]*([A-Z]{2}|ITA|UK|USA|ESP|FRA|GER|DEU|ALB|POR|NLD|POL|ROM|TUR|SUI|AUT|BEL|GRE)\\s*[|:\\])-]+\\s*", RegexOption.IGNORE_CASE)
    private val brackets = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}")
    private val quality = Regex("(?<![a-z0-9_])(fhd|uhd|hd|sd|4k|8k|hevc|h ?26[45]|1080[pi]?|720p?|576[pi]?|480p?|2160p?|[0-9]{2}fps|fps|backup|bkp|raw|multi|vip|hq|lq|low|high|test|new|nuovo|orig|original|feed|direct|dash)(?![a-z0-9_])")
    private val marks = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9+]+")

    fun key(value: String): String {
        var text = Normalizer.normalize(value, Normalizer.Form.NFKD).replace(marks, "")
        text = country.replace(text, "")
        text = text.lowercase(Locale.ROOT).replace(brackets, " ").replace("&", " and ")
            .replace(quality, " ").replace(nonAlnum, "")
        return text.takeIf { it.length >= 2 }.orEmpty()
    }
}

class XmltvIndex(
    private val ids: Map<String, Set<String>>,
    private val names: Map<String, Set<String>>,
) {
    fun keys(id: String, displayNames: Iterable<String>): List<String> {
        val direct = ids[id.lowercase(Locale.ROOT)].orEmpty()
        if (direct.isNotEmpty()) return direct.toList()
        val out = LinkedHashSet<String>()
        displayNames.forEach { out.addAll(nameKeys(it)) }
        out.addAll(idNameKeys(id))
        return out.toList()
    }

    fun idKeys(id: String): Set<String> = ids[id.lowercase(Locale.ROOT)].orEmpty()
    fun nameKeys(name: String): Set<String> = names[EpgName.key(name)].orEmpty()
    fun idNameKeys(id: String): Set<String> =
        EpgName.key(id.substringBeforeLast('.')).takeIf { it.isNotEmpty() }?.let { names[it].orEmpty() }.orEmpty()
}

data class XmltvProgramme(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String,
)

class XmltvLimiter(
    private val perChannel: Int = 60,
    private val total: Int = 30_000,
) {
    private val counts = HashMap<String, Int>()
    private val starts = HashMap<String, MutableSet<Long>>()
    var size: Int = 0
        private set
    val full: Boolean get() = size >= total

    fun accept(channel: String, start: Long): Boolean {
        if (full) return false
        val channelStarts = starts.getOrPut(channel) { HashSet() }
        if (start in channelStarts || (counts[channel] ?: 0) >= perChannel) return false
        channelStarts.add(start)
        counts[channel] = (counts[channel] ?: 0) + 1
        size++
        return true
    }
}

class XmltvAborted : Exception()

object XmltvParser {
    private val timestamp = Regex("^(\\d{12})(\\d{2})?(?:\\s*([+-])(\\d{2}):?(\\d{2}))?$")

    fun timeSeconds(value: String?): Long? {
        val match = timestamp.matchEntire(value?.trim().orEmpty()) ?: return null
        return try {
            val base = match.groupValues[1]
            val second = match.groupValues[2].toIntOrNull() ?: 0
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
            calendar.isLenient = false
            calendar.clear()
            calendar.set(
                base.substring(0, 4).toInt(),
                base.substring(4, 6).toInt() - 1,
                base.substring(6, 8).toInt(),
                base.substring(8, 10).toInt(),
                base.substring(10, 12).toInt(),
                second,
            )
            var millis = calendar.timeInMillis
            val sign = match.groupValues[3]
            if (sign.isNotEmpty()) {
                val hours = match.groupValues[4].toInt()
                val minutes = match.groupValues[5].toInt()
                if (hours > 23 || minutes > 59) return null
                val offset = (hours * 60 + minutes) * 60_000L
                millis -= if (sign == "+") offset else -offset
            }
            millis / 1000L
        } catch (_: Exception) {
            null
        }
    }

    fun parse(
        input: InputStream,
        parser: XmlPullParser,
        index: XmltvIndex,
        fromSeconds: Long,
        toSeconds: Long,
        stopAtLimit: () -> Boolean = { false },
        shouldAbort: () -> Boolean = { false },
        onProgramme: (XmltvProgramme) -> Unit,
    ): Int {
        val stream = maybeGzip(input)
        var count = 0
        stream.use { xml ->
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(xml, null)
            val matched = HashMap<String, List<String>>()
            var channelId: String? = null
            var directKeys = LinkedHashSet<String>()
            var nameKeys = LinkedHashSet<String>()
            var programmeChannel = ""
            var programmeKeys: List<String> = emptyList()
            var programmeStart = 0L
            var programmeStop = 0L
            var inProgramme = false
            var title = ""
            var description = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (shouldAbort()) throw XmltvAborted()
                if (stopAtLimit()) break
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            channelId = parser.getAttributeValue(null, "id")
                            directKeys = LinkedHashSet()
                            nameKeys = LinkedHashSet()
                            channelId?.let { directKeys.addAll(index.idKeys(it)) }
                        }
                        "display-name" -> if (channelId != null) {
                            val key = EpgName.key(parser.nextText().orEmpty())
                            if (key.isNotEmpty()) nameKeys.addAll(index.nameKeys(key))
                        }
                        "programme" -> {
                            programmeChannel = parser.getAttributeValue(null, "channel") ?: ""
                            programmeKeys = matched[programmeChannel.lowercase(Locale.ROOT)].orEmpty()
                            val start = timeSeconds(parser.getAttributeValue(null, "start"))
                            val stop = timeSeconds(parser.getAttributeValue(null, "stop"))
                            inProgramme = programmeKeys.isNotEmpty() && start != null && stop != null &&
                                stop > start && stop > fromSeconds && start < toSeconds
                            if (inProgramme) {
                                programmeStart = start!!
                                programmeStop = stop!!
                                title = ""
                                description = ""
                            }
                        }
                        "title" -> if (inProgramme && title.isEmpty()) title = parser.nextText().trim().take(240)
                        "desc" -> if (inProgramme && description.isEmpty()) description = parser.nextText().trim().take(600)
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            val id = channelId
                            if (!id.isNullOrEmpty()) {
                                val keys = LinkedHashSet<String>()
                                if (directKeys.isNotEmpty()) keys.addAll(directKeys) else {
                                    keys.addAll(nameKeys)
                                    keys.addAll(index.idNameKeys(id))
                                }
                                if (keys.isNotEmpty()) matched[id.lowercase(Locale.ROOT)] = keys.toList()
                            }
                            channelId = null
                        }
                        "programme" -> if (inProgramme) {
                            inProgramme = false
                            if (title.isNotEmpty()) {
                                for (key in programmeKeys) {
                                    onProgramme(XmltvProgramme(key, programmeStart, programmeStop, title, description))
                                    count++
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
        return count
    }

    private fun maybeGzip(input: InputStream): InputStream {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 32 * 1024)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered, 32 * 1024) else buffered
    }
}
