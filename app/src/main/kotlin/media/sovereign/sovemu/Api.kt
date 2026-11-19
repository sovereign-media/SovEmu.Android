package media.sovereign.sovemu

import android.content.Context
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.network.okHttpClient
import java.util.concurrent.TimeUnit
import media.sovereign.sovemu.core.ApiException
import media.sovereign.sovemu.core.Binding
import media.sovereign.sovemu.core.ControllerMapping
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.HomeFeed
import media.sovereign.sovemu.core.IceServer
import media.sovereign.sovemu.core.PadLayout
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.SystemControls
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.User
import media.sovereign.sovemu.core.Viewer
import media.sovereign.sovemu.core.graphql.ClientConfigQuery
import media.sovereign.sovemu.core.graphql.ControllerMappingsQuery
import media.sovereign.sovemu.core.graphql.ControlsQuery
import media.sovereign.sovemu.core.graphql.HomeQuery
import media.sovereign.sovemu.core.graphql.LoadStateMutation
import media.sovereign.sovemu.core.graphql.LoginMutation
import media.sovereign.sovemu.core.graphql.LogoutMutation
import media.sovereign.sovemu.core.graphql.ResetControllerMappingMutation
import media.sovereign.sovemu.core.graphql.SaveControllerMappingMutation
import media.sovereign.sovemu.core.graphql.SaveStateMutation
import media.sovereign.sovemu.core.graphql.SavesQuery
import media.sovereign.sovemu.core.graphql.SessionOfferMutation
import media.sovereign.sovemu.core.graphql.StartSessionMutation
import media.sovereign.sovemu.core.graphql.StopSessionMutation
import media.sovereign.sovemu.core.graphql.SystemGamesQuery
import media.sovereign.sovemu.core.graphql.SystemsQuery
import media.sovereign.sovemu.core.graphql.ViewerQuery
import media.sovereign.sovemu.core.iceServers
import media.sovereign.sovemu.core.orThrow
import media.sovereign.sovemu.core.toControls
import media.sovereign.sovemu.core.toInput
import media.sovereign.sovemu.core.toMapping
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** The server address and the sign-in cookie, kept across launches. */
class Settings(context: Context) {
    internal val prefs = context.getSharedPreferences("sovemu", Context.MODE_PRIVATE)

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

/**
 * Calls to the SovEmu GraphQL API (`POST /graphql` on `SovEmu.Server`), through Apollo: the
 * operations and their models are generated in `core` from the server's schema. Requests run
 * and parse off the main thread (Apollo's own dispatcher).
 */
class SovEmuApi(private val settings: Settings) {
    /** Shared with Apollo and the image loader, so every request carries the sign-in cookie. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(SessionCookieJar(settings))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** One Apollo client per server address (the address changes at sign-in). */
    private var apollo: Pair<String, ApolloClient>? = null

    private fun apollo(): ApolloClient {
        val url = "${settings.serverUrl}/graphql"
        if (url.toHttpUrlOrNull() == null) throw ApiException("BAD_REQUEST", "Invalid server address")
        apollo?.let { (u, c) -> if (u == url) return c }
        return ApolloClient.Builder().serverUrl(url).okHttpClient(client).build().also { apollo = url to it }
    }

    private suspend fun <D : Query.Data> query(q: Query<D>): D = apollo().query(q).execute().orThrow()
    private suspend fun <D : Mutation.Data> mutate(m: Mutation<D>): D = apollo().mutation(m).execute().orThrow()

    /** An absolute URL for a path the API hands out (`/media/games/7/thumb`). */
    fun media(path: String?): String? = path?.let { if (it.startsWith("http")) it else settings.serverUrl + it }

    suspend fun status(): Viewer = query(ViewerQuery()).viewer.let { Viewer(it.setupRequired, it.user?.userFields) }
    suspend fun login(username: String, password: String): User = mutate(LoginMutation(username, password)).login.userFields
    suspend fun logout() {
        mutate(LogoutMutation())
        settings.sessionCookie = null
    }
    suspend fun iceServers(): List<IceServer> = iceServers(query(ClientConfigQuery()).clientConfig.iceServers)
    suspend fun systems(): List<SystemSummary> = query(SystemsQuery()).systems.map { it.systemFields }
    suspend fun home(): HomeFeed = query(HomeQuery()).home.let { h ->
        HomeFeed(h.featured.map { it.gameFields }, h.recent.map { it.gameFields }, h.added.map { it.gameFields })
    }
    suspend fun games(system: String): List<Game> =
        query(SystemGamesQuery(system)).system?.games?.map { it.gameFields } ?: throw ApiException("NOT_FOUND", "No such system")
    suspend fun startSession(gameId: Int): SessionInfo = mutate(StartSessionMutation(gameId)).startSession.sessionFields
    /** Sends the complete offer SDP; returns the answer SDP. */
    suspend fun offer(sessionId: String, sdp: String): String = mutate(SessionOfferMutation(sessionId, sdp)).sessionOffer
    suspend fun stopSession(sessionId: String) {
        mutate(StopSessionMutation(sessionId))
    }
    suspend fun states(gameId: Int): List<SaveSlot> = query(SavesQuery(gameId)).game.saves.map { it.saveFields }
    suspend fun saveState(sessionId: String, slot: Int) {
        mutate(SaveStateMutation(sessionId, slot))
    }
    suspend fun loadState(sessionId: String, slot: Int) {
        mutate(LoadStateMutation(sessionId, slot))
    }

    /**
     * The mappings in force for this user on [system] (null: the every-system ones), one per
     * controller model; a model missing from the list uses the built-in defaults.
     */
    suspend fun controllerMappings(system: String?): List<ControllerMapping> =
        query(ControllerMappingsQuery(Optional.present(system))).controllerMappings.map { it.mappingFields.toMapping() }
    /** Saves this user's mapping for a model on [system], or on every system when it is null. The server allows names up to 128 characters. */
    suspend fun saveControllerMapping(controller: String, system: String?, name: String, bindings: List<Binding>): ControllerMapping =
        mutate(SaveControllerMappingMutation(controller, Optional.present(system), name.take(128), bindings.map { it.toInput() }))
            .saveControllerMapping.mappingFields.toMapping()
    /** Removes this user's mapping for a model on [system] (or every system); returns whether there was one. */
    suspend fun resetControllerMapping(controller: String, system: String?): Boolean =
        mutate(ResetControllerMappingMutation(controller, Optional.present(system))).resetControllerMapping
    /** A system's button labels and pad (null for an unknown system), and the controllers' pads. */
    suspend fun controls(system: String): Pair<SystemControls?, List<PadLayout>> = query(ControlsQuery(system)).toControls()
}
