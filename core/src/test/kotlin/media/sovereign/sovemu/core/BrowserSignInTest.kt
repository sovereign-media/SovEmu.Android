package media.sovereign.sovemu.core

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phone's browser sign-in: the request it opens, and what it makes of the redirect (Sovereign's cases). */
class BrowserSignInTest {
    private val config = SignInConfig(
        address = "192.168.1.10:8080",
        signInAvailable = true,
        clientId = "device-id",
        tokenEndpoint = "/oauth/token",
        revocationEndpoint = "/oauth/revoke",
        passwordEndpoint = null,
        authorizationEndpoint = "/oauth/authorize",
        mobileClientId = "mobile-id",
        mobileRedirectUri = "media.sovereign.sovemu:/oauth2redirect",
    )
    private val request = BrowserSignInRequest.create("http://192.168.1.10:8080", config, SecureRandom())!!

    @Test
    fun pkceIsRfc7636s() {
        // RFC 7636 appendix B's example.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val pkce = Pkce.generate()
        assertEquals(43, pkce.verifier.length)
        assertFalse("the verifier never goes in a log line", pkce.toString().contains(pkce.verifier))
        assertFalse(request.toString().contains(request.pkce.verifier))
    }

    @Test
    fun theRequestIsTheCodeFlowWithPkceAsTheMobileClient() {
        val url = request.authorizeUrl
        assertTrue(url, url.startsWith("http://192.168.1.10:8080/oauth/authorize?"))
        for (part in listOf(
            "client_id=mobile-id",
            "response_type=code",
            "redirect_uri=media.sovereign.sovemu%3A%2Foauth2redirect",
            "scope=openid%20profile%20offline_access",
            "code_challenge=${request.pkce.challenge}",
            "code_challenge_method=S256",
            "state=${request.state}",
        )) {
            assertTrue(part, url.contains(part))
        }
        assertFalse(url.contains(request.pkce.verifier))
        // A server without the mobile client offers none.
        assertNull(BrowserSignInRequest.create("http://x", config.copy(mobileClientId = null)))
    }

    @Test
    fun theRedirectIsBelievedOnlyWithThisRequestsState() {
        val back = "media.sovereign.sovemu:/oauth2redirect"
        assertEquals(BrowserRedirect.Code("abc"), BrowserRedirect.parse("$back?code=abc&state=${request.state}&iss=x", request))
        // The state first: an error is believed only when the server sent it.
        assertEquals(BrowserRedirect.StateMismatch, BrowserRedirect.parse("$back?error=access_denied&state=other", request))
        assertEquals(BrowserRedirect.StateMismatch, BrowserRedirect.parse("$back?code=abc", request))
        assertEquals(
            BrowserRedirect.Refused("access_denied", "no thanks"),
            BrowserRedirect.parse("$back?error=access_denied&error_description=no+thanks&state=${request.state}", request),
        )
        assertEquals(BrowserRedirect.Malformed, BrowserRedirect.parse("media.sovereign.sovemu:/elsewhere?code=a&state=${request.state}", request))
        assertEquals(BrowserRedirect.Malformed, BrowserRedirect.parse("$back?state=${request.state}", request))
    }
}
