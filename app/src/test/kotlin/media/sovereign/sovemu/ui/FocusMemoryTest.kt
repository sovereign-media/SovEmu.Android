package media.sovereign.sovemu.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A screen's focus as a console menu keeps it: its default item on a fresh visit, the D-pad
 * between items, and the item it left from when the screen comes back (FocusMemory).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class FocusMemoryTest {
    @get:Rule
    val rule = createComposeRule()

    /** Three cards in a row; [initial] is the screen's default. Tags stand in for card art. */
    @Composable
    private fun Cards(initial: String = "b") {
        val memory = rememberFocusMemory()
        Row {
            for (key in listOf("a", "b", "c")) {
                Box(
                    Modifier.testTag(key).size(40.dp).remembersFocus(
                        memory,
                        key,
                        initial =
                            key == initial,
                    ).focusable(),
                ) {
                    Text(key)
                }
            }
        }
    }

    @Test
    fun aFreshVisitFocusesTheDefault() {
        rule.setContent { TestApp { Cards() } }
        rule.waitForIdle()
        rule.onNodeWithTag("b").assertIsFocused()
    }

    @Test
    fun theDpadMovesAlongTheRow() {
        rule.setContent { TestApp { Cards() } }
        rule.waitForIdle()
        rule.onNodeWithTag("b").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("c").assertIsFocused()
        rule.onNodeWithTag("c").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithTag("c").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithTag("a").assertIsFocused()
    }

    @Test
    fun comingBackFocusesTheItemItLeftFrom() {
        val restore = StateRestorationTester(rule)
        restore.setContent { TestApp { Cards() } }
        rule.waitForIdle()
        rule.onNodeWithTag("c").requestFocus()
        // The screen's saved state comes back (Back to it, or the activity recreated).
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag("c").assertIsFocused()
    }
}
