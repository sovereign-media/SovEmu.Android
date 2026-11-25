package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedTokenTest {
    /** Preferences in a map. */
    private class Prefs : KeyValue {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** A reversible stand-in for the Keystore's AES-GCM: marks what it sealed. */
    private class FakeSealer : Sealer {
        var unseals = 0
        override fun seal(plaintext: String) = "sealed:" + plaintext.reversed()
        override fun unseal(sealed: String): String {
            unseals++
            require(sealed.startsWith("sealed:")) { "not ours" }
            return sealed.removePrefix("sealed:").reversed()
        }
    }

    @Test
    fun aTokenIsStoredSealedNeverPlain() {
        val prefs = Prefs()
        val token = SealedToken(prefs, FakeSealer())
        token.value = "refresh-1"
        assertEquals("refresh-1", token.value)
        assertFalse(prefs.map.values.any { it.contains("refresh-1") })
        // Another launch reads it back.
        assertEquals("refresh-1", SealedToken(prefs, FakeSealer()).value)
        token.value = null
        assertTrue(prefs.map.isEmpty())
        assertNull(SealedToken(prefs, FakeSealer()).value)
    }

    @Test
    fun anEarlierVersionsPlainTokenIsSealedWithoutSigningOut() {
        val prefs = Prefs().apply { map[SealedToken.PLAIN] = "old-token" }
        val token = SealedToken(prefs, FakeSealer())
        assertEquals("old-token", token.value)
        assertNull(prefs.map[SealedToken.PLAIN])
        assertEquals("sealed:nekot-dlo", prefs.map[SealedToken.SEALED])
    }

    @Test
    fun aTokenThatCannotBeOpenedReadsAsSignedOut() {
        val prefs = Prefs().apply { map[SealedToken.SEALED] = "from another key" }
        assertNull(SealedToken(prefs, FakeSealer()).value)
        assertNull("dropped, not tried again", prefs.map[SealedToken.SEALED])
    }

    @Test
    fun theTokenIsUnsealedOnce() {
        val prefs = Prefs()
        val sealer = FakeSealer()
        SealedToken(prefs, sealer).value = "t"
        val token = SealedToken(prefs, sealer)
        repeat(5) { assertEquals("t", token.value) }
        assertEquals(1, sealer.unseals)
    }
}
