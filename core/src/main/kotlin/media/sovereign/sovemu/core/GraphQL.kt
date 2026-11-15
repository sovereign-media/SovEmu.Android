package media.sovereign.sovemu.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.serializer

/**
 * An API error. [code] is the GraphQL error's `extensions.code` (`UNAUTHENTICATED`,
 * `FORBIDDEN`, `NOT_FOUND`, …), or `HTTP_<status>` when the server answered with no GraphQL
 * response at all.
 */
class ApiException(val code: String, message: String) : Exception(message) {
    /** Not signed in (or the session expired): sign in again. */
    val unauthenticated: Boolean get() = code == "UNAUTHENTICATED"
}

/** Request bodies for, and responses from, `POST /graphql`. */
object GraphQL {
    /** `{"query": …, "variables": {…}}`. */
    fun body(query: String, variables: JsonObject = JsonObject(emptyMap())): String =
        ApiJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("query", JsonPrimitive(query))
                put("variables", variables)
            },
        )

    /**
     * The `data.<field>` of a response, decoded; or the first error as an [ApiException].
     * [httpStatus] is used only when [text] is not a GraphQL response.
     */
    fun <T> decode(text: String, field: String, serializer: KSerializer<T>, httpStatus: Int = 200): T {
        val response = runCatching { ApiJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw ApiException("HTTP_$httpStatus", "Unexpected response from the server (HTTP $httpStatus)")
        (response["errors"] as? JsonArray)?.firstOrNull()?.let { throw error(it) }
        val data = response["data"] as? JsonObject
            ?: throw ApiException("HTTP_$httpStatus", "Unexpected response from the server (HTTP $httpStatus)")
        return ApiJson.decodeFromJsonElement(serializer, data[field] ?: JsonNull)
    }

    inline fun <reified T> decode(text: String, field: String, httpStatus: Int = 200): T =
        decode(text, field, serializer<T>(), httpStatus)

    private fun error(e: JsonElement): ApiException {
        val obj = e as? JsonObject
        val message = (obj?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Request failed"
        val code = ((obj?.get("extensions") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
        return ApiException(code ?: "INTERNAL", message)
    }
}

/** The query documents the app sends. Field names follow `SovEmu.Server/schema.graphql`. */
object Queries {
    private const val GAME = "id system title tags size lastPlayedAt playCount"
    private const val SESSION = "id gameId system title core width height fps connected"
    private const val USER = "id username isAdmin"
    private const val SAVE = "slot savedAt screenshotUrl"
    private const val MAPPING = "controller system name source bindings { input button }"
    private const val LAYOUT = "id name width height body { x y w h r tone } keys { key label x y w h r }"

    const val VIEWER = "query { viewer { setupRequired user { $USER } } }"
    const val LOGIN = "mutation(\$username: String!, \$password: String!) { login(username: \$username, password: \$password) { $USER } }"
    const val LOGOUT = "mutation { logout }"
    const val CLIENT_CONFIG = "query { clientConfig { iceServers } }"
    const val SYSTEMS = "query { systems { id name short manufacturer year color gameCount playable needsGpu rendering } }"
    const val SYSTEM_GAMES = "query(\$id: ID!) { system(id: \$id) { games { $GAME } } }"
    const val SAVES = "query(\$id: Int!) { game(id: \$id) { saves { $SAVE } } }"
    const val START_SESSION = "mutation(\$gameId: Int!) { startSession(gameId: \$gameId) { $SESSION } }"
    const val SESSION_OFFER = "mutation(\$id: ID!, \$sdp: String!) { sessionOffer(id: \$id, sdp: \$sdp) }"
    const val STOP_SESSION = "mutation(\$id: ID!) { stopSession(id: \$id) }"
    const val SAVE_STATE = "mutation(\$sessionId: ID!, \$slot: Int!) { saveState(sessionId: \$sessionId, slot: \$slot) }"
    const val LOAD_STATE = "mutation(\$sessionId: ID!, \$slot: Int!) { loadState(sessionId: \$sessionId, slot: \$slot) }"
    /** The mappings in force on a system (`$system`), or every-system ones when it is null. */
    const val CONTROLLER_MAPPINGS = "query(\$system: ID) { controllerMappings(system: \$system) { $MAPPING } }"
    const val SAVE_CONTROLLER_MAPPING =
        "mutation(\$controller: String!, \$system: ID, \$name: String!, \$bindings: [BindingInput!]!) " +
            "{ saveControllerMapping(controller: \$controller, system: \$system, name: \$name, bindings: \$bindings) { $MAPPING } }"
    const val RESET_CONTROLLER_MAPPING =
        "mutation(\$controller: String!, \$system: ID) { resetControllerMapping(controller: \$controller, system: \$system) }"
    /** A system's labels and pad, and the controllers' pads: two fields, `system` and `controllerLayouts`. */
    const val CONTROLS =
        "query(\$id: ID!) { system(id: \$id) { controls { buttons { button label } layout { $LAYOUT } } } controllerLayouts { $LAYOUT } }"

    private fun system(system: String?) = system?.let(::JsonPrimitive) ?: JsonNull

    fun mappingsVariables(system: String?): JsonObject = buildJsonObject { put("system", system(system)) }

    /**
     * `saveControllerMapping`'s variables: [system] null saves the every-system mapping. The
     * server allows names up to 128 characters.
     */
    fun saveMappingVariables(controller: String, system: String?, name: String, bindings: List<Binding>): JsonObject = buildJsonObject {
        put("controller", JsonPrimitive(controller))
        put("system", system(system))
        put("name", JsonPrimitive(name.take(128)))
        put("bindings", ApiJson.encodeToJsonElement(ListSerializer(Binding.serializer()), bindings))
    }

    fun resetMappingVariables(controller: String, system: String?): JsonObject = buildJsonObject {
        put("controller", JsonPrimitive(controller))
        put("system", system(system))
    }
}
