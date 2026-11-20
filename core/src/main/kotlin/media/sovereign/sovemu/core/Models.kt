package media.sovereign.sovemu.core

import media.sovereign.sovemu.core.graphql.ControlsQuery
import media.sovereign.sovemu.core.graphql.fragment.CheatFields
import media.sovereign.sovemu.core.graphql.fragment.GameFields
import media.sovereign.sovemu.core.graphql.fragment.LayoutFields
import media.sovereign.sovemu.core.graphql.fragment.MappingFields
import media.sovereign.sovemu.core.graphql.fragment.SaveFields
import media.sovereign.sovemu.core.graphql.fragment.SessionFields
import media.sovereign.sovemu.core.graphql.fragment.SystemFields
import media.sovereign.sovemu.core.graphql.fragment.UserFields
import media.sovereign.sovemu.core.graphql.type.BindingInput
import media.sovereign.sovemu.core.graphql.type.PadButton
import media.sovereign.sovemu.core.graphql.type.Rendering

// The API's models. Most are the fragments Apollo generates from operations.graphql against
// SovEmu.Server's schema, under the names the app uses; the controller models stay hand-written
// (they implement docs/CONTROLLERS.md and the wire protocol's Button) and are converted here.

typealias Game = GameFields
typealias SystemSummary = SystemFields
typealias SaveSlot = SaveFields
typealias SessionInfo = SessionFields
typealias User = UserFields
typealias Cheat = CheatFields

/**
 * An API error. [code] is the GraphQL error's `extensions.code` (`UNAUTHENTICATED`,
 * `FORBIDDEN`, `NOT_FOUND`, …), or `HTTP_<status>` when the server answered with no GraphQL
 * response at all.
 */
class ApiException(val code: String, message: String) : Exception(message) {
    /** Not signed in (or the session expired): sign in again. */
    val unauthenticated: Boolean get() = code == "UNAUTHENTICATED"
}

/** `Query.viewer`: who is signed in, and whether first-run setup is needed. */
data class Viewer(val setupRequired: Boolean, val user: User?)

/**
 * `Query.home` and this user's collections: the hero's games, recently played (with their auto
 * saves), recently added, favourites, and a row per collection.
 */
data class HomeFeed(
    val featured: List<Game>,
    val recent: List<Game>,
    val added: List<Game>,
    val favourites: List<Game> = emptyList(),
    val collections: List<HomeCollection> = emptyList(),
)

/** One of this user's collections, with its games (most recently added first). */
data class HomeCollection(val id: Int, val name: String, val games: List<Game>)

/**
 * A soft patch for a game (a translation, a fix, a hack; applied as it loads). [isDefault]: it
 * sits beside the game with its name, and applies unless the player chose otherwise.
 */
data class GamePatch(val id: Int, val name: String, val isDefault: Boolean)

/** The choice after [current] when stepping through the original (null) and then [patches]. */
fun nextPatch(patches: List<GamePatch>, current: String?): GamePatch? {
    val options = listOf<GamePatch?>(null) + patches
    val at = options.indexOfFirst { it?.name == current }.coerceAtLeast(0)
    return options[(at + 1) % options.size]
}

/**
 * Artwork drawn around a game's picture (`Game.decoration`, Batocera's format): an image
 * [width] x [height] with a transparent window [left]/[top]/[right]/[bottom] pixels in from its
 * edges, which the picture shows through.
 */
data class GameDecoration(
    val url: String,
    val width: Int,
    val height: Int,
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
) {
    /** The image's shape. */
    val aspect: Float get() = if (height > 0) width.toFloat() / height else 16f / 9f
}

/**
 * Whether to frame the picture: only when the screen is wider than it, so there are bars to
 * fill (a phone held upright shows the picture alone, as large as it can be).
 */
fun shouldFrame(screenAspect: Float, pictureAspect: Float): Boolean = screenAspect > pictureAspect * 1.05f

/** A collection a game can go in, and whether it is in it. */
data class CollectionChoice(val id: Int, val name: String, val member: Boolean)

/** "1996", from the release date. */
val Game.year: String? get() = releaseDate?.take(4)?.takeIf { it.length == 4 }

/** "1996 · Nintendo · Platform", from what is known. */
val Game.byline: String get() = listOfNotNull(year, developer ?: publisher, genre).joinToString(" · ")

/** The small status badge on a system's card, if it needs one (as the web client's). */
fun SystemSummary.badge(): String? = when {
    needsGpu && rendering == Rendering.OFF -> "no 3D rendering"
    !playable -> "no core"
    biosMissing -> "no BIOS"
    needsGpu && rendering == Rendering.SOFTWARE -> "software rendering"
    else -> null
}

/** Systems grouped by maker, in the server's order (a maker first appears where its first system does). */
fun groupByMaker(systems: List<SystemSummary>): List<Pair<String, List<SystemSummary>>> =
    systems.groupBy { it.manufacturer }.toList()

/** One STUN/TURN server, from `clientConfig.iceServers` (JSON `RTCIceServer`s). */
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

/** `clientConfig.iceServers` (the JSON scalar, as Apollo hands it over: lists and maps). */
fun iceServers(json: Any?): List<IceServer> = (json as? List<*>).orEmpty().mapNotNull { entry ->
    val s = entry as? Map<*, *> ?: return@mapNotNull null
    val urls = when (val u = s["urls"]) {
        is String -> listOf(u)
        is List<*> -> u.filterIsInstance<String>()
        else -> emptyList()
    }
    if (urls.isEmpty()) null else IceServer(urls, s["username"] as? String, s["credential"] as? String)
}

// --- Controllers ------------------------------------------------------------------------------

/** `Binding`: one input (`south`, `l1` … or a keyboard's `KeyboardEvent.code`) driving one button. */
data class Binding(val input: String, val button: Button)

/**
 * `ControllerMapping`: the bindings in force for one controller model (docs/CONTROLLERS.md):
 * the user's own, else the server's default for that model.
 */
data class ControllerMapping(
    val controller: String,
    val name: String = "",
    val bindings: List<Binding> = emptyList(),
    /** `MappingSource`: `USER` (theirs), `ADMIN` or `ENVIRONMENT` (the server's default). */
    val source: String = MappingSource.USER,
    /** The system it is for, or null for every system. */
    val system: String? = null,
)

/** `Shape`: a rounded rectangle of a diagram's body; `tone` 1 is a darker recess. */
data class Shape(val x: Double, val y: Double, val w: Double, val h: Double, val r: Double, val tone: Int = 0)

/**
 * `PadKey`: one button on a diagram, labelled. [key] is a RetroPad button (`B`, `START` …) on a
 * system's pad, an input name (`south`, `l1` …) on a controller's.
 */
data class PadKey(val key: String, val label: String, val x: Double, val y: Double, val w: Double, val h: Double, val r: Double)

/** `PadLayout`: a pad drawn on a [width]×[height] canvas (docs/CONTROLLERS.md, "Systems and diagrams"). */
data class PadLayout(
    /** A system id, or a controller family (`xbox`, `playstation`, `nintendo`, `generic`). */
    val id: String,
    val name: String,
    val width: Double,
    val height: Double,
    val body: List<Shape> = emptyList(),
    val keys: List<PadKey> = emptyList(),
)

/** `SystemButton`: one of a system's buttons, the RetroPad button driving it and the console's label. */
data class SystemButton(val button: Button, val label: String)

/** `System.controls`: the system's buttons in listing order, and its pad. */
data class SystemControls(val buttons: List<SystemButton> = emptyList(), val layout: PadLayout)

/** GraphQL's `MappingSource`. A model the server has no mapping for uses the built-in defaults. */
object MappingSource {
    const val USER = "USER"
    const val ADMIN = "ADMIN"
    const val ENVIRONMENT = "ENVIRONMENT"
}

/** The schema's `PadButton` is the wire protocol's [Button], name for name. */
fun PadButton.toButton(): Button? = Button.entries.firstOrNull { it.name == rawValue }

fun Button.toPadButton(): PadButton = PadButton.safeValueOf(name)

fun MappingFields.toMapping(): ControllerMapping = ControllerMapping(
    controller = controller,
    name = name,
    bindings = bindings.mapNotNull { b -> b.button.toButton()?.let { Binding(b.input, it) } },
    source = source.rawValue,
    system = system,
)

fun Binding.toInput(): BindingInput = BindingInput(input = input, button = button.toPadButton())

fun LayoutFields.toLayout(): PadLayout = PadLayout(
    id = id,
    name = name,
    width = width,
    height = height,
    body = body.map { Shape(it.x, it.y, it.w, it.h, it.r, it.tone) },
    keys = keys.map { PadKey(it.key, it.label, it.x, it.y, it.w, it.h, it.r) },
)

/** A system's labels and pad (null for an unknown system), and the controllers' pads. */
fun ControlsQuery.Data.toControls(): Pair<SystemControls?, List<PadLayout>> {
    val system = system?.controls?.let { c ->
        SystemControls(
            buttons = c.buttons.mapNotNull { b -> b.button.toButton()?.let { SystemButton(it, b.label) } },
            layout = c.layout.layoutFields.toLayout(),
        )
    }
    return system to controllerLayouts.map { it.layoutFields.toLayout() }
}

/** Parses an API colour (`#rrggbb`) to ARGB. */
fun parseColor(hex: String): Long {
    val rgb = hex.removePrefix("#").toLongOrNull(16) ?: 0x4169e1
    return 0xff00_0000L or rgb
}

/** The query parameters of a signed file URL (the server's `assets.rs`, Sovereign's names). */
private val SIGNATURE_PARAMS = setOf("Sov-UserId", "Sov-Expiry", "Sov-Signature")

/**
 * A file URL without its signature: the same picture's key in the image caches, whichever
 * signature it was fetched with (the server signs URLs afresh each hour). Other parameters, such
 * as a save screenshot's `?t=` (which changes with each save), are kept.
 */
fun assetCacheKey(url: String): String {
    val at = url.indexOf('?')
    if (at < 0) return url
    val kept = url.substring(at + 1).split('&').filter { it.substringBefore('=') !in SIGNATURE_PARAMS }
    return if (kept.isEmpty()) url.substring(0, at) else url.substring(0, at) + "?" + kept.joinToString("&")
}
