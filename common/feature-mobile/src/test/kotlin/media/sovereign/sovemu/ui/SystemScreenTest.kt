package media.sovereign.sovemu.ui

import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import media.sovereign.sovemu.Load
import media.sovereign.sovemu.core.GameIndex
import media.sovereign.sovemu.core.GameSort
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A phone's system page: its filters search with a touch field, not the TV's keyboard. */
@Config(qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class SystemScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theFiltersSearchTypesWithATouchFieldThatWaitsForATap() {
        rule.setContent {
            TestApp(tv = false) {
                SystemScreen(
                    NoPanelData, system(),
                    Load.Ready(
                        GameIndex(listOf(game(id = 1, title = "Metroid"), game(id = 2, "Zelda"))),
                    ),
                    openFirst = null, sort = GameSort.TITLE, onSort = {},
                    onPlay = { _, _ -> }, onFavourite = { _, _ -> null },
                    onPatch = { _, _ -> null },
                )
            }
        }
        rule.onNodeWithText("2 games").assertExists()
        rule.onNodeWithText("Filters").performClick()
        val search = rule.onNode(hasSetTextAction())
        // Opening the filters does not bring up the keyboard: the field waits for a tap.
        search.assertIsNotFocused()
        rule.onNodeWithText("press A", substring = true).assertDoesNotExist()
        search.performTextInput("zel")
        rule.onNodeWithText("1 of 2").assertExists()
    }
}
