package media.sovereign.sovemu.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.PairingText
import media.sovereign.sovemu.core.SignInConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A phone signs in as Sovereign's phones do: through the browser (the server's own page), with a
 * code as the other way in and no password typed into the app, except on a server with no browser
 * sign-in. A TV keeps its password form and *Sign in with your phone*.
 */
@Config(qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class PhoneSignInTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(tv: Boolean, browser: Boolean = true) {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        settings.serverUrl = "http://192.168.1.10:8080"
        settings.signInConfig = SignInConfig(
            address = "192.168.1.10:8080",
            signInAvailable = true,
            clientId = "device-id",
            tokenEndpoint = "/oauth/token",
            revocationEndpoint = "/oauth/revoke",
            passwordEndpoint = "/oauth/password",
            deviceAuthorizationEndpoint = "/oauth/device_authorization",
            verificationUri = "http://192.168.1.10:8080/device",
            authorizationEndpoint = "/oauth/authorize".takeIf { browser },
            mobileClientId = "mobile-id",
            mobileRedirectUri = "media.sovereign.sovemu:/oauth2redirect",
        )
        rule.setContent {
            TestApp(tv = tv) { LoginScreen(settings, SovEmuApi(settings), onSignedIn = {}) }
        }
    }

    @Test
    fun aPhoneOffersTheBrowserThenACodeAndNoPassword() {
        show(tv = false)
        rule.onNodeWithText("Sign in").assertExists()
        rule.onAllNodesWithText("Username").assertCountEquals(0)
        rule.onNodeWithText(PairingText.USE_PASSWORD).assertDoesNotExist()
        // The code's screen leads back to the browser, not to a password.
        rule.onNodeWithText("Sign in with a code").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sign in with your browser").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sign in with a code").assertExists()
        rule.onAllNodesWithText("Username").assertCountEquals(0)
    }

    @Test
    fun aPhoneUsesThePasswordOnlyWhereTheServerHasNoBrowserSignIn() {
        show(tv = false, browser = false)
        assertTrue(rule.onAllNodesWithText("Username").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aTvKeepsItsPasswordFormAndTheCode() {
        show(tv = true)
        assertTrue(rule.onAllNodesWithText("Username").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText(PairingText.USE_CODE).assertExists()
        rule.onNodeWithText("Sign in with your browser").assertDoesNotExist()
    }
}
