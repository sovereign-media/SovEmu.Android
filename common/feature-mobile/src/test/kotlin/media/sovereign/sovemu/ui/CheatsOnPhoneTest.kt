package media.sovereign.sovemu.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The in-game menu's cheats are shared with the TV, and a phone types them with its own touch
 * field. The bug this guards: the shared screens drew the TV's field, which asks for "press A"
 * on a phone.
 */
@Config(qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class CheatsOnPhoneTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aCheatIsTypedWithTouchFieldsAndAddedByTheKeyboard() {
        var added: Pair<String, String>? = null
        rule.setContent {
            TestApp {
                CheatsPage(emptyList(), null, {}, {}, { code, name -> added = code to name })
            }
        }
        rule.onAllNodesWithText("press A", substring = true).assertCountEquals(0)
        // The code, then the name (a placeholder goes once typed in, so they are found by order).
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("SXIOPO")
        fields[1].performTextInput("Lives")
        fields[1].performImeAction()
        assertEquals("SXIOPO" to "Lives", added)
        rule.onNodeWithText("Add cheat").assertExists()
    }
}
