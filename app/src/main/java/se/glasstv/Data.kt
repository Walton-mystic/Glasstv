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
        var info: String? = null
        var uid = 0
        for (raw in lines) {
            val l = raw.trim().removePrefix("﻿")
            if (l.startsWith("#EXTINF")) {
                info = l
            } else if (l.isNotEmpty() && !l.startsWith("#") && info != null) {
                val inf = info
                info = null
                // Filmer och serier hoppas över innan raden tolkas, så stora listor går snabbt.
                if (isVod(l)) continue
                val m = attr.findAll(inf).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                // Namnet står efter första kommatecknet som kommer efter sista attributet
                val name = inf.substring(maxOf(inf.lastIndexOf('"'), 0)).substringAfter(',', "").trim()
                val group = m["group-title"].orEmpty()
                val code = m["tvg-country"]?.split(';', ',')?.firstOrNull()?.trim().orEmpty()
                out += Channel(
                    uid = uid++,
                    id = m["tvg-id"].orEmpty(),
                    name = name.ifEmpty { m["tvg-name"].orEmpty() }.ifEmpty { "Kanal $uid" },
                    url = l,
                    logo = m["tvg-logo"]?.takeIf { it.isNotBlank() },
                    country = country(code, group, name),
                    group = group
                )
            }
        }
        return out
    }

    private fun nice(code: String): String = names.getOrPut(code.uppercase()) {
        Locale("", code.uppercase()).getDisplayCountry(SV).ifBlank { code.uppercase() }
    }

    /** Vanliga förkortningar i IPTV-listor som inte är ISO-koder. */
    private val alias = mapOf(
        "CRO" to "HR", "GER" to "DE", "SWE" to "SE", "SVE" to "SE", "SW" to "SE", "DEN" to "DK", "DAN" to "DK",
        "NOR" to "NO", "SPA" to "ES", "ENG" to "GB", "UK" to "GB", "POR" to "PT", "SUI" to "CH", "NED" to "NL",
        "HOL" to "NL", "SLO" to "SI", "SER" to "RS", "SRB" to "RS", "BIH" to "BA", "MNE" to "ME", "ALB" to "AL",
        "GRE" to "GR", "EL" to "GR", "ROM" to "RO", "BUL" to "BG", "IRE" to "IE", "KSA" to "SA",
        "UAE" to "AE", "CHI" to "CL", "LIT" to "LT", "FIN" to "FI", "ICE" to "IS", "ISL" to "IS",
        "AUT" to "AT", "BEL" to "BE", "CZE" to "CZ", "SVK" to "SK", "HUN" to "HU", "POL" to "PL", "TUR" to "TR",
        "RUS" to "RU", "UKR" to "UA", "USA" to "US", "CAN" to "CA", "MEX" to "MX", "BRA" to "BR", "IND" to "IN",
        "PAK" to "PK", "AFG" to "AF", "IRN" to "IR", "IRQ" to "IQ", "KUR" to "IQ", "ITA" to "IT", "FRA" to "FR"
    )
    /** Kortnamn som inte är länder men ändå hör ihop, t.ex. arabiska kanaler märkta "AR:". */
    private val labels = mapOf("AR" to "Arabiska", "ARA" to "Arabiska", "ARAB" to "Arabiska", "EXYU" to "Ex-Yu")
    /** Ord som ser ut som landskoder men inte är det. */
    private val notCountry = setOf("TV", "HD", "FHD", "UHD", "SD", "VIP", "PPV", "EU", "NEW", "ALL", "LIVE", "VOD", "XXX", "MY", "THE")
    private val isoSet by lazy { Locale.getISOCountries().toHashSet() }
    private val iso3 by lazy {
        Locale.getISOCountries().associateBy { runCatching { Locale("", it).isO3Country.uppercase() }.getOrDefault("") }
    }
    private val english by lazy { Locale.getISOCountries().associateBy { Locale("", it).getDisplayCountry(Locale.ENGLISH).uppercase() } }
    private val prefix = Regex("""^\W*([A-Za-z]{2,4})\s*[:|\-]\s*""")

    /** Gör om "CRO", "se", "SWE" eller "Croatia" till en ISO-kod, eller null om det inte är ett land. */
    private fun iso(raw: String): String? {
        val u = raw.trim().uppercase()
        if (u.isEmpty() || u in notCountry) return null
        alias[u]?.let { return it }
        if (u.length == 2 && u in isoSet) return u
        if (u.length == 3) iso3[u]?.let { return it }
        return english[u]
    }

    /**
     * Landet hämtas i första hand från tvg-country, sedan från början av kanalnamnet ("CRO: Mini TV"),
     * sedan från gruppnamnet ("EURO | Croatia"). Hittas inget land används gruppnamnet som det är.
     */
    private fun country(code: String, group: String, name: String): String {
        if (code.length == 2 && code.uppercase() in isoSet) return nice(code)
        iso(code)?.let { return nice(it) }
        prefix.find(name)?.groupValues?.get(1)?.uppercase()?.let { p ->
            labels[p]?.let { return it }
            iso(p)?.let { return nice(it) }
        }
        for (part in group.split('|', ':', '-', '/', '(', ')')) iso(part)?.let { return nice(it) }
        return groupLabel(group)
    }

    /** Regionsord som leverantörer sätter före gruppen, t.ex. "EURO | EX-YU". */
    private val regions = setOf("EURO", "EUROPE", "ASIA", "LAME", "NAME", "AFRICA", "LATAM", "WORLD", "INT", "INTERNATIONAL")
    private val pretty = mapOf(
        "EX-YU" to "Ex-Yu", "EXYU" to "Ex-Yu", "ARABIC" to "Arabiska", "ARAB" to "Arabiska",
        "EURO" to "Europa", "EUROPE" to "Europa", "ASIA" to "Asien", "AFRICA" to "Afrika",
        "LAME" to "Latinamerika", "LATAM" to "Latinamerika", "NAME" to "Nordamerika", "CARIBBEAN" to "Karibien"
    )

    /** "EURO | EX-YU" blir "Ex-Yu", "ASIA | KURDISTAN" blir "Kurdistan". */
    private fun groupLabel(group: String): String {
        val parts = group.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val label = parts.lastOrNull { it.uppercase() !in regions } ?: parts.firstOrNull() ?: return "Övrigt"
        pretty[label.uppercase()]?.let { return it }
        // Bara bokstäver med versaler, t.ex. "CARIBBEAN", görs om till "Caribbean". "PPV" och "XXX-VIP [18+]" lämnas.
        return if (label.length > 3 && label.all { it.isLetter() || it == ' ' } && label == label.uppercase())
            label.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        else label
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

/** Sant för riktiga länder (de som har en flagga), falskt för grupper som "EURO". */
fun isCountry(name: String): Boolean = flagMap.containsKey(name)

/** Förbered flaggtabellen i bakgrunden så att första ritningen inte hackar. */
fun warmFlags() { flagMap.size }
