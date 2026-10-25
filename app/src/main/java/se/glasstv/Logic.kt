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
const val MAX_PROFILES = 5

/** En sparad spellista (server). Upp till [MAX_PROFILES] kan sparas, en i taget används. */
data class Profile(val name: String, val m3u: String, val epg: String)
/** Höjs när sättet att tolka spellistan ändras, så att den sparade kanallistan byggs om. */
const val CACHE_VERSION = 5

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

    var profiles: List<Profile>
        get() = try {
            val a = org.json.JSONArray(prefs.getString("profiles", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Profile(o.optString("name"), o.optString("m3u"), o.optString("epg")) }
        } catch (e: Exception) { emptyList() }
        set(v) {
            val a = org.json.JSONArray()
            v.forEach { a.put(org.json.JSONObject().put("name", it.name).put("m3u", it.m3u).put("epg", it.epg)) }
            prefs.edit().putString("profiles", a.toString()).apply()
        }
    var activeProfile: Int
        get() = prefs.getInt("activeProfile", 0)
        set(v) { prefs.edit().putInt("activeProfile", v).apply() }

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
    /** Hur många sekunder man tittat på varje kanal (nyckel = kanalens adress). */
    var watchStats: Map<String, Long>
        get() = try {
            val o = org.json.JSONObject(prefs.getString("watch", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optLong(it) }
        } catch (e: Exception) { emptyMap() }
        set(v) {
            val o = org.json.JSONObject(); v.forEach { (k, s) -> o.put(k, s) }
            prefs.edit().putString("watch", o.toString()).apply()
        }
    var sortAz: Boolean
        get() = prefs.getBoolean("sortAz", true)
        set(v) { prefs.edit().putBoolean("sortAz", v).apply() }

    private val channelCache = File(ctx.filesDir, "channels.bin")
    private val cacheDir = ctx.cacheDir

    /** Läser versionsnumret för senaste bygget på GitHub, eller null om det inte går. */
    fun latestVersion(): Long? = try {
        val f = File(cacheDir, "version.txt")
        download(UPDATE_BASE + "version.txt", f) { it.trim().firstOrNull()?.isDigit() == true }
        f.readText().trim().toLongOrNull()
    } catch (e: Exception) { null }

    /** Laddar ner senaste GlassTV.apk till appens tillfälliga mapp. */
    fun downloadUpdate(): File {
        val f = File(cacheDir, "update.apk")
        download(UPDATE_BASE + "GlassTV.apk", f) { it.startsWith("PK") }
        return f
    }

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
            if (code != 200) {
                conn.disconnect()
                throw IOException(when {
                    code == 401 || code == 403 -> "inloggningen nekades (fel $code), kontrollera användarnamn och lösenord"
                    code >= 500 -> "leverantörens server svarar inte just nu (fel $code), försök igen senare"
                    else -> "servern svarade $code"
                })
            }
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
    var updateAvailable by mutableStateOf(false)
    val versionLabel = "Version " + Updater.installedName(app)
    var updateStatus by mutableStateOf("")
    private var updating = false

    var updateCheckMsg by mutableStateOf("")

    /** Kollar på GitHub om det finns en nyare version av appen. [manual] = knappen i inställningarna. */
    fun checkUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            if (manual) updateCheckMsg = "Söker efter uppdatering…"
            val latest = withContext(Dispatchers.IO) { repo.latestVersion() }
            if (latest == null) { if (manual) updateCheckMsg = "Kunde inte nå GitHub just nu, försök igen om en stund."; return@launch }
            if (latest > Updater.installedVersion(getApplication())) {
                updateStatus = ""; updateCheckMsg = ""; updateAvailable = true
            } else if (manual) updateCheckMsg = "Du har senaste versionen."
        }
    }

    fun startUpdate() {
        if (updating) return
        val ctx = getApplication<Application>()
        if (!Updater.ensurePermission(ctx)) {
            updateStatus = "Tillåt GlassTV att installera appar, tryck sedan Tillbaka och Uppdatera igen."
            return
        }
        updating = true
        viewModelScope.launch {
            updateStatus = "Laddar ner den nya versionen…"
            try {
                val apk = withContext(Dispatchers.IO) { repo.downloadUpdate() }
                updateStatus = "Installerar…"
                withContext(Dispatchers.IO) { Updater.install(ctx, apk) }
                updateStatus = "Tryck Installera i rutan som visas."
            } catch (e: Exception) {
                updateStatus = "Uppdateringen misslyckades: ${e.message ?: e.javaClass.simpleName}"
            }
            updating = false
        }
    }
    val gridState = LazyGridState()

    val countries by derivedStateOf {
        val f = favorites
        val all = channels.asSequence().map { it.country }.distinct().toList()
        (if (onlyFav && f.isNotEmpty()) all.filter { it in f } else all)
            .sortedWith(compareBy<String>({ it !in f }, { !isCountry(it) }, { it }))
    }
    /** Antal kanaler per land/grupp, som visas i menyn. */
    val countryCounts by derivedStateOf { channels.groupingBy { it.country }.eachCount() }

    val shown by derivedStateOf {
        val s = selected
        val cs = countries
        val base = if (s != null && s in cs) channels.filter { it.country == s }
            else { val set = cs.toHashSet(); channels.filter { it.country in set } }
        if (!Th.sortAz) base
        else {
            // A–Ö inom varje land; länderna ligger kvar i samma ordning som i menyn.
            val order = HashMap<String, Int>(cs.size * 2).also { m -> cs.forEachIndexed { i, c -> m[c] = i } }
            base.map { Triple(order[it.country] ?: Int.MAX_VALUE, sortKey(it.name), it) }
                .sortedWith(compareBy<Triple<Int, String, Channel>>({ it.first }, { it.second }))
                .map { it.third }
        }
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
    /** Kort besked längst ner på skärmen. Slutar texten med "…" ligger den kvar tills nästa besked. */
    var toast by mutableStateOf<String?>(null)
    /** Spellista som skickats från mobilen och väntar på att bekräftas på TV:n. */
    var pendingPlaylist by mutableStateOf<Pair<String, String>?>(null)

    /**
     * Hämtar spellistan på nytt. Tv-guiden hämtas bara om den är gammal, eller om [force] (knappen ↻).
     * [notify] = visa besked på skärmen (när användaren själv bett om det).
     */
    fun refresh(force: Boolean = true, notify: Boolean = false) {
        if (refreshJob?.isActive == true) { if (notify) toast = "Hämtar redan kanaler…"; return }
        refreshJob = viewModelScope.launch {
            status = "Uppdaterar…"
            if (notify) toast = "Hämtar kanaler…"
            val errors = ArrayList<String>()
            var listOk = false
            if (repo.m3uUrl.isNotBlank()) try {
                val list = withContext(Dispatchers.IO) { repo.downloadPlaylist() }
                if (list.isEmpty()) errors += "spellistan var tom" else { listOk = true; if (list != channels) channels = list }
            } catch (e: Exception) { errors += "spellistan: ${e.message ?: e.javaClass.simpleName}" }
            var epgFailed = false
            if (repo.epgUrl.isNotBlank() && (force || !repo.epgFresh())) {
                if (notify && listOk) toast = "✓ ${channels.size} kanaler hämtade – hämtar tv-guiden…"
                try {
                    withContext(Dispatchers.IO) { repo.downloadEpg() }
                } catch (e: Exception) { epgFailed = true; errors += "tv-guiden: ${e.message ?: e.javaClass.simpleName}" }
            }
            status = if (errors.isEmpty()) "" else "Kunde inte uppdatera " + errors.joinToString(", ")
            if (notify) toast = when {
                listOk && !epgFailed -> "✓ ${channels.size} kanaler hämtade" + if (repo.epgUrl.isNotBlank()) " med tv-guide" else ""
                listOk -> "✓ ${channels.size} kanaler hämtade (tv-guiden kunde inte hämtas just nu)"
                else -> "Kunde inte hämta kanalerna: " + errors.first().substringAfter(": ")
            }
            reloadEpg()
        }
    }

    var profiles by mutableStateOf(repo.profiles); private set
    var activeProfile by mutableIntStateOf(repo.activeProfile); private set
    /** Vilken spellista inställningsrutan redigerar: index, -1 = ny, null = (första start) ny. */
    var editProfile by mutableStateOf<Int?>(null)

    private fun profileName(m3u: String): String =
        Xtream.parse(m3u)?.let { it.first.substringAfter("://") + " · " + it.second }
            ?: runCatching { java.net.URL(m3u).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Spellista"

    /** Sparar en spellista. Ny spellista, eller ändring av den som används, laddas direkt. */
    fun saveProfile(index: Int, m3u: String, epg: String) {
        val p = Profile(profileName(m3u), m3u, epg)
        val list = profiles.toMutableList()
        val isNew = index !in list.indices
        val i = when {
            !isNew -> { list[index] = p; index }
            list.size < MAX_PROFILES -> { list += p; list.lastIndex }
            else -> { val a = activeProfile.coerceIn(list.indices); list[a] = p; a }
        }
        profiles = list; repo.profiles = list
        if (isNew || i == activeProfile) useProfile(i)
    }

    /** Byter till spellista [i] och hämtar dess kanaler. */
    fun useProfile(i: Int) {
        val p = profiles.getOrNull(i) ?: return
        activeProfile = i; repo.activeProfile = i
        saveUrls(p.m3u, p.epg)
    }

    fun deleteProfile(i: Int) {
        if (i !in profiles.indices) return
        val list = profiles.toMutableList().also { it.removeAt(i) }
        profiles = list; repo.profiles = list
        when {
            list.isEmpty() -> { activeProfile = 0; repo.activeProfile = 0 }
            i == activeProfile -> useProfile(0)
            i < activeProfile -> { activeProfile -= 1; repo.activeProfile = activeProfile }
        }
    }

    fun saveUrls(m3u: String, epgUrl: String) {
        repo.m3uUrl = m3u; repo.epgUrl = epgUrl
        refreshJob?.cancel()
        refreshJob = null
        refresh(notify = true)
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

    var watchStats by mutableStateOf(repo.watchStats); private set
    private var watching: Channel? = null
    private var watchStart = 0L

    private fun startWatch(c: Channel) { endWatch(); watching = c; watchStart = System.currentTimeMillis() }

    /** Lägger till tittartiden för kanalen man just lämnat. Under 15 sekunder (zappning) räknas inte. */
    private fun endWatch() {
        val c = watching ?: return
        watching = null
        val secs = ((System.currentTimeMillis() - watchStart) / 1000).coerceIn(0L, 4L * 3600)
        if (secs < 15) return
        val m = watchStats.toMutableMap()
        m[c.url] = (m[c.url] ?: 0L) + secs
        val kept = if (m.size > 300) m.entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value } else m
        watchStats = kept; repo.watchStats = kept
    }

    /** Upp till 10 mest sedda kanaler bland dem som visas just nu (minst en minuts tittande). */
    val topChannels by derivedStateOf {
        val st = watchStats
        if (st.isEmpty()) emptyList()
        else shown.filter { (st[it.url] ?: 0L) >= 60 }.sortedByDescending { st[it.url] ?: 0L }.take(10)
    }

    fun open(c: Channel) { preview(c); quality(true); player.volume = 1f; fullscreen = true; startWatch(c) }

    fun exitFull() {
        endWatch()
        fullscreen = false; player.volume = 0f; quality(false)
        val p = playing
        focused = p
        if (!Th.preview) player.stop()
        // Raden "Mest sedda" ligger först i rutnätet, så kanalernas plats är en steg längre ned när den visas.
        val i = if (p == null) -1 else shown.indexOfFirst { it.uid == p.uid }.let { if (it >= 0 && topChannels.isNotEmpty()) it + 1 else it }
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
        if (fullscreen) startWatch(l[n])
    }

    fun onPreviewSetting(on: Boolean) { if (!on && !fullscreen) player.stop() }

    fun onStart() {
        foreground = true
        if (playing != null && (fullscreen || Th.preview)) runCatching { player.seekToDefaultPosition(); player.prepare(); player.play() }
        if (fullscreen) playing?.let { startWatch(it) }
    }
    fun onStop() { endWatch(); foreground = false; retryJob?.cancel(); player.stop() }

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
        Th.theme = repo.theme; Th.accent = repo.accent; Th.anim = repo.anim; Th.preview = repo.preview; Th.sortAz = repo.sortAz
        // Den spellista man redan hade blir spellista nummer 1.
        if (profiles.isEmpty() && repo.m3uUrl.isNotBlank()) {
            val p = listOf(Profile(profileName(repo.m3uUrl), repo.m3uUrl, repo.epgUrl))
            profiles = p; repo.profiles = p; activeProfile = 0; repo.activeProfile = 0
        }
        viewModelScope.launch { delay(6000); checkUpdate() }
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
