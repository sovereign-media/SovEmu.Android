package media.sovereign.sovemu.ui

import android.os.SystemClock
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import media.sovereign.sovemu.core.DeviceCode
import media.sovereign.sovemu.core.PairingText
import media.sovereign.sovemu.core.PairingUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * *Sign in with your phone*: the code, where to enter it, and its QR code; waiting is never
 * shown as an error; the password is the way back unless the server has none.
 */
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class PairingScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val code = DeviceCode(
        deviceCode = "secret",
        userCode = "BCDFGHJK",
        verificationUri = "http://192.168.1.10:8080/device",
        verificationUriComplete = "http://192.168.1.10:8080/device?user_code=BCDF-GHJK",
        expiresInSeconds = 600,
        intervalSeconds = 5,
    )

    private fun show(
        state: PairingUi,
        password: Boolean = true,
        actions: MutableList<String> = mutableListOf(),
    ) = rule.setContent {
        TestApp {
            PairingScreen(
                state,
                onNewCode = { actions += "new" },
                onUsePassword = if (password) ({ actions += "password" }) else null,
                onChangeServer = { actions += "server" },
            )
        }
    }

    @Test
    fun showsTheCodeWhereToEnterItAndItsQrCode() {
        show(PairingUi.Showing(code, SystemClock.elapsedRealtime() + 600_000))
        rule.onNodeWithText(PairingText.TITLE).assertExists()
        rule.onNodeWithText("192.168.1.10:8080/device").assertExists()
        rule.onNodeWithText("BCDF-GHJK").assertExists()
        // Waiting, and how long the code has left (a second may already have gone).
        rule.onNodeWithText(
            "Waiting for you to approve… · Expires in ",
            substring = true,
        ).assertExists()
        rule.onNodeWithText("Expires in 9:5", substring = true).assertExists()
        rule.onNodeWithContentDescription(
            "QR code for ${code.verificationUriComplete}",
        ).assertExists()
        rule.onNodeWithText("secret", substring = true).assertDoesNotExist()
        // Nothing to retry while it waits; the password is the first thing to reach with a remote.
        rule.onNodeWithText(PairingText.NEW_CODE).assertDoesNotExist()
        rule.onNodeWithText(PairingText.USE_PASSWORD).assertIsFocused()
    }

    @Test
    fun aPollWithNoAnswerSaysSoBesideTheCode() {
        show(PairingUi.Showing(code, SystemClock.elapsedRealtime() + 600_000, trouble = true))
        rule.onNodeWithText("BCDF-GHJK").assertExists()
        rule.onNodeWithText(PairingText.TROUBLE, substring = true).assertExists()
    }

    @Test
    fun anExpiredCodeOffersANewOne() {
        val actions = mutableListOf<String>()
        show(PairingUi.Failed("That code has expired."), actions = actions)
        rule.onNodeWithText("That code has expired.").assertExists()
        rule.onNodeWithText(PairingText.NEW_CODE).assertIsFocused().performClick()
        rule.onNodeWithText(PairingText.USE_PASSWORD).performClick()
        assertEquals(listOf("new", "password"), actions)
    }

    @Test
    fun withoutAPasswordSignInTheCodeIsTheOnlyWayIn() {
        show(PairingUi.Showing(code, SystemClock.elapsedRealtime() + 600_000), password = false)
        rule.onNodeWithText(PairingText.USE_PASSWORD).assertDoesNotExist()
        rule.onNodeWithText("Change server").assertIsFocused()
    }
}
