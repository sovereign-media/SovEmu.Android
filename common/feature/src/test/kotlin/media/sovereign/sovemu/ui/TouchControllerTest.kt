package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import media.sovereign.sovemu.Controller
import media.sovereign.sovemu.core.Button
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The on-screen pad holds a button only while a finger is on it. */
@Config(qualifiers = "w800dp-h400dp")
@RunWith(RobolectricTestRunner::class)
class TouchControllerTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val controller = Controller()

    private fun held() = controller.read().ports[0] and Button.A.bit

    @Test
    fun aButtonHeldAsTheOverlayGoesIsLetGo() {
        // The menu opening mid-press removes the overlay: the game must not be left holding
        // the button.
        var shown by mutableStateOf(true)
        rule.setContent {
            TestApp { if (shown) TouchController(controller, Modifier.fillMaxSize()) }
        }
        rule.onNodeWithText("A").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals(Button.A.bit, held())

        shown = false
        rule.waitForIdle()
        assertEquals(0, held())
    }

    @Test
    fun liftingTheFingerLetsGo() {
        rule.setContent { TestApp { TouchController(controller, Modifier.fillMaxSize()) } }
        rule.onNodeWithText("A").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()
        assertEquals(0, held())
    }
}
