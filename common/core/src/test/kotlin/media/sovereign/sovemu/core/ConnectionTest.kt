package media.sovereign.sovemu.core

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTest {
    private fun h(name: String, value: String = "v") = CustomHeader(name, value)

    @Test
    fun aHeaderNameMustBeATokenAndNotTheApps() {
        assertNull(CustomHeaderRules.nameError("CF-Access-Client-Id"))
        assertTrue(CustomHeaderRules.nameError("X Bad")!!.contains("no spaces"))
        assertTrue(CustomHeaderRules.nameError("X:Bad")!!.contains("no spaces"))
        assertTrue(CustomHeaderRules.nameError("Authorization")!!.contains("set by the app"))
        assertEquals("Enter a header name.", CustomHeaderRules.nameError(""))
        assertEquals("abc", CustomHeaderRules.cleanValue(" a\r\nbc "))
    }

    @Test
    fun oneHeaderPerNameWhateverItsCaseAndFiveAtMost() {
        val one = CustomHeaderRules.upsert(emptyList(), "X-Token", "a")
        val replaced = CustomHeaderRules.upsert(one + h("Other"), "x-token", "b")
        assertEquals(listOf(h("x-token", "b"), h("Other")), replaced)
        // An edit in place keeps its row, and its old name goes.
        assertEquals(
            listOf(h("Renamed", "c"), h("Other")),
            CustomHeaderRules.upsert(replaced, "Renamed", "c", 0),
        )
        val five = (1..5).map { h("H$it") }
        assertTrue(CustomHeaderRules.validate(five, "H6", "v")!!.contains("up to 5"))
        assertNull(CustomHeaderRules.validate(five, "h3", "new"))
        assertNull(CustomHeaderRules.validate(five, "Other", "v", replaceIndex = 2))
    }

    @Test
    fun aDamagedStoreKeepsWhatIsUsable() {
        val blob = "X-Token\ta\nbad line\nAuthorization\tsecret\nx-token\tdup\nY\tb\nZ\t"
        assertEquals(listOf(h("X-Token", "a"), h("Y", "b")), CustomHeaderRules.decode(blob))
        val headers = listOf(h("A", "1"), h("B", "2"))
        assertEquals(headers, CustomHeaderRules.decode(CustomHeaderRules.encode(headers)))
        assertTrue(CustomHeaderRules.decode(null).isEmpty())
        assertEquals("CustomHeader(name=A)", h("A", "secret").toString())
    }

    @Test
    fun aBareAddressIsAskedOverHttpsFirst() {
        assertEquals(
            listOf("https://tv.local:8080", "http://tv.local:8080"),
            ServerAddress.candidates(" tv.local:8080/ ", secureOnly = false),
        )
        assertEquals(
            listOf("https://tv.local"),
            ServerAddress.candidates("tv.local", secureOnly = true),
        )
        assertEquals(listOf("http://x"), ServerAddress.candidates("http://x", secureOnly = false))
        assertTrue(ServerAddress.candidates("http://x", secureOnly = true).isEmpty())
        assertEquals(listOf("HTTPS://x"), ServerAddress.candidates("HTTPS://x", secureOnly = true))
        assertTrue(ServerAddress.candidates("  ", secureOnly = false).isEmpty())
        assertTrue(ServerAddress.names("tv.local:8080", "http://tv.local:8080"))
    }

    @Test
    fun headersGoToTheServerAndNowhereElse() {
        MockWebServer().use { server ->
            server.start()
            MockWebServer().use { other ->
                other.start()
                val client = OkHttpClient.Builder()
                    .addInterceptor(
                        CustomHeaderInterceptor(
                            { listOf(h("CF-Access-Client-Id", "id"), h("X-Secret", "s")) },
                            { server.url("/").toString().trimEnd('/') },
                        ),
                    )
                    .build()
                server.enqueue(MockResponse.Builder().code(200).build())
                other.enqueue(MockResponse.Builder().code(200).build())
                client.newCall(
                    Request.Builder().url(server.url("/graphql")).build(),
                ).execute().close()
                client.newCall(
                    Request.Builder().url(other.url("/image.jpg")).build(),
                ).execute().close()
                val sent = server.takeRequest()
                assertEquals("id", sent.headers["CF-Access-Client-Id"])
                assertEquals("s", sent.headers["X-Secret"])
                assertNull(other.takeRequest().headers["X-Secret"])
            }
        }
    }

    /** Answers how to sign in only at [up]. */
    private class Service(val up: String) : SignInService {
        val asked = mutableListOf<String>()
        var current = ""
        override val server get() = current
        override suspend fun bootstrapAt(address: String): SignInConfig {
            asked += address
            current = address
            if (address != up) throw java.io.IOException("refused")
            return SignInConfig(address, true, "c", "/t", "/r", "/p")
        }
        override fun setServer(address: String) {
            current = address
        }
        override suspend fun setupRequired() = false
        override suspend fun signIn(username: String, password: String) = Unit
        override fun startBrowserSignIn(): BrowserSignInRequest? = null
        override suspend fun finishBrowserSignIn(uri: String) = Unit
        override suspend fun requestDeviceCode(): DeviceCode = error("unused")
        override suspend fun pollDeviceCode(code: DeviceCode): DevicePoll = error("unused")
        override suspend fun signedInWith(tokens: Tokens) = Unit
    }

    @Test
    fun aServerWithoutTlsIsReachedOverHttpUnlessSecureOnly() = runBlocking {
        val service = Service(up = "http://tv.local:8080")
        val flow = SignInFlow(service, CoroutineScope(Dispatchers.Unconfined), null, {}, { 0L })
        flow.check("tv.local:8080", secureOnly = false)
        assertEquals(listOf("https://tv.local:8080", "http://tv.local:8080"), service.asked)
        assertEquals("http://tv.local:8080", flow.config.value?.address)

        val strict = Service(up = "http://tv.local:8080")
        val refused = SignInFlow(strict, CoroutineScope(Dispatchers.Unconfined), null, {}, { 0L })
        refused.check("tv.local:8080", secureOnly = true)
        assertEquals(listOf("https://tv.local:8080"), strict.asked)
        assertNull(refused.config.value)
        assertEquals("the address it was on comes back", "", strict.current)
        refused.check("http://tv.local:8080", secureOnly = true)
        assertEquals(ServerAddress.INSECURE, refused.error.value)
        assertEquals(1, strict.asked.size)
    }

    @Test
    fun aLongWaitRequestGetsItsOwnReadTimeoutAndNoMark() {
        MockWebServer().use { server ->
            server.start()
            val timeouts = mutableListOf<Int>()
            val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(LongWaitInterceptor)
                .addInterceptor { chain ->
                    timeouts += chain.readTimeoutMillis()
                    chain.proceed(chain.request())
                }
                .build()
            repeat(2) { server.enqueue(MockResponse.Builder().code(200).build()) }
            val url = server.url("/graphql")
            client.newCall(Request.Builder().url(url).header(LONG_WAIT_HEADER, "1").build())
                .execute().close()
            client.newCall(Request.Builder().url(url).build()).execute().close()
            assertEquals(listOf(LONG_WAIT_SECONDS * 1000, 30_000), timeouts)
            assertNull(server.takeRequest().headers[LONG_WAIT_HEADER])
        }
    }
}
