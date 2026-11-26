package media.sovereign.sovemu.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.AccountStore
import media.sovereign.sovemu.core.CustomHeader
import media.sovereign.sovemu.core.Sealer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The account screens' ViewModels follow the settings and the accounts as they change. */
@RunWith(RobolectricTestRunner::class)
class LaunchViewModelsTest {
    private val settings = Settings(ApplicationProvider.getApplicationContext())

    /** Accounts as an earlier launch stored them (nothing here is sealed). */
    private fun stored(vararg accounts: Triple<String, String, Long>) {
        val store = AccountStore(
            settings.keyValue,
            object : Sealer {
                override fun seal(plaintext: String) = plaintext
                override fun unseal(sealed: String) = sealed
            },
        )
        for ((subject, server, used) in accounts) {
            store.upsert(
                subject,
                server,
                null,
                "u$subject",
                used,
            )
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun thePickerShowsTheServersAccountsAndFollowsAChangeOfServer() {
        settings.serverUrl = "http://one"
        stored(
            Triple("1", "http://one", 5),
            Triple("2", "http://one", 9),
            Triple("3", "http://two", 1),
        )
        val api = SovEmuApi(settings)
        val picker = AccountPickerViewModel(api, settings)
        idle()
        assertEquals(listOf("u1", "u2"), picker.uiState.value.accounts.map { it.userName })
        assertEquals(
            "the one used last",
            "u2",
            picker.uiState.value.accounts.first {
                it.id == picker.uiState.value.lastUsedId
            }.userName,
        )
        settings.serverUrl = "http://two"
        idle()
        assertEquals(listOf("u3"), picker.uiState.value.accounts.map { it.userName })
        assertEquals("http://two", picker.uiState.value.server)
        val servers = ChooseServerViewModel(api, settings)
        idle()
        assertEquals(listOf(false, true), servers.uiState.value.servers.map { it.inUse })
    }

    @Test
    fun savedHeadersAreCountedOnTheSignInForm() {
        val api = SovEmuApi(settings)
        val form = LoginViewModel(newServer = true, api = api, settings = settings)
        val headers = CustomHeadersViewModel(settings)
        idle()
        assertEquals(0, form.customHeaders.value)
        headers.save(listOf(CustomHeader("X-Token", "a"), CustomHeader("Authorization", "no")))
        idle()
        // The app's own header is dropped on the way in.
        assertEquals(listOf(CustomHeader("X-Token", "a")), headers.headers.value)
        assertEquals(1, form.customHeaders.value)
        assertEquals("", form.initialServer)
    }
}
