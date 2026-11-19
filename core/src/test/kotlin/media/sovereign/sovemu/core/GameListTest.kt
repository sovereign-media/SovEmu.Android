package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameListTest {
    private val games = listOf(
        game(1, "Super Mario Bros.", "Platform", "1-2", 3, "2026-11-01 10:00:00", "1985-09-13", "Nintendo"),
        game(2, "Pokémon Red", "RPG", "1", 1, "2026-11-10 10:00:00", "1996-02-27"),
        game(3, "Mega Man 10", "Platform, Action", "1"),
        game(4, "Mega Man 2", "Platform, Action", "1", releaseDate = "1988"),
        game(5, "1942", "Shooter", "1 to 2"),
        game(6, "the Legend of Zelda", "Adventure", favourite = true),
    )
    private val index = GameIndex(games)
    private fun ids(list: List<Game>) = list.map { it.id }

    @Test
    fun maxPlayersReadsEveryForm() {
        assertEquals(2, maxPlayers("1-2"))
        assertEquals(4, maxPlayers("1 to 4"))
        assertEquals(1, maxPlayers("1"))
        assertNull(maxPlayers(null))
        assertNull(maxPlayers("unknown"))
    }

    @Test
    fun titlesSortNaturallyIgnoringCaseAndAccents() {
        assertEquals(listOf(5, 4, 3, 2, 1, 6), ids(index.query("", GameFilters(), GameSort.TITLE)))
    }

    @Test
    fun searchFoldsAccentsAndNeedsEveryWord() {
        assertEquals(listOf(2), ids(index.query("pokemon", GameFilters(), GameSort.TITLE)))
        assertEquals(listOf(4, 3), ids(index.query("mega action", GameFilters(), GameSort.TITLE)))
        assertEquals(listOf(1), ids(index.query("nintendo", GameFilters(), GameSort.TITLE)))
        assertTrue(index.query("mega rpg", GameFilters(), GameSort.TITLE).isEmpty())
    }

    @Test
    fun filtersCombine() {
        assertEquals(listOf(2, 1), ids(index.query("", GameFilters(played = PlayedFilter.PLAYED), GameSort.TITLE)))
        assertEquals(listOf(5, 1), ids(index.query("", GameFilters(multiplayer = true), GameSort.TITLE)))
        assertEquals(listOf(4, 3, 1), ids(index.query("", GameFilters(genres = setOf("Platform")), GameSort.TITLE)))
        assertEquals(listOf(6), ids(index.query("", GameFilters(favourites = true), GameSort.TITLE)))
        assertEquals(2, GameFilters(played = PlayedFilter.UNPLAYED, genres = setOf("RPG")).count)
        assertEquals(1, GameFilters(favourites = true).count)
    }

    @Test
    fun sortsFallBackToTitle() {
        assertEquals(listOf(2, 1), ids(index.query("", GameFilters(), GameSort.RECENT)).take(2))
        assertEquals(listOf(1, 4, 2), ids(index.query("", GameFilters(), GameSort.YEAR)).take(3))
        assertEquals(listOf(1, 2, 5), ids(index.query("", GameFilters(), GameSort.PLAYS)).take(3))
    }

    @Test
    fun genresAreCountedMostCommonFirst() {
        assertEquals("Platform" to 3, index.genreCounts.first())
        assertTrue(index.knowsPlayers)
        assertFalse(GameIndex(listOf(game(1, "A"))).knowsPlayers)
    }

    @Test
    fun jumpStripStopsAndTargets() {
        val sorted = index.query("", GameFilters(), GameSort.TITLE)
        assertEquals(listOf("#", "M", "P", "S", "T"), jumpStops(sorted))
        assertEquals(1, jumpTo(sorted, "M"))
        // No game under N: the next letter that has one.
        assertEquals(3, jumpTo(sorted, "N"))
        assertEquals(sorted.size - 1, jumpTo(sorted, "Z"))
        assertEquals(-1, jumpTo(emptyList(), "A"))
        assertEquals("#", letterOf("1942"))
        assertEquals("E", letterOf("Évoland"))
    }

    @Test
    fun formatting() {
        assertEquals("1985 · Nintendo · Platform", games[0].byline)
        assertEquals("3 hours ago", formatAgo(1000, 1000 + 3 * 3600))
        assertEquals("1.1 GB", formatSize(1_137_133_112.0))
        assertEquals(1762250400L, GameIndex.playedAt("2025-11-04 10:00:00"))
    }

    @Test
    fun releaseDatesAndPlayersReadNaturally() {
        assertEquals("March 11, 1995", formatReleaseDate("1995-03-11"))
        assertEquals("March 1995", formatReleaseDate("1995-03"))
        assertEquals("1995", formatReleaseDate("1995"))
        assertNull(formatReleaseDate(null))
        assertNull(formatReleaseDate("unknown"))
        assertEquals("1 player", formatPlayers("1"))
        assertEquals("1-2 players", formatPlayers("1 - 2"))
        assertEquals("4 players", formatPlayers("4"))
        assertEquals("Up to 4 players", formatPlayers("1 to 4"))
        assertNull(formatPlayers(null))
    }

    @Test
    fun slotPickersOfferWhatCanBePicked() {
        val saves = listOf(save(AUTO_SLOT), save(2))
        val load = slotRows(saves, listOf(AUTO_SLOT) + SAVE_SLOTS, emptyEnabled = false)
        assertEquals(listOf("Auto save", "Slot 1", "Slot 2", "Slot 3", "Slot 4"), load.map { it.label })
        assertEquals(listOf(true, false, true, false, false), load.map { it.enabled })
        val saving = slotRows(saves, SAVE_SLOTS, emptyEnabled = true)
        assertTrue(saving.all { it.enabled })
        assertEquals(2, saving.single { it.save != null }.slot)
    }
}
