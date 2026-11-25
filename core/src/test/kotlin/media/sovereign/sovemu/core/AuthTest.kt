package media.sovereign.sovemu.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
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

/** The OAuth token client and the sign-in over it, against a server answering as SovEmu.Server's `/oauth/…`. */
class AuthTest {
    private class MemoryStore(
        override var refreshToken: String? = null,
        override var tokenClientId: String? = null,
    ) : TokenStore

    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val store = MemoryStore()
    private var clock = 0L
    private lateinit var auth: Auth
    /** What the server's bootstrapConfig said: SovEmu's own paths, unless a test asks it. */
    private var config: SignInConfig? = null

    @Before
    fun start() {
        server.start()
        config = SignInConfig(
            address = "${server.hostName}:${server.port}",
            signInAvailable = true,
            clientId = "sovemu-native",
            tokenEndpoint = server.url("/oauth/token").toString(),
            revocationEndpoint = server.url("/oauth/revoke").toString(),
            passwordEndpoint = server.url("/oauth/password").toString(),
        )
        auth = auth()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun address() = server.url("/").toString().trimEnd('/')

    private fun auth() = Auth(TokenClient(http, ::address, { config }), store, now = { clock })

    private fun bootstrapAnswer(available: Boolean = true, password: String? = "/auth/pw") = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(
            if (!available) {
                """{"data":{"bootstrapConfig":{"signInAvailable":false,"address":"10.0.0.5:8080","deviceClientId":null,""" +
                    """"tokenEndpoint":null,"revocationEndpoint":null,"deviceAuthorizationEndpoint":null,""" +
                    """"verificationUri":null,"passwordEndpoint":null,"authorizationEndpoint":null,""" +
                    """"mobileClientId":null,"mobileRedirectUri":null}}}"""
            } else {
                // Elsewhere than SovEmu's own paths, and another client id: the app must use these.
                """{"data":{"bootstrapConfig":{"signInAvailable":true,"address":"10.0.0.5:8080","deviceClientId":"tv-app",""" +
                    """"tokenEndpoint":"${server.url("/auth/token")}","revocationEndpoint":"/auth/revoke",""" +
                    """"deviceAuthorizationEndpoint":"/auth/device","verificationUri":"http://10.0.0.5:8080/device",""" +
                    """"authorizationEndpoint":"/auth/authorize","mobileClientId":"phone-app",""" +
                    """"mobileRedirectUri":"media.sovereign.sovemu:/oauth2redirect",""" +
                    """"passwordEndpoint":${password?.let { "\"$it\"" } ?: "null"}}}}"""
            },
        )
        .build()

    private suspend fun fetchBootstrap(): SignInConfig =
        ApolloClient.Builder().serverUrl("${address()}/graphql").okHttpClient(http).build().use { bootstrap(it, address()) }

    private fun tokens(access: String, refresh: String, expiresIn: Int = 3600) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(
            """{"access_token":"$access","token_type":"Bearer","expires_in":$expiresIn,"refresh_token":"$refresh",""" +
                """"id_token":"x.y.z","scope":"openid profile offline_access"}""",
        )
        .build()

    private fun error(code: Int, error: String, description: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .body("""{"error":"$error","error_description":"$description"}""")
        .build()

    private fun RecordedRequest.form(): Map<String, String> =
        body!!.utf8().split('&').associate { pair ->
            val (k, v) = pair.split('=', limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }

    @Test
    fun signInPostsToThePasswordRouteAndKeepsTheTokens() = runBlocking {
        server.enqueue(tokens("a1", "r1"))
        auth.signIn("ada", "p&ss word")

        val request = server.takeRequest()
        assertEquals("/oauth/password", request.url.encodedPath)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"]?.substringBefore(';'))
        assertEquals(
            mapOf(
                "client_id" to "sovemu-native", "username" to "ada",
                "password" to "p&ss word", "scope" to "openid profile offline_access",
            ),
            request.form(),
        )
        assertEquals("r1", store.refreshToken)
        assertTrue(auth.signedIn.value)
        // Still fresh: no second request.
        assertEquals("a1", auth.accessToken())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aWrongPasswordIsTheServersDescription() = runBlocking {
        server.enqueue(error(400, "invalid_grant", "Wrong username or password"))
        val e = assertThrows(OAuthException::class.java) { runBlocking { auth.signIn("ada", "nope") } }
        assertEquals("invalid_grant", e.error)
        assertEquals(400, e.status)
        assertEquals("Wrong username or password", e.message)
        assertNull(store.refreshToken)
        assertFalse(auth.signedIn.value)
    }

    @Test
    fun throttledSignInSaysHowLongToWait() = runBlocking {
        server.enqueue(error(429, "too_many_requests", "Too many failed sign-ins: try again in 5 minutes"))
        val e = assertThrows(OAuthException::class.java) { runBlocking { auth.signIn("ada", "nope") } }
        assertEquals("Too many failed sign-ins: try again in 5 minutes", e.message)
    }

    @Test
    fun passwordSignInSwitchedOffIsTheServersDescription() = runBlocking {
        server.enqueue(error(400, "unsupported_grant_type", "Password sign-in is off: sign in with a code"))
        val e = assertThrows(OAuthException::class.java) { runBlocking { auth.signIn("ada", "pw") } }
        assertEquals("unsupported_grant_type", e.error)
        assertEquals("Password sign-in is off: sign in with a code", e.message)
        assertFalse(auth.signedIn.value)
    }

    @Test
    fun refreshingStoresTheRotatedRefreshToken() = runBlocking {
        server.enqueue(tokens("a1", "r1"))
        server.enqueue(tokens("a2", "r2"))
        server.enqueue(tokens("a3", "r3"))
        auth.signIn("ada", "pw")
        server.takeRequest()

        // Under a minute left: refreshed before use, with the stored token, and the new one kept.
        clock += 3_541_000
        assertEquals("a2", auth.accessToken())
        val refresh = server.takeRequest()
        assertEquals("/oauth/token", refresh.url.encodedPath)
        assertEquals(mapOf("grant_type" to "refresh_token", "client_id" to "sovemu-native", "refresh_token" to "r1"), refresh.form())
        assertEquals("r2", store.refreshToken)

        clock += 3_600_000
        assertEquals("a3", auth.accessToken())
        assertEquals("r2", server.takeRequest().form()["refresh_token"])
        assertEquals("r3", store.refreshToken)
    }

    @Test
    fun aRefreshTokenFromAnEarlierLaunchIsUsedFirst() = runBlocking {
        store.refreshToken = "r0"
        auth = auth()
        assertTrue(auth.signedIn.value)
        server.enqueue(tokens("a1", "r1"))
        assertEquals("a1", auth.accessToken())
        assertEquals("r0", server.takeRequest().form()["refresh_token"])
        assertEquals("r1", store.refreshToken)
    }

    @Test
    fun invalidGrantOnRefreshSignsOut() = runBlocking {
        store.refreshToken = "revoked"
        auth = auth()
        server.enqueue(error(400, "invalid_grant", "The sign-in has expired"))
        assertNull(auth.accessToken())
        assertNull(store.refreshToken)
        assertFalse(auth.signedIn.value)
        // Signed out: nothing more is asked of the server.
        assertNull(auth.accessToken())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anyOtherRefreshFailureKeepsTheSignIn() = runBlocking {
        store.refreshToken = "r0"
        auth = auth()
        server.enqueue(error(500, "server_error", "Try again"))
        assertThrows(OAuthException::class.java) { runBlocking { auth.accessToken() } }
        assertEquals("r0", store.refreshToken)
        assertTrue(auth.signedIn.value)
    }

    @Test
    fun concurrentCallersShareOneRefresh() = runBlocking {
        store.refreshToken = "r0"
        auth = auth()
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = refreshes.incrementAndGet()
                // Slow enough that every caller arrives while the first refresh is in flight.
                return tokens("a$n", "r$n").newBuilder().headersDelay(300, TimeUnit.MILLISECONDS).build()
            }
        }
        val got = (1..8).map { async(Dispatchers.IO) { auth.accessToken() } }.awaitAll()
        assertEquals(List(8) { "a1" }, got)
        assertEquals(1, refreshes.get())
        assertEquals("r1", store.refreshToken)

        // Two requests rejected with the same token: one refresh between them.
        val again = (1..4).map { async(Dispatchers.IO) { auth.refresh(stale = "a1") } }.awaitAll()
        assertEquals(List(4) { "a2" }, again)
        assertEquals(2, refreshes.get())
    }

    @Test
    fun unauthenticatedRefreshesAndRetriesOnce() = runBlocking {
        server.enqueue(tokens("a1", "r1"))
        server.enqueue(tokens("a2", "r2"))
        auth.signIn("ada", "pw")
        var calls = 0
        val result = auth.retryingUnauthenticated {
            calls++
            if (auth.accessToken() == "a1") throw ApiException("UNAUTHENTICATED", "Not signed in")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals("r2", store.refreshToken)

        // Rejected again after the refresh: thrown, not refreshed a second time.
        server.enqueue(tokens("a3", "r3"))
        calls = 0
        val e = assertThrows(ApiException::class.java) {
            runBlocking { auth.retryingUnauthenticated<Unit> { calls++; throw ApiException("UNAUTHENTICATED", "No") } }
        }
        assertTrue(e.unauthenticated)
        assertEquals(2, calls)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun signedOutUnauthenticatedIsNotRetried() = runBlocking {
        var calls = 0
        assertThrows(ApiException::class.java) {
            runBlocking { auth.retryingUnauthenticated<Unit> { calls++; throw ApiException("UNAUTHENTICATED", "No") } }
        }
        assertEquals(1, calls)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun signOutRevokesThenForgets() = runBlocking {
        server.enqueue(tokens("a1", "r1"))
        server.enqueue(MockResponse.Builder().code(200).build())
        auth.signIn("ada", "pw")
        server.takeRequest()
        auth.signOut()
        val revoke = server.takeRequest()
        assertEquals("/oauth/revoke", revoke.url.encodedPath)
        assertEquals(mapOf("token" to "r1", "client_id" to "sovemu-native"), revoke.form())
        assertNull(store.refreshToken)
        assertFalse(auth.signedIn.value)
    }

    @Test
    fun signOutForgetsEvenOffline() = runBlocking {
        server.enqueue(tokens("a1", "r1"))
        auth.signIn("ada", "pw")
        server.close()
        auth.signOut()
        assertNull(store.refreshToken)
        assertFalse(auth.signedIn.value)
    }

    @Test
    fun theInterceptorSignsGraphqlRequestsOnly() = runBlocking {
        server.enqueue(tokens("a1", "r1"))
        auth.signIn("ada", "pw")
        server.takeRequest()
        val client = http.newBuilder().addInterceptor(BearerInterceptor(auth)).build()
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        server.enqueue(MockResponse.Builder().code(200).body("").build())
        client.newCall(Request.Builder().url(server.url("/graphql")).build()).execute().close()
        client.newCall(Request.Builder().url(server.url("/media/games/7/thumb")).build()).execute().close()
        assertEquals("Bearer a1", server.takeRequest().headers["Authorization"])
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun theBootstrapAnswerNamesTheEndpointsAndClient() = runBlocking {
        server.enqueue(bootstrapAnswer())
        config = fetchBootstrap()
        val ask = server.takeRequest()
        assertEquals("/graphql", ask.url.encodedPath)
        assertNull(ask.headers["Authorization"])
        assertNull(config!!.problem)

        server.enqueue(tokens("a1", "r1"))
        server.enqueue(tokens("a2", "r2"))
        server.enqueue(MockResponse.Builder().code(200).build())
        auth.signIn("ada", "pw")
        val signIn = server.takeRequest()
        assertEquals("/auth/pw", signIn.url.encodedPath)
        assertEquals("tv-app", signIn.form()["client_id"])

        clock += 3_600_000
        assertEquals("a2", auth.accessToken())
        val refresh = server.takeRequest()
        assertEquals("/auth/token", refresh.url.encodedPath)
        assertEquals("tv-app", refresh.form()["client_id"])

        auth.signOut()
        val revoke = server.takeRequest()
        // A relative endpoint resolves against the address the user typed.
        assertEquals("/auth/revoke", revoke.url.encodedPath)
        assertEquals(mapOf("token" to "r2", "client_id" to "tv-app"), revoke.form())
    }

    @Test
    fun anAddressOutsidePublicUrlCannotSignIn() = runBlocking {
        server.enqueue(bootstrapAnswer(available = false))
        config = fetchBootstrap()
        val why = "Signing in isn't available at 10.0.0.5:8080. Add it to SOVEMU_PUBLIC_URL on the server."
        assertEquals(why, config!!.problem)
        val e = assertThrows(OAuthException::class.java) { runBlocking { auth.signIn("ada", "pw") } }
        assertEquals(why, e.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun passwordSignInOffLeavesTheCode() = runBlocking {
        server.enqueue(bootstrapAnswer(password = null))
        config = fetchBootstrap()
        assertNull("a code still signs in", config!!.problem)
        assertTrue(config!!.pairing)
        assertFalse(config!!.password)
        val why = "This server signs in only with a code shown on the screen."
        assertEquals(why, assertThrows(OAuthException::class.java) { runBlocking { auth.signIn("ada", "pw") } }.message)
        assertEquals(1, server.requestCount)
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun pairingConfig() {
        config = config!!.copy(
            deviceAuthorizationEndpoint = server.url("/oauth/device_authorization").toString(),
            verificationUri = server.url("/device").toString(),
        )
    }

    private val client get() = TokenClient(http, ::address, { config })

    @Test
    fun aCodeIsAskedForAsTheDeviceClient() = runBlocking {
        pairingConfig()
        server.enqueue(
            json(
                200,
                """{"device_code":"secret-device-code","user_code":"BCDF-GHJK","verification_uri":"http://tv.local/device",""" +
                    """"verification_uri_complete":"http://tv.local/device?user_code=BCDF-GHJK","expires_in":600,"interval":5}""",
            ),
        )
        val code = client.requestDeviceCode()
        val asked = server.takeRequest()
        assertEquals("/oauth/device_authorization", asked.url.encodedPath)
        assertEquals(mapOf("client_id" to "sovemu-native", "scope" to TokenClient.SCOPE), asked.form())
        assertEquals("BCDF-GHJK", code.grouped)
        assertEquals("tv.local/device", code.shownUri)
        assertEquals("http://tv.local/device?user_code=BCDF-GHJK", code.qrContent)
        assertEquals(600L, code.expiresInSeconds)
        assertEquals(5L, code.intervalSeconds)
        assertFalse("the credential never goes in a log line", code.toString().contains("secret"))
    }

    @Test
    fun aPollSaysWhatTheServerSaid() = runBlocking {
        pairingConfig()
        val code = DeviceCode("dc", "BCDFGHJK", "http://x/device", null, 600, 5)
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(400, """{"error":"slow_down"}"""))
        server.enqueue(json(429, """{"error":"too_many_requests"}"""))
        server.enqueue(json(503, "<html>"))
        server.enqueue(json(400, """{"error":"access_denied"}"""))
        server.enqueue(json(400, """{"error":"expired_token"}"""))
        server.enqueue(json(400, """{"error":"invalid_grant"}"""))
        server.enqueue(json(200, """{"access_token":"a1","token_type":"Bearer","expires_in":3600,"refresh_token":"r1"}"""))
        val answers = List(8) { client.pollDeviceCode(code) }
        assertEquals(
            listOf(
                DevicePoll.Pending,
                DevicePoll.SlowDown,
                DevicePoll.SlowDown,
                DevicePoll.NoAnswer,
                DevicePoll.Refused(PairingFailure.Denied),
                DevicePoll.Refused(PairingFailure.Expired),
                DevicePoll.Refused(PairingFailure.Failed),
                DevicePoll.Approved(Tokens("a1", 3600, "r1")),
            ),
            answers,
        )
        val poll = server.takeRequest().form()
        assertEquals(TokenClient.DEVICE_CODE_GRANT, poll["grant_type"])
        assertEquals("sovemu-native", poll["client_id"])
        assertEquals("dc", poll["device_code"])
    }

    @Test
    fun aBrowserSignInRefreshesAndSignsOutAsTheMobileClient() = runBlocking {
        config = config!!.copy(
            authorizationEndpoint = "/oauth/authorize",
            mobileClientId = "phone-app",
            mobileRedirectUri = "media.sovereign.sovemu:/oauth2redirect",
        )
        val request = BrowserSignInRequest.create(address(), config!!)!!
        server.enqueue(tokens("a1", "r1"))
        val got = TokenClient(http, ::address, { config }).exchangeCode("the-code", request)
        val exchange = server.takeRequest().form()
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "phone-app",
                "code" to "the-code",
                "redirect_uri" to "media.sovereign.sovemu:/oauth2redirect",
                "code_verifier" to request.pkce.verifier,
            ),
            exchange,
        )
        auth.signedInWith(got, clientId = "phone-app")
        assertEquals("phone-app", store.tokenClientId)

        // A refresh token is good only with its own client: the browser's is the mobile client's.
        server.enqueue(tokens("a2", "r2"))
        clock += 3_600_000
        assertEquals("a2", auth.accessToken())
        assertEquals("phone-app", server.takeRequest().form()["client_id"])
        server.enqueue(MockResponse.Builder().code(200).build())
        auth.signOut()
        assertEquals("phone-app", server.takeRequest().form()["client_id"])
        assertNull(store.tokenClientId)
    }

    @Test
    fun anApprovedCodeSignsIn() = runBlocking<Unit> {
        auth.signedInWith(Tokens("a1", 3600, "r1"))
        assertTrue(auth.signedIn.value)
        assertEquals("r1", store.refreshToken)
        assertEquals("a1", auth.accessToken())
        assertThrows(OAuthException::class.java) { runBlocking { auth.signedInWith(Tokens("a2", 3600, null)) } }
    }

    @Test
    fun noSovEmuServerAtTheAddress() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
        val e = assertThrows(ApiException::class.java) { runBlocking { fetchBootstrap() } }
        assertEquals("NOT_SOVEMU", e.code)
    }
}
