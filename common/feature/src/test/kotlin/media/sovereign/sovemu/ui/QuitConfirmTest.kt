package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The bug this guards: NavDisplay lets Back through to the system on its last screen, which
     * finished the activity before the question could be asked.
     */
    @Test
    fun backOnTheLastScreenAsksInsteadOfFinishing() {
        var asked = 0
        rule.setContent {
            TestApp {
                QuitOnRootBack(atRoot = true) { asked++ }
                NavDisplay(
                    backStack = listOf("home"),
                    onBack = {},
                    entryProvider = { key -> NavEntry(key) { Text("Home") } },
                )
            }
        }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, asked)
        assertFalse("the app is still open", rule.activity.isFinishing)
    }

    @Test
    fun onlyQuitQuits() {
        show()
        rule.onNodeWithText("Quit").performClick()
        assertEquals(1, quit)
        assertEquals(0, stayed)
    }
}
