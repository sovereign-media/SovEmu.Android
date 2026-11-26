package media.sovereign.sovemu.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.sovereign.sovemu.core.KnownServers
import media.sovereign.sovemu.core.LaunchText
import media.sovereign.sovemu.core.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** *Who's watching?* and *Choose a server*, on a TV. */
@OptIn(ExperimentalTestApi::class)
@Config(qualifiers = "w960dp-h540dp")
@RunWith(RobolectricTestRunner::class)
class AccountsScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private fun account(
        id: String,
        name: String,
        server: String = "http://tv.local",
        used: Long = 0,
    ) = StoredAccount(id, "s$id", server, "c", name, used)

    private fun picker(
        accounts: List<StoredAccount>,
        focusOn: String? = null,
        current: String? = null,
        chosen: MutableList<String> = mutableListOf(),
    ) = rule.setContent {
        TestApp {
            WhoIsWatchingScreen(
                accounts,
                server = "http://tv.local",
                onChoose = { chosen += it.id },
                onAddUser = { chosen += "add" },
                onChangeServer = { chosen += "server" },
                focusOn = focusOn,
                current = current,
            )
        }
    }

    @Test
    fun focusStartsOnTheAccountUsedLastAndChoosingItSaysWho() {
        val chosen = mutableListOf<String>()
        picker(listOf(account("a", "ada"), account("b", "ben")), focusOn = "b", chosen = chosen)
        rule.waitForIdle()
        rule.onNodeWithText("ben").assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf("b"), chosen)
        // Right of the last account: Add user.
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf("b", "add"), chosen)
    }

    @Test
    fun aTileTakesATouch() {
        val chosen = mutableListOf<String>()
        picker(listOf(account("a", "ada")), chosen = chosen)
        rule.onNodeWithText("ada").performClick()
        rule.onNodeWithText(LaunchText.ADD_USER).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a", "add"), chosen)
    }

    @Test
    fun downOffTheTilesReachesChangeServer() {
        val chosen = mutableListOf<String>()
        picker(listOf(account("a", "ada")), chosen = chosen)
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf("server"), chosen)
    }

    @Test
    fun addUserIsHiddenAtFiveAndTheCurrentAccountIsMarked() {
        picker((1..5).map { account("$it", "u$it") }, current = "3")
        rule.onNodeWithText(LaunchText.ADD_USER).assertDoesNotExist()
        rule.onNodeWithText(LaunchText.SIGNED_IN_NOW).assertExists()
    }

    @Test
    fun removingAServerAsksFirstWithCancelFocused() {
        val removed = mutableListOf<String>()
        val accounts = listOf(account("a", "ada"), account("b", "ben", server = "http://other"))
        rule.setContent {
            TestApp {
                ChooseServerScreen(
                    KnownServers.list(accounts, "http://tv.local"),
                    onChoose = {},
                    onRemove = { removed += it.address },
                    onAddServer = {},
                )
            }
        }
        rule.onNodeWithText("1 user  ·  in use").assertExists()
        rule.onNodeWithText("http://other").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasText(KnownServers.REMOVE))[1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Remove http://other?").assertExists()
        rule.onNodeWithText(
            "Everyone signed in to it on this device will be signed out: ben.",
        ).assertExists()
        rule.onNodeWithText(KnownServers.CANCEL).assertIsFocused()
        assertEquals(emptyList<String>(), removed)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf("http://other"), removed)
    }
}
