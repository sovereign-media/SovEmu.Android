package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The shoulder buttons move a grid by the rows on screen, keeping the column. */
class GridPageTest {
    @Test
    fun `a page is the rows on screen, in the same column`() {
        // Six across, three rows on screen: from the second game of the first row to the
        // second game of the fourth row, and back.
        assertEquals(19, GridPage.target(1, 100, columns = 6, rows = 3, forward = true))
        assertEquals(1, GridPage.target(19, 100, columns = 6, rows = 3, forward = false))
    }

    @Test
    fun `it stops at the first and last game`() {
        assertEquals(99, GridPage.target(90, 100, columns = 6, rows = 3, forward = true))
        assertEquals(0, GridPage.target(5, 100, columns = 6, rows = 3, forward = false))
        assertEquals(3, GridPage.target(3, 0, columns = 6, rows = 3, forward = true))
    }

    @Test
    fun `a grid it cannot measure still moves a row`() {
        assertEquals(1, GridPage.target(0, 10, columns = 0, rows = 0, forward = true))
    }
}
