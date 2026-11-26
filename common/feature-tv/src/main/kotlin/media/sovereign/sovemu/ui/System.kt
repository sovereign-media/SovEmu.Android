package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.Load
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.attempt
import media.sovereign.sovemu.core.AUTO_SLOT
import media.sovereign.sovemu.core.CollectionsModel
import media.sovereign.sovemu.core.DEFAULT_BOX_ASPECT
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameFilters
import media.sovereign.sovemu.core.GameIndex
import media.sovereign.sovemu.core.GamePatch
import media.sovereign.sovemu.core.GameSort
import media.sovereign.sovemu.core.ListFilter
import media.sovereign.sovemu.core.PlayedFilter
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.artFills
import media.sovereign.sovemu.core.esrbInfo
import media.sovereign.sovemu.core.formatAgo
import media.sovereign.sovemu.core.formatSize
import media.sovereign.sovemu.core.graphql.type.EsrbRating
import media.sovereign.sovemu.core.graphql.type.PartKind
import media.sovereign.sovemu.core.jumpStops
import media.sovereign.sovemu.core.jumpTo
import media.sovereign.sovemu.core.letterOf
import media.sovereign.sovemu.core.maxPlayers
import media.sovereign.sovemu.core.nextPatch
import media.sovereign.sovemu.core.starsText
import media.sovereign.sovemu.core.year

/**
 * One system's games, as the web client's system page: a header with a capsule of chips (Sort,
 * Filters, Jump), a grid of covers, and a side panel with the chosen game's details. With the
 * panel open the D-pad stays in it (nothing behind it can take the focus); B closes it into the
 * grid, on its game. Start opens the filters.
 *
 * Opened on a game from another screen ([openFirst]: Home, Search), the grid scrolls to it and
 * its panel opens; closing the panel lands on its card in the grid, as from any other game.
 */
@Composable
fun SystemScreen(
    panel: GamePanelData,
    system: SystemSummary,
    index: Load<GameIndex>,
    openFirst: Int?,
    sort: GameSort,
    onSort: (GameSort) -> Unit,
    onPlay: (Game, Start) -> Unit,
    onFavourite: suspend (Game, Boolean) -> String?,
    onPatch: suspend (Game, GamePatch?) -> String?,
    modifier: Modifier = Modifier,
) {
    val colour = system.colour()
    // The page's tint: the system's colour from the top-left corner, as on the web. It is the
    // page's only full-screen fill (NavFrame skips its own), and it reaches under the dock.
    val tint = remember(colour) {
        Brush.radialGradient(
            listOf(lerp(Sov.Surface, colour, 0.25f), Sov.Surface),
            center = Offset.Zero,
            radius = 1800f,
        )
    }
    val dock = 64.dp
    Box(
        modifier.fillMaxSize().drawBehind {
            translate(left = -dock.toPx()) {
                drawRect(tint, size = size.copy(width = size.width + dock.toPx()))
            }
        },
    ) {
        when (index) {
            Load.Loading -> Header(
                system, null, null, sort, onSort, 0, {}, null,
                Modifier.padding(32.dp).statusBarsPadding(),
            )

            is Load.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(index.message, color = Sov.Red300)
            }

            is Load.Ready -> Games(
                panel, system, index.value, openFirst, sort, onSort, onPlay, onFavourite, onPatch,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Games(
    panel: GamePanelData,
    system: SystemSummary,
    index: GameIndex,
    openFirst: Int?,
    sort: GameSort,
    onSort: (GameSort) -> Unit,
    onPlay: (Game, Start) -> Unit,
    onFavourite: suspend (Game, Boolean) -> String?,
    onPatch: suspend (Game, GamePatch?) -> String?,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable(stateSaver = FiltersSaver) { mutableStateOf(GameFilters()) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var collectionsOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Counts the collections drawer's closings: each sends the focus back to the panel's button.
    var backToCollections by remember { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableStateOf(openFirst) }
    var jump by remember { mutableStateOf<Int?>(null) }
    val slots by panel.saves.collectAsStateWithLifecycle()
    val patches by panel.patches.collectAsStateWithLifecycle()
    val memory = rememberFocusMemory()
    val grid = rememberLazyGridState()
    // Back from a game with the panel open: the panel takes the focus, not the card behind it.
    remember { if (openId != null) memory.pending = null }

    // Every visible card reads this list, so it changes only when the inputs do. The index is
    // in title order already: filtering and re-sorting 1,000 games is well under a frame.
    val shown = remember(index, query, filters, sort) { index.query(query, filters, sort) }
    val stops = remember(shown) { jumpStops(shown) }
    val open = remember(openId, index) {
        openId?.let { id -> index.games.firstOrNull { it.id == id } }
    }
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
    // The panel's patches and saves, which the ViewModel asks for after a pause. A patched game's
    // saves are its own: they change with the patch.
    LaunchedEffect(openId) { notice = null }
    LaunchedEffect(openId, open?.patch) { panel.show(openId, open?.patch) }

    fun play(g: Game, start: Start) = onPlay(g, start)

    /**
     * An overlay closed: the focus goes back to the card it came from (it went with the
     * overlay).
     */
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
                Key.ButtonStart, Key.Menu -> {
                    if (openId == null) filtersOpen = true
                    true
                }

                else -> false
            }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 32.dp,
                end = 32.dp,
                bottom = 112.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Header(
                    system, shown.size, index.games.size, sort, onSort,
                    filters.count + if (query.isNotBlank()) 1 else 0,
                    { filtersOpen = true },
                    if (shown.size > 12 && stops.size > 1) {
                        {
                            jump = stops.indexOf(
                                shown.getOrNull(grid.firstVisibleItemIndex)?.title?.let(::letterOf),
                            ).coerceAtLeast(0)
                        }
                    } else {
                        null
                    },
                    Modifier.statusBarsPadding().padding(top = 40.dp, bottom = 8.dp),
                    canJump = sort == GameSort.TITLE,
                )
            }
            if (shown.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (index.games.isEmpty()) {
                            "No games for this system yet."
                        } else {
                            "No games match. Try fewer words, or clear the filters."
                        },
                        color = Sov.Slate400,
                        modifier = Modifier
                            .padding(vertical = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(12.dp),
                            )
                            .padding(vertical = 80.dp, horizontal = 32.dp),
                    )
                }
            }
            itemsIndexed(shown, key = { _, g -> g.id }, contentType = { _, _ -> "game" }) { i, g ->
                CoverCard(
                    g,
                    system,
                    memory,
                    isOpen = g.id == openId,
                    initial = i == 0,
                ) { openId = g.id }
            }
        }

        GamePanel(
            open, system, slots, auto, onClose = ::closePanel,
            onPlay = { start -> open?.let { play(it, start) } },
            onFavourite = {
                open?.let { g ->
                    scope.launch {
                        notice = onFavourite(g, !g.favourite)?.let {
                            "Could not change favourites: $it"
                        }
                    }
                }
            },
            onCollections = { collectionsOpen = true },
            patches = patches,
            onPatch = { patch ->
                open?.let { g ->
                    scope.launch {
                        notice = onPatch(g, patch)?.let { "Could not change the patch: $it" }
                    }
                }
            },
            notice = notice,
            backToCollections = backToCollections,
            trapFocus = !collectionsOpen,
        )

        if (collectionsOpen && open != null) {
            // The drawer draws; its state and the calls behind it are the screen's.
            val collections = remember(open.id) { panel.collections(open.id) }
            LaunchedEffect(collections) { collections.load() }
            val choices by collections.choices.collectAsStateWithLifecycle()
            val collectionError by collections.error.collectAsStateWithLifecycle()
            CollectionsDrawer(
                open,
                choices,
                collectionError,
                collections::toggle,
                collections::create,
                onClose = {
                    collectionsOpen = false
                    backToCollections++
                },
            )
        }
        if (filtersOpen) {
            FilterDrawer(
                index,
                query,
                { query = it },
                filters,
                { filters = it },
                onClose = {
                    filtersOpen = false
                    refocus()
                },
                search = {
                    // A box the D-pad passes over until A swaps in the field (a focused text
                    // field opens a TV's keyboard at once, and the keyboard keeps the D-pad).
                    var typing by remember { mutableStateOf(false) }
                    TvTextField(
                        "Search titles, makers, genres",
                        query,
                        { query = it },
                        typing,
                        { typing = it },
                        action = ImeAction.Search,
                        onAction = { typing = false },
                    )
                },
            )
        }
        jump?.let { at ->
            JumpStrip(
                stops,
                at,
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
    canJump: Boolean = true,
) {
    val title = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SystemBadge(system.short, system.colour(), large = true)
            Text(
                system.name,
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (total != null) {
                Text(
                    if (shown == total) "$total games" else "$shown of $total",
                    color = Sov.Slate400,
                    fontSize = 14.sp,
                )
            }
            if (system.biosMissing) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0x33F59E0B))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LucideIcon(Lucide.Microchip, tint = Sov.Amber200, size = 14.dp)
                    Text(
                        "BIOS missing: games will not start",
                        color = Sov.Amber200,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
    val chips = @Composable {
        if (total != null) {
            ChipStrip {
                SortChip(sort, onSort)
                ChipDivider()
                Chip(
                    if (filterCount > 0) "Filters ($filterCount)" else "Filters",
                    Lucide.SlidersHorizontal,
                    onFilters,
                    on = filterCount > 0,
                )
                // Kept in place outside title order, dimmed, so the strip does not redraw
                // without it: letters only mean something in title order.
                if (onJump != null) Chip("Jump", Lucide.BookA, onJump, enabled = canJump)
            }
        }
    }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.weight(1f)) { title() }
        chips()
    }
}
