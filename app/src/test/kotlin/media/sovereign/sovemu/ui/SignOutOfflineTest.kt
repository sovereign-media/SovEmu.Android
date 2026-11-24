package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With the server gone, the app must still let you sign out (and so reach another server).
 * The bug this guards: the account menu, the only way to sign out, waited for the server to
 * name the user, so an app whose server had moved was stuck on an error.
 */
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class SignOutOfflineTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var signedOut = 0
    private var retried = 0

    private val nav = Nav(
        home = {}, search = {}, system = {},
        scanlines = { false }, toggleScanlines = {},
        music = { false }, toggleMusic = {},
        screensaver = { 0 }, nextScreensaver = {},
        gameMode = { false }, toggleGameMode = {},
        signOut = { signedOut++ },
    )

    @Test
    fun theDockOffersSignOutBeforeTheUserIsKnown() {
        rule.setContent {
            TestApp { NavFrame(Section.Home, nav, user = null, systems = emptyList()) {} }
        }
        rule.onNodeWithContentDescription("Account").performClick()
        rule.onNodeWithText("Sign out").performClick()
        assertEquals(1, signedOut)
    }

    @Test
    fun theTabBarOffersSignOutBeforeTheUserIsKnown() {
        rule.setContent {
            TestApp {
                CompositionLocalProvider(LocalForm provides Form(tv = false, wide = false)) {
                    NavFrame(Section.Home, nav, user = null, systems = emptyList()) {}
                }
            }
        }
        rule.onNodeWithText("Account").performClick()
        rule.onNodeWithText("Sign out").performClick()
        assertEquals(1, signedOut)
    }

    @Test
    fun unreachableStartsOnTryAgainAndOffersSignOut() {
        rule.setContent {
            TestApp {
                Unreachable("https://sovemu.example", "Unable to resolve host", { retried++ }, { signedOut++ })
            }
        }
        rule.onNodeWithText("https://sovemu.example").assertExists()
        rule.onNodeWithText("Try again").assertIsFocused()
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithText("Sign out").performClick()
        assertEquals(1, retried)
        assertEquals(1, signedOut)
    }
}
