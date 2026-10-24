package se.glasstv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Channel(
    val uid: Int, val id: String, val name: String, val url: String,
    val logo: String?, val country: String, val group: String
)

data class Programme(val start: Long, val stop: Long, val title: String)

private val SV = Locale("sv")

object M3u {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")
    private val names = ConcurrentHashMap<String, String>()

    /** Ladda inte filmer och serier (VOD) – de gör stora listor oanvändbara och kan ta slut på minnet. */
    private fun isVod(u: String): Boolean {
        val l = u.lowercase()
        return "/movie/" in l || "/series/" in l || l.endsWith(".mkv") || l.endsWith(".mp4") || l.endsWith(".avi")
    }

    fun parse(lines: Sequence<String>): List<Channel> {
        val out = ArrayList<Channel>()
        var meta: Map<String, String>? = null
        var name = ""
        var uid = 0
        for (raw in lines) {
            val l = raw.trim().removePrefix("﻿")
            if (l.startsWith("#EXTINF")) {
                meta = attr.findAll(l).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                // Namnet står efter första kommatecknet som kommer efter sista attributet
                name = l.substring(maxOf(l.lastIndexOf('"'), 0)).substringAfter(',', "").trim()
            } else if (l.isNotEmpty() && !l.startsWith("#") && meta != null) {
                val m = meta
                meta = null
                if (isVod(l)) continue
                val group = m["group-title"].orEmpty()
                val code = m["tvg-country"]?.split(';', ',')?.firstOrNull()?.trim().orEmpty()
                out += Channel(
                    uid = uid++,
                    id = m["tvg-id"].orEmpty(),
                    name = name.ifEmpty { m["tvg-name"].orEmpty() }.ifEmpty { "Kanal $uid" },
                    url = l,
                    logo = m["tvg-logo"]?.takeIf { it.isNotBlank() },
                    country = country(code, group),
                    group = group
                )
            }
        }
        return out
    }

    private fun nice(code: String): String = names.getOrPut(code.uppercase()) {
        Locale("", code.uppercase()).getDisplayCountry(SV).ifBlank { code.uppercase() }
    }

    private fun country(code: String, group: String): String {
        if (code.length == 2) return nice(code)
        if (code.isNotBlank()) return code
        val g = group.substringBefore('|').substringBefore(':').trim()
        if (g.length == 2) return nice(g)
        return g.ifBlank { "Övrigt" }
    }
}

object Xmltv {
    /** Trådsäker tolkning av XMLTV-tider: "20261024183000 +0200", utan sekunder, med "+02:00" eller utan zon. */
    fun time(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return try {
            val t = s.trim()
            val dg = t.takeWhile { it.isDigit() }
            if (dg.length < 8) return 0L
            val d = dg.padEnd(14, '0')
            val tz = t.drop(dg.length).trim().replace(":", "")
            val off = if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-'))
                ZoneOffset.of(tz.take(3) + ":" + tz.substring(3, 5)) else ZoneOffset.UTC
            LocalDateTime.of(
                d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt(),
                d.substring(8, 10).toInt(), d.substring(10, 12).toInt(), d.substring(12, 14).toInt()
            ).toEpochSecond(off) * 1000
        } catch (e: Exception) { 0L }
    }

    /**
     * Läser bara program för kanaler i [wanted] och bara ca 1 timme bakåt till 12 timmar framåt,
     * så att minnet räcker även för mycket stora guider. Avbryts när [active] blir false.
     * Om filen är trasig mitt i behålls det som redan lästs.
     */
    fun parse(input: InputStream, wanted: Set<String>, active: () -> Boolean): Map<String, List<Programme>> {
        val now = System.currentTimeMillis()
        val from = now - 1 * 3600_000L
        val to = now + 12 * 3600_000L
        val map = HashMap<String, ArrayList<Programme>>()
        try {
            val p = Xml.newPullParser()
            p.setInput(input, null)
            var ch = ""; var st = 0L; var en = 0L; var title = ""; var inProg = false
            var n = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (++n % 4000 == 0 && !active()) throw kotlinx.coroutines.CancellationException()
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "programme" -> {
                            ch = p.getAttributeValue(null, "channel") ?: ""
                            inProg = ch in wanted
                            title = ""
                            if (inProg) {
                                st = time(p.getAttributeValue(null, "start"))
                                en = time(p.getAttributeValue(null, "stop"))
                            }
                        }
                        "title" -> if (inProg && title.isEmpty()) title = runCatching { p.nextText() }.getOrDefault("")
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                    if (inProg && st > 0 && st < to && (en > from || (en == 0L && st > from - 6 * 3600_000L)) && title.isNotEmpty())
                        map.getOrPut(ch) { ArrayList() }.add(Programme(st, en, title.trim()))
                    inProg = false
                }
                ev = p.next()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Trasig XML eller avkortad .gz: använd det som hann läsas.
        }
        // Sortera i stället för att kopiera, och fyll i saknade sluttider från nästa programs start.
        for (list in map.values) {
            list.sortBy { it.start }
            for (i in list.indices) {
                val pr = list[i]
                if (pr.stop <= pr.start) {
                    val next = list.getOrNull(i + 1)?.start ?: (pr.start + 3600_000L)
                    list[i] = pr.copy(stop = next)
                }
            }
            list.trimToSize()
        }
        @Suppress("UNCHECKED_CAST")
        return map as Map<String, List<Programme>>
    }
}

private val flagMap: Map<String, String> by lazy {
    Locale.getISOCountries().associateBy { Locale("", it).getDisplayCountry(SV) }
}

fun flag(name: String): String {
    val c = flagMap[name] ?: return "🌍"
    if (c.length != 2) return "🌍"
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}

/** Förbered flaggtabellen i bakgrunden så att första ritningen inte hackar. */
fun warmFlags() { flagMap.size }
