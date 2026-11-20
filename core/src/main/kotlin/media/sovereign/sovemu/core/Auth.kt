package media.sovereign.sovemu.core

import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.json.JsonReader
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** What `POST /oauth/token` grants: [expiresIn] is in seconds. */
data class Tokens(val accessToken: String, val expiresIn: Long, val refreshToken: String?)

/**
 * `/oauth/token` refused: [error] is the OAuth error code (`invalid_grant`, `too_many_requests`
 * …), the message the server's `error_description`, written for people.
 */
class OAuthException(val error: String, description: String, val status: Int) : Exception(description) {
    /** Wrong username or password, or (refreshing) a refresh token that is expired or revoked. */
    val invalidGrant: Boolean get() = error == "invalid_grant"
}

/** Where the refresh token is kept across launches; "signed in" means there is one. */
interface TokenStore {
    var refreshToken: String?
}

/**
 * SovEmu's OAuth endpoints, as the public client `sovemu-native`: `/oauth/password` to sign in,
 * the refresh_token grant at `/oauth/token`, and revocation to sign out. [server] is the address the user
 * typed (`http://192.168.1.10:8080`).
 */
class TokenClient(
    private val http: OkHttpClient,
    private val server: () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Signs in with a username and password at the server's own `/oauth/password` route (OAuth
     * 2.1 has no password grant, so it takes no `grant_type`); it answers as `/oauth/token` does.
     */
    suspend fun signIn(username: String, password: String): Tokens = token(
        "/oauth/password",
        FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("username", username)
            .add("password", password)
            .add("scope", SCOPE)
            .build(),
    )

    suspend fun refresh(refreshToken: String): Tokens = token(
        "/oauth/token",
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refreshToken)
            .build(),
    )

    /** Revokes the sign-in [refreshToken] belongs to (the server always answers 200). */
    suspend fun revoke(refreshToken: String) {
        val form = FormBody.Builder().add("token", refreshToken).add("client_id", CLIENT_ID).build()
        withContext(io) { http.newCall(post("/oauth/revoke", form)).execute().close() }
    }

    private suspend fun token(path: String, form: FormBody): Tokens = withContext(io) {
        http.newCall(post(path, form)).execute().use { response ->
            val body = response.body.string()
            val json = parse(body)
            if (!response.isSuccessful) {
                throw OAuthException(
                    json?.get("error") as? String ?: "http_${response.code}",
                    json?.get("error_description") as? String
                        ?: "The server refused the sign-in (HTTP ${response.code})",
                    response.code,
                )
            }
            val access = json?.get("access_token") as? String
            val expiresIn = (json?.get("expires_in") as? Number)?.toLong()
                ?: json?.get("expires_in")?.toString()?.toLongOrNull()
            if (access == null || expiresIn == null) {
                throw OAuthException("invalid_response", "The server sent no access token", response.code)
            }
            Tokens(access, expiresIn, json["refresh_token"] as? String)
        }
    }

    private fun post(path: String, form: FormBody): Request {
        val url = "${server()}$path".toHttpUrlOrNull()
            ?: throw ApiException("BAD_REQUEST", "Invalid server address")
        return Request.Builder().url(url).post(form).build()
    }

    /**
     * The string and number members of the JSON object [body] (all the token endpoint sends), or
     * null when it is not one (a proxy's HTML error page, say).
     */
    private fun parse(body: String): Map<String, Any?>? = try {
        val reader = Buffer().writeUtf8(body).jsonReader()
        val members = HashMap<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonReader.Token.STRING -> members[name] = reader.nextString()
                JsonReader.Token.NUMBER, JsonReader.Token.LONG -> members[name] = reader.nextLong()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        members
    } catch (_: Exception) {
        null
    }

    companion object {
        const val CLIENT_ID = "sovemu-native"
        const val SCOPE = "openid profile offline_access"
    }
}

/**
 * The app's sign-in: the refresh token in [store], the access token in memory. The access token
 * is refreshed when it has under [AuthDefaults.REFRESH_MARGIN_MS] left, or after the server
 * rejects it; one refresh at a time (callers that arrive meanwhile wait for it and use its
 * token), since refresh tokens rotate and a reused one revokes the sign-in. A refresh that
 * answers `invalid_grant` signs the app out.
 *
 * [now] is a monotonic clock in milliseconds.
 */
class Auth(
    private val client: TokenClient,
    private val store: TokenStore,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Access(val token: String, val expiresAt: Long)

    @Volatile private var access: Access? = null
    private val lock = Mutex()
    private val _signedIn = MutableStateFlow(store.refreshToken != null)

    /** Whether a refresh token is stored; false once signed out (or the sign-in expires). */
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** Signs in with a username and password; throws [OAuthException] with the server's reason. */
    suspend fun signIn(username: String, password: String) {
        val tokens = client.signIn(username, password)
        if (tokens.refreshToken == null) {
            throw OAuthException("invalid_response", "The server granted no refresh token", 200)
        }
        lock.withLock { keep(tokens) }
        _signedIn.value = true
    }

    /**
     * The access token to send, refreshed first when it is about to expire; null when signed out
     * (also when the refresh just found the sign-in revoked). A network failure is thrown.
     */
    suspend fun accessToken(): String? {
        access?.takeIf { it.fresh() }?.let { return it.token }
        return refresh(stale = access?.token)
    }

    /**
     * A new access token, after [stale] (null: none) was rejected or expired, unless another
     * caller has already replaced it. Null when signed out.
     */
    suspend fun refresh(stale: String?): String? = lock.withLock {
        access?.let { if (it.token != stale && it.fresh()) return@withLock it.token }
        val refreshToken = store.refreshToken ?: return@withLock null
        val tokens = try {
            client.refresh(refreshToken)
        } catch (e: OAuthException) {
            if (!e.invalidGrant) throw e
            forget()
            return@withLock null
        }
        keep(tokens)
        tokens.accessToken
    }

    /**
     * Runs a GraphQL [call]; when it fails `UNAUTHENTICATED`, refreshes the access token and
     * runs it once more. When there is nothing to refresh with, the first failure is thrown.
     */
    suspend fun <T> retryingUnauthenticated(call: suspend () -> T): T {
        val sent = access?.token
        try {
            return call()
        } catch (e: ApiException) {
            if (!e.unauthenticated || refresh(stale = sent) == null) throw e
        }
        return call()
    }

    /** Revokes the sign-in on the server, then forgets it here whatever happened (offline too). */
    suspend fun signOut() {
        val refreshToken = lock.withLock { store.refreshToken.also { forget() } } ?: return
        try {
            client.revoke(refreshToken)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Forgotten here already; the server lets an unused sign-in expire on its own.
        }
    }

    /** Forgets the sign-in without telling the server (it has already refused it). */
    fun forget() {
        access = null
        store.refreshToken = null
        _signedIn.value = false
    }

    private fun Access.fresh() = expiresAt - now() > AuthDefaults.REFRESH_MARGIN_MS

    private fun keep(tokens: Tokens) {
        // Refresh tokens rotate: the new one replaces the old, which stops working soon after.
        tokens.refreshToken?.let { store.refreshToken = it }
        access = Access(tokens.accessToken, now() + tokens.expiresIn * 1000)
    }
}

object AuthDefaults {
    /** Refresh an access token with less than this left (ms). */
    const val REFRESH_MARGIN_MS = 60_000L
}

/**
 * Adds `Authorization: Bearer` to GraphQL requests (`…/graphql`), refreshing the access token
 * first when it is about to expire. Other requests (`/media/…` URLs are signed) go as they are.
 * Runs on OkHttp's threads, so waiting for a refresh blocks only the request that needs it.
 */
class BearerInterceptor(private val auth: Auth) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.endsWith("/graphql")) return chain.proceed(request)
        val token = try {
            runBlocking { auth.accessToken() }
        } catch (e: OAuthException) {
            throw IOException(e.message, e)
        } catch (e: ApiException) {
            throw IOException(e.message, e)
        }
        val signed = token?.let { request.newBuilder().header("Authorization", "Bearer $it").build() }
        return chain.proceed(signed ?: request)
    }
}
