package media.sovereign.sovemu.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
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

/** The filter drawer's dropdowns, and the Jump chip that stays in place but disabled. */
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class FilterFieldsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val series = listOf(FilterOption("Castlevania", "Castlevania", 4), FilterOption("Mega Man", "Mega Man", 2))

    @Test
    fun aMultiSelectStaysOpenWhileValuesAreTicked() {
        var picked by mutableStateOf(emptySet<String>())
        rule.setContent { TestApp { MultiSelectField("Series", series, picked, { picked = it }) } }
        rule.onNodeWithContentDescription("Series: Any").performClick()
        rule.onNodeWithText("Castlevania").performClick()
        rule.onNodeWithText("Mega Man").performClick()
        assertEquals(setOf("Castlevania", "Mega Man"), picked)
        rule.onNodeWithContentDescription("Series: 2 selected").assertExists()
        rule.onNodeWithText("Clear").performClick()
        assertEquals(emptySet<String>(), picked)
        rule.onNodeWithContentDescription("Series: Any").assertExists()
    }

    @Test
    fun aChoiceShowsWhatIsPicked() {
        var stars by mutableStateOf<Double?>(null)
        val options = listOf<FilterOption<Double?>>(FilterOption(null, "Any"), FilterOption(4.0, "4 stars or more"))
        rule.setContent { TestApp { ChoiceField("Rating", options, stars, { stars = it }) } }
        rule.onNodeWithContentDescription("Rating: Any").performClick()
        rule.onNodeWithText("4 stars or more").performClick()
        assertEquals(4.0, stars)
        rule.onNodeWithContentDescription("Rating: 4 stars or more").assertExists()
    }

    @Test
    fun aDisabledChipStaysButCannotBePressed() {
        var pressed = 0
        rule.setContent { TestApp { Chip("Jump", Lucide.BookA, { pressed++ }, enabled = false) } }
        rule.onNodeWithText("Jump").assertIsNotEnabled()
        rule.onNodeWithText("Jump").performClick()
        assertEquals(0, pressed)
    }
}
