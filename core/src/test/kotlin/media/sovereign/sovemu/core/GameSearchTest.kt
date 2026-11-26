package media.sovereign.sovemu.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Searching as it is typed: after a pause, the latest query only. */
@OptIn(ExperimentalCoroutinesApi::class)
class GameSearchTest {
    private val asked = mutableListOf<String>()
    private val none: suspend (String) -> List<Game> = {
        asked += it
        emptyList()
    }

    @Test
    fun itAsksAfterAPauseAndOnlyForTheLatestQuery() = runTest {
        val search = GameSearch(none, this)
        search.query("ma")
        advanceTimeBy(100)
        search.query("mar")
        advanceTimeBy(100)
        search.query(" mario ")
        advanceTimeBy(GameSearch.DELAY_MS - 1)
        assertEquals("not yet: still typing", emptyList<String>(), asked)
        advanceUntilIdle()
        assertEquals(listOf("mario"), asked)
        assertEquals(SearchResults.Found(emptyList()), search.results.value)
    }

    @Test
    fun clearingTheFieldClearsTheResultsWithoutAsking() = runTest {
        val search = GameSearch(none, this)
        search.query("zelda")
        advanceUntilIdle()
        search.query("  ")
        advanceUntilIdle()
        assertNull(search.results.value)
        assertEquals(listOf("zelda"), asked)
    }

    @Test
    fun aFailedSearchSaysWhy() = runTest {
        val search = GameSearch({ throw ApiException("INTERNAL", "the server is busy") }, this)
        search.query("metroid")
        advanceUntilIdle()
        assertEquals(SearchResults.Failed("the server is busy"), search.results.value)
    }
}
