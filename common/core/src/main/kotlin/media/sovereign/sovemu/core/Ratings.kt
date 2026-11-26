package media.sovereign.sovemu.core

import media.sovereign.sovemu.core.graphql.type.EsrbRating

// A game's ratings as the game panel shows them, after the web client's `lib/esrb.ts`: the
// ESRB's age rating as the ESRB's own icon and the
// score out of 1 as stars out of 5.

/**
 * What the badge for an ESRB rating shows: its [code] as the ESRB writes it ("E10+"), its
 * [name] ("Everyone 10+") and the [ages] along the badge's foot ("AGES 10+").
 */
data class EsrbInfo(val code: String, val name: String, val ages: String) {
    /** What the badge says to a screen reader: "ESRB: Teen (T)". */
    val label: String get() = "ESRB: $name ($code)"
}

/** Each rating's badge, or null for one this build does not know (a newer server's). */
fun esrbInfo(rating: EsrbRating): EsrbInfo? = when (rating) {
    EsrbRating.EARLY_CHILDHOOD -> EsrbInfo("EC", "Early Childhood", "AGES 3+")
    EsrbRating.EVERYONE -> EsrbInfo("E", "Everyone", "EVERYONE")
    EsrbRating.EVERYONE_10_PLUS -> EsrbInfo("E10+", "Everyone 10+", "AGES 10+")
    EsrbRating.TEEN -> EsrbInfo("T", "Teen", "TEEN")
    EsrbRating.MATURE -> EsrbInfo("M", "Mature 17+", "MATURE 17+")
    EsrbRating.ADULTS_ONLY -> EsrbInfo("AO", "Adults Only 18+", "ADULTS 18+")
    EsrbRating.RATING_PENDING -> EsrbInfo("RP", "Rating Pending", "PENDING")
    EsrbRating.UNKNOWN__ -> null
}

/** A rating out of 1 as stars out of 5, to the half: 0.8 → 4, 0.72 → 3.5. */
fun stars(rating: Double): Double = Math.round(rating.coerceIn(0.0, 1.0) * 10) / 2.0

/** "4 out of 5", "3.5 out of 5": [stars] without a ".0" on a whole number. */
fun starsText(rating: Double): String {
    val s = stars(rating)
    val n = if (s % 1.0 == 0.0) s.toInt().toString() else s.toString()
    return "$n out of 5"
}
