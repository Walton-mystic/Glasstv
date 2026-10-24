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

/** Liten webbserver på TV:n: mobilen öppnar sidan via QR-koden och skickar in länken. */
class PairServer(private val onSave: (String, String) -> Unit) {
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
            if (m.isNotEmpty()) { onSave(m, f["epg"].orEmpty().trim()); body = DONE }
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        c.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    companion object {
        private const val CSS = "body{font-family:system-ui,sans-serif;background:#0b1020;color:#fff;margin:0;padding:24px}input,button{width:100%;box-sizing:border-box;font-size:17px;padding:14px;border-radius:14px;border:1px solid #ffffff44;background:#ffffff14;color:#fff;margin:6px 0 16px}button{background:linear-gradient(135deg,#7aa2ff,#b14bff);border:0;font-weight:600}label{font-size:12px;letter-spacing:.1em;color:#ffffff99}"
        private const val FORM = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>$CSS</style><h2>GlassTV</h2><form method=post><label>M3U-LÄNK</label><input name=m3u type=url required autofocus><label>TV-GUIDE (XMLTV) – VALFRI</label><input name=epg type=url><button>Skicka till TV:n</button></form>"
        private const val DONE = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>$CSS</style><h2>Klart ✓</h2><p>Titta på TV:n, kanalerna laddas nu.</p>"
    }
}
