package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import media.sovereign.sovemu.Load
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameIndex
import media.sovereign.sovemu.core.HomeFeed
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.graphql.fragment.GameFields
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A "Continue playing" card opens Home's own panel (where the player left off) rather than
 * starting the game or leaving Home: Continue takes the focus and resumes the auto save, Game
 * details goes on to the game's panel on its system's page, and Back closes it, giving the
 * focus back to the card. Back from that system page's panel lands in its grid, on the game.
 */
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class ContinuePlayingTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val played = mutableListOf<Pair<Int, Start>>()
    private val opened = mutableListOf<Pair<String, Int?>>()

    private fun home(recent: Game) = rule.setContent {
        TestApp {
            HomeScreen(
                Load.Ready(
                    HomeFeed(featured = emptyList(), recent = listOf(recent), added = emptyList()),
                ),
                listOf(system()),
                onPlay = { g, start -> played += g.id to start },
                onSystem = { s: SystemSummary, open -> opened += s.id to open },
                onSearch = {},
                server = "http://h:8080",
                onRetry = {},
                onSignOut = {},
            )
        }
    }

    /** The card above the title (its tile shows the system, the game having no art here); the
     * Continue row is the first row. */
    private fun openCard() {
        rule.onAllNodes(hasClickAction() and hasText("NES")).onFirst().performClick()
        rule.waitForIdle()
    }

    private val saved = GameFields.AutoSave(slot = 0, savedAt = 0.0, screenshotUrl = null)

    @Test
    fun aContinueCardOpensHomesPanelWithContinueFocused() {
        home(game(id = 7, title = "Zelda", playCount = 3, autoSave = saved))
        openCard()
        rule.onNodeWithText("Continue").assertIsFocused()
        rule.onNodeWithText("Played 3×", substring = true).assertExists()
        assertEquals(emptyList<Pair<String, Int?>>(), opened)
        assertEquals(emptyList<Pair<Int, Start>>(), played)

        rule.onNodeWithText("Continue").performClick()
        assertEquals(listOf(7 to Start.Continue), played)
    }

    @Test
    fun gameDetailsOpensTheGamesPanelOnItsSystemsPage() {
        home(game(id = 7, title = "Zelda", autoSave = saved))
        openCard()
        rule.onNodeWithText("Game details").performClick()
        assertEquals(listOf("nes" to 7), opened)
        assertEquals(emptyList<Pair<Int, Start>>(), played)
    }

    @Test
    fun withNoAutoSaveItOffersPlay() {
        home(game(id = 7, title = "Zelda"))
        openCard()
        rule.onNodeWithText("Play").assertIsFocused()
        rule.onNodeWithText("Continue").assertDoesNotExist()
        rule.onNodeWithText("Play").performClick()
        assertEquals(listOf(7 to Start.New), played)
    }

    @Test
    fun backClosesThePanelAndTheCardTakesTheFocusBack() {
        home(game(id = 7, title = "Zelda", autoSave = saved))
        openCard()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Game details").assertDoesNotExist()
        rule.onAllNodes(hasClickAction() and hasText("NES")).onFirst().assertIsFocused()
    }

    /** Continue playing → Game details opens the game's panel on its system's page; Back from
     *  that panel lands on its card in the grid, not back on Home. */
    @Test
    fun backFromThePanelItWasOpenedOnLandsInTheGrid() {
        rule.setContent {
            TestApp {
                SystemScreen(
                    NoPanelData, system(),
                    Load.Ready(
                        GameIndex(listOf(game(id = 7, title = "Zelda"))),
                    ),
                    openFirst = 7, sort = media.sovereign.sovemu.core.GameSort.TITLE, onSort = {},
                    onPlay = { _, _ -> }, onFavourite = { _, _ -> null },
                    onPatch = { _, _ -> null },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Play").assertIsFocused()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Play").assertDoesNotExist()
        rule.onAllNodes(hasClickAction() and hasText("Zelda")).onFirst().assertIsFocused()
    }
}
