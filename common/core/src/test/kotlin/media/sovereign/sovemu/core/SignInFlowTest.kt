package media.sovereign.sovemu.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signing in, out of the sign-in screen: the address, the password, the browser, a code. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInFlowTest {
    private val config = SignInConfig(
        address = "tv.local:8080",
        signInAvailable = true,
        clientId = "device",
        tokenEndpoint = "/oauth/token",
        revocationEndpoint = null,
        passwordEndpoint = "/oauth/password",
        deviceAuthorizationEndpoint = "/oauth/device_authorization",
    )

    private inner class FakeService : SignInService {
        override var server = ""
        var answer = config
        var setup = false
        val calls = mutableListOf<String>()
        var refuse: Exception? = null
        val polls = ArrayDeque<DevicePoll>()

        override suspend fun bootstrapAt(address: String): SignInConfig {
            calls += "bootstrap $address"
            refuse?.let { throw it }
            server = address
            return answer
        }
        override suspend fun setupRequired() = setup
        override suspend fun signIn(username: String, password: String) {
            refuse?.let { throw it }
            calls += "password $username"
        }
        override fun startBrowserSignIn(): BrowserSignInRequest? = null
        override suspend fun finishBrowserSignIn(uri: String) {
            refuse?.let { throw it }
            calls += "browser $uri"
        }
        override suspend fun requestDeviceCode(): DeviceCode {
            calls += "code"
            return DeviceCode("dc", "BCDF-GHJK", "http://tv.local/device", null, 60, 5)
        }
        override suspend fun pollDeviceCode(code: DeviceCode) =
            polls.removeFirstOrNull() ?: DevicePoll.Pending
        override suspend fun signedInWith(tokens: Tokens) {
            calls += "tokens ${tokens.accessToken}"
        }
    }

    private val service = FakeService()
    private var signedIn = 0

    private fun TestScope.flow(stored: SignInConfig? = null) = SignInFlow(service, this, stored, {
        signedIn++
    }, now = { currentTime }, sleep = { kotlinx.coroutines.delay(it) })

    @Test
    fun aNewAddressIsCheckedBeforeAnyoneSignsIn() = runTest {
        val flow = flow()
        flow.submit("http://tv.local:8080", "ada", "pw")
        advanceUntilIdle()
        assertEquals(listOf("bootstrap http://tv.local:8080"), service.calls)
        assertTrue(flow.ready)
        // Checked: the same address now signs in, with the username trimmed.
        flow.submit("http://tv.local:8080/", " ada ", "pw")
        advanceUntilIdle()
        assertEquals("password ada", service.calls.last())
        assertEquals(1, signedIn)
        assertFalse(flow.busy.value)
    }

    @Test
    fun aServerAwaitingSetupSendsPeopleToABrowser() = runTest {
        service.setup = true
        val flow = flow()
        flow.check("http://tv.local:8080")
        assertNull(flow.config.value)
        assertEquals(
            "Finish first-run setup in a browser at http://tv.local:8080 first.",
            flow.error.value,
        )
    }

    @Test
    fun theServersWordsAreShownAsTheyAre() = runTest {
        val flow = flow(stored = config)
        service.server = "http://tv.local:8080"
        service.refuse = OAuthException("invalid_grant", "wrong username or password", 400)
        flow.submit("http://tv.local:8080", "ada", "nope")
        advanceUntilIdle()
        assertEquals("wrong username or password", flow.error.value)
        assertEquals(0, signedIn)
        // Anything else is the server not answering.
        service.refuse = java.io.IOException("timeout")
        flow.finishBrowserSignIn("media.sovereign.sovemu:/oauth2redirect?code=x")
        advanceUntilIdle()
        assertEquals("Cannot reach the server: timeout", flow.error.value)
    }

    @Test
    fun aQuietCheckOnLaunchShowsNothingWhenTheServerIsAway() = runTest {
        service.refuse = java.io.IOException("offline")
        val flow = flow(stored = config)
        flow.check("http://tv.local:8080", quiet = true)
        assertNull(flow.error.value)
        assertEquals("the stored details stay", config, flow.config.value)
    }

    @Test
    fun anApprovedCodeSignsIn() = runTest {
        val flow = flow(stored = config)
        service.polls += listOf(DevicePoll.Pending, DevicePoll.Approved(Tokens("a1", 3600, "r1")))
        flow.startPairing()
        runCurrent()
        val showing = flow.pairing.value as PairingUi.Showing
        assertEquals("BCDF-GHJK", showing.code.grouped)
        assertEquals(60_000L, showing.deadline)
        advanceTimeBy(10_001)
        assertEquals(listOf("code", "tokens a1"), service.calls)
        assertEquals(1, signedIn)
    }

    @Test
    fun aCodeNobodyApprovesExpiresAndANewOneStartsAgain() = runTest {
        val flow = flow(stored = config)
        flow.startPairing()
        advanceTimeBy(61_000)
        assertEquals(PairingUi.Failed("That code has expired."), flow.pairing.value)
        flow.startPairing()
        runCurrent()
        assertTrue(flow.pairing.value is PairingUi.Showing)
        assertEquals(2, service.calls.count { it == "code" })
        // Leaving the screen stops the poll: nothing signs in behind it.
        flow.stopPairing()
        service.polls += DevicePoll.Approved(Tokens("late", 3600, "r"))
        advanceUntilIdle()
        assertEquals(0, signedIn)
    }
}
