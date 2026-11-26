package media.sovereign.sovemu.core

import kotlin.math.abs
import kotlin.math.ln

/** How far (as a ratio) art's shape may be from its card's and still fill it, as the web's. */
private const val CLOSE = 1.15

/** A card's shape when its system gives none: the old portrait card. */
const val DEFAULT_BOX_ASPECT = 3f / 4f

/**
 * Whether box art fills its card (cropping a little) rather than showing whole inside it. A
 * system's cards take the shape of its boxes (`System.boxAspect`: SNES landscape, NES portrait);
 * art close to that shape fills the card, art of another shape (a Super Famicom box in a SNES
 * grid, a screenshot) is shown whole. Unknown shapes fill. The same rule as SovEmu.Web's
 * `artFit` (`src/lib/boxArt.ts`).
 */
fun artFills(imageAspect: Float, cardAspect: Float): Boolean {
    if (!(imageAspect > 0f) || !(cardAspect > 0f)) return true
    return abs(ln(imageAspect / cardAspect)) <= ln(CLOSE)
}
