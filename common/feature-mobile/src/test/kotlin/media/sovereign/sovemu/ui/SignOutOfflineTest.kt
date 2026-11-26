package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With the server gone, the phone must still let you sign out (and so reach another server).
 * The bug this guards: the account menu, the only way to sign out, waited for the server to
 * name the user, so an app whose server had moved was stuck on an error.
 */
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class SignOutOfflineTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var signedOut = 0

    private val nav = Nav(
        home = {}, search = {}, system = {},
        scanlines = { false }, toggleScanlines = {},
        music = { false }, toggleMusic = {},
        screensaver = { 0 }, nextScreensaver = {},
        gameMode = { false }, toggleGameMode = {},
        signOut = { signedOut++ },
    )

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
}
