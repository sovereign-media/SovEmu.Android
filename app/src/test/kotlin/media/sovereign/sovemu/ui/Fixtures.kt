package media.sovereign.sovemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.graphql.fragment.GameFields
import media.sovereign.sovemu.core.graphql.type.EsrbRating

/** A game with only what a UI test cares about set (the generated model has no defaults). */
fun game(
    id: Int = 1,
    title: String = "Metroid",
    favourite: Boolean = false,
    discs: Int = 1,
    release: String? = null,
    patch: String? = null,
    playCount: Int = 0,
    autoSave: GameFields.AutoSave? = null,
    rating: Double? = null,
    esrb: EsrbRating? = null,
    esrbBadgeUrl: String? = null,
    modes: List<String> = emptyList(),
    series: List<String> = emptyList(),
    themes: List<String> = emptyList(),
): Game = Game(
    id = id, system = "nes", title = title, fileTitle = null, tags = emptyList(), path = "nes/$title.nes", size = 0.0,
    lastPlayedAt = null, playCount = playCount, image = null, imageSmall = null, cover = null, thumb = null, video = null, description = null,
    releaseDate = null, developer = null, publisher = null, genre = null, genres = emptyList(), players = null, rating = rating, esrb = esrb, esrbBadgeUrl = esrbBadgeUrl,
    modes = modes, series = series, themes = themes,
    discs = discs, partKind = null, favourite = favourite, release = release, patch = patch, autoSave = autoSave,
)

fun system(): SystemSummary = SystemSummary(
    id = "nes", name = "Nintendo Entertainment System", short = "NES", manufacturer = "Nintendo", year = 1983,
    color = "#c0392b", photoUrl = null, gameCount = 1, playable = true, needsGpu = false, rendering = null,
    biosMissing = false, boxAspect = 0.72,
)

/**
 * The app's composition locals for a test: an API with no server (nothing in these tests has a
 * picture to load) and the TV form, so the D-pad paths are the ones under test.
 */
@Composable
fun TestApp(tv: Boolean = true, content: @Composable () -> Unit) {
    val api = SovEmuApi(Settings(ApplicationProvider.getApplicationContext()))
    CompositionLocalProvider(LocalApi provides api, LocalForm provides Form(tv = tv, wide = true), content = content)
}
