package se.glasstv

import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** Hämtar TV:ns adress på hemnätet, med wifi och kabel före VPN eller andra gränssnitt. */
fun localIp(): String? = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .sortedBy { n -> val nm = n.name; if (nm.startsWith("wlan") || nm.startsWith("eth")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
} catch (e: Exception) { null }

/** Bygger M3U- och tv-guidelänk av en Xtream-inloggning (server, användarnamn, lösenord). */
object Xtream {
    fun host(raw: String): String {
        var h = raw.trim().trimEnd('/')
        if (h.isEmpty()) return ""
        if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://" + h
        return h
    }
    private fun enc(s: String) = java.net.URLEncoder.encode(s.trim(), "UTF-8")
    fun m3u(h: String, u: String, p: String) = host(h) + "/get.php?username=" + enc(u) + "&password=" + enc(p) + "&type=m3u_plus&output=ts"
    fun epg(h: String, u: String, p: String) = host(h) + "/xmltv.php?username=" + enc(u) + "&password=" + enc(p)

    /** Inloggning ur ett meddelande från leverantören: första adressen är huvudservern, resten reserver. */
    data class Info(val hosts: List<String>, val user: String, val pass: String) {
        val m3u get() = Xtream.m3u(hosts[0], user, pass)
        val epg get() = Xtream.epg(hosts[0], user, pass)
        val alts get() = hosts.drop(1).joinToString("\n")
    }
    private val urlRx = Regex("""https?://[^\s|,;"'<>]+""", RegexOption.IGNORE_CASE)
    private val userRx = Regex("""(?i)\buser(?:name)?\b\s*[=:]\s*([^\s|,;&]+)""")
    private val passRx = Regex("""(?i)\bpass(?:word)?\b\s*[=:]\s*([^\s|,;&]+)""")

    /** Sant om texten ser ut som ett helt meddelande (med användarnamn och lösenord), inte bara en adress. */
    fun looksLikeMessage(t: String) = userRx.containsMatchIn(t) && passRx.containsMatchIn(t)

    /**
     * Tolkar t.ex. "XC API = http://server.xyz || Username = abc || Password = 123 || Alternative URL 1 = http://reserv.xyz".
     * Adresser märkta VPN hoppas över, eftersom de bara fungerar via VPN.
     */
    fun fromText(t: String): Info? {
        val user = userRx.find(t)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
        val pass = passRx.find(t)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
        val hosts = t.split("||", "\n").filterNot { it.contains("vpn", ignoreCase = true) }
            .flatMap { seg -> urlRx.findAll(seg).map { it.value.trimEnd('/', '.') }.toList() }
            .mapNotNull { runCatching { val u = java.net.URL(it); u.protocol + "://" + u.host + (if (u.port > 0) ":" + u.port else "") }.getOrNull() }
            .distinct()
        if (hosts.isEmpty()) return null
        return Info(hosts, user, pass)
    }

    /** Läser tillbaka server, användarnamn och lösenord ur en sparad Xtream-länk, annars null. */
    fun parse(url: String): Triple<String, String, String>? {
        return try {
            val u = java.net.URL(url)
            if (!u.path.endsWith("get.php")) return null
            val q = (u.query ?: return null).split("&").associate {
                val kv = it.split("=", limit = 2); kv[0] to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val user = q["username"] ?: return null
            val pass = q["password"] ?: return null
            Triple(u.protocol + "://" + u.host + (if (u.port > 0) ":" + u.port else ""), user, pass)
        } catch (e: Exception) { null }
    }
}

/** Liten webbserver på TV:n: mobilen öppnar sidan via QR-koden och skickar in länken. */
class PairServer(private val onSave: (String, String, String) -> Unit) {
    val port = 8765
    private var ss: ServerSocket? = null

    /** Binder porten direkt (inte i tråden), så att stop() alltid kan stänga den. Returnerar false om porten är upptagen. */
    fun start(): Boolean {
        val s = try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } } catch (e: IOException) { null }
            ?: return false
        ss = s
        thread(isDaemon = true, name = "glasstv-pair") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                runCatching { handle(c) }; runCatching { c.close() }
            }
        }
        return true
    }
    fun stop() { runCatching { ss?.close() } }

    private fun handle(c: Socket) {
        c.soTimeout = 5000
        val inp = c.getInputStream().buffered()
        fun line(): String {
            val sb = StringBuilder()
            while (true) { val b = inp.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }
            return sb.toString()
        }
        val req = line(); var len = 0
        while (true) { val h = line(); if (h.isEmpty()) break; if (h.startsWith("Content-Length", true)) len = h.substringAfter(":").trim().toInt() }
        var body = FORM
        if (req.startsWith("POST") && len in 1..20000) {
            val buf = ByteArray(len); var r = 0
            while (r < len) { val n = inp.read(buf, r, len - r); if (n < 0) break; r += n }
            val f = String(buf, 0, r, Charsets.UTF_8).split("&").associate {
                val kv = it.split("=", limit = 2); kv[0] to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val m = f["m3u"].orEmpty().trim()
            val xh = f["host"].orEmpty().trim(); val xu = f["user"].orEmpty().trim(); val xp = f["pass"].orEmpty().trim()
            // Hela meddelandet från leverantören kan klistras in i rutan överst (eller i vilket fält som helst).
            val pasted = listOf(f["text"].orEmpty(), xh, m).firstOrNull { Xtream.looksLikeMessage(it) }
            if (pasted != null) {
                val x = Xtream.fromText(pasted)
                if (x != null) { onSave(x.m3u, x.epg, x.alts); body = DONE } else body = BAD
            }
            else if (m.isNotEmpty()) { onSave(m, f["epg"].orEmpty().trim(), ""); body = DONE }
            else if (xh.isNotEmpty() && xu.isNotEmpty() && xp.isNotEmpty()) { onSave(Xtream.m3u(xh, xu, xp), Xtream.epg(xh, xu, xp), ""); body = DONE }
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        c.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    companion object {
        private const val CSS = "body{font-family:system-ui,sans-serif;background:#0b1020;color:#fff;margin:0;padding:24px}input,textarea,button{width:100%;box-sizing:border-box;font-size:17px;padding:14px;border-radius:14px;border:1px solid #ffffff44;background:#ffffff14;color:#fff;margin:6px 0 16px}button{background:linear-gradient(135deg,#7aa2ff,#b14bff);border:0;font-weight:600}label{font-size:12px;letter-spacing:.1em;color:#ffffff99}h3{margin:18px 0 8px;font-size:16px}"
        private const val FORM = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>$CSS</style><h2>GlassTV</h2><form method=post autocomplete=off><h3>Klistra in meddelandet från leverantören</h3><label>HELA TEXTEN MED SERVER, USERNAME OCH PASSWORD</label><textarea name=text rows=5 autocapitalize=off autocorrect=off placeholder='Xtream Code (XC) API = http://… || Username = … || Password = …'></textarea><button>Skicka till TV:n</button><h3>eller Xtream-inloggning</h3><label>SERVER (HOST)</label><input name=host inputmode=url autocapitalize=off autocorrect=off placeholder='http://server.top:8080'><label>ANVÄNDARNAMN</label><input name=user autocapitalize=off autocorrect=off><label>LÖSENORD</label><input name=pass autocapitalize=off autocorrect=off><h3>eller M3U-länk</h3><label>M3U-LÄNK</label><input name=m3u type=url><label>TV-GUIDE (XMLTV) – VALFRI</label><input name=epg type=url><button>Skicka till TV:n</button></form>"
        private const val BAD = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>$CSS</style><h2>Hittade ingen inloggning</h2><p>Texten behöver innehålla en serveradress (http://…), Username och Password. Gå tillbaka och prova igen.</p>"
        private const val DONE = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>$CSS</style><h2>Skickat ✓</h2><p>Välj på TV:n om den ska <b>ersätta</b> din spellista eller <b>sparas som ny</b>.</p>"
    }
}
