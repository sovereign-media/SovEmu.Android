package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import media.sovereign.sovemu.core.AUTO_SLOT
import media.sovereign.sovemu.core.year
import media.sovereign.sovemu.attempt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.Load
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameFilters
import media.sovereign.sovemu.core.GameIndex
import media.sovereign.sovemu.core.GameSort
import media.sovereign.sovemu.core.PlayedFilter
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.formatAgo
import media.sovereign.sovemu.core.formatSize
import media.sovereign.sovemu.core.jumpStops
import media.sovereign.sovemu.core.jumpTo
import media.sovereign.sovemu.core.letterOf
import media.sovereign.sovemu.core.maxPlayers

/** How long the jump strip rests on a letter before the grid follows it. */
private const val FOLLOW_DELAY_MS = 300L

private val FiltersSaver: Saver<GameFilters, Any> = listSaver(
    save = { listOf(it.played?.name ?: "", it.multiplayer.toString()) + it.genres },
    restore = { l -> GameFilters(l[0].ifEmpty { null }?.let(PlayedFilter::valueOf), l[1].toBoolean(), l.drop(2).toSet()) },
)

/**
 * One system's games, as the web client's system page: a header with a capsule of chips (Sort,
 * Filters, Jump), a grid of covers, and a side panel with the chosen game's details. With the
 * panel open the D-pad still moves through the grid and the panel follows; A plays (continues
 * from the auto save if there is one), X starts over, B closes it. Start opens the filters.
 */
@Composable
fun SystemScreen(
    api: SovEmuApi,
    system: SystemSummary,
    index: Load<GameIndex>,
    openFirst: Int?,
    sort: GameSort,
    onSort: (GameSort) -> Unit,
    onPlay: (Game, Start) -> Unit,
) {
    val colour = system.colour()
    // The page's tint: the system's colour from the top-left corner, as on the web. It is the
    // page's only full-screen fill (NavFrame skips its own), and it reaches under the dock.
    val tint = remember(colour) { Brush.radialGradient(listOf(lerp(Sov.Surface, colour, 0.25f), Sov.Surface), center = Offset.Zero, radius = 1800f) }
    val dock = if (LocalForm.current.wide) 64.dp else 0.dp
    Box(Modifier.fillMaxSize().drawBehind {
        translate(left = -dock.toPx()) { drawRect(tint, size = size.copy(width = size.width + dock.toPx())) }
    }) {
        when (index) {
            Load.Loading -> Header(system, null, null, sort, onSort, 0, {}, null, Modifier.padding(32.dp).statusBarsPadding())
            is Load.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(index.message, color = Sov.Red300) }
            is Load.Ready -> Games(api, system, index.value, openFirst, sort, onSort, onPlay)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Games(
    api: SovEmuApi,
    system: SystemSummary,
    index: GameIndex,
    openFirst: Int?,
    sort: GameSort,
    onSort: (GameSort) -> Unit,
    onPlay: (Game, Start) -> Unit,
) {
    val form = LocalForm.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable(stateSaver = FiltersSaver) { mutableStateOf(GameFilters()) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf(openFirst) }
    var jump by remember { mutableStateOf<Int?>(null) }
    var slots by remember { mutableStateOf<List<SaveSlot>>(emptyList()) }
    val memory = rememberFocusMemory()
    val grid = rememberLazyGridState()
    // Back from a game with the panel open: the panel takes the focus, not the card behind it.
    remember { if (openId != null) memory.pending = null }

    // Every visible card reads this list, so it changes only when the inputs do. The index is
    // in title order already: filtering and re-sorting 1,000 games is well under a frame.
    val shown = remember(index, query, filters, sort) { index.query(query, filters, sort) }
    val stops = remember(shown) { jumpStops(shown) }
    val open = remember(openId, index) { openId?.let { id -> index.games.firstOrNull { it.id == id } } }
    // The auto save comes with the game, so Continue never waits on the saves request.
    val auto = open?.autoSave != null || slots.any { it.slot == AUTO_SLOT }

    // Opened on a game (from Home's Recently added): scroll to it and focus it.
    LaunchedEffect(Unit) {
        if (openFirst != null && memory.fresh) {
            val i = shown.indexOfFirst { it.id == openFirst }
            if (i >= 0) {
                grid.scrollToItem(i + 1)
                memory.focus("game:$openFirst")
            }
        }
    }
    // The panel's saves, after a pause: moving quickly through games does not ask for each.
    LaunchedEffect(openId) {
        slots = emptyList()
        val id = openId ?: return@LaunchedEffect
        delay(150)
        slots = attempt { api.states(id) }.getOrDefault(emptyList())
    }

    fun play(g: Game, start: Start) = onPlay(g, start)
    /** An overlay closed: the focus goes back to the card it came from (it went with the overlay). */
    fun refocus() {
        val key = memory.last ?: return
        scope.launch {
            withFrameNanos { }
            memory.focus(key)
        }
    }
    fun focusGame(i: Int) {
        val g = shown.getOrNull(i) ?: return
        scope.launch {
            if (grid.layoutInfo.visibleItemsInfo.none { it.key == g.id }) grid.scrollToItem(i + 1)
            withFrameNanos { }
            memory.focus("game:${g.id}")
        }
    }

    fun closePanel() {
        val id = openId ?: return
        openId = null
        scope.launch {
            withFrameNanos { }
            memory.focus("game:$id")
        }
    }

    BackHandler(enabled = openId != null, onBack = ::closePanel)

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.ButtonStart, Key.Menu -> { if (openId == null) filtersOpen = true; true }
                else -> false
            }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = if (form.wide) 32.dp else 16.dp, end = if (form.wide) 32.dp else 16.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Header(
                    system, shown.size, index.games.size, sort, onSort, filters.count + if (query.isNotBlank()) 1 else 0,
                    { filtersOpen = true },
                    if (sort == GameSort.TITLE && shown.size > 12 && stops.size > 1) {
                        { jump = stops.indexOf(shown.getOrNull(grid.firstVisibleItemIndex)?.title?.let(::letterOf)).coerceAtLeast(0) }
                    } else null,
                    Modifier.statusBarsPadding().padding(top = 40.dp, bottom = 8.dp),
                )
            }
            if (shown.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (index.games.isEmpty()) "No games for this system yet." else "No games match. Try fewer words, or clear the filters.",
                        color = Sov.Slate400,
                        modifier = Modifier.padding(vertical = 48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(vertical = 80.dp, horizontal = 32.dp),
                    )
                }
            }
            itemsIndexed(shown, key = { _, g -> g.id }, contentType = { _, _ -> "game" }) { i, g ->
                CoverCard(g, system, memory, isOpen = g.id == openId, initial = form.tv && i == 0) { openId = g.id }
            }
        }

        GamePanel(open, system, slots, auto, onClose = ::closePanel, onPlay = { start -> open?.let { play(it, start) } })

        HintBar(
            if (open != null) listOf(Hint.Dpad to "Choose", Hint.A to "Select", Hint.B to "Close")
            else listOf(Hint.Dpad to "Choose", Hint.A to "Open", Hint.Start to "Filters", Hint.B to "Home"),
            Modifier.align(Alignment.BottomCenter),
        )

    if (filtersOpen) {
        FilterDrawer(index, query, { query = it }, filters, { filters = it }) {
            filtersOpen = false
            refocus()
        }
    }
    jump?.let { at ->
        JumpStrip(
            stops, at,
            onIndex = { i ->
                jump = i
                scope.launch {
                    delay(FOLLOW_DELAY_MS)
                    if (jump == i) grid.scrollToItem(jumpTo(shown, stops[i]) + 1)
                }
            },
            onCommit = {
                jump = null
                focusGame(jumpTo(shown, stops[at]))
            },
            onCancel = {
                jump = null
                refocus()
            },
        )
    }
    }
}

/** The system, its count and the chips beside it. */
@Composable
private fun Header(
    system: SystemSummary,
    shown: Int?,
    total: Int?,
    sort: GameSort,
    onSort: (GameSort) -> Unit,
    filterCount: Int,
    onFilters: () -> Unit,
    onJump: (() -> Unit)?,
    modifier: Modifier,
) {
    val form = LocalForm.current
    val title = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SystemBadge(system.short, system.colour(), large = true)
            Text(system.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (total != null) {
                Text(if (shown == total) "$total games" else "$shown of $total", color = Sov.Slate400, fontSize = 14.sp)
            }
            if (system.biosMissing) {
                Row(
                    Modifier.clip(CircleShape).background(Color(0x33F59E0B)).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LucideIcon(Lucide.Microchip, tint = Sov.Amber200, size = 14.dp)
                    Text("BIOS missing: games will not start", color = Sov.Amber200, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    val chips = @Composable {
        if (total != null) {
            ChipStrip {
                SortChip(sort, onSort)
                ChipDivider()
                Chip(if (filterCount > 0) "Filters ($filterCount)" else "Filters", Lucide.SlidersHorizontal, onFilters, on = filterCount > 0)
                if (onJump != null) Chip("Jump", Lucide.BookA, onJump)
            }
        }
    }
    if (form.wide) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { title() }
            chips()
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            title()
            chips()
        }
    }
}

@Composable
private fun SortChip(sort: GameSort, onSort: (GameSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Chip(sort.label, Lucide.ArrowDownAZ, { expanded = true })
        DropdownMenu(
            expanded, { expanded = false },
            shape = RoundedCornerShape(12.dp), containerColor = Sov.Panel,
            border = androidx.compose.foundation.BorderStroke(1.dp, Sov.Edge),
        ) {
            for (s in GameSort.entries) {
                DropdownMenuItem(
                    text = { Text(s.label, fontSize = 14.sp) },
                    onClick = { expanded = false; onSort(s) },
                    leadingIcon = { Box(Modifier.size(14.dp)) { if (s == sort) LucideIcon(Lucide.Check, size = 14.dp) } },
                    colors = MenuDefaults.itemColors(textColor = Sov.Slate200),
                    modifier = Modifier.height(40.dp),
                )
            }
        }
    }
}

/** A cover in the grid. Reading only its own game keeps a focus move to two recompositions. */
@Composable
private fun CoverCard(
    game: Game,
    system: SystemSummary,
    memory: FocusMemory,
    isOpen: Boolean,
    initial: Boolean,
    onOpen: () -> Unit,
) {
    val brush = remember(system.color) { tile(system.colour()) }
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusCard(
            onOpen,
            Modifier.fillMaxWidth().aspectRatio(3 / 4f).remembersFocus(memory, "game:${game.id}", initial).borderIf(isOpen, 2.dp, Sov.Accent, shape),
            shape,
        ) {
            // The tile and title are only a stand-in: once the cover is drawn they are not (a
            // cover hides them anyway, and drawing them under it costs fill rate on every frame).
            var loaded by remember(game.id) { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().then(if (loaded) Modifier else Modifier.background(brush)), contentAlignment = Alignment.Center) {
                if (!loaded) {
                    Text(game.title, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 4,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                }
                Art(game.thumb ?: game.image, Modifier.fillMaxSize(), onLoaded = { loaded = it })
                if (game.playCount > 0) {
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                        LucideIcon(Lucide.CircleCheck, size = 16.dp)
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(game.title, color = Sov.Slate100, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val players = maxPlayers(game.players)
            val line = (game.year ?: game.tags.firstOrNull() ?: system.short) + if (players != null && players >= 2) " · $players players" else ""
            Text(line, color = Sov.Slate400, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A game's details in a panel on the right edge, over the grid (which never reflows for it):
 * artwork, facts, Continue / Start over or Play, the description, saves and play history. It
 * takes the focus when it opens (Continue, else Play) and keeps it until it closes: the grid
 * behind cannot be navigated meanwhile. Back closes it and the focus returns to the card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GamePanel(
    game: Game?,
    system: SystemSummary,
    slots: List<SaveSlot>,
    auto: Boolean,
    onClose: () -> Unit,
    onPlay: (Start) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The last game stays drawn while the panel slides out: a plain holder, not state (writing
    // state read in this same composition would recompose it again).
    val last = remember { arrayOfNulls<Game>(1) }
    if (game != null) last[0] = game
    val first = remember { FocusRequester() }
    LaunchedEffect(game?.id) {
        if (game == null) return@LaunchedEffect
        // Its buttons attach a frame or two after it opens (or after coming back to the page).
        repeat(10) {
            withFrameNanos { }
            if (first.requestFocus()) return@LaunchedEffect
        }
    }
    AnimatedVisibility(game != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            game != null,
            enter = slideInHorizontally(tween(260)) { it },
            exit = slideOutHorizontally(tween(200)) { it },
        ) {
            val g = game ?: last[0] ?: return@AnimatedVisibility
            Column(
                Modifier.fillMaxHeight().widthIn(max = 448.dp).fillMaxWidth()
                    .background(Sov.Surface.copy(alpha = 0.96f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f))
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    // Traps the focus only while open: sliding out, it must let the focus go
                    // back to the card.
                    .focusProperties { onExit = { if (game != null) cancelFocusChange() } }
                    .focusGroup(),
            ) {
                Box {
                    PanelArt(g, system)
                    Box(
                        Modifier.focusProperties { canFocus = false }.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(36.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { LucideIcon(Lucide.X) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column {
                        Text(g.title, color = Color.White, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                        if (g.tags.isNotEmpty()) Text(g.tags.joinToString(" · "), color = Sov.Slate500, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            g.year?.let { Fact(Lucide.CalendarDays, it) }
                            (g.developer ?: g.publisher)?.let { Fact(Lucide.Building2, it) }
                            g.genre?.let { Fact(Lucide.Tag, it) }
                            maxPlayers(g.players)?.let { Fact(Lucide.Users, if (it == 1) "1 player" else "${g.players} players") }
                            if (g.discs > 1) Fact(Lucide.Disc3, "${g.discs} discs")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (auto) {
                            SovButton("Continue", { onPlay(Start.Continue) }, Modifier.focusRequester(first), icon = Lucide.Play, filledIcon = true)
                            SovButton("Start over", { onPlay(Start.New) }, variant = Variant.Outline, icon = Lucide.RotateCcw)
                        } else {
                            SovButton("Play", { onPlay(Start.New) }, Modifier.focusRequester(first), icon = Lucide.Play, filledIcon = true)
                        }
                    }
                    g.description?.let { Text(it, color = Sov.Slate300, fontSize = 14.sp, lineHeight = 22.sp) }
                    if (slots.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Eyebrow("Start from a save")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                for (s in slots.sortedBy { it.slot }) SaveThumb(s) { onPlay(Start.FromSlot(s.slot)) }
                            }
                        }
                    }
                    val now = remember(g.id) { System.currentTimeMillis() / 1000 }
                    val history = if (g.playCount > 0) "Played ${g.playCount}× · last ${formatAgo(GameIndex.playedAt(g.lastPlayedAt), now)}" else "Not played yet"
                    Column(Modifier.padding(top = 4.dp)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                        Text(
                            "$history · ${g.path} · ${formatSize(g.size)}",
                            color = Sov.Slate500, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelArt(g: Game, system: SystemSummary) {
    val brush = remember(system.color) { tile(system.colour()) }
    Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(brush), contentAlignment = Alignment.Center) {
        Text(system.short, color = Color.White.copy(alpha = 0.8f), fontSize = 60.sp, fontWeight = FontWeight.Black)
        Art(g.image ?: g.thumb, Modifier.fillMaxSize(), scale = if (g.image != null) ContentScale.Crop else ContentScale.Fit)
        // Its video snap, after a moment, over the artwork (muted: the menus' music plays on).
        if (g.video != null) VideoSnap(g.video, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f).background(Brush.verticalGradient(listOf(Color.Transparent, Sov.Surface.copy(alpha = 0.8f)))))
        if (g.image != null && g.thumb != null) {
            Art(g.thumb, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 12.dp).fillMaxHeight(0.5f).aspectRatio(3 / 4f).clip(RoundedCornerShape(6.dp)), scale = ContentScale.Fit)
        }
    }
}

@Composable
private fun Fact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LucideIcon(icon, tint = Sov.Slate500, size = 16.dp)
        Text(text, color = Sov.Slate300, fontSize = 14.sp)
    }

/** A save, as a button that starts the game from it: its screenshot, slot and age. */
@Composable
private fun SaveThumb(s: SaveSlot, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val now = remember { System.currentTimeMillis() / 1000 }
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.width(112.dp).clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4 / 3f).clip(shape).background(Color.Black)
                .border(if (focused) 3.dp else 1.dp, if (focused) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.1f), shape),
            contentAlignment = Alignment.Center,
        ) {
            s.screenshotUrl?.let { Screenshot(it, Modifier.fillMaxSize()) }
            if (focused) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    LucideIcon(PlayFilled, size = 22.dp)
                }
            }
        }
        Text(if (s.slot == AUTO_SLOT) "Auto save" else "Slot ${s.slot}", color = if (focused) Color.White else Sov.Slate300, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(formatAgo(s.savedAt.toLong(), now), color = Sov.Slate500, fontSize = 11.sp)
    }
}

/** Search and filters, in a drawer from the right: search, played or not, players and genres. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDrawer(
    index: GameIndex,
    query: String,
    onQuery: (String) -> Unit,
    filters: GameFilters,
    onFilters: (GameFilters) -> Unit,
    onClose: () -> Unit,
) {
    val tv = LocalForm.current.tv
    val genres = remember(index) { index.genreCounts.take(24) }
    val n = filters.count + if (query.isNotBlank()) 1 else 0
    val first = remember { FocusRequester() }
    // Drawn in the page's own window (a Dialog window loses Back on some TVs): Back closes it,
    // and the focus cannot leave it for the grid behind.
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.ButtonStart || e.key == Key.Menu)) { onClose(); true } else false
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
            Column(
                Modifier.fillMaxHeight().widthIn(max = 384.dp).fillMaxWidth().background(Sov.Surface.copy(alpha = 0.97f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f)).clickable(remember { MutableInteractionSource() }, null) {}
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
            ) {
                Row(
                    Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LucideIcon(Lucide.SlidersHorizontal)
                    Text("Filters", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (n > 0) Text("$n on", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).background(Sov.Accent).padding(horizontal = 10.dp, vertical = 2.dp))
                    Box(Modifier.weight(1f))
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) { LucideIcon(Lucide.X, tint = Sov.Slate400) }
                }
                Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    SearchField(query, onQuery, onClose, if (tv) Modifier else Modifier.focusRequester(first))
                    FilterSection("Played") {
                        Toggle(filters.played == PlayedFilter.PLAYED, {
                            onFilters(filters.copy(played = if (filters.played == PlayedFilter.PLAYED) null else PlayedFilter.PLAYED))
                        }, if (tv) Modifier.focusRequester(first) else Modifier) { ToggleText("Played before") }
                        Toggle(filters.played == PlayedFilter.UNPLAYED, {
                            onFilters(filters.copy(played = if (filters.played == PlayedFilter.UNPLAYED) null else PlayedFilter.UNPLAYED))
                        }) { ToggleText("Not played yet") }
                    }
                    if (index.knowsPlayers) {
                        FilterSection("Players") {
                            Toggle(filters.multiplayer, { onFilters(filters.copy(multiplayer = !filters.multiplayer)) }) { ToggleText("2 players or more") }
                        }
                    }
                    if (genres.isNotEmpty()) {
                        FilterSection("Genre") {
                            for ((genre, count) in genres) {
                                val on = genre in filters.genres
                                Toggle(on, { onFilters(filters.copy(genres = if (on) filters.genres - genre else filters.genres + genre)) }) {
                                    ToggleText(genre)
                                    Text("$count", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (!index.knowsPlayers && genres.isEmpty()) {
                        Text("Player and genre filters appear once game information has been fetched for these games.", color = Sov.Slate500, fontSize = 14.sp)
                    }
                }
                if (n > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                    Row(
                        Modifier.padding(20.dp).clip(RoundedCornerShape(6.dp)).clickable { onFilters(GameFilters()); onQuery("") }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LucideIcon(Lucide.X, tint = Sov.Slate400, size = 16.dp)
                        Text("Clear search and filters", color = Sov.Slate400, fontSize = 14.sp)
                    }
                }
            }
    }
}

@Composable
private fun ToggleText(text: String) = Text(text, color = Sov.Slate200, fontSize = 14.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        query, onQuery,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        singleLine = true,
        textStyle = TextStyle(color = Sov.Slate100, fontSize = 14.sp),
        cursorBrush = SolidColor(Sov.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        decorationBox = { inner ->
            Row(
                Modifier.height(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f))
                    .border(1.dp, if (focused) Sov.Accent else Color.White.copy(alpha = 0.15f), CircleShape).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LucideIcon(Lucide.Search, tint = Sov.Slate500, size = 16.dp)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search titles, makers, genres", color = Sov.Slate500, fontSize = 14.sp)
                    inner()
                }
            }
        },
    )
}

/** Jump to a letter: a carousel of letter discs, the current one large in the middle. */
@Composable
private fun JumpStrip(stops: List<String>, index: Int, onIndex: (Int) -> Unit, onCommit: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().background(Sov.Surface.copy(alpha = 0.5f)).clickable(remember { MutableInteractionSource() }, null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .focusRequester(focus)
                .focusProperties { onExit = { cancelFocusChange() } }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { onIndex((index - 1).coerceAtLeast(0)); true }
                        Key.DirectionRight -> { onIndex((index + 1).coerceAtMost(stops.lastIndex)); true }
                        Key.DirectionCenter, Key.Enter, Key.ButtonA, Key.NumPadEnter -> { onCommit(); true }
                        else -> false
                    }
                }
                .focusable()
                .clip(CircleShape)
                .background(Sov.Surface.copy(alpha = 0.95f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = index > 0) { onIndex(index - 1) }, contentAlignment = Alignment.Center) {
                if (index > 0) LucideIcon(Lucide.ChevronLeft, tint = Sov.Slate400)
            }
            Row(Modifier.width(152.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                for (i in (index - 3).coerceAtLeast(0) until index) Disc(stops[i]) { onIndex(i) }
            }
            Box(Modifier.size(64.dp).clip(CircleShape).background(Sov.Accent.copy(alpha = 0.2f)).border(2.dp, Sov.Accent, CircleShape).clickable(onClick = onCommit), contentAlignment = Alignment.Center) {
                Text(stops[index], color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.width(152.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in index + 1..(index + 3).coerceAtMost(stops.lastIndex)) Disc(stops[i]) { onIndex(i) }
            }
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = index < stops.lastIndex) { onIndex(index + 1) }, contentAlignment = Alignment.Center) {
                if (index < stops.lastIndex) LucideIcon(Lucide.ChevronRight, tint = Sov.Slate400)
            }
        }
    }
}

@Composable
private fun Disc(stop: String, onClick: () -> Unit) =
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(stop, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
