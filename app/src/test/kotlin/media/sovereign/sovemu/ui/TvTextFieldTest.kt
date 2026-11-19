package media.sovereign.sovemu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The TV text field: a box the D-pad passes over until A swaps in the real field (on a TV a
 * focused field opens the keyboard at once, and the keyboard keeps the D-pad).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class TvTextFieldTest {
    @get:Rule
    val rule = createComposeRule()

    private var value by mutableStateOf("")
    private var typing by mutableStateOf(false)
    private var actions = 0

    private fun show() = rule.setContent {
        TestApp {
            Column {
                TvTextField("Name", value, { value = it }, typing, { typing = it }, onAction = { actions++ })
            }
        }
    }

    @Test
    fun atRestItIsABoxNotAField() {
        show()
        rule.onNodeWithText("Name").assertExists()
        assertTrue(rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aSwapsInTheFieldFocused() {
        show()
        rule.onNodeWithText("Name").performClick()
        rule.waitForIdle()
        assertTrue(typing)
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.onNode(hasSetTextAction()).performTextInput("Co-op")
        assertEquals("Co-op", value)
    }

    @Test
    fun leavingTypingGivesTheFocusBackToTheBox() {
        show()
        rule.onNodeWithText("Name").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("Co-op")
        typing = false
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Co-op").assertIsFocused()
        assertFalse(typing)
    }
}
