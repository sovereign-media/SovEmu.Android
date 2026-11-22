package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import media.sovereign.sovemu.Load
import media.sovereign.sovemu.core.GameIndex
import media.sovereign.sovemu.core.HomeFeed
import media.sovereign.sovemu.core.SystemSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A "Continue playing" card opens the game's panel (Continue or Start over) on its system's page
 * rather than starting the game, and Back from that panel goes straight back to Home.
 */
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class ContinuePlayingTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aContinueCardOpensTheGamesPanel() {
        val played = mutableListOf<Int>()
        val opened = mutableListOf<Pair<String, Int?>>()
        val recent = game(id = 7, title = "Zelda")
        rule.setContent {
            TestApp {
                HomeScreen(
                    Load.Ready(HomeFeed(featured = emptyList(), recent = listOf(recent), added = emptyList())),
                    listOf(system()),
                    onPlay = { g, _ -> played += g.id },
                    onSystem = { s: SystemSummary, open -> opened += s.id to open },
                    onSearch = {},
                )
            }
        }
        // The card above the title (its tile shows the system, the game having no art here); the
        // Continue row is the first row.
        rule.onAllNodes(hasClickAction() and hasText("NES")).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(listOf("nes" to 7), opened)
        assertEquals(emptyList<Int>(), played)
    }

    @Test
    fun backFromThePanelItWasOpenedOnLeaves() {
        var left = 0
        rule.setContent {
            TestApp {
                SystemScreen(
                    LocalApi.current, system(), Load.Ready(GameIndex(listOf(game(id = 7, title = "Zelda")))),
                    openFirst = 7, sort = media.sovereign.sovemu.core.GameSort.TITLE, onSort = {},
                    onPlay = { _, _ -> }, onFavourite = { _, _ -> null }, onCollectionsChanged = {},
                    onPatch = { _, _ -> null }, onLeave = { left++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Play").assertIsFocused()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, left)
    }
}
