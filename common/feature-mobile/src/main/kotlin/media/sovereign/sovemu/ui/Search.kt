package media.sovereign.sovemu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.Load
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.attempt
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameSearch
import media.sovereign.sovemu.core.SearchResults
import media.sovereign.sovemu.core.SystemSummary

/** How many results the server sends (`Query.search`'s default). */
private const val SEARCH_LIMIT = 48

/**
 * Search across every system, as the web client's search page: a field (A types, the keyboard's
 * search key or Back returns to the D-pad), then the matching games as covers; A on one opens
 * its system with its panel open. The query is kept with the screen, so Back from that system
 * shows the same results with the focus on the game it came from.
 */
@Composable
fun SearchScreen(
    results: SearchResults?,
    onQuery: (String) -> Unit,
    systems: List<SystemSummary>,
    onOpen: (Game) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Asking (after a pause in typing, the latest query only) is the ViewModel's; this draws it.
    val byId = remember(systems) { systems.associateBy { it.id } }
    val memory = rememberFocusMemory()

    LaunchedEffect(query) { onQuery(query) }

    val found = (results as? SearchResults.Found)?.games.orEmpty()
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    /** Scrolls to the [i]th game found (after the header) and focuses it. */
    fun focusGame(i: Int) {
        val g = found.getOrNull(i) ?: return
        scope.launch {
            if (grid.layoutInfo.visibleItemsInfo.none { it.key == g.id }) grid.scrollToItem(i + 1)
            withFrameNanos { }
            memory.focus("game:${g.id}")
        }
    }
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            state = grid,
            // L1 and R1 move a page of games.
            modifier = Modifier.fillMaxSize().focusRestorer().shoulderPaging(
                grid,
                current = { focusedGame(memory, found) },
                count = { found.size },
                focus = ::focusGame,
            ),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                androidx.compose.foundation.layout.Column(
                    Modifier.statusBarsPadding().padding(top = 40.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Search",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(Modifier.widthIn(max = 640.dp)) {
                        PhoneTextField(
                            "Search every system: titles, makers, genres",
                            query,
                            { query = it.take(100) },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    val line = when (val r = results) {
                        null -> ""

                        is SearchResults.Failed -> r.message

                        is SearchResults.Found -> when (r.games.size) {
                            0 -> "No games match. Try fewer words."
                            1 -> "1 game"
                            SEARCH_LIMIT -> "The first $SEARCH_LIMIT games"
                            else -> "${r.games.size} games"
                        }
                    }
                    Text(
                        line,
                        color = if (results is SearchResults.Failed) Sov.Red300 else Sov.Slate400,
                        fontSize = 14.sp,
                    )
                }
            }
            items(found, key = { it.id }, contentType = { "game" }) { g ->
                Box(contentAlignment = Alignment.TopCenter) {
                    GameCard(
                        g,
                        byId[g.system],
                        wide = false,
                        memory = memory,
                        key = "game:${g.id}",
                    ) { onOpen(g) }
                }
            }
        }
    }
}
