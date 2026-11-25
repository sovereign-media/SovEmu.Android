package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import media.sovereign.sovemu.Load
import media.sovereign.sovemu.core.HomeFeed
import media.sovereign.sovemu.core.SystemSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's "Top rated" row: shown only when the server has rated games, and a card opens the game. */
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class TopRatedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<Pair<String, Int?>>()

    private fun home(feed: HomeFeed) = rule.setContent {
        TestApp {
            HomeScreen(
                Load.Ready(feed),
                listOf(system()),
                onPlay = { _, _ -> },
                onSystem = { s: SystemSummary, open -> opened += s.id to open },
                onSearch = {},
                server = "http://h:8080",
                onRetry = {},
                onSignOut = {},
            )
        }
    }

    @Test
    fun aTopRatedCardOpensTheGameOnItsSystemsPage() {
        home(HomeFeed(featured = emptyList(), recent = emptyList(), added = emptyList(), topRated = listOf(game(id = 9, title = "Zelda"))))
        rule.onNodeWithText("Top rated").assertExists()
        rule.onNodeWithText("Zelda").assertExists()
        // The card's tile (it shows the system, the game having no art here); the Systems row's
        // card comes first, the Top rated row being the last. Below the fold, so the click is
        // the card's own action rather than a touch.
        rule.onAllNodes(hasClickAction() and hasText("NES")).onLast().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("nes" to 9), opened)
    }

    @Test
    fun withNoRatedGamesTheRowIsHidden() {
        home(HomeFeed(featured = emptyList(), recent = emptyList(), added = emptyList()))
        rule.onNodeWithText("Top rated").assertDoesNotExist()
    }
}
