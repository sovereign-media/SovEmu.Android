package media.sovereign.sovemu.core

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.exception.ApolloNetworkException
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.sovereign.sovemu.core.graphql.BootstrapQuery
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * What `POST /oauth/token` grants: [expiresIn] is in seconds. [idToken] says who signed in
 * ([IdToken]); the server adds one to every grant that asked for `openid`.
 */
data class Tokens(
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String?,
    val idToken: String? = null,
)

/**
 * `/oauth/token` refused: [error] is the OAuth error code (`invalid_grant`, `too_many_requests`
 * …), the message the server's `error_description`, written for people.
 */
class OAuthException(val error: String, description: String, val status: Int) :
    Exception(description) {
    /** Wrong username or password, or (refreshing) a refresh token that is expired or revoked. */
    val invalidGrant: Boolean get() = error == "invalid_grant"
}

/**
 * Where and how to sign in at a server address: its `bootstrapConfig`, which the app asks first
 * (signed out) and keeps with the address, so a stored sign-in refreshes even when the app
 * starts offline. The endpoints are used as given; the app hard-codes nothing but `/graphql`.
 */
data class SignInConfig(
    /** The address the server saw (`Host`), which is what `SOVEMU_PUBLIC_URL` must list. */
    val address: String,
    val signInAvailable: Boolean,
    /** The device client's id: what the app signs in as, with a code or a password. */
    val clientId: String?,
    val tokenEndpoint: String?,
    val revocationEndpoint: String?,
    /** The password sign-in; null when the server has switched it off. */
    val passwordEndpoint: String?,
    /** Where to ask for a code to show (RFC 8628); null from a server that offers none. */
    val deviceAuthorizationEndpoint: String? = null,
    /** Where the person approves that code, in a signed-in browser. */
    val verificationUri: String? = null,
    /** Where a browser sign-in starts (the code flow with PKCE). */
    val authorizationEndpoint: String? = null,
    /** The mobile client: what a phone signs in as through the browser. */
    val mobileClientId: String? = null,
    /** Where the browser's sign-in comes back to, the app's own scheme. */
    val mobileRedirectUri: String? = null,
) {
    /** Whether a phone can sign in through the system browser here. */
    val browser: Boolean
        get() = authorizationEndpoint != null && mobileClientId != null && mobileRedirectUri != null

    /** Whether the app can sign in by showing a code to approve on another screen. */
    val pairing: Boolean get() = deviceAuthorizationEndpoint != null

    /** Whether the app can sign in with a username and password. */
    val password: Boolean get() = passwordEndpoint != null

    /** Why this app cannot sign in here at all, written for people; null when it can. */
    val problem: String?
        get() = when {
            !signInAvailable ->
                "Signing in isn't available at $address. Add it to SOVEMU_PUBLIC_URL on the server."

            clientId == null || tokenEndpoint == null || (!pairing && !password) ->
                "The server sent no sign-in details."

            else -> null
        }
}

/**
 * Asks the server at [apollo]'s address how to sign in (`bootstrapConfig`, which works signed
 * out). Anything but a GraphQL answer with it means there is no SovEmu server there, or one too
 * old for this app: that is an [ApiException] `NOT_SOVEMU`. A network failure is thrown as it is.
 */
suspend fun bootstrap(apollo: ApolloClient, address: String): SignInConfig {
    val response = apollo.query(BootstrapQuery()).execute()
    if (response.exception is ApolloNetworkException) throw response.exception!!
    val data = try {
        response.orThrow()
    } catch (_: ApiException) {
        throw ApiException(
            "NOT_SOVEMU",
            "No SovEmu server answered at $address (or it is older than this app).",
        )
    }
    return data.bootstrapConfig.toSignInConfig()
}

fun BootstrapQuery.BootstrapConfig.toSignInConfig(): SignInConfig = SignInConfig(
    address = address,
    signInAvailable = signInAvailable,
    clientId = deviceClientId,
    tokenEndpoint = tokenEndpoint,
    revocationEndpoint = revocationEndpoint,
    passwordEndpoint = passwordEndpoint,
    deviceAuthorizationEndpoint = deviceAuthorizationEndpoint,
    verificationUri = verificationUri,
    authorizationEndpoint = authorizationEndpoint,
    mobileClientId = mobileClientId,
    mobileRedirectUri = mobileRedirectUri,
)

/**
 * SovEmu's OAuth endpoints, as the device client, at the URLs [config] (the server's
 * `bootstrapConfig`) gives: a code to approve elsewhere (the device grant), the password
 * sign-in, the refresh_token grant at the token endpoint, and revocation to sign out. [server] is
 * the address the user typed (`http://192.168.1.10:8080`), which relative endpoints resolve
 * against.
 */
class TokenClient(
    private val http: OkHttpClient,
    private val server: () -> String,
    private val config: () -> SignInConfig?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The device client's id at the current server: what a code or a password signs in as. */
    val deviceClientId: String? get() = config()?.clientId

    /**
     * Signs in with a username and password at the server's password endpoint (OAuth 2.1 has no
     * password grant, so it takes no `grant_type`); it answers as the token endpoint does.
     */
    suspend fun signIn(username: String, password: String): Tokens {
        val c = config() ?: throw unavailable("Check the server address first.")
        c.problem?.let { throw unavailable(it) }
        if (!c.password) {
            throw unavailable(
                "This server signs in only with a code shown on the screen.",
            )
        }
        return token(
            c.passwordEndpoint!!,
            FormBody.Builder()
                .add("client_id", c.clientId!!)
                .add("username", username)
                .add("password", password)
                .add("scope", SCOPE)
                .build(),
        )
    }

    /**
     * A new access token for [refreshToken], as [client] (null: the device client) it was issued
     * to.
     */
    suspend fun refresh(refreshToken: String, client: String? = null): Tokens {
        val c = config()
        val endpoint = c?.tokenEndpoint
        val clientId = client ?: c?.clientId
        if (endpoint == null ||
            clientId == null
        ) {
            throw unavailable("The server sent no sign-in details.")
        }
        return token(
            endpoint,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .build(),
        )
    }

    /**
     * Asks the server for a code to show (RFC 8628 device authorization). A refusal is an
     * [OAuthException]; a network failure is thrown as it is.
     */
    suspend fun requestDeviceCode(): DeviceCode {
        val c = config() ?: throw unavailable("Check the server address first.")
        c.problem?.let { throw unavailable(it) }
        val endpoint = c.deviceAuthorizationEndpoint
            ?: throw unavailable("This server does not sign in with a code.")
        val form = FormBody.Builder().add("client_id", c.clientId!!).add("scope", SCOPE).build()
        val (status, json) = send(endpoint, form)
        if (status !in 200..299) throw refusal(status, json)
        val deviceCode = json?.get("device_code") as? String
        val userCode = json?.get("user_code") as? String
        val verificationUri = json?.get("verification_uri") as? String ?: c.verificationUri
        val expiresIn = (json?.get("expires_in") as? Long)
        if (deviceCode == null || userCode == null || verificationUri == null ||
            expiresIn == null
        ) {
            throw OAuthException("invalid_response", "The server sent no code to show", status)
        }
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = json["verification_uri_complete"] as? String,
            expiresInSeconds = expiresIn,
            intervalSeconds = (json["interval"] as? Long) ?: DeviceCode.DEFAULT_INTERVAL_SECONDS,
        )
    }

    /**
     * Asks the token endpoint whether [code] has been approved: one device_code grant. Never
     * throws for an answer, only for cancellation: a request that got none is
     * [DevicePoll.NoAnswer], and the next poll is the retry.
     */
    suspend fun pollDeviceCode(code: DeviceCode): DevicePoll {
        val c = config()
        val endpoint = c?.tokenEndpoint
        val clientId = c?.clientId
        if (endpoint == null || clientId == null) return DevicePoll.Refused(PairingFailure.Failed)
        val form = FormBody.Builder()
            .add("grant_type", DEVICE_CODE_GRANT)
            .add("client_id", clientId)
            .add("device_code", code.deviceCode)
            .build()
        val (status, json) = try {
            send(endpoint, form)
        } catch (_: IOException) {
            return DevicePoll.NoAnswer
        }
        if (status in 200..299) {
            return try {
                DevicePoll.Approved(tokens(status, json))
            } catch (_: OAuthException) {
                DevicePoll.Refused(PairingFailure.Failed)
            }
        }
        if (status == 429) return DevicePoll.SlowDown
        return when (json?.get("error") as? String) {
            // On every poll until someone approves: waiting, not a failure.
            "authorization_pending" -> DevicePoll.Pending

            "slow_down" -> DevicePoll.SlowDown

            "expired_token" -> DevicePoll.Refused(PairingFailure.Expired)

            "access_denied" -> DevicePoll.Refused(PairingFailure.Denied)

            else -> if (status >=
                500
            ) {
                DevicePoll.NoAnswer
            } else {
                DevicePoll.Refused(PairingFailure.Failed)
            }
        }
    }

    /**
     * Exchanges the code a browser sign-in came back with, as the mobile client, with the
     * verifier only this app holds (RFC 7636).
     */
    suspend fun exchangeCode(code: String, request: BrowserSignInRequest): Tokens {
        val c = config() ?: throw unavailable("Check the server address first.")
        val endpoint = c.tokenEndpoint ?: throw unavailable("The server sent no sign-in details.")
        return token(
            endpoint,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", request.clientId)
                .add("code", code)
                .add("redirect_uri", request.redirectUri)
                .add("code_verifier", request.pkce.verifier)
                .build(),
        )
    }

    /** Revokes the sign-in [refreshToken] belongs to (the server always answers 200). */
    suspend fun revoke(refreshToken: String, client: String? = null) {
        val c = config()
        val endpoint = c?.revocationEndpoint ?: return
        val clientId = client ?: c.clientId ?: return
        val form = FormBody.Builder().add("token", refreshToken).add("client_id", clientId).build()
        withContext(io) { http.newCall(post(endpoint, form)).execute().close() }
    }

    private fun unavailable(why: String) = OAuthException(SIGN_IN_UNAVAILABLE, why, 0)

    private suspend fun token(path: String, form: FormBody): Tokens {
        val (status, json) = send(path, form)
        if (status !in 200..299) throw refusal(status, json)
        return tokens(status, json)
    }

    /** A form post: the status and the answer's JSON members (null when it is not JSON). */
    private suspend fun send(path: String, form: FormBody): Pair<Int, Map<String, Any?>?> =
        withContext(io) {
            http.newCall(post(path, form)).execute().use { response ->
                response.code to
                    parse(response.body.string())
            }
        }

    private fun refusal(status: Int, json: Map<String, Any?>?) = OAuthException(
        json?.get("error") as? String ?: "http_$status",
        json?.get(
            "error_description",
        ) as? String ?: "The server refused the sign-in (HTTP $status)",
        status,
    )

    private fun tokens(status: Int, json: Map<String, Any?>?): Tokens {
        val access = json?.get("access_token") as? String
        val expiresIn = (json?.get("expires_in") as? Number)?.toLong()
            ?: json?.get("expires_in")?.toString()?.toLongOrNull()
        if (access == null || expiresIn == null) {
            throw OAuthException("invalid_response", "The server sent no access token", status)
        }
        return Tokens(
            access,
            expiresIn,
            json["refresh_token"] as? String,
            json["id_token"] as? String,
        )
    }

    private fun post(endpoint: String, form: FormBody): Request {
        val url = server().toHttpUrlOrNull()?.resolve(endpoint)
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
        const val SCOPE = "openid profile offline_access"

        /** RFC 8628's grant type, polled at the token endpoint. */
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

        /** [OAuthException.error] when the server's sign-in details rule a sign-in out. */
        const val SIGN_IN_UNAVAILABLE = "sign_in_unavailable"
    }
}
