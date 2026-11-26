package media.sovereign.sovemu.core

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Several accounts on one device, as Sovereign.Android keeps them. */
class SessionTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val prefs = MemoryKeyValue()
    private var clock = 0L
    private lateinit var address: String
    private lateinit var accounts: AccountStore
    private lateinit var session: Session

    @Before
    fun start() {
        server.start()
        address = server.url("/").toString().trimEnd('/')
        session = session()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun config() = SignInConfig(
        address = "tv.local",
        signInAvailable = true,
        clientId = "sovemu-native",
        tokenEndpoint = "/oauth/token",
        revocationEndpoint = "/oauth/revoke",
        passwordEndpoint = "/oauth/password",
    )

    private fun session(): Session {
        accounts = AccountStore(prefs, FakeSealer(), { address })
        return Session(
            TokenClient(http, {
                address
            }, ::config),
            accounts,
            { address },
            now = { clock },
        )
    }

    private fun tokens(access: String, refresh: String, subject: String, name: String) =
        MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                """{"access_token":"$access","expires_in":3600,"refresh_token":"$refresh",""" +
                    """"id_token":"${idToken(subject, name)}"}""",
            )
            .build()

    private fun RecordedRequest.form(): Map<String, String> =
        body!!.utf8().split('&').associate { pair ->
            val (k, v) = pair.split('=', limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }

    /** Signs [name] in with a password, answered by the server as user [subject]. */
    private suspend fun signIn(name: String, subject: String, refresh: String = "r-$name") {
        server.enqueue(tokens("a-$name", refresh, subject, name))
        session.signIn(name, "pw")
        server.takeRequest()
    }

    @Test
    fun twoPeopleStaySignedInAndALaunchAsksWhoIsWatching() = runBlocking {
        signIn("ada", "7")
        signIn("ben", "8")
        assertEquals(listOf("ada", "ben"), session.accountsHere().map { it.userName })
        assertEquals("ben", session.active!!.userName)

        // The next launch: the picker, and not one request to the server.
        session = session()
        val before = server.requestCount
        assertEquals(LaunchAccounts.Picker, session.launch())
        assertEquals(AuthState.Choosing, session.state.value)
        assertNull(session.accessToken())
        assertEquals(before, server.requestCount)

        // Choosing Ada refreshes Ada's sign-in, and only hers.
        val ada = session.accountsHere().first { it.userName == "ada" }
        session.activate(ada.id)
        assertEquals(AuthState.Authenticated(ada.id), session.state.value)
        server.enqueue(tokens("a2", "r-ada-2", "7", "ada"))
        assertEquals("a2", session.accessToken())
        assertEquals("r-ada", server.takeRequest().form()["refresh_token"])
        assertEquals("r-ada-2", accounts.refreshToken(ada.id))
        assertEquals("r-ben", accounts.refreshToken(session.accountsHere()[1].id))
    }

    @Test
    fun aWindowMadeAgainKeepsWhoeverIsSignedIn() = runBlocking {
        signIn("ada", "7")
        signIn("ben", "8")
        session = session()
        session.start()
        assertEquals(AuthState.Choosing, session.state.value)
        session.activate(session.accountsHere().first().id)
        // The activity is made again (a language change): the same session, nobody sent back.
        session.start()
        assertEquals("ada", session.active!!.userName)
        assertTrue(session.signedIn)
    }

    @Test
    fun signingInAgainReplacesTheSameUser() = runBlocking {
        signIn("ada", "7", refresh = "r1")
        signIn("ada", "7", refresh = "r2")
        val only = session.accountsHere().single()
        assertEquals("r2", accounts.refreshToken(only.id))
    }

    @Test
    fun aSwitchNeverSendsThePreviousViewersToken() = runBlocking {
        signIn("ada", "7")
        signIn("ben", "8")
        assertEquals("a-ben", session.accessToken())
        session.activate(session.accountsHere().first().id)
        server.enqueue(tokens("a-ada-2", "r-ada-2", "7", "ada"))
        assertEquals("a-ada-2", session.accessToken())
    }

    @Test
    fun signingOutLandsOnWhoIsLeft() = runBlocking {
        signIn("ada", "7")
        signIn("ben", "8")
        signIn("cy", "9")
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(LaunchAccounts.Picker, session.signOut())
        assertEquals("r-cy", server.takeRequest().form()["token"])
        assertEquals(AuthState.Choosing, session.state.value)

        session.activate(session.accountsHere().first().id)
        server.enqueue(MockResponse.Builder().code(200).build())
        // One left: straight in as them.
        assertEquals(LaunchAccounts.Restore, session.signOut())
        assertEquals("ben", session.active!!.userName)
        assertTrue(session.signedIn)

        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(LaunchAccounts.Login, session.signOut())
        assertEquals(AuthState.SignedOut, session.state.value)
    }

    @Test
    fun aSixthUserIsRefusedRevokedAndNobodyIsSignedOut() = runBlocking {
        for (i in 1..5) signIn("u$i", "$i")
        server.enqueue(tokens("a6", "r6", "6", "u6"))
        server.enqueue(MockResponse.Builder().code(200).build())
        val e = assertThrows(OAuthException::class.java) {
            runBlocking { session.signIn("u6", "pw") }
        }
        assertEquals("This device can't hold another user.", e.message)
        server.takeRequest()
        val revoke = server.takeRequest()
        assertEquals("/oauth/revoke", revoke.url.encodedPath)
        assertEquals("r6", revoke.form()["token"])
        assertEquals(5, session.accountsHere().size)
        assertEquals("u5", session.active!!.userName)
    }

    @Test
    fun anAnswerThatSaysNobodySignedInIsRefused() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        val e = assertThrows(OAuthException::class.java) {
            runBlocking { session.signedInWith(Tokens("a", 3600, "r", "x.y.z"), "sovemu-native") }
        }
        assertEquals(AccountRefusal.ACCOUNT_REFUSED, e.error)
        assertEquals("r", server.takeRequest().form()["token"])
        assertTrue(accounts.all().isEmpty())
        assertFalse(session.signedIn)
    }

    @Test
    fun aRefusedRefreshRemovesOnlyThatAccount() = runBlocking {
        signIn("ada", "7")
        signIn("ben", "8")
        clock += 3_600_000
        server.enqueue(
            MockResponse.Builder().code(400)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":"invalid_grant","error_description":"expired"}""")
                .build(),
        )
        assertNull(session.accessToken())
        assertEquals(AuthState.Expired, session.state.value)
        assertEquals("ben", session.lastExpired!!.userName)
        assertEquals(listOf("ada"), session.accountsHere().map { it.userName })
    }

    @Test
    fun aTokenGoesOnlyToTheServerThatIssuedIt() = runBlocking {
        signIn("ada", "7")
        val issuer = address
        // The address changes under the live sign-in (*Add a server*): nothing goes to the new one.
        address = "http://elsewhere.invalid:8080"
        assertNull(session.accessToken())
        clock += 3_600_000
        assertNull(session.refresh(stale = null))
        assertEquals(1, server.requestCount)
        assertEquals(listOf("ada"), accounts.all().map { it.userName })
        // A request to another host never carries it, even while it is current.
        address = issuer
        val signed = http.newBuilder().addInterceptor(BearerInterceptor(session)).build()
        server.enqueue(tokens("a2", "r2", "7", "ada"))
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        signed.newCall(Request.Builder().url(server.url("/graphql")).build()).execute().close()
        server.takeRequest()
        assertEquals("Bearer a2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun removingAServerSignsOutEveryoneOnIt() = runBlocking {
        signIn("ada", "7")
        signIn("ben", "8")
        server.enqueue(MockResponse.Builder().code(200).build())
        server.enqueue(MockResponse.Builder().code(200).build())
        assertTrue(session.removeServer(address))
        val revoked =
            setOf(server.takeRequest().form()["token"], server.takeRequest().form()["token"])
        assertEquals(setOf("r-ada", "r-ben"), revoked)
        assertTrue(accounts.all().isEmpty())
        assertEquals(AuthState.SignedOut, session.state.value)
    }

    @Test
    fun aTokenRefusedRightAfterItsRefreshEndsTheSignIn() = runBlocking {
        signIn("ada", "7")
        session.refusedFreshToken()
        assertEquals(AuthState.Expired, session.state.value)
        assertTrue(accounts.all().isEmpty())
    }
}
