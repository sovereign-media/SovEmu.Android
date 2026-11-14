package media.sovereign.sovemu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import media.sovereign.sovemu.core.ApiJson
import media.sovereign.sovemu.core.AuthStatus
import media.sovereign.sovemu.core.ClientConfig
import media.sovereign.sovemu.core.Credentials
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SessionDescription
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.StartSession
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.User
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : Exception(message)

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

/** Calls to the SovEmu API (`SovEmu.Server`). */
class SovEmuApi(private val settings: Settings) {
    private val client = OkHttpClient.Builder()
        .cookieJar(SessionCookieJar(settings))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String): HttpUrl =
        "${settings.serverUrl}/api$path".toHttpUrlOrNull() ?: throw ApiException(0, "Invalid server address")

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(path))
            .method(method, body?.toRequestBody(jsonType) ?: if (method == "POST") "".toRequestBody(jsonType) else null)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching {
                    ApiJson.parseToJsonElement(text) as JsonObject
                }.getOrNull()?.get("error")?.toString()?.trim('"') ?: response.message
                throw ApiException(response.code, message)
            }
            text
        }
    }

    private suspend inline fun <reified T> get(path: String): T = ApiJson.decodeFromString(call("GET", path))
    private suspend inline fun <reified T, reified B> post(path: String, body: B): T =
        ApiJson.decodeFromString(call("POST", path, ApiJson.encodeToString(body)))

    suspend fun status(): AuthStatus = get("/auth/status")
    suspend fun login(username: String, password: String): User = post("/auth/login", Credentials(username, password))
    suspend fun logout() {
        call("POST", "/auth/logout")
        settings.sessionCookie = null
    }
    suspend fun config(): ClientConfig = get("/config")
    suspend fun systems(): List<SystemSummary> = get("/systems")
    suspend fun games(system: String): List<Game> = get("/systems/$system/games")
    suspend fun startSession(gameId: Long): SessionInfo = post("/sessions", StartSession(gameId))
    suspend fun offer(sessionId: String, offer: SessionDescription): SessionDescription = post("/sessions/$sessionId/offer", offer)
    suspend fun stopSession(sessionId: String) {
        call("DELETE", "/sessions/$sessionId")
    }
    suspend fun states(gameId: Long): List<SaveSlot> = get("/games/$gameId/states")
    suspend fun saveState(sessionId: String, slot: Int) {
        call("POST", "/sessions/$sessionId/states/$slot")
    }
    suspend fun loadState(sessionId: String, slot: Int) {
        call("POST", "/sessions/$sessionId/states/$slot/load")
    }
}
