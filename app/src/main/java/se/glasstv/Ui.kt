@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package se.glasstv

import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private fun hm(ms: Long) = DateFormat.format("HH:mm", ms).toString()
object Th {
    var theme by mutableIntStateOf(0)      // 0 mörkt, 1 ljust, 2 auto
    var accent by mutableIntStateOf(0)
    var anim by mutableStateOf(true)
    var preview by mutableStateOf(true)
    var sysLight by mutableStateOf(false)
    val light: Boolean get() = theme == 1 || (theme == 2 && sysLight)
}
private val ACC = listOf(
    Color(0xFF7AA2FF) to Color(0xFFB14BFF), Color(0xFFB78BFF) to Color(0xFFFF6BD6),
    Color(0xFF4FD1A5) to Color(0xFF3B9DFF), Color(0xFFFF9A6B) to Color(0xFFFF5D8F)
)
private val White: Color get() = if (Th.light) Color(0xFF1E2640) else Color.White
private val Blue: Color get() = ACC[Th.accent].first
private val Violet: Color get() = ACC[Th.accent].second
private val Pill = RoundedCornerShape(999.dp)
private const val TWO_PI = 6.2831855f

@Composable
fun App(vm: MainVm) {
    val sysDark = isSystemInDarkTheme()
    SideEffect { Th.sysLight = !sysDark }
    // Designen ritas alltid på en 1280 dp bred yta, oavsett TV:ns skärmtäthet.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth.coerceAtLeast(1) / 1280f)) {
            Box(Modifier.fillMaxSize()) {
                if (!vm.fullscreen) Background(paused = vm.gridState.isScrollInProgress)
                if (vm.fullscreen) Fullscreen(vm) else Home(vm)
                if (vm.showPrefs) Prefs(vm)
                if (vm.showSettings) Settings(vm)
                if (vm.updateAvailable && !vm.showSettings) UpdateDialog(vm)
                vm.lastCrash?.let { CrashNote(it) { vm.lastCrash = null } }
            }
        }
    }
}

/**
 * Mjuk rörlig bakgrund. Uppdateras ca 30 gånger per sekund (räcker för långsam rörelse och halverar
 * jobbet för TV:ns grafikchip), pausar medan kanallistan scrollar och ritas helt stilla när
 * "Rörlig bakgrund" är avstängd. Tiden läses bara i ritsteget, så inget annat i appen ritas om.
 */
@Composable
fun Background(paused: Boolean = false) {
    val time = remember { mutableFloatStateOf(0f) }
    val pausedNow by rememberUpdatedState(paused)
    if (Th.anim) LaunchedEffect(Unit) {
        var last = System.nanoTime()
        while (true) {
            if (pausedNow) { snapshotFlow { pausedNow }.first { !it }; last = System.nanoTime() }
            delay(33)
            val n = System.nanoTime()
            time.floatValue = (time.floatValue + (n - last) / 1e9f) % 3600f
            last = n
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val lt = Th.light
        val s = time.floatValue
        val a = s * TWO_PI / 60f
        val b = s * TWO_PI / 37f
        drawRect(if (lt) Color(0xFFEEF2FB) else Color(0xFF080C16))
        fun blob(c: Color, cx: Float, cy: Float, r: Float) =
            drawRect(Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r))
        blob(if (lt) Color(0xAA8FB0FF) else Color(0x997AA2FF), w * (0.25f + 0.22f * sin(a)), h * (0.30f + 0.20f * cos(b)), w * 0.55f)
        blob(if (lt) Color(0x99E0A8FF) else Color(0x88B14BFF), w * (0.80f + 0.16f * cos(a * 1.3f)), h * (0.75f + 0.16f * sin(b)), w * 0.50f)
        blob(if (lt) Color(0x887FE0D7) else Color(0x7722D3EE), w * (0.55f + 0.26f * sin(b * 1.1f)), h * (0.15f + 0.13f * cos(a)), w * 0.40f)
        blob(if (lt) Color(0x88FFB3C7) else Color(0x77FF6BB0), w * (0.15f + 0.13f * cos(b)), h * (0.85f + 0.10f * sin(a * 0.8f)), w * 0.38f)
        if (Th.anim) for (i in 0 until 16) {
            val ph = (a / TWO_PI * (1 + i % 3) + i * 0.19f) % 1f
            val x = w * ((i * 0.173f + 0.05f) % 1f) + 30f * sin(ph * TWO_PI)
            val alpha = (if (ph < 0.15f) ph / 0.15f else if (ph > 0.85f) (1f - ph) / 0.15f else 1f) * 0.55f
            drawCircle((if (lt) Color(0xFF1E2640) else Color.White).copy(alpha = alpha), radius = 2.5f + (i % 4) * 1.6f, center = Offset(x, h * (1f - ph)))
        }
    }
}

@Composable
fun Glass(modifier: Modifier = Modifier, radius: Dp = 28.dp, alpha: Float = 0.10f, solid: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.clip(shape)
            .background(if (Th.light) Color.White.copy(alpha = if (solid) 0.96f else 0.32f) else Color(0xFF111A2E).copy(alpha = if (solid) 0.95f else 0.40f))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha + 0.07f), Color.White.copy(alpha * 0.5f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.38f), Color.White.copy(0.06f))), shape),
        content = content
    )
}

@Composable
fun Focusable(
    onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false,
    onFocus: () -> Unit = {}, radius: Dp = 999.dp, content: @Composable BoxScope.() -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.04f else 1f, tween(150), label = "sc")
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .clip(shape)
            .background(
                if (f) Brush.horizontalGradient(listOf(Blue.copy(0.75f), Violet.copy(0.6f)))
                else SolidColor(if (selected) White.copy(0.12f) else Color.Transparent)
            )
            .then(if (f) Modifier.border(1.dp, White.copy(0.5f), shape) else Modifier)
            .drawBehind {
                if (selected && !f) drawRoundRect(Blue, Offset(0f, size.height * 0.2f), Size(3.dp.toPx(), size.height * 0.6f), CornerRadius(2.dp.toPx()))
            }
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(onClick = onClick),
        content = content
    )
}

@Composable
fun Btn(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(
        onClick,
        modifier.background(if (primary) Brush.horizontalGradient(listOf(Blue, Violet)) else SolidColor(White.copy(0.12f)), Pill)
    ) { Text(text, color = if (primary) Color.White else White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 13.dp)) }
}

@Composable
fun Home(vm: MainVm) {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val shown = vm.shown
    val focused = vm.focused ?: shown.firstOrNull()
    LaunchedEffect(focused?.uid, Th.preview) {
        val c = focused ?: return@LaunchedEffect
        if (!Th.preview) return@LaunchedEffect
        if (vm.playing?.uid != c.uid) vm.stopPreview()   // stoppa direkt när man bläddrar vidare
        delay(800)
        vm.preview(c)
    }
    // Tillbaka en gång visar en påminnelse, två gånger inom 2 sekunder avslutar appen.
    var armed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = !armed && !vm.showPrefs && !vm.showSettings) {
        armed = true
        scope.launch { delay(2000); armed = false }
    }
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Rail(vm, Modifier.width(270.dp).fillMaxHeight())
        // Apple TV-upplägg: stor panel med livebild överst, kanalikoner i rutnät under.
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Glass(Modifier.fillMaxWidth().height(250.dp)) { Info(vm, focused, now) }
            Glass(Modifier.fillMaxWidth().weight(1f)) { ChannelList(vm, shown, now) }
        }
    }
    Text(vm.versionLabel, color = White.copy(0.45f), fontSize = 12.sp,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 7.dp))
    if (armed) Glass(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), radius = 999.dp, alpha = 0.2f) {
        Text("Tryck Tillbaka igen för att avsluta", color = White, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
    }
    }
}

@Composable
fun Rail(vm: MainVm, modifier: Modifier) {
    val allReq = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    Glass(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 16.dp)) {
            Text("GlassTV", color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, bottom = 14.dp))
            Focusable(onClick = { vm.toggleOnlyFav() }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(Modifier.padding(4.dp)) {
                    listOf("★ Favoriter" to true, "Alla" to false).forEach { (t, v) ->
                        val on = vm.onlyFav == v
                        Box(Modifier.weight(1f).clip(Pill).background(if (on) White.copy(0.22f) else Color.Transparent).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(t, color = if (on) White else White.copy(0.6f), fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
            if (vm.onlyFav && vm.favorites.isEmpty())
                Text("Inga favoriter än – tryck ★ vid ett land", color = White.copy(0.55f), fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            Focusable(onClick = { vm.select(null) }, modifier = Modifier.fillMaxWidth().focusRequester(allReq), selected = vm.selected == null) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📺", fontSize = 20.sp); Spacer(Modifier.width(12.dp))
                    Text("Alla kanaler", color = White, fontSize = 17.sp)
                }
            }
            Text("LÄNDER", color = White.copy(0.45f), fontSize = 12.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 6.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(vm.countries, key = { it }) { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Stjärnan ligger till vänster, så att Vänster från kanallistan landar på landet och inte råkar favorisera.
                        Focusable(onClick = {
                            val disappears = vm.onlyFav && c in vm.favorites && vm.favorites.size > 1
                            vm.toggleFav(c)
                            if (disappears) scope.launch { delay(50); runCatching { allReq.requestFocus() } }
                        }, modifier = Modifier.padding(end = 4.dp)) {
                            Text("★", fontSize = 18.sp, color = if (c in vm.favorites) Color(0xFFFFD60A) else White.copy(0.35f), modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp))
                        }
                        Focusable(onClick = { vm.select(c) }, modifier = Modifier.weight(1f), selected = vm.selected == c) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(flag(c), fontSize = 20.sp); Spacer(Modifier.width(12.dp))
                                Text(c, color = White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                Focusable(onClick = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                    Text("↻ Uppdatera", color = White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
                Focusable(onClick = { vm.showPrefs = true }, modifier = Modifier.weight(1f)) {
                    Text("⚙ Inställn.", color = White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
fun ChannelList(vm: MainVm, list: List<Channel>, now: Long) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val msg = when {
                vm.loading -> "Laddar kanaler…"
                vm.status.isNotEmpty() -> vm.status
                else -> "Ingen spellista. Öppna ⚙ Inställningar."
            }
            Text(msg, color = White.copy(0.7f), fontSize = 20.sp)
        }
        return
    }
    // Ge första kanalen fokus när appen startar och när startrutan stängs.
    LaunchedEffect(vm.showSettings, vm.showPrefs) {
        if (!vm.initialFocusDone && !vm.showSettings && !vm.showPrefs) {
            vm.initialFocusDone = true
            vm.focusUid = (vm.focused?.takeIf { f -> list.any { it.uid == f.uid } } ?: list.first()).uid
        }
    }
    val lastRepeat = remember { LongArray(1) }
    val reqs = remember { HashMap<Int, FocusRequester>() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = vm.gridState,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxSize()
            // Håller man ned en pil kommer ca 20 tryck i sekunden. Begränsa till ca 12, så hinner rutnätet med utan att hacka eller tappa fokus.
            .onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                if (e.type == KeyEventType.KeyDown && n.repeatCount > 0 &&
                    (e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
                    if (n.eventTime - lastRepeat[0] < 85) return@onPreviewKeyEvent true
                    lastRepeat[0] = n.eventTime
                }
                false
            }
            // In i rutnätet: till senast valda kanal om den syns, annars första synliga rutan.
            // Bara Vänster får lämna rutnätet (till menyn). Upp/ned/höger vid kanten stannar kvar.
            .focusProperties {
                enter = {
                    vm.focused?.uid?.let { reqs[it] }
                        ?: vm.gridState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info -> (info.key as? Int)?.let { k -> reqs[k] } }
                        ?: FocusRequester.Default
                }
                exit = { d -> if (d == FocusDirection.Left) FocusRequester.Default else FocusRequester.Cancel }
            }
            .focusGroup()
    ) {
        gridItems(list, key = { it.uid }, contentType = { 0 }) { c -> ChannelTile(vm, c, now, reqs) }
    }
}

private fun hueOf(s: String): Float = ((s.fold(7) { a, ch -> a * 31 + ch.code } and 0x7fffffff) % 360).toFloat()

/** En kanal som glasikon i Apple TV-stil. Fast storlek, så rutnätet aldrig hoppar. */
@Composable
fun ChannelTile(vm: MainVm, c: Channel, now: Long, reqs: MutableMap<Int, FocusRequester>) {
    val prog = vm.epg[c.id]?.firstOrNull { now >= it.start && now < it.stop }
    val fr = remember { FocusRequester() }
    DisposableEffect(c.uid) {
        reqs[c.uid] = fr
        onDispose { if (reqs[c.uid] === fr) reqs.remove(c.uid) }
    }
    if (vm.focusUid == c.uid) {
        LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() }; if (vm.focusUid == c.uid) vm.focusUid = null }
    }
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.12f else 1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "tile")
    val hue = remember(c.name) { hueOf(c.name) }
    val tileBg = remember(hue) { Brush.linearGradient(listOf(Color.hsv(hue, 0.62f, 0.62f), Color.hsv((hue + 40f) % 360f, 0.6f, 0.36f))) }
    val shape = RoundedCornerShape(22.dp)
    var logoOk by remember(c.logo) { mutableStateOf(false) }
    Column(Modifier.zIndex(if (f) 1f else 0f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .graphicsLayer { scaleX = sc; scaleY = sc; translationY = (sc - 1f) * -30f }
                .then(if (f) Modifier.shadow(26.dp, shape, ambientColor = Blue, spotColor = Blue) else Modifier)
                .clip(shape)
                .background(tileBg)
                .background(Brush.verticalGradient(listOf(Color.White.copy(0.30f), Color.White.copy(0.05f), Color.Transparent)))
                .border(if (f) 3.dp else 1.dp, Color.White.copy(if (f) 0.9f else 0.35f), shape)
                .focusRequester(fr)
                .onFocusChanged { f = it.isFocused; if (it.isFocused) vm.focused = c }
                .clickable { vm.open(c) },
            contentAlignment = Alignment.Center
        ) {
            if (c.logo != null) AsyncImage(
                c.logo, null, Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit,
                onSuccess = { logoOk = true }, onError = { logoOk = false }
            )
            if (!logoOk) {
                val n = c.name
                val size = when { n.length <= 5 -> 28.sp; n.length <= 9 -> 21.sp; n.length <= 13 -> 16.sp; else -> 14.sp }
                Text(n, color = Color.White, fontSize = size, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = size * 1.1f, modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(prog?.title ?: if (f) c.name else "", color = White.copy(if (f) 0.95f else 0.6f), fontSize = 13.sp,
            fontWeight = if (f) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Panelen överst: livebild till vänster, kanal och tv-guide till höger. */
@Composable
fun Info(vm: MainVm, c: Channel?, now: Long) {
    Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(0.5f))) {
            if (!Th.preview) Text("Livebild avstängd", color = Color.White.copy(0.5f), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
            if (Th.preview && vm.playing != null) AndroidView(
                factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.preview_player, null) as PlayerView).also { it.player = vm.player } },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (c != null) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.logo != null) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(White.copy(0.1f))) {
                        AsyncImage(c.logo, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Text(c.name, color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val list = vm.epg[c.id].orEmpty()
            val cur = list.firstOrNull { now >= it.start && now < it.stop }
            if (cur != null) {
                Text("Nu  ${hm(cur.start)}–${hm(cur.stop)}", color = White.copy(0.6f), fontSize = 14.sp)
                Text(cur.title, color = White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val p = ((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(White.copy(0.15f))) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(White.copy(0.85f)))
                }
            } else Text("Ingen tv-guide för kanalen", color = White.copy(0.5f), fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                list.filter { it.start >= (cur?.stop ?: now) }.take(3).forEach {
                    Text("${hm(it.start)}  ${it.title}", color = White.copy(0.65f), fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

@Composable
fun Fullscreen(vm: MainVm) {
    val fr = remember { FocusRequester() }
    var overlay by remember { mutableStateOf(true) }
    val c = vm.playing
    LaunchedEffect(c?.url) { overlay = true; delay(4000); overlay = false }
    LaunchedEffect(Unit) { delay(50); runCatching { fr.requestFocus() } }
    BackHandler { vm.exitFull() }
    var lastZap by remember { mutableLongStateOf(0L) }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.DirectionDown, Key.ChannelDown -> {
                        val t0 = e.nativeKeyEvent.eventTime
                        if (t0 - lastZap > 250) { lastZap = t0; vm.step(if (e.key == Key.DirectionUp || e.key == Key.ChannelUp) -1 else 1) }
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> { overlay = true; true }
                    else -> false
                }
            }
            .focusRequester(fr).focusable()
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; isFocusable = false; player = vm.player } },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
        AnimatedVisibility(overlay, Modifier.align(Alignment.BottomCenter).padding(36.dp)) {
            val now = System.currentTimeMillis()
            val cur = c?.let { vm.epg[it.id] }?.firstOrNull { now >= it.start && now < it.stop }
            Glass(Modifier.fillMaxWidth(), alpha = 0.16f) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (c?.logo != null) { AsyncImage(c.logo, null, Modifier.size(64.dp), contentScale = ContentScale.Fit); Spacer(Modifier.width(20.dp)) }
                    Column {
                        Text(c?.name ?: "", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        if (cur != null) Text("${hm(cur.start)}–${hm(cur.stop)}  ${cur.title}", color = White.copy(0.75f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun Qr(text: String, box: Dp) {
    val m = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(Modifier.size(box).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
        val cell = this.size.width / m.width
        for (x in 0 until m.width) for (y in 0 until m.height)
            if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

@Composable
fun Settings(vm: MainVm) {
    var m by remember { mutableStateOf(vm.repo.m3uUrl) }
    var e by remember { mutableStateOf(vm.repo.epgUrl) }
    var link by remember { mutableStateOf<String?>(null) }
    var qrMsg by remember { mutableStateOf("Anslut TV:n till wifi för att få en QR-kod.") }
    val primary = remember { FocusRequester() }
    DisposableEffect(Unit) {
        val srv = PairServer { a, b -> Handler(Looper.getMainLooper()).post { vm.saveUrls(a, b); vm.showSettings = false } }
        if (srv.start()) link = localIp()?.let { "http://$it:${srv.port}" }
        else qrMsg = "QR-koden går inte att visa just nu. Skriv in länken här."
        onDispose { srv.stop() }
    }
    LaunchedEffect(Unit) { delay(150); runCatching { primary.requestFocus() } }
    Dialog(onDismissRequest = { vm.showSettings = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Glass(Modifier.fillMaxWidth(0.82f), alpha = 0.18f, solid = true) {
            Row(Modifier.padding(36.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Blue, Violet))), contentAlignment = Alignment.Center) { Text("📺", fontSize = 28.sp) }
                    Text("Välkommen till GlassTV", color = White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Lägg till din spellista för att komma igång.", color = White.copy(0.6f), fontSize = 16.sp)
                    OutlinedTextField(m, { m = it }, label = { Text("M3U-länk") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(e, { e = it }, label = { Text("Tv-guide, XMLTV (valfri)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (vm.status.isNotEmpty()) Text(vm.status, color = Color(0xFFFFB4A9), fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val hasList = vm.channels.isNotEmpty()
                        Btn("Spara och ladda", true, if (!hasList) Modifier.focusRequester(primary) else Modifier) {
                            if (m.isBlank()) vm.status = "Skriv in en M3U-länk först"
                            else { vm.saveUrls(m.trim(), e.trim()); vm.showSettings = false }
                        }
                        Btn("Läs in fil", false) { vm.pickFile() }
                        Btn("Stäng", false, if (hasList) Modifier.focusRequester(primary) else Modifier) { vm.showSettings = false }
                    }
                }
                Column(Modifier.width(220.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val l = link
                    if (l != null) {
                        Qr(l, 190.dp)
                        Text("Skanna med mobilen", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("och klistra in länken där", color = White.copy(0.6f), fontSize = 14.sp)
                        Text(l, color = White.copy(0.45f), fontSize = 12.sp)
                    } else Text(qrMsg, color = White.copy(0.6f), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun PrefRow(label: String, opts: List<String>, sel: Int, set: (Int) -> Unit, req: FocusRequester? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            opts.forEachIndexed { i, o ->
                Focusable(
                    onClick = { set(i) }, selected = i == sel,
                    modifier = if (i == sel && req != null) Modifier.focusRequester(req) else Modifier
                ) {
                    Text(o, color = White, fontSize = 16.sp, fontWeight = if (i == sel) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        }
    }
}

@Composable
fun Prefs(vm: MainVm) {
    val orig = remember { listOf(Th.theme, Th.accent, if (Th.anim) 0 else 1, if (Th.preview) 0 else 1) }
    val first = remember { FocusRequester() }
    fun cancel() { Th.theme = orig[0]; Th.accent = orig[1]; Th.anim = orig[2] == 0; Th.preview = orig[3] == 0; vm.onPreviewSetting(Th.preview); vm.showPrefs = false }
    fun save() { vm.repo.theme = Th.theme; vm.repo.accent = Th.accent; vm.repo.anim = Th.anim; vm.repo.preview = Th.preview; vm.showPrefs = false }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = { cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Glass(
            Modifier.fillMaxWidth(0.62f).onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key(android.view.KeyEvent.KEYCODE_PROG_GREEN) -> { save(); true }
                    Key(android.view.KeyEvent.KEYCODE_PROG_RED) -> { cancel(); true }
                    else -> false
                }
            },
            alpha = 0.18f, solid = true
        ) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⚙ Inställningar", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("UTSEENDE OCH PRESTANDA", color = White.copy(0.45f), fontSize = 12.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 6.dp))
                PrefRow("Tema", listOf("Mörkt", "Ljust", "Auto"), Th.theme, { Th.theme = it }, first)
                PrefRow("Accentfärg", listOf("Blå", "Lila", "Grön", "Korall"), Th.accent, { Th.accent = it })
                PrefRow("Rörlig bakgrund", listOf("På", "Av"), if (Th.anim) 0 else 1, { Th.anim = it == 0 })
                PrefRow("Livebild i förhandsvisning", listOf("På", "Av"), if (Th.preview) 0 else 1, { Th.preview = it == 0; vm.onPreviewSetting(Th.preview) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Spellista", color = White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Btn("Ändra länk…", false) { vm.showSettings = true }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    Btn("Spara", true) { save() }
                    Btn("Avbryt", false) { cancel() }
                }
                Text("Grön knapp = spara · Röd knapp = avbryt · Tillbaka = avbryt", color = White.copy(0.5f), fontSize = 13.sp)
            }
        }
    }
}

/** Visas när en nyare version finns på GitHub. */
@Composable
fun UpdateDialog(vm: MainVm) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = { vm.updateAvailable = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Glass(Modifier.fillMaxWidth(0.52f), alpha = 0.2f, solid = true) {
            Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Blue, Violet))), contentAlignment = Alignment.Center) {
                    Text("↓", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Text("Ny version av GlassTV finns", color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(vm.updateStatus.ifEmpty { "Vill du uppdatera nu? Dina kanaler, favoriter och inställningar finns kvar." },
                    color = White.copy(0.75f), fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Btn("Uppdatera", true, Modifier.focusRequester(fr)) { vm.startUpdate() }
                    Btn("Senare", false) { vm.updateAvailable = false }
                }
            }
        }
    }
}

/** Visas en gång om appen kraschade förra gången, så att felet kan fotas och skickas. */
@Composable
fun CrashNote(text: String, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Glass(Modifier.fillMaxWidth(0.8f), alpha = 0.2f, solid = true) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appen stängdes oväntat förra gången", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Ta gärna ett foto av texten nedan och skicka den, så kan felet rättas.", color = White.copy(0.7f), fontSize = 15.sp)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(0.35f)).padding(14.dp)) {
                    Text(text.lines().take(14).joinToString("\n"), color = Color.White.copy(0.85f), fontSize = 12.sp, lineHeight = 15.sp)
                }
                Btn("OK", true, Modifier.focusRequester(fr)) { onClose() }
            }
        }
    }
}
