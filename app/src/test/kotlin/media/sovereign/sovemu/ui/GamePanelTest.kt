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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import media.sovereign.sovemu.core.Game
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
    private var trapFocus by mutableStateOf(true)
    private val outside = FocusRequester()
    private var favourites = 0
    private var collections = 0

    /** A card of the grid behind the panel (left of it), and the panel. */
    private fun show() = rule.setContent {
        TestApp {
            Row {
                Box(Modifier.testTag("card").size(40.dp).focusRequester(outside).focusable()) { Text("card") }
                Box {
                    GamePanel(
                        open, system(), slots = emptyList(), auto = false,
                        onClose = { open = null }, onPlay = {},
                        onFavourite = { favourites++ }, onCollections = { collections++ },
                        trapFocus = trapFocus,
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
}
