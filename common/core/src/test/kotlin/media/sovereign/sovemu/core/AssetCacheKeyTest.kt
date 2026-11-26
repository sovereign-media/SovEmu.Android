package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetCacheKeyTest {
    @Test
    fun theSignatureIsNotPartOfTheKey() {
        val a = "/media/games/7/thumb?Sov-UserId=1&Sov-Expiry=1700003600&Sov-Signature=ab12"
        val b = "/media/games/7/thumb?Sov-UserId=1&Sov-Expiry=1700007200&Sov-Signature=cd34"
        assertEquals("/media/games/7/thumb", assetCacheKey(a))
        assertEquals(assetCacheKey(a), assetCacheKey(b))
    }

    @Test
    fun otherParametersStay() {
        // A save screenshot's URL changes with each save: that must still change the key.
        val url = "/media/games/7/saves/1?t=99&Sov-UserId=1&Sov-Expiry=1&Sov-Signature=ab"
        assertEquals("/media/games/7/saves/1?t=99", assetCacheKey(url))
        assertEquals("http://h:8080/media/x.png", assetCacheKey("http://h:8080/media/x.png"))
    }
}
