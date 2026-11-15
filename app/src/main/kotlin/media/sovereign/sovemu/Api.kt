package media.sovereign.sovemu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import media.sovereign.sovemu.core.ApiException
import media.sovereign.sovemu.core.ClientConfig
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameSaves
import media.sovereign.sovemu.core.GraphQL
import media.sovereign.sovemu.core.Queries
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.SystemGames
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.User
import media.sovereign.sovemu.core.Viewer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** The server address and the sign-in cookie, kept across launches. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("sovemu", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(value) = prefs.edit().putString("server", value.trim().trimEnd('/')).apply()

    var sessionCookie: String?
        get() = prefs.getString("cookie", null)
        set(value) = prefs.edit().putString("cookie", value).apply()
}

/** Keeps the server's `sovemu_session` cookie in [Settings]. */
private class SessionCookieJar(private val settings: Settings) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.firstOrNull { it.name == "sovemu_session" }?.let {
            settings.sessionCookie = if (it.value.isEmpty()) null else it.value
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val value = settings.sessionCookie ?: return emptyList()
        return listOf(Cookie.Builder().name("sovemu_session").value(value).hostOnlyDomain(url.host).path("/").build())
    }
}

/** Calls to the SovEmu GraphQL API (`POST /graphql` on `SovEmu.Server`). */
class SovEmuApi(private val settings: Settings) {
    private val client = OkHttpClient.Builder()
        .cookieJar(SessionCookieJar(settings))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private fun url(): HttpUrl =
        "${settings.serverUrl}/graphql".toHttpUrlOrNull() ?: throw ApiException("BAD_REQUEST", "Invalid server address")

    /** Sends one operation; returns the raw response body and its HTTP status. */
    private suspend fun post(query: String, variables: JsonObject): Pair<String, Int> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url())
            .post(GraphQL.body(query, variables).toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response -> response.body.string() to response.code }
    }

    /** Runs [query] and decodes `data.<field>`; GraphQL errors become [ApiException]s. */
    private suspend inline fun <reified T> request(
        query: String,
        field: String,
        variables: JsonObject = JsonObject(emptyMap()),
    ): T {
        val (text, status) = post(query, variables)
        return GraphQL.decode(text, field, status)
    }

    suspend fun status(): Viewer = request(Queries.VIEWER, "viewer")
    suspend fun login(username: String, password: String): User =
        request(Queries.LOGIN, "login", buildJsonObject { put("username", username); put("password", password) })
    suspend fun logout() {
        request<Boolean>(Queries.LOGOUT, "logout")
        settings.sessionCookie = null
    }
    suspend fun config(): ClientConfig = request(Queries.CLIENT_CONFIG, "clientConfig")
    suspend fun systems(): List<SystemSummary> = request(Queries.SYSTEMS, "systems")
    suspend fun games(system: String): List<Game> =
        request<SystemGames?>(Queries.SYSTEM_GAMES, "system", buildJsonObject { put("id", system) })?.games
            ?: throw ApiException("NOT_FOUND", "No such system")
    suspend fun startSession(gameId: Long): SessionInfo =
        request(Queries.START_SESSION, "startSession", buildJsonObject { put("gameId", gameId) })
    /** Sends the complete offer SDP; returns the answer SDP. */
    suspend fun offer(sessionId: String, sdp: String): String =
        request(Queries.SESSION_OFFER, "sessionOffer", buildJsonObject { put("id", sessionId); put("sdp", sdp) })
    suspend fun stopSession(sessionId: String) {
        request<Boolean>(Queries.STOP_SESSION, "stopSession", buildJsonObject { put("id", sessionId) })
    }
    suspend fun states(gameId: Long): List<SaveSlot> =
        request<GameSaves>(Queries.SAVES, "game", buildJsonObject { put("id", gameId) }).saves
    suspend fun saveState(sessionId: String, slot: Int) {
        request<Boolean>(Queries.SAVE_STATE, "saveState", slotVariables(sessionId, slot))
    }
    suspend fun loadState(sessionId: String, slot: Int) {
        request<Boolean>(Queries.LOAD_STATE, "loadState", slotVariables(sessionId, slot))
    }

    private fun slotVariables(sessionId: String, slot: Int) = buildJsonObject {
        put("sessionId", sessionId)
        put("slot", slot)
    }
}
