package media.sovereign.sovemu.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GamePatch
import media.sovereign.sovemu.core.graphql.type.EsrbRating
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The game panel over a system's grid: it takes the focus when it opens, keeps the D-pad inside
 * while open, and lets an overlay drawn over it (the collections drawer) take the focus.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class GamePanelTest {
    @get:Rule
    val rule = createComposeRule()

    private var open: Game? by mutableStateOf(game())
    private var patches: List<GamePatch> = emptyList()
    private var chosen: GamePatch? = null
    private var trapFocus by mutableStateOf(true)
    private val outside = FocusRequester()
    private var favourites = 0
    private var collections = 0
    private lateinit var focusManager: FocusManager

    /** A card of the grid behind the panel (left of it), and the panel. */
    private fun show() = rule.setContent {
        TestApp {
            focusManager = LocalFocusManager.current
            Row {
                Box(Modifier.testTag("card").size(40.dp).focusRequester(outside).focusable()) {
                    Text("card")
                }
                Box {
                    GamePanel(
                        open, system(), slots = emptyList(), auto = false,
                        onClose = { open = null }, onPlay = {},
                        onFavourite = { favourites++ }, onCollections = { collections++ },
                        trapFocus = trapFocus,
                        patches = patches,
                        onPatch = { chosen = it },
                    )
                }
            }
        }
    }

    @Test
    fun openingFocusesPlay() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun theDpadCannotLeaveAnOpenPanel() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Play").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithText("Play").performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("card").assertIsNotFocused()
    }

    @Test
    fun withoutTheTrapTheDpadWouldLeave() {
        // The control for the test above: the card is within the D-pad's reach.
        show()
        rule.waitForIdle()
        trapFocus = false
        rule.waitForIdle()
        rule.onNodeWithText("Play").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithTag("card").assertIsFocused()
    }

    @Test
    fun anOverlayCanTakeTheFocusWhenTheTrapIsOff() {
        // Regression: with the trap on, the collections drawer could not take the focus, and the
        // D-pad and A went on to the panel behind it (A started the game).
        show()
        rule.waitForIdle()
        trapFocus = false
        rule.waitForIdle()
        rule.runOnIdle { outside.requestFocus() }
        rule.onNodeWithTag("card").assertIsFocused()
    }

    @Test
    fun withTheTrapOnAnOutsideRequestIsRefused() {
        show()
        rule.waitForIdle()
        rule.runOnIdle { outside.requestFocus() }
        rule.onNodeWithTag("card").assertIsNotFocused()
        rule.onNodeWithText("Play").assertIsFocused()
    }

    /** Regression: back from a game, the focus could land in the grid behind the open panel (the
     *  panel had stopped asking for it), and the D-pad then moved through the grid behind it. */
    @Test
    fun focusThatLandsBehindAnOpenPanelIsBroughtBack() {
        show()
        rule.waitForIdle()
        rule.runOnIdle {
            focusManager.clearFocus(force = true)
            outside.requestFocus()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("card").assertIsNotFocused()
        rule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun favouriteAndCollectionsAreButtons() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Add to favourites").performClick()
        rule.onNodeWithText("Collections").performClick()
        assertEquals(1, favourites)
        assertEquals(1, collections)
        open = game(favourite = true)
        rule.onNodeWithText("Favourite").assertExists()
    }

    @Test
    fun aReleaseLabelTellsSameTitledGamesApart() {
        show()
        rule.waitForIdle()
        open = game(title = "Final Fantasy VII", release = "Translation")
        rule.onNodeWithText("Translation").assertExists()
    }

    @Test
    fun aGameWithoutPatchesShowsNoPatchButton() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Patch: Original").assertDoesNotExist()
    }

    @Test
    fun thePatchButtonStepsToTheNextPatch() {
        patches = listOf(GamePatch(1, "IPS patch", true), GamePatch(2, "Hard Mode", false))
        open = game(patch = "IPS patch")
        show()
        rule.waitForIdle()
        rule.onNodeWithText("Patch: IPS patch").performClick()
        assertEquals("Hard Mode", chosen?.name)
    }

    @Test
    fun theRatingsShowWhenTheGameHasThem() {
        show()
        rule.waitForIdle()
        open =
            game(
                rating = 0.72,
                esrb = EsrbRating.TEEN,
                esrbBadgeUrl = "/media/decorations/sovemu/ratings/esrb/t.png?Sov-UserId=1",
                modes = listOf("Single player", "Co-op"),
                series = listOf("Chozo", "Zebes"),
            )
        rule.onNodeWithText("3.5 out of 5").assertExists()
        rule.onNodeWithText("Single player, Co-op").assertExists()
        rule.onNodeWithText("Chozo, Zebes").assertExists()
        rule.onNodeWithContentDescription("ESRB: Teen (T)").assertExists()
    }

    @Test
    fun aGameWithoutRatingsShowsNone() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("out of 5", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("ESRB", substring = true).assertDoesNotExist()
    }
}
