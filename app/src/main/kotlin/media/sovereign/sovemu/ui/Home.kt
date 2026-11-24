package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import media.sovereign.sovemu.core.byline
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.Load
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.HomeFeed
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.badge
import media.sovereign.sovemu.core.formatAgo
import media.sovereign.sovemu.core.GameIndex
import media.sovereign.sovemu.core.assetCacheKey

private const val ADVANCE_MS = 9000L

/**
 * Home, as the web client's: a hero of featured games over rows of games to continue, systems
 * and recently added games. The D-pad moves between rows and along them; on the hero, left and
 * right change game and A plays. A "Continue playing" card opens a panel here (where the player
 * left off, Continue, Game details); any other game card opens the game's panel on its system's
 * page. X searches every system.
 */
@Composable
fun HomeScreen(
    feed: Load<HomeFeed>,
    systems: List<SystemSummary>,
    onPlay: (Game, Start) -> Unit,
    onSystem: (SystemSummary, Int?) -> Unit,
    onSearch: () -> Unit,
    server: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    when (feed) {
        Load.Loading -> Box(Modifier.fillMaxWidth().aspectRatio(16 / 6.3f).background(Sov.Panel.copy(alpha = 0.4f)))
        is Load.Failed -> Unreachable(server, feed.message, onRetry, onSignOut)
        is Load.Ready -> HomeContent(feed.value, systems, onPlay, onSystem, onSearch)
    }
}

@Composable
private fun HomeContent(
    feed: HomeFeed,
    all: List<SystemSummary>,
    onPlay: (Game, Start) -> Unit,
    onSystem: (SystemSummary, Int?) -> Unit,
    onSearch: () -> Unit,
) {
    val systems = remember(all) { all.filter { it.gameCount > 0 } }
    val byId = remember(all) { all.associateBy { it.id } }
    val memory = rememberFocusMemory()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // The "Continue playing" game whose panel is open, kept across a game and Game details.
    var resumeId by rememberSaveable { mutableStateOf<Int?>(null) }
    val resume = remember(resumeId, feed) { resumeId?.let { id -> feed.recent.firstOrNull { it.id == id } } }
    // Back from a game with the panel open: the panel takes the focus, not the card behind it.
    remember { if (resumeId != null) memory.pending = null }
    fun closeResume() {
        val id = resumeId ?: return
        resumeId = null
        scope.launch {
            withFrameNanos { }
            memory.focus("recent:$id")
        }
    }
    BackHandler(enabled = resume != null, onBack = ::closeResume)
    Box(
        // X (the top face button) searches every system, as on the web.
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (resume == null && e.type == KeyEventType.KeyDown && (e.key == Key.ButtonY || e.key == Key.Search)) {
                onSearch()
                true
            } else {
                false
            }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 112.dp)) {
            Hero(feed.featured, byId, memory, systems.isEmpty(), onFocus = { scope.launch { scroll.animateScrollTo(0) } }, onPlay = { onPlay(it, Start.Ask) }, onBrowse = { g -> byId[g.system]?.let { onSystem(it, null) } })
            Column(Modifier.offset(y = (-32).dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
                if (feed.recent.isNotEmpty()) {
                    HomeRow("Continue playing", Lucide.History) {
                        itemsIndexed(feed.recent, key = { _, g -> g.id }) { _, g ->
                            GameCard(g, byId[g.system], wide = true, memory = memory, key = "recent:${g.id}") { resumeId = g.id }
                        }
                    }
                }
                if (feed.favourites.isNotEmpty()) {
                    HomeRow("Favourites", Lucide.Heart) {
                        itemsIndexed(feed.favourites, key = { _, g -> g.id }) { _, g ->
                            GameCard(g, byId[g.system], wide = false, memory = memory, key = "favourite:${g.id}") {
                                byId[g.system]?.let { onSystem(it, g.id) }
                            }
                        }
                    }
                }
                if (systems.isNotEmpty()) {
                    HomeRow("Systems", Lucide.Gamepad2) {
                        itemsIndexed(systems, key = { _, s -> s.id }) { _, s -> SystemCard(s, memory) { onSystem(s, null) } }
                    }
                }
                if (feed.added.isNotEmpty()) {
                    HomeRow("Recently added", Lucide.Sparkles) {
                        itemsIndexed(feed.added, key = { _, g -> g.id }) { _, g ->
                            GameCard(g, byId[g.system], wide = false, memory = memory, key = "added:${g.id}") {
                                byId[g.system]?.let { onSystem(it, g.id) }
                            }
                        }
                    }
                }
                for (c in feed.collections) {
                    if (c.games.isEmpty()) continue
                    key(c.id) {
                        HomeRow(c.name, Lucide.FolderHeart) {
                            itemsIndexed(c.games, key = { _, g -> g.id }) { _, g ->
                                GameCard(g, byId[g.system], wide = false, memory = memory, key = "collection:${c.id}:${g.id}") {
                                    byId[g.system]?.let { onSystem(it, g.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
        ContinuePanel(
            resume, resume?.let { byId[it.system] }, onClose = ::closeResume,
            onPlay = { start -> resume?.let { onPlay(it, start) } },
            // On to the game's own panel; Back from there comes back here, to this panel.
            onDetails = { resume?.let { g -> byId[g.system]?.let { onSystem(it, g.id) } } },
        )
    }
}

/** The hero: featured games one at a time over their artwork, moving on by itself. */
@Composable
private fun Hero(
    games: List<Game>,
    systems: Map<String, SystemSummary>,
    memory: FocusMemory,
    empty: Boolean,
    onFocus: () -> Unit,
    onPlay: (Game) -> Unit,
    onBrowse: (Game) -> Unit,
) {
    val form = LocalForm.current
    val n = games.size
    var index by rememberSaveable { mutableIntStateOf(0) }
    val go = { i: Int -> if (n > 0) index = (i + n) % n }
    LaunchedEffect(index, n) {
        if (n < 2) return@LaunchedEffect
        delay(ADVANCE_MS)
        index = (index + 1) % n
    }
    val box = if (form.wide) Modifier.fillMaxWidth().aspectRatio(16 / 6.3f) else Modifier.fillMaxWidth().aspectRatio(1f)
    if (n == 0) {
        Box(box.background(Brush.radialGradient(listOf(Color(0xFF1E2A4A), Sov.Surface))), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
                LucideIcon(Lucide.Gamepad2, tint = Sov.Slate500, size = 48.dp)
                Text(
                    if (empty) "No games yet. Add ROMs to the library (one folder per system: nes/, snes/, …) and rescan."
                    else "No artwork yet: fetch game information on the Library page to fill this space.",
                    color = Sov.Slate400,
                )
            }
        }
        return
    }
    val game = games[index.coerceIn(0, n - 1)]
    val system = systems[game.system]
    val short = system?.short ?: game.system.uppercase()
    Box(
        box.pointerInput(n) {
            var dx = 0f
            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (kotlin.math.abs(dx) > 40) go(index + if (dx < 0) 1 else -1) }) { _, d -> dx += d }
        },
    ) {
        // Changing game cross-fades; only the outgoing and incoming slides are ever composed.
        Crossfade(game, animationSpec = tween(700), label = "hero") { g -> Slide(g, systems[g.system]) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.5f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to Color.Black.copy(alpha = 0.3f), 1f to Color.Black)))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Sov.Surface))))

        Column(
            Modifier.align(Alignment.BottomStart).widthIn(max = 672.dp)
                .padding(start = if (form.wide) 48.dp else 24.dp, end = 48.dp, bottom = if (form.wide) 64.dp else 56.dp),
        ) {
            SystemBadge(short, system?.colour() ?: Sov.Accent)
            Text(game.title, color = Color.White, fontWeight = FontWeight.Black, fontSize = if (form.wide) 44.sp else 30.sp,
                lineHeight = if (form.wide) 48.sp else 34.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            val line = game.byline
            if (line.isNotEmpty()) Text(line, color = Sov.Slate300, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            game.description?.let {
                Text(it, color = Sov.Slate300, fontSize = 14.sp, lineHeight = 21.sp, maxLines = if (form.wide) 3 else 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SovButton(
                    "Play", { onPlay(game) }, variant = Variant.Light, icon = Lucide.Play, filledIcon = true,
                    modifier = Modifier
                        .remembersFocus(memory, "hero", initial = form.tv)
                        // The hero is the top of the page: focusing it shows all of it.
                        .onFocusChanged { if (it.isFocused) onFocus() }
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (it.key) {
                                Key.DirectionLeft -> { go(index - 1); true }
                                Key.DirectionRight -> { go(index + 1); true }
                                else -> false
                            }
                        },
                )
                if (!form.tv) SovButton("More $short games", { onBrowse(game) }, variant = Variant.Glass, icon = Lucide.LayoutGrid)
            }
        }

        if (n > 1) {
            if (form.wide && !form.tv) {
                Arrow(Lucide.ChevronLeft, Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { go(index - 1) }
                Arrow(Lucide.ChevronRight, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { go(index + 1) }
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
                games.forEachIndexed { i, _ -> Dot(i == index) { go(i) } }
            }
        }
    }
}

/** One featured game's backdrop: its screenshot, else its box art blurred behind a sharp copy. */
@Composable
private fun Slide(game: Game, system: SystemSummary?) {
    val c = system?.colour() ?: Sov.Accent
    val brush = remember(c) { tile(c, 0.25f) }
    Box(Modifier.fillMaxSize().background(brush)) {
        if (game.image != null) {
            Art(game.image, Modifier.fillMaxSize())
        } else if (game.thumb != null) {
            // A blur for the price of a 32-pixel decode: drawn up to size, the bitmap's own
            // filtering smears it (RenderEffect blurs cost a full-screen pass on every frame).
            Art(game.thumb, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.1f; scaleY = 1.1f }, alpha = 0.5f, sizePx = 32)
            if (LocalForm.current.wide) {
                Box(Modifier.fillMaxSize().padding(end = 64.dp), contentAlignment = Alignment.CenterEnd) {
                    Art(game.thumb, Modifier.fillMaxHeight(0.7f).aspectRatio(3 / 4f).clip(RoundedCornerShape(8.dp)), scale = ContentScale.Fit)
                }
            }
        }
    }
}

@Composable
private fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) =
    Box(modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        LucideIcon(icon, size = 24.dp)
    }

@Composable
private fun Dot(current: Boolean, onClick: () -> Unit) {
    val width by animateDpAsState(if (current) 20.dp else 8.dp, label = "dot")
    Box(Modifier.height(24.dp).clickable(onClick = onClick).padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(width).height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = if (current) 1f else 0.4f)))
    }
}

/** A titled, horizontally scrolling row; focus comes back to the card it left from. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        SectionTitle(title, icon, Modifier.padding(horizontal = 32.dp).padding(bottom = 4.dp))
        LazyRow(
            Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A game: box art (portrait), or where the player left off (landscape, [wide]). */
@Composable
internal fun GameCard(game: Game, system: SystemSummary?, wide: Boolean, memory: FocusMemory, key: String, onOpen: () -> Unit) {
    val c = system?.colour() ?: Sov.Accent
    val brush = remember(c) { tile(c) }
    val short = system?.short ?: game.system.uppercase()
    Column(Modifier.width(if (wide) 288.dp else 160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusCard(onOpen, Modifier.fillMaxWidth().aspectRatio(if (wide) 16 / 9f else 3 / 4f).remembersFocus(memory, key)) {
            // Only the Continue row shows where the player left off; the others show the box art.
            val shot = if (wide) game.autoSave?.screenshotUrl else null
            // A fitted box art leaves the tile showing round it; a cropped picture hides it.
            var covered by remember(game.id) { mutableStateOf(false) }
            if (shot != null) {
                // Where the player left off: the whole frame, never cropped, on black like a TV.
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Screenshot(shot, Modifier.fillMaxSize())
                }
            } else Box(Modifier.fillMaxSize().then(if (covered) Modifier else Modifier.background(brush)), contentAlignment = Alignment.Center) {
                if (!covered) Text(short, color = Color.White.copy(alpha = 0.8f), fontSize = 28.sp, fontWeight = FontWeight.Black)
                Art(game.image ?: game.thumb, Modifier.fillMaxSize(), scale = if (wide) ContentScale.Crop else ContentScale.Fit,
                    onLoaded = { if (wide) covered = it })
            }
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(game.title, color = Sov.Slate100, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ago = remember(game.lastPlayedAt, wide) {
                if (wide) game.lastPlayedAt?.let { " · " + formatAgo(GameIndex.playedAt(it), System.currentTimeMillis() / 1000) } ?: "" else ""
            }
            Text(withRelease(game.release, short + ago, first = false), color = Sov.Slate400, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A save's screenshot: console pixels, scaled without smoothing. */
@Composable
fun Screenshot(path: String, modifier: Modifier) {
    val api = LocalApi.current
    val context = LocalContext.current
    val url = api.media(path) ?: return
    val request = remember(url) {
        val key = assetCacheKey(url)
        ImageRequest.Builder(context).data(url).memoryCacheKey(key).diskCacheKey(key).build()
    }
    // Fitted, never cropped: a console frame's edges (a HUD, a score) are part of the picture.
    AsyncImage(request, null, modifier, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
}

@Composable
private fun SystemCard(s: SystemSummary, memory: FocusMemory, onOpen: () -> Unit) {
    val brush = remember(s.color) { tile(s.colour(), 0.35f) }
    Column(Modifier.width(224.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusCard(onOpen, Modifier.fillMaxWidth().height(128.dp).remembersFocus(memory, "system:${s.id}"), shape = RoundedCornerShape(16.dp)) {
            Box(Modifier.fillMaxSize().background(brush).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                val photo = s.photoUrl
                if (photo == null) {
                    Text(s.short, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                } else {
                    // The server's photo of the hardware on the right, the short name on the left,
                    // as on the web.
                    Text(s.short, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                    Art(photo, Modifier.align(Alignment.BottomEnd).padding(8.dp).fillMaxWidth(0.58f).fillMaxHeight(0.76f),
                        scale = ContentScale.Fit)
                }
                s.badge()?.let {
                    Text(it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp, 8.dp).clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(s.name, color = Sov.Slate100, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${s.manufacturer} · ${s.year} · ${s.gameCount} game${if (s.gameCount == 1) "" else "s"}", color = Sov.Slate400, fontSize = 12.sp, maxLines = 1)
        }
    }
}
