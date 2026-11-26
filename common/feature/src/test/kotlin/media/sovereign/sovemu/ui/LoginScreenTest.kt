package media.sovereign.sovemu.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.test.core.app.ApplicationProvider
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Signing in on a TV: each field is picked on its own. The bug this guards: focusing a field
 * opened the keyboard at once, and its Next key jumped to the following field and opened it
 * again, so the form was hard to fill in with a remote.
 */
@OptIn(ExperimentalTestApi::class)
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class LoginScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show() = rule.setContent {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        TestApp { LoginScreen(settings, SovEmuApi(settings), onSignedIn = {}) }
    }

    private fun noField() = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()

    @Test
    fun startsOnTheFirstFieldWithoutTheKeyboard() {
        show()
        // A fresh app's address field holds "http://", ready to be completed.
        rule.onNodeWithText("http://").assertIsFocused()
        assertTrue("no text field (and so no keyboard) until A", noField())
    }

    @Test
    fun typingShowsOnlyThatFieldAndDoneGoesBackToIt() {
        show()
        rule.onNodeWithText("http://").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        // Only the field being typed in shows, at the top: the keyboard has the screen below it.
        rule.onNodeWithText("Continue").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
        assertTrue("Done closes the field rather than moving on", noField())
        rule.onNodeWithText("Continue").assertExists()
        rule.onNodeWithText("http://").assertIsFocused()
    }
}
