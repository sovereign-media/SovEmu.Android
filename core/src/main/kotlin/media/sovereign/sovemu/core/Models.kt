package media.sovereign.sovemu.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON shapes of the SovEmu GraphQL API (`SovEmu.Server/schema.graphql`). */
val ApiJson = Json { ignoreUnknownKeys = true }

@Serializable
data class User(val id: Long, val username: String, val isAdmin: Boolean)

/** `Query.viewer`: who is signed in, and whether first-run setup is needed. */
@Serializable
data class Viewer(val setupRequired: Boolean, val user: User? = null)

@Serializable
data class SystemSummary(
    val id: String,
    val name: String,
    val short: String,
    val manufacturer: String,
    val year: Int,
    val color: String,
    val gameCount: Int,
    val playable: Boolean,
    /** A 3D system: renders with OpenGL and reads analog sticks. */
    val needsGpu: Boolean = false,
    /** For a 3D system: `GPU`, `SOFTWARE` (slow) or `OFF`. */
    val rendering: String? = null,
)

@Serializable
data class Game(
    val id: Long,
    val system: String,
    val title: String,
    /** No-Intro tags from the file name: "USA", "Rev 1" … */
    val tags: List<String> = emptyList(),
    /** Bytes (a GraphQL `Float`). */
    val size: Double = 0.0,
    val lastPlayedAt: String? = null,
    val playCount: Int = 0,
)

@Serializable
data class SessionInfo(
    val id: String,
    val gameId: Long,
    val system: String,
    val title: String,
    val core: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    /** A client is streaming it. */
    val connected: Boolean = false,
)

@Serializable
data class IceServer(val urls: kotlinx.serialization.json.JsonElement, val username: String? = null, val credential: String? = null)

@Serializable
data class ClientConfig(val iceServers: List<IceServer> = emptyList())

@Serializable
data class SaveSlot(
    /** 0 is the auto save; 1–9 are the player's. */
    val slot: Int,
    /** Unix seconds (a GraphQL `Float`). */
    val savedAt: Double,
    /** Relative to the server's origin; changes with each save. */
    val screenshotUrl: String? = null,
)

/** `System.games`, as `Query.system` returns it. */
@Serializable
data class SystemGames(val games: List<Game> = emptyList())

/** `Game.saves`, as `Query.game` returns it. */
@Serializable
data class GameSaves(val saves: List<SaveSlot> = emptyList())

/** `Binding`: one input (`south`, `l1` … or a keyboard's `KeyboardEvent.code`) driving one button. */
@Serializable
data class Binding(val input: String, val button: Button)

/**
 * `ControllerMapping`: the bindings in force for one controller model (docs/CONTROLLERS.md):
 * the user's own, else the server's default for that model.
 */
@Serializable
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
@Serializable
data class Shape(val x: Double, val y: Double, val w: Double, val h: Double, val r: Double, val tone: Int = 0)

/**
 * `PadKey`: one button on a diagram, labelled. [key] is a RetroPad button (`B`, `START` …) on a
 * system's pad, an input name (`south`, `l1` …) on a controller's.
 */
@Serializable
data class PadKey(val key: String, val label: String, val x: Double, val y: Double, val w: Double, val h: Double, val r: Double)

/** `PadLayout`: a pad drawn on a [width]×[height] canvas (docs/CONTROLLERS.md, "Systems and diagrams"). */
@Serializable
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
@Serializable
data class SystemButton(val button: Button, val label: String)

/** `System.controls`: the system's buttons in listing order, and its pad. */
@Serializable
data class SystemControls(val buttons: List<SystemButton> = emptyList(), val layout: PadLayout)

/** `Query.system`, asked only for its controls. */
@Serializable
data class SystemWithControls(val controls: SystemControls)

/** GraphQL's `MappingSource`. A model the server has no mapping for uses the built-in defaults. */
object MappingSource {
    const val USER = "USER"
    const val ADMIN = "ADMIN"
    const val ENVIRONMENT = "ENVIRONMENT"
}

/** Parses an API colour (`#rrggbb`) to ARGB. */
fun parseColor(hex: String): Long {
    val rgb = hex.removePrefix("#").toLongOrNull(16) ?: 0x4169e1
    return 0xff00_0000L or rgb
}
