package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** *Sign in a TV*: the code as typed or pasted, and what each answer says (Sovereign's cases). */
class TvSignInTest {
    @Test
    fun theCodeIsTakenAsTypedOrPasted() {
        for (typed in listOf("qg3w", "QG3W-RY4W", "qg3w ry4w", " QG3WRY4W ", "qg3w-ry4w-extra")) {
            assertTrue(typed, TvSignIn.format(typed).startsWith("QG3W"))
        }
        assertEquals("QG3W-RY4W", TvSignIn.format("qg3w ry4w"))
        assertEquals("QG3W", TvSignIn.format("qg3w-"))
        assertTrue(TvSignIn.isComplete("QG3W-RY4W"))
        assertFalse("Continue waits for all eight", TvSignIn.isComplete("QG3W-RY4"))
    }

    @Test
    fun theConfirmationNamesTheServerAndTheAccount() {
        assertEquals(
            "Sign in a TV to http://192.168.1.10:8080 as ada?",
            TvSignIn.confirmation("http://192.168.1.10:8080", "ada"),
        )
    }

    @Test
    fun eachRefusalHasItsSentence() {
        assertTrue(
            TvSignIn.failure(
                ApiException("NOT_FOUND", "x"),
            ).startsWith("No TV is waiting with that code"),
        )
        assertTrue(
            TvSignIn.failure(ApiException("TOO_MANY_REQUESTS", "x")).startsWith("Too many codes"),
        )
        assertEquals("boom", TvSignIn.failure(ApiException("INTERNAL", "boom")))
    }
}
