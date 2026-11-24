package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app's own keyboard on a TV: Fire TV's system keyboard covers the whole screen, so text
 * fields type with a strip of keys along the bottom instead, the form in view above it.
 */
@OptIn(ExperimentalTestApi::class)
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class TvKeyboardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var value by mutableStateOf("")
    private var typing by mutableStateOf(false)
    private var actions = 0

    private fun show() = rule.setContent {
        TestApp {
            TvKeyboardHost(enabled = true) {
                Column {
                    TvTextField("Name", value, { value = it }, typing, { typing = it }, onAction = { actions++ })
                }
            }
        }
    }

    @Test
    fun typesWithItsOwnKeysNotTheSystemKeyboard() {
        show()
        rule.onNodeWithText("Name").performClick()
        rule.waitForIdle()
        assertTrue(typing)
        assertTrue("no system text field, so no system keyboard", rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("q").assertIsFocused()
        rule.onNodeWithText("a").performClick()
        rule.onNodeWithText("⇧").performClick()
        rule.onNodeWithText("B").performClick()
        rule.onNodeWithText("b").assertExists() // Shift is for one letter
        rule.onNodeWithText("Space").performClick()
        rule.onNodeWithText("c").performClick()
        rule.onNodeWithText("⌫").performClick()
        assertEquals("aB ", value)
    }

    @Test
    fun itsActionKeyClosesItThenRunsTheFieldsAction() {
        show()
        rule.onNodeWithText("Name").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        assertFalse(typing)
        assertEquals(1, actions)
        rule.onNodeWithText("Done").assertDoesNotExist()
        rule.onNodeWithText("Name", substring = true).assertIsFocused()
    }

    @Test
    fun backClosesItOnTheField() {
        show()
        rule.onNodeWithText("Name").performClick()
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse(typing)
        assertEquals("Back closes without the field's action", 0, actions)
        rule.onNodeWithText("Name", substring = true).assertIsFocused()
    }

    @Test
    fun keysSuitTheField() {
        val address = tvKeyRows(TvKeyPage.LETTERS, shift = false, KeyboardType.Uri).flatten()
        assertTrue(listOf(":", "/", ".").all { TvKey.Text(it) in address })
        assertTrue(TvKey.Text("@") in tvKeyRows(TvKeyPage.LETTERS, shift = false, KeyboardType.Email).flatten())
        assertTrue(TvKey.Text("Q") in tvKeyRows(TvKeyPage.LETTERS, shift = true, KeyboardType.Text).flatten())
        assertTrue(TvKey.Text("#") in tvKeyRows(TvKeyPage.SYMBOLS, shift = false, KeyboardType.Text).flatten())
        assertEquals("Search", tvKeyLabel(TvKey.Action, TvKeyPage.LETTERS, false, ImeAction.Search))
        assertEquals("ab", tvKeyTyped("abc", TvKey.Delete))
        assertEquals("", tvKeyTyped("", TvKey.Delete))
    }
}
