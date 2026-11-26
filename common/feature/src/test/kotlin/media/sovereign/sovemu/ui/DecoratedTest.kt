package media.sovereign.sovemu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import media.sovereign.sovemu.core.GameDecoration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the picture lands with and without a decoration, on a wide and on an upright screen. */
// A screen large enough for the boxes below (Robolectric's default is 320 dp wide).
@Config(qualifiers = "w2000dp-h1200dp")
@RunWith(RobolectricTestRunner::class)
class DecoratedTest {
    @get:Rule
    val rule = createComposeRule()

    /** Batocera's usual frame: 1920x1080 with a 4:3 window 240 px in from each side. */
    private val frame =
        GameDecoration("/media/decorations/sovemu/systems/snes.png", 1920, 1080, 0, 240, 0, 240)

    private fun show(width: Int, height: Int, decoration: GameDecoration?) = rule.setContent {
        TestApp {
            Box(Modifier.size(width.dp, height.dp)) {
                Decorated(decoration, aspect = 4f / 3f) {
                    Box(Modifier.fillMaxSize().testTag("picture"))
                }
            }
        }
    }

    @Test
    fun onAWideScreenThePictureFillsTheFramesWindow() {
        // The frame fills 1600x900; its window is 1440/1920 of that width: 1200x900.
        show(1600, 900, frame)
        rule.onNodeWithTag("picture").assertWidthIsEqualTo(1200.dp).assertHeightIsEqualTo(900.dp)
    }

    @Test
    fun withoutADecorationThePictureFillsTheHeight() {
        show(1600, 900, null)
        rule.onNodeWithTag("picture").assertWidthIsEqualTo(1200.dp).assertHeightIsEqualTo(900.dp)
    }

    @Test
    fun onAnUprightScreenThePictureIsNotFramed() {
        // 400 wide: the picture is 400x300, as large as it can be, with no frame shrinking it.
        show(400, 800, frame)
        rule.onNodeWithTag("picture").assertWidthIsEqualTo(400.dp).assertHeightIsEqualTo(300.dp)
    }

    @Test
    fun aNarrowerWindowShrinksThePicture() {
        // A frame with a wider border: its window is 1280x960 of 1920x1080, centred (shown at
        // its own size, so the numbers are whole pixels).
        show(1920, 1080, frame.copy(left = 320, right = 320, top = 60, bottom = 60))
        rule.onNodeWithTag("picture").assertWidthIsEqualTo(1280.dp).assertHeightIsEqualTo(960.dp)
    }
}
