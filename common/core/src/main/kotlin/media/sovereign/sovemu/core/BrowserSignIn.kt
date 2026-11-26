package media.sovereign.sovemu.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// A phone signing in through the system browser, as Sovereign's phones do: the server's own
// sign-in page in a Custom Tab, the authorization code flow with PKCE as the mobile client, back
// to the app's scheme (`media.sovereign.sovemu:/oauth2redirect`). The app never sees the
// password. The pure half: the request, and what came back; the exchange is
// [TokenClient.exchangeCode].

/**
 * A PKCE pair (RFC 7636): the verifier the app keeps, and the S256 challenge the browser carries.
 * 32 random bytes, base64url without padding: 43 characters, the RFC's recommended construction.
 */
data class Pkce(val verifier: String, val challenge: String) {
    /** Never the verifier: it is what makes an intercepted code worthless. */
    override fun toString() = "Pkce(challenge=$challenge)"

    companion object {
        const val METHOD = "S256"

        fun generate(random: SecureRandom = SecureRandom()): Pkce {
            val verifier = token(random, 32)
            return Pkce(verifier, challengeOf(verifier))
        }

        /** `BASE64URL(SHA256(ASCII(verifier)))`, RFC 7636 section 4.2. */
        fun challengeOf(verifier: String): String = base64Url(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
        )
    }
}

/**
 * One browser sign-in in flight: what the app holds between opening the browser and the
 * redirect coming back. [state] binds the redirect to this request and [pkce] the code to this
 * app; both are made afresh for every attempt.
 */
data class BrowserSignInRequest(
    val clientId: String,
    val redirectUri: String,
    /** The authorization endpoint, absolute. */
    val authorizationEndpoint: String,
    val state: String,
    val pkce: Pkce,
) {
    /**
     * The URL for the system browser: the code flow with PKCE and the scopes every sign-in asks
     * for.
     */
    val authorizeUrl: String
        get() = authorizationEndpoint + "?" + listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to TokenClient.SCOPE,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to Pkce.METHOD,
            "state" to state,
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }

    /**
     * [authorizeUrl] with OpenID Connect's `prompt=login`: the server's page asks who is signing
     * in even when the browser is signed in there already. For *Add user*, where a browser still
     * signed in as someone on the device would otherwise only sign them in again.
     */
    val authorizeUrlForAnotherUser: String get() = "$authorizeUrl&prompt=login"

    /** Never the verifier. */
    override fun toString() = "BrowserSignInRequest(clientId=$clientId, state=$state)"

    companion object {
        /**
         * The request kept while the browser was open, read back (the app may have been ended
         * meanwhile): its challenge follows from its verifier.
         */
        fun restore(
            clientId: String,
            redirectUri: String,
            authorizationEndpoint: String,
            state: String,
            verifier: String,
        ) = BrowserSignInRequest(
            clientId,
            redirectUri,
            authorizationEndpoint,
            state,
            Pkce(verifier, Pkce.challengeOf(verifier)),
        )

        /**
         * A request for the server [config] describes, at [server] (the address the user typed,
         * which a relative endpoint resolves against). Null when it offers no browser sign-in.
         */
        fun create(
            server: String,
            config: SignInConfig,
            random: SecureRandom = SecureRandom(),
        ): BrowserSignInRequest? {
            if (!config.browser) return null
            val endpoint = server.toHttpUrlOrNull()
                ?.resolve(config.authorizationEndpoint!!)
                ?.toString()
                ?: return null
            return BrowserSignInRequest(
                clientId = config.mobileClientId!!,
                redirectUri = config.mobileRedirectUri!!,
                authorizationEndpoint = endpoint,
                state = token(random, 16),
                pkce = Pkce.generate(random),
            )
        }
    }
}

/** What came back on the redirect, judged against the request that was sent. */
sealed interface BrowserRedirect {
    /** A code for this request: exchange it with the verifier. */
    data class Code(val code: String) : BrowserRedirect {
        override fun toString() = "Code(…)"
    }

    /** The server answered this request with an OAuth error (`access_denied` …). */
    data class Refused(val error: String, val description: String?) : BrowserRedirect

    /**
     * The `state` is missing or not this request's: a stale tab, an attempt crossing another, or
     * another app opening the scheme. Never exchanged.
     */
    data object StateMismatch : BrowserRedirect

    /** Not the app's redirect URI, or neither a code nor an error in it. */
    data object Malformed : BrowserRedirect

    companion object {
        /**
         * Reads [uri], the redirect the app was opened with, for [request]. The state is checked
         * first, before an error is believed: anything on the device can open the scheme, and
         * only the state proves the server sent it.
         */
        fun parse(uri: String, request: BrowserSignInRequest): BrowserRedirect {
            val at = uri.indexOf('?')
            if (at < 0 || uri.substring(0, at).substringBefore('#') != request.redirectUri) {
                return Malformed
            }
            val params = linkedMapOf<String, String>()
            for (pair in uri.substring(at + 1).substringBefore('#').split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val name = decode(if (eq < 0) pair else pair.substring(0, eq)) ?: continue
                val value = (if (eq < 0) "" else decode(pair.substring(eq + 1))) ?: continue
                params.putIfAbsent(name, value)
            }
            if (params["state"] != request.state) return StateMismatch
            params["error"]?.takeIf { it.isNotEmpty() }?.let {
                return Refused(it, params["error_description"])
            }
            return params["code"]?.takeIf { it.isNotEmpty() }?.let(::Code) ?: Malformed
        }

        private fun decode(text: String): String? = try {
            URLDecoder.decode(text, StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * What a browser sign-in that did not sign in says, as Sovereign's phones say it: one sentence of
 * the app's own whatever went wrong, since the next step is the same. Never the redirect's
 * `error_description`: anything on the device can open the redirect URI, and its text is not the
 * server's until the state proves it; nor a code exchange's, which is protocol prose written for a
 * developer. A refusal the person made in the browser says only that it was cancelled.
 */
fun BrowserRedirect.message(): String = when (this) {
    is BrowserRedirect.Code -> ""

    is BrowserRedirect.Refused ->
        if (error == "access_denied") BrowserSignInText.CANCELLED else BrowserSignInText.FAILED

    BrowserRedirect.StateMismatch, BrowserRedirect.Malformed -> BrowserSignInText.FAILED
}

/** The browser sign-in's own words. */
object BrowserSignInText {
    const val FAILED = "Signing in didn't finish. Sign in again."
    const val CANCELLED = "The sign-in was cancelled."
    const val NO_BROWSER = "There is no browser on this device to sign in with."
}

private fun token(random: SecureRandom, bytes: Int): String =
    base64Url(ByteArray(bytes).also(random::nextBytes))

private fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
