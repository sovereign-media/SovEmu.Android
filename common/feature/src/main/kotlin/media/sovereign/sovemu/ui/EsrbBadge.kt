package media.sovereign.sovemu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import media.sovereign.sovemu.core.esrbInfo
import media.sovereign.sovemu.core.graphql.type.EsrbRating

object EsrbBadgeDefaults {
    /** As tall as the web client's (`h-14`); the icons are 2:3. */
    val Height: Dp = 56.dp
}

/**
 * An ESRB rating's icon, from the server ([url]: `Game.esrbBadgeUrl`, the built-in decoration
 * set's `ratings/esrb/<rating>.png`, which an admin may replace), named for screen readers
 * ("ESRB: Teen (T)"). Loaded and cached as artwork is ([Art]). Nothing for a rating this build
 * does not know.
 */
@Composable
fun EsrbBadge(rating: EsrbRating, url: String, modifier: Modifier = Modifier) {
    val info = esrbInfo(rating) ?: return
    Box(
        modifier
            .height(EsrbBadgeDefaults.Height)
            .aspectRatio(2f / 3f)
            .semantics {
                contentDescription = info.label
                role = Role.Image
            },
    ) {
        Art(url, Modifier.fillMaxSize(), scale = ContentScale.Fit)
    }
}
