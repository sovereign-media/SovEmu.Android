package media.sovereign.sovemu.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON shapes of the SovEmu API (`SovEmu.Server`). */
val ApiJson = Json { ignoreUnknownKeys = true }

@Serializable
data class User(val id: Long, val username: String, @SerialName("is_admin") val isAdmin: Boolean)

@Serializable
data class AuthStatus(@SerialName("setup_required") val setupRequired: Boolean, val user: User? = null)

@Serializable
data class SystemSummary(
    val id: String,
    val name: String,
    val short: String,
    val manufacturer: String,
    val year: Int,
    val color: String,
    @SerialName("game_count") val gameCount: Int,
    val playable: Boolean,
    /** A 3D system: renders with OpenGL and reads analog sticks. */
    @SerialName("needs_gpu") val needsGpu: Boolean = false,
    /** For a 3D system: `gpu`, `software` (slow) or `off`. */
    val rendering: String? = null,
)

@Serializable
data class Game(
    val id: Long,
    val system: String,
    val title: String,
    val tags: String = "",
    val size: Long = 0,
    @SerialName("last_played_at") val lastPlayedAt: String? = null,
    @SerialName("play_count") val playCount: Int? = null,
)

@Serializable
data class SessionInfo(
    val id: String,
    @SerialName("game_id") val gameId: Long,
    val system: String,
    val title: String,
    val core: String,
    val width: Int,
    val height: Int,
    val fps: Double,
)

@Serializable
data class SessionDescription(val type: String, val sdp: String)

@Serializable
data class IceServer(val urls: kotlinx.serialization.json.JsonElement, val username: String? = null, val credential: String? = null)

@Serializable
data class ClientConfig(@SerialName("ice_servers") val iceServers: List<IceServer> = emptyList())

@Serializable
data class SaveSlot(val slot: Int, @SerialName("saved_at") val savedAt: Long, val screenshot: Boolean)

@Serializable
data class Credentials(val username: String, val password: String)

@Serializable
data class StartSession(@SerialName("game_id") val gameId: Long)

/** Parses an API colour (`#rrggbb`) to ARGB. */
fun parseColor(hex: String): Long {
    val rgb = hex.removePrefix("#").toLongOrNull(16) ?: 0x4169e1
    return 0xff00_0000L or rgb
}
