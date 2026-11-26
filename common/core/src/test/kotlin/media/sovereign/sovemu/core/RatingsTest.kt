package media.sovereign.sovemu.core

import media.sovereign.sovemu.core.graphql.type.EsrbRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingsTest {
    @Test
    fun everyRatingHasItsCodeNameAndAges() {
        val table = EsrbRating.knownEntries.associateWith { esrbInfo(it) }
        assertEquals(
            mapOf(
                EsrbRating.EARLY_CHILDHOOD to EsrbInfo("EC", "Early Childhood", "AGES 3+"),
                EsrbRating.EVERYONE to EsrbInfo("E", "Everyone", "EVERYONE"),
                EsrbRating.EVERYONE_10_PLUS to EsrbInfo("E10+", "Everyone 10+", "AGES 10+"),
                EsrbRating.TEEN to EsrbInfo("T", "Teen", "TEEN"),
                EsrbRating.MATURE to EsrbInfo("M", "Mature 17+", "MATURE 17+"),
                EsrbRating.ADULTS_ONLY to EsrbInfo("AO", "Adults Only 18+", "ADULTS 18+"),
                EsrbRating.RATING_PENDING to EsrbInfo("RP", "Rating Pending", "PENDING"),
            ),
            table,
        )
    }

    @Test
    fun aRatingThisBuildDoesNotKnowHasNoBadge() {
        assertNull(esrbInfo(EsrbRating.UNKNOWN__))
    }

    @Test
    fun theLabelNamesTheRatingAndItsCode() {
        assertEquals("ESRB: Teen (T)", esrbInfo(EsrbRating.TEEN)?.label)
        assertEquals("ESRB: Everyone 10+ (E10+)", esrbInfo(EsrbRating.EVERYONE_10_PLUS)?.label)
    }

    @Test
    fun starsRoundToTheHalf() {
        assertEquals(4.0, stars(0.8), 0.0)
        assertEquals(3.5, stars(0.72), 0.0)
        assertEquals(4.0, stars(0.75), 0.0)
        assertEquals(0.0, stars(0.0), 0.0)
        assertEquals(5.0, stars(1.0), 0.0)
    }

    @Test
    fun starsStayBetweenNoneAndFive() {
        assertEquals(0.0, stars(-0.3), 0.0)
        assertEquals(5.0, stars(1.4), 0.0)
    }

    @Test
    fun starsTextDropsTheDecimalOfAWholeNumber() {
        assertEquals("4 out of 5", starsText(0.8))
        assertEquals("3.5 out of 5", starsText(0.72))
        assertEquals("5 out of 5", starsText(1.0))
        assertEquals("0.5 out of 5", starsText(0.1))
    }
}
