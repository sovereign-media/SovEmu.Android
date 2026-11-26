package media.sovereign.sovemu.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import media.sovereign.sovemu.core.ApiException
import media.sovereign.sovemu.core.TvSignIn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * *Sign in a TV* on a phone: the code, a confirmation naming the server and account, and what came
 * of it.
 */
@Config(qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class SignInTvScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(approve: suspend (String) -> Unit) = rule.setContent {
        TestApp(tv = false) {
            SignInTvScreen("http://192.168.1.10:8080", "ada", approve, onDone = {})
        }
    }

    private fun type(text: String) {
        rule.onAllNodesWithText(TvSignIn.FIELD_LABEL).onLast().performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.waitForIdle()
    }

    @Test
    fun continueWaitsForTheWholeCodeAndApprovalIsAskedFirst() {
        val sent = mutableListOf<String>()
        show { sent += it }
        rule.onNodeWithText(TvSignIn.CONTINUE).assertIsNotEnabled()
        type("qg3w ry4")
        rule.onNodeWithText(TvSignIn.CONTINUE).assertIsNotEnabled()
        type("w")
        rule.onNodeWithText(TvSignIn.CONTINUE).assertIsEnabled().performClick()
        // Never automatic: the confirmation names the server and the account.
        rule.onNodeWithText("Sign in a TV to http://192.168.1.10:8080 as ada?").assertExists()
        assertEquals(emptyList<String>(), sent)
        rule.onNodeWithText(TvSignIn.APPROVE).performClick()
        rule.waitForIdle()
        assertEquals(listOf("QG3W-RY4W"), sent)
        rule.onNodeWithText(TvSignIn.APPROVED).assertExists()
    }

    @Test
    fun aRefusalKeepsTheCodeToFix() {
        show { throw ApiException("NOT_FOUND", "no such code") }
        type("QG3WRY4W")
        rule.onNodeWithText(TvSignIn.CONTINUE).performClick()
        rule.onNodeWithText(TvSignIn.APPROVE).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No TV is waiting with that code", substring = true).assertExists()
        rule.onNodeWithText("QG3WRY4W", substring = true).assertExists() // as typed
        rule.onNodeWithText(TvSignIn.CONTINUE).assertIsEnabled()
    }
}
