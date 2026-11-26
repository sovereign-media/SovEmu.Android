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
 * A phone signs in through the browser first (the server's own page), with a code and the
 * password as the other ways in; a TV keeps its password form and *Sign in with your phone*.
 */
@Config(qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class PhoneSignInTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(tv: Boolean) {
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
            authorizationEndpoint = "/oauth/authorize",
            mobileClientId = "mobile-id",
            mobileRedirectUri = "media.sovereign.sovemu:/oauth2redirect",
        )
        rule.setContent {
            TestApp(tv = tv) { LoginScreen(settings, SovEmuApi(settings), onSignedIn = {}) }
        }
    }

    @Test
    fun aPhoneOffersTheBrowserFirstThenACodeOrThePassword() {
        show(tv = false)
        rule.onNodeWithText("Sign in").assertExists()
        rule.onNodeWithText("Sign in with a code").assertExists()
        rule.onAllNodesWithText("Username").assertCountEquals(0)
        rule.onNodeWithText(PairingText.USE_PASSWORD).performClick()
        assertTrue(rule.onAllNodesWithText("Username").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Sign in with your browser").performClick()
        rule.onAllNodesWithText("Username").assertCountEquals(0)
    }

    @Test
    fun aTvKeepsItsPasswordFormAndTheCode() {
        show(tv = true)
        assertTrue(rule.onAllNodesWithText("Username").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText(PairingText.USE_CODE).assertExists()
        rule.onNodeWithText("Sign in with your browser").assertDoesNotExist()
    }
}
