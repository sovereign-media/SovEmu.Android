package media.sovereign.sovemu.ui

import media.sovereign.sovemu.core.graphql.type.EsrbRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every ESRB rating the server sends has its own badge image; a newer server's has none. */
class EsrbBadgeTest {
    @Test
    fun everyRatingHasItsOwnBadge() {
        val known = EsrbRating.entries.filter { it != EsrbRating.UNKNOWN__ }
        val badges = known.mapNotNull { esrbBadge(it) }
        assertEquals(known.size, badges.size)
        assertEquals("one image each", known.size, badges.toSet().size)
        assertNull(esrbBadge(EsrbRating.UNKNOWN__))
    }
}
