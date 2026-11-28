package media.sovereign.sovemu.core

/**
 * A page of a game grid, as the shoulder buttons move by it: the rows on screen. Moving a page
 * keeps the column (a page is whole rows), stopping at the first and last game.
 */
object GridPage {
    /**
     * Where a page [forward] (or back) from [current] lands, in a grid of [count] games laid
     * [columns] across with [rows] on screen; [current] when there is nowhere to go.
     */
    fun target(current: Int, count: Int, columns: Int, rows: Int, forward: Boolean): Int {
        if (count <= 0) return current
        val step = columns.coerceAtLeast(1) * rows.coerceAtLeast(1)
        val to = if (forward) current + step else current - step
        return to.coerceIn(0, count - 1)
    }
}
