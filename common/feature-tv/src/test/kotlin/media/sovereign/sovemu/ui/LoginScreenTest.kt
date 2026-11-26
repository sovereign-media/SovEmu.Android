package media.sovereign.sovemu.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.test.core.app.ApplicationProvider
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.PairingText
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
        TestApp {
            LoginScreen(LoginViewModel(false, SovEmuApi(settings), settings), onSignedIn = {})
        }
    }

    /** The empty address field (the first node with its name is its label). */
    private fun address() = rule.onAllNodesWithText("Server address", substring = true)[1]

    private fun noField() = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()

    @Test
    fun startsOnTheFirstFieldWithoutTheKeyboard() {
        show()
        // A fresh app's address field is empty: a bare address is tried over https first.
        address().assertIsFocused()
        assertTrue("no text field (and so no keyboard) until A", noField())
    }

    @Test
    fun typingShowsOnlyThatFieldAndDoneGoesBackToIt() {
        show()
        address().performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        // Only the field being typed in shows, at the top: the keyboard has the screen below it.
        rule.onNodeWithText("Continue").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
        assertTrue("Done closes the field rather than moving on", noField())
        rule.onNodeWithText("Continue").assertExists()
        address().assertIsFocused()
    }

    /** A server that offers the browser sign-in too: the TV still signs in by code or password. */
    private fun showWithBrowser() {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        settings.serverUrl = "http://192.168.1.10:8080"
        settings.signInConfig = media.sovereign.sovemu.core.SignInConfig(
            address = "192.168.1.10:8080",
            signInAvailable = true,
            clientId = "device-id",
            tokenEndpoint = "/oauth/token",
            revocationEndpoint = "/oauth/revoke",
            passwordEndpoint = "/oauth/password",
            deviceAuthorizationEndpoint = "/oauth/device_authorization",
            verificationUri = "http://192.168.1.10:8080/device",
            authorizationEndpoint = "/oauth/authorize",
            mobileClientId = "mobile-id",
            mobileRedirectUri = "media.sovereign.sovemu:/oauth2redirect",
        )
        rule.setContent {
            TestApp {
                LoginScreen(LoginViewModel(false, SovEmuApi(settings), settings), onSignedIn = {})
            }
        }
    }

    @Test
    fun aTvKeepsItsPasswordFormAndTheCode() {
        showWithBrowser()
        assertTrue(rule.onAllNodesWithText("Username").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText(PairingText.USE_CODE).assertExists()
        rule.onNodeWithText("Sign in with your browser").assertDoesNotExist()
    }
}
