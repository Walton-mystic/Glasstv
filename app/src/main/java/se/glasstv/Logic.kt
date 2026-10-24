package se.glasstv

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

const val UA = "Mozilla/5.0 (Linux; Android 12) GlassTV/1.0"
/** Höjs när sättet att tolka spellistan ändras, så att den sparade kanallistan byggs om. */
const val CACHE_VERSION = 2

class Repo(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("glasstv", Context.MODE_PRIVATE)
    private val playlistFile = File(ctx.filesDir, "playlist.m3u")
    private val epgFile = File(ctx.filesDir, "epg.xml")

    var m3uUrl: String
        get() = prefs.getString("m3u", "") ?: ""
        set(v) { prefs.edit().putString("m3u", v).apply() }
    var epgUrl: String
        get() = prefs.getString("epg", "") ?: ""
        set(v) { prefs.edit().putString("epg", v).apply() }
    var favorites: Set<String>
        get() = prefs.getStringSet("fav", emptySet())?.toSet() ?: emptySet()
        set(v) { prefs.edit().putStringSet("fav", v).apply() }
    var onlyFav: Boolean
        get() = prefs.getBoolean("onlyFav", false)
        set(v) { prefs.edit().putBoolean("onlyFav", v).apply() }

    var theme: Int
        get() = prefs.getInt("theme", 0)
        set(v) { prefs.edit().putInt("theme", v).apply() }
    var accent: Int
        get() = prefs.getInt("accent", 0)
        set(v) { prefs.edit().putInt("accent", v).apply() }
    var anim: Boolean
        get() = prefs.getBoolean("anim", true)
        set(v) { prefs.edit().putBoolean("anim", v).apply() }
    var preview: Boolean
        get() = prefs.getBoolean("pv", true)
        set(v) { prefs.edit().putBoolean("pv", v).apply() }

    private val channelCache = File(ctx.filesDir, "channels.bin")

    /**
     * Kanallistan sparas färdigtolkad, så att appen startar direkt utan att läsa om hela spellistan.
     * Bara om spellistan är nyare än den sparade listan (eller formatet ändrats) tolkas den om.
     */
    fun cachedChannels(): List<Channel> {
        if (!playlistFile.exists()) return emptyList()
        if (channelCache.exists() && channelCache.lastModified() >= playlistFile.lastModified())
            readCache()?.let { return it }
        val list = try { playlistFile.bufferedReader().useLines { M3u.parse(it) } } catch (e: Exception) { emptyList() }
        if (list.isNotEmpty()) writeCache(list)
        return list
    }

    private fun writeCache(list: List<Channel>) {
        try {
            val tmp = File.createTempFile("channels", ".tmp", channelCache.parentFile)
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { o ->
                o.writeInt(CACHE_VERSION); o.writeInt(list.size)
                for (c in list) {
                    o.writeInt(c.uid); o.writeUTF(c.id.take(2000)); o.writeUTF(c.name.take(2000)); o.writeUTF(c.url.take(8000))
                    o.writeUTF(c.logo?.take(4000) ?: ""); o.writeUTF(c.country.take(500)); o.writeUTF(c.group.take(500))
                }
            }
            if (!tmp.renameTo(channelCache)) { tmp.copyTo(channelCache, overwrite = true); tmp.delete() }
        } catch (e: Exception) { channelCache.delete() }
    }

    private fun readCache(): List<Channel>? {
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(channelCache), 64 * 1024)).use { i ->
                if (i.readInt() != CACHE_VERSION) return null
                val n = i.readInt()
                if (n < 0 || n > 2_000_000) return null
                val out = ArrayList<Channel>(n)
                repeat(n) {
                    out += Channel(i.readInt(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF().ifEmpty { null }, i.readUTF(), i.readUTF())
                }
                out
            }
        } catch (e: Exception) { null }
    }

    fun hasPlaylist() = playlistFile.exists() && playlistFile.length() > 0
    private fun fresh(f: File) = f.exists() && System.currentTimeMillis() - f.lastModified() < 12 * 3600_000L
    fun playlistFresh() = fresh(playlistFile)
    fun epgFresh() = fresh(epgFile)

    private fun stripBom(h: String) = h.removePrefix("\u00EF\u00BB\u00BF").removePrefix("\uFEFF")

    fun downloadPlaylist(): List<Channel> {
        download(m3uUrl, playlistFile) { head -> stripBom(head).trimStart().startsWith("#EXTM3U", true) }
        return cachedChannels()
    }

    fun downloadEpg() = download(epgUrl, epgFile) { head ->
        val b = head.toByteArray(Charsets.ISO_8859_1)
        (b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) || stripBom(head).trimStart().startsWith("<")
    }

    fun importStream(i: InputStream) {
        val tmp = File.createTempFile("import", ".tmp", playlistFile.parentFile)
        tmp.outputStream().use { o -> i.copyTo(o) }
        if (!tmp.renameTo(playlistFile)) { tmp.copyTo(playlistFile, overwrite = true); tmp.delete() }
    }

    /**
     * Laddar ner till en tillfällig fil och ersätter den gamla först när svaret ser rätt ut.
     * Följer omdirigeringar mellan http och https själv, och kräver status 200.
     * En felsida från leverantören ("konto utgånget") skriver alltså aldrig över en fungerande lista.
     */
    /**
     * Många IPTV-servrar har bara http på sin port (t.ex. :8080). Är länken skriven med https
     * och servern inte svarar med TLS, provas samma adress automatiskt med http.
     */
    private fun download(start: String, dest: File, looksValid: (String) -> Boolean) {
        try {
            downloadOnce(start, dest, looksValid)
        } catch (e: javax.net.ssl.SSLException) {
            if (!start.startsWith("https://", ignoreCase = true)) throw e
            downloadOnce("http://" + start.substring(8), dest, looksValid)
        }
    }

    private fun downloadOnce(start: String, dest: File, looksValid: (String) -> Boolean) {
        var url = URL(start)
        var c: HttpURLConnection? = null
        for (hop in 0 until 6) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            val (code, loc) = try {
                val rc = conn.responseCode
                rc to (if (rc in 300..399) conn.getHeaderField("Location") ?: throw IOException("Omdirigering utan adress") else null)
            } catch (e: IOException) { conn.disconnect(); throw e }
            if (loc != null) {
                conn.disconnect()
                url = URL(url, loc)
                continue
            }
            if (code != 200) { conn.disconnect(); throw IOException("Servern svarade $code") }
            c = conn
            break
        }
        val conn = c ?: throw IOException("För många omdirigeringar")
        val tmp = File.createTempFile(dest.name, ".tmp", dest.parentFile)
        try {
            BufferedInputStream(conn.inputStream, 64 * 1024).use { i ->
                i.mark(64)
                val head = ByteArray(64)
                var n = 0
                while (n < 64) { val r = i.read(head, n, 64 - n); if (r < 0) break; n += r }
                i.reset()
                if (!looksValid(String(head, 0, n, Charsets.ISO_8859_1)))
                    throw IOException("Svaret är inte en giltig fil")
                tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
            }
        } catch (e: Exception) {
            tmp.delete(); throw e
        } finally { conn.disconnect() }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
    }

    private val crashFile = File(ctx.filesDir, "crash.txt")
    /** Läser och tar bort kraschloggen från förra körningen, om det finns någon. */
    fun takeCrash(): String? = try {
        if (crashFile.exists()) crashFile.readText().also { crashFile.delete() } else null
    } catch (e: Exception) { null }

    fun loadEpg(ids: Set<String>, active: () -> Boolean): Map<String, List<Programme>> {
        if (!epgFile.exists() || ids.isEmpty()) return emptyMap()
        return try {
            val b = BufferedInputStream(FileInputStream(epgFile), 64 * 1024)
            b.mark(2); val a = b.read(); val c = b.read(); b.reset()
            val s: InputStream = if (a == 0x1f && c == 0x8b) GZIPInputStream(b, 64 * 1024) else b
            s.use { Xmltv.parse(it, ids, active) }
        } catch (e: CancellationException) { throw e
        } catch (e: OutOfMemoryError) { emptyMap()
        } catch (e: Exception) { emptyMap() }
    }
}

@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class MainVm(app: Application) : AndroidViewModel(app) {
    val repo = Repo(app)

    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var epg by mutableStateOf<Map<String, List<Programme>>>(emptyMap()); private set
    var favorites by mutableStateOf(repo.favorites); private set
    var onlyFav by mutableStateOf(repo.onlyFav); private set
    var selected by mutableStateOf<String?>(null); private set
    var focused by mutableStateOf<Channel?>(null)
    var status by mutableStateOf("")
    var loading by mutableStateOf(true); private set
    var showSettings by mutableStateOf(repo.m3uUrl.isBlank() && !repo.hasPlaylist())
    var showPrefs by mutableStateOf(false)
    var fullscreen by mutableStateOf(false); private set
    var playing by mutableStateOf<Channel?>(null); private set
    /** uid för raden som ska få fokus (efter helskärm eller vid start). */
    var focusUid by mutableStateOf<Int?>(null)
    var initialFocusDone = false
    var lastCrash by mutableStateOf(repo.takeCrash())
    val gridState = LazyGridState()

    val countries by derivedStateOf {
        val f = favorites
        val all = channels.asSequence().map { it.country }.distinct().toList()
        (if (onlyFav && f.isNotEmpty()) all.filter { it in f } else all)
            .sortedWith(compareBy<String>({ it !in f }, { !isCountry(it) }, { it }))
    }
    val shown by derivedStateOf {
        val s = selected
        val cs = countries
        if (s != null && s in cs) channels.filter { it.country == s }
        else { val set = cs.toHashSet(); channels.filter { it.country in set } }
    }

    private val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(UA)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http)))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 20_000, 1_000, 2_000)
                .setTargetBufferBytes(24 shl 20)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        )
        .build().apply {
            playWhenReady = true
            volume = 0f
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { scheduleRetry() }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) { retries = 0; if (status.startsWith("Kanalen")) status = "" }
                }
            })
        }

    private var retries = 0
    private var retryJob: Job? = null
    private var foreground = true
    private fun scheduleRetry() {
        retryJob?.cancel()
        if (!foreground) return
        if (retries >= 5) { status = "Kanalen svarar inte just nu"; return }
        val wait = longArrayOf(3_000, 5_000, 10_000, 20_000, 30_000)[retries++]
        retryJob = viewModelScope.launch {
            delay(wait)
            runCatching { player.seekToDefaultPosition(); player.prepare() }
        }
    }

    private var refreshJob: Job? = null
    /** Hämtar spellistan på nytt. Tv-guiden hämtas bara om den är gammal, eller om [force] (knappen ↻). */
    fun refresh(force: Boolean = true) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            status = "Uppdaterar…"
            val errors = ArrayList<String>()
            if (repo.m3uUrl.isNotBlank()) try {
                val list = withContext(Dispatchers.IO) { repo.downloadPlaylist() }
                if (list.isEmpty()) errors += "spellistan var tom" else if (list != channels) channels = list
            } catch (e: Exception) { errors += "spellistan: ${e.message ?: e.javaClass.simpleName}" }
            if (repo.epgUrl.isNotBlank() && (force || !repo.epgFresh())) try {
                withContext(Dispatchers.IO) { repo.downloadEpg() }
            } catch (e: Exception) { errors += "tv-guiden: ${e.message ?: e.javaClass.simpleName}" }
            status = if (errors.isEmpty()) "" else "Kunde inte uppdatera " + errors.joinToString(", ")
            reloadEpg()
        }
    }

    fun saveUrls(m3u: String, epgUrl: String) {
        repo.m3uUrl = m3u; repo.epgUrl = epgUrl
        refreshJob?.cancel()
        refresh()
    }

    /** En guide-tolkning åt gången; en ny avbryter den gamla på riktigt. */
    private val epgDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var epgJob: Job? = null
    private fun reloadEpg() {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            delay(800)
            val chans = channels
            val cs = countries.toHashSet()
            val result = withContext(epgDispatcher) {
                val ids = chans.filter { it.country in cs && it.id.isNotEmpty() }.mapTo(HashSet()) { it.id }
                repo.loadEpg(ids) { isActive }
            }
            epg = result
        }
    }

    fun toggleFav(c: String) {
        favorites = if (c in favorites) favorites - c else favorites + c
        repo.favorites = favorites
        if (selected != null && selected !in countries) selected = null
        reloadEpg()
    }
    fun toggleOnlyFav() { onlyFav = !onlyFav; repo.onlyFav = onlyFav; select(null); reloadEpg() }
    fun select(c: String?) {
        selected = c; focused = null
        viewModelScope.launch { runCatching { gridState.scrollToItem(0) } }
    }

    private fun quality(full: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (full) clearVideoSizeConstraints() else setMaxVideoSize(854, 480)
        }.build()
    }

    fun preview(c: Channel) {
        if (playing?.url == c.url) {
            if (player.playbackState == Player.STATE_IDLE) runCatching { player.prepare() }
            return
        }
        playing = c
        retries = 0; retryJob?.cancel()
        try {
            player.setMediaItem(MediaItem.fromUri(c.url))
            player.prepare()
        } catch (e: Exception) {
            status = "Kan inte spela den här kanalen"
        }
    }

    /** Stoppa förhandsvisningen när man bläddrar vidare, så att avkodaren inte arbetar i onödan. */
    fun stopPreview() {
        retryJob?.cancel()
        if (!fullscreen && player.playbackState != Player.STATE_IDLE) player.stop()
    }

    fun open(c: Channel) { preview(c); quality(true); player.volume = 1f; fullscreen = true }

    fun exitFull() {
        fullscreen = false; player.volume = 0f; quality(false)
        val p = playing
        focused = p
        if (!Th.preview) player.stop()
        val i = if (p == null) -1 else shown.indexOfFirst { it.uid == p.uid }
        viewModelScope.launch {
            if (i >= 0) runCatching { gridState.scrollToItem(i, -200) }
            focusUid = p?.uid
            delay(700)
            focusUid = null
        }
    }

    fun step(d: Int) {
        val l = shown
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.uid == playing?.uid }
        val n = if (i < 0) (if (d > 0) 0 else l.lastIndex) else (i + d + l.size) % l.size
        preview(l[n])
    }

    fun onPreviewSetting(on: Boolean) { if (!on && !fullscreen) player.stop() }

    fun onStart() {
        foreground = true
        if (playing != null && (fullscreen || Th.preview)) runCatching { player.seekToDefaultPosition(); player.prepare(); player.play() }
    }
    fun onStop() { foreground = false; retryJob?.cancel(); player.stop() }

    var pickFile: () -> Unit = {}
    fun importFile(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { repo.importStream(it) }
                    repo.cachedChannels()
                }
                if (list.isEmpty()) { status = "Filen innehöll inga kanaler"; return@launch }
                repo.m3uUrl = ""
                channels = list
                showSettings = false; status = ""; reloadEpg()
            } catch (e: Exception) { status = "Kunde inte läsa filen" }
        }
    }

    // Körs sist, när alla fält ovan redan finns (viewModelScope startar direkt på huvudtråden).
    init {
        Th.theme = repo.theme; Th.accent = repo.accent; Th.anim = repo.anim; Th.preview = repo.preview
        viewModelScope.launch {
            launch(Dispatchers.Default) { warmFlags() }
            channels = withContext(Dispatchers.IO) { repo.cachedChannels() }
            loading = false
            reloadEpg()
            // Den sparade listan visas direkt. Efter en kort stund letas nya kanaler upp i bakgrunden.
            if (repo.m3uUrl.isNotBlank() || repo.epgUrl.isNotBlank()) {
                delay(if (channels.isEmpty()) 0L else 4000L)
                refresh(force = false)
            }
        }
    }

    override fun onCleared() { retryJob?.cancel(); player.release() }
}
