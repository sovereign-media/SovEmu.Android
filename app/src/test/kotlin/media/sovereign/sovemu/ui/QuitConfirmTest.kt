package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leaving the app takes a deliberate Quit: the question starts on Stay, and Back means stay. */
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class QuitConfirmTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var stayed = 0
    private var quit = 0

    private fun show() = rule.setContent {
        TestApp { QuitConfirm(onStay = { stayed++ }, onQuit = { quit++ }) }
    }

    @Test
    fun startsOnStay() {
        show()
        rule.onNodeWithText("Stay").assertIsFocused()
    }

    @Test
    fun backMeansStay() {
        show()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, stayed)
        assertEquals(0, quit)
    }

    @Test
    fun onlyQuitQuits() {
        show()
        rule.onNodeWithText("Quit").performClick()
        assertEquals(1, quit)
        assertEquals(0, stayed)
    }
}
