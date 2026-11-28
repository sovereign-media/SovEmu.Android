package media.sovereign.sovemu.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GridPage

/**
 * The shoulder buttons (L1, R1; Page Up and Page Down on a keyboard) move the focus a page back
 * or forward in a game grid: by the rows on screen, in the same column, so a long list is crossed
 * in a few presses rather than a row at a time. [current] is the focused game's place in the
 * grid's games (null while none is focused, when the keys do nothing), [count] how many there
 * are, and [focus] scrolls to one and focuses it. [enabled] false (a panel open over the grid)
 * leaves the keys alone.
 */
fun Modifier.shoulderPaging(
    grid: LazyGridState,
    current: () -> Int?,
    count: () -> Int,
    focus: (Int) -> Unit,
    enabled: () -> Boolean = { true },
): Modifier = onPreviewKeyEvent { e ->
    val forward = when (e.key) {
        Key.ButtonR1, Key.PageDown -> true
        Key.ButtonL1, Key.PageUp -> false
        else -> return@onPreviewKeyEvent false
    }
    if (!enabled()) return@onPreviewKeyEvent false
    val at = current() ?: return@onPreviewKeyEvent false
    // The release is the press's: taken too, so it reaches nothing else.
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
    val (columns, rows) = grid.page()
    val to = GridPage.target(at, count(), columns, rows, forward)
    if (to != at) focus(to)
    true
}

/**
 * The grid's games across and the rows of them on screen, even in part, from its last layout
 * (the header, which spans the grid, counted out). Partly shown rows count: a TV's covers are
 * tall enough that one row is often the only whole one, and a page of one row would be no page.
 */
private fun LazyGridState.page(): Pair<Int, Int> {
    val info = layoutInfo
    val games = info.visibleItemsInfo.filter { it.contentType == "game" }
    val columns = games.maxOfOrNull { it.column }?.plus(1) ?: 1
    val rows = games.filter {
        it.offset.y < info.viewportEndOffset &&
            it.offset.y + it.size.height > info.viewportStartOffset
    }.map { it.row }.distinct().size
    return columns to rows.coerceAtLeast(1)
}

/** Where the game [memory] last focused (`game:<id>`) is in [games]; null when none of them. */
fun focusedGame(memory: FocusMemory, games: List<Game>): Int? {
    val id = memory.last?.removePrefix("game:")?.takeIf { it != memory.last } ?: return null
    return games.indexOfFirst { it.id.toString() == id }.takeIf { it >= 0 }
}
