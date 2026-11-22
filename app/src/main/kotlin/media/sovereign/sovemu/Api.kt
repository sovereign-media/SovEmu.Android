package media.sovereign.sovemu

import android.content.Context
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.network.okHttpClient
import java.util.concurrent.TimeUnit
import media.sovereign.sovemu.core.ApiException
import media.sovereign.sovemu.core.Auth
import media.sovereign.sovemu.core.BearerInterceptor
import media.sovereign.sovemu.core.Binding
import media.sovereign.sovemu.core.Cheat
import media.sovereign.sovemu.core.CollectionChoice
import media.sovereign.sovemu.core.HomeCollection
import media.sovereign.sovemu.core.ControllerMapping
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameDecoration
import media.sovereign.sovemu.core.GamePatch
import media.sovereign.sovemu.core.HomeFeed
import media.sovereign.sovemu.core.IceServer
import media.sovereign.sovemu.core.PadLayout
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.SignInConfig
import media.sovereign.sovemu.core.bootstrap
import media.sovereign.sovemu.core.SystemControls
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.TokenClient
import media.sovereign.sovemu.core.TokenStore
import media.sovereign.sovemu.core.Viewer
import media.sovereign.sovemu.core.graphql.AddCheatMutation
import media.sovereign.sovemu.core.graphql.ReportClientEventsMutation
import media.sovereign.sovemu.core.graphql.type.ClientEventInput
import media.sovereign.sovemu.core.graphql.type.ClientEventKind
import media.sovereign.sovemu.core.graphql.CheatsQuery
import media.sovereign.sovemu.core.graphql.CollectionChoicesQuery
import media.sovereign.sovemu.core.graphql.CreateCollectionMutation
import media.sovereign.sovemu.core.graphql.ClientConfigQuery
import media.sovereign.sovemu.core.graphql.DeleteCheatMutation
import media.sovereign.sovemu.core.graphql.SetCheatEnabledMutation
import media.sovereign.sovemu.core.graphql.ControllerMappingsQuery
import media.sovereign.sovemu.core.graphql.ControlsQuery
import media.sovereign.sovemu.core.graphql.GameDecorationQuery
import media.sovereign.sovemu.core.graphql.HomeQuery
import media.sovereign.sovemu.core.graphql.LoadStateMutation
import media.sovereign.sovemu.core.graphql.ResetControllerMappingMutation
import media.sovereign.sovemu.core.graphql.SaveControllerMappingMutation
import media.sovereign.sovemu.core.graphql.PatchesQuery
import media.sovereign.sovemu.core.graphql.SaveStateMutation
import media.sovereign.sovemu.core.graphql.SearchQuery
import media.sovereign.sovemu.core.graphql.SavesQuery
import media.sovereign.sovemu.core.graphql.SessionOfferMutation
import media.sovereign.sovemu.core.graphql.SetDiscMutation
import media.sovereign.sovemu.core.graphql.SetFavouriteMutation
import media.sovereign.sovemu.core.graphql.SetInCollectionMutation
import media.sovereign.sovemu.core.graphql.SetPatchMutation
import media.sovereign.sovemu.core.graphql.StartSessionMutation
import media.sovereign.sovemu.core.graphql.StartProgressQuery
import media.sovereign.sovemu.core.StartStatus
import media.sovereign.sovemu.core.toStartStatus
import media.sovereign.sovemu.core.graphql.StopSessionMutation
import media.sovereign.sovemu.core.graphql.SystemGamesQuery
import media.sovereign.sovemu.core.graphql.SystemsQuery
import media.sovereign.sovemu.core.graphql.ViewerQuery
import media.sovereign.sovemu.core.iceServers
import media.sovereign.sovemu.core.orThrow
import media.sovereign.sovemu.core.toControls
import media.sovereign.sovemu.core.toInput
import media.sovereign.sovemu.core.toMapping
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * The server address, how to sign in there (its `bootstrapConfig`) and the sign-in's refresh
 * token, kept across launches. The token is in the app's private preferences (excluded from
 * backups by `allowBackup="false"`); the access token lives only in memory.
 */
class Settings(context: Context) : TokenStore {
    internal val prefs = context.getSharedPreferences("sovemu", Context.MODE_PRIVATE)

    init {
        // Before OAuth the app signed in with a session cookie, which the server no longer
        // accepts: dropping it shows the sign-in screen once after the upgrade.
        if (prefs.contains("cookie")) prefs.edit().remove("cookie").apply()
        // The "Low-latency decoding" switch is gone: the decoder gets low latency wherever it
        // supports it.
        if (prefs.contains("low_latency_decode")) prefs.edit().remove("low_latency_decode").apply()
    }

    var serverUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(value) = prefs.edit().putString("server", value.trim().trimEnd('/')).apply()

    override var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    /** The sign-in details last fetched from [serverUrl]; null for another address, or none yet. */
    var signInConfig: SignInConfig?
        get() {
            if (prefs.getString("signin.server", null) != serverUrl) return null
            return SignInConfig(
                address = prefs.getString("signin.address", null) ?: return null,
                signInAvailable = prefs.getBoolean("signin.available", false),
                clientId = prefs.getString("signin.client", null),
                tokenEndpoint = prefs.getString("signin.token", null),
                revocationEndpoint = prefs.getString("signin.revoke", null),
                passwordEndpoint = prefs.getString("signin.password", null),
            )
        }
        set(value) {
            prefs.edit()
                .putString("signin.server", value?.let { serverUrl })
                .putString("signin.address", value?.address)
                .putBoolean("signin.available", value?.signInAvailable == true)
                .putString("signin.client", value?.clientId)
                .putString("signin.token", value?.tokenEndpoint)
                .putString("signin.revoke", value?.revocationEndpoint)
                .putString("signin.password", value?.passwordEndpoint)
                .apply()
        }
}

/**
 * Calls to the SovEmu GraphQL API (`POST /graphql` on `SovEmu.Server`), through Apollo: the
 * operations and their models are generated in `core` from the server's schema. Requests run
 * and parse off the main thread (Apollo's own dispatcher).
 */
class SovEmuApi(private val settings: Settings) {
    /** The sign-in's token client: plain HTTP, without the bearer interceptor. */
    private val plain: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The sign-in: OAuth tokens from the endpoints the server's `bootstrapConfig` names. */
    val auth = Auth(TokenClient(plain, { settings.serverUrl }, { settings.signInConfig }), settings)

    /**
     * Shared with Apollo, the image loader and the video players: GraphQL requests carry the
     * access token; `/media/…` URLs are signed and need nothing.
     */
    val client: OkHttpClient = plain.newBuilder().addInterceptor(BearerInterceptor(auth)).build()

    /** One Apollo client per server address (the address changes at sign-in). */
    private var apollo: Pair<String, ApolloClient>? = null

    private fun apollo(): ApolloClient {
        val url = graphqlUrl()
        apollo?.let { (u, c) -> if (u == url) return c }
        return ApolloClient.Builder().serverUrl(url).okHttpClient(client).build().also { apollo = url to it }
    }

    private fun graphqlUrl(): String {
        val url = "${settings.serverUrl}/graphql"
        if (url.toHttpUrlOrNull() == null) throw ApiException("BAD_REQUEST", "Invalid server address")
        return url
    }

    /**
     * Asks the server how to sign in (`bootstrapConfig`), which also tells whether a SovEmu server
     * is at the address, and keeps the answer with it. Signed out: no bearer, no refresh.
     */
    suspend fun bootstrap(): SignInConfig {
        val url = graphqlUrl()
        val anonymous = signedOut?.takeIf { it.first == url }?.second
            ?: ApolloClient.Builder().serverUrl(url).okHttpClient(plain).build().also { signedOut = url to it }
        return bootstrap(anonymous, settings.serverUrl).also { settings.signInConfig = it }
    }

    /** [bootstrap]'s Apollo client, per address: the plain HTTP client, without the bearer. */
    private var signedOut: Pair<String, ApolloClient>? = null

    // An access token the server rejects (revoked, or the clocks disagree) is refreshed and the
    // request sent once more.
    private suspend fun <D : Query.Data> query(q: Query<D>): D =
        auth.retryingUnauthenticated { apollo().query(q).execute().orThrow() }
    private suspend fun <D : Mutation.Data> mutate(m: Mutation<D>): D =
        auth.retryingUnauthenticated { apollo().mutation(m).execute().orThrow() }

    /** An absolute URL for a path the API hands out (`/media/games/7/thumb`). */
    fun media(path: String?): String? = path?.let { if (it.startsWith("http")) it else settings.serverUrl + it }

    suspend fun status(): Viewer = query(ViewerQuery()).viewer.let { Viewer(it.setupRequired, it.user?.userFields) }
    /** Signs in with a username and password (after [bootstrap]); a refusal is an `OAuthException` with the server's reason. */
    suspend fun signIn(username: String, password: String) = auth.signIn(username, password)
    /** Revokes the sign-in on the server, and forgets it here even when that fails. */
    suspend fun signOut() = auth.signOut()
    suspend fun iceServers(): List<IceServer> = iceServers(query(ClientConfigQuery()).clientConfig.iceServers)
    suspend fun systems(): List<SystemSummary> = query(SystemsQuery()).systems.map { it.systemFields }
    suspend fun home(): HomeFeed = query(HomeQuery()).let { d ->
        val h = d.home
        HomeFeed(
            h.featured.map { it.gameFields }, h.recent.map { it.gameFields }, h.added.map { it.gameFields },
            h.favourites.map { it.gameFields },
            d.collections.map { c -> HomeCollection(c.id, c.name, c.games.map { it.gameFields }) },
        )
    }
    /** Games from every system matching every word of [text], best first (at most 48). */
    suspend fun search(text: String): List<Game> = query(SearchQuery(text)).search.map { it.gameFields }
    /** The artwork to frame a game's picture with, if its system has any. */
    suspend fun decoration(gameId: Int): GameDecoration? =
        query(GameDecorationQuery(gameId)).game.decoration?.let {
            GameDecoration(it.url, it.width, it.height, it.top, it.left, it.bottom, it.right)
        }
    /** A game's soft patches, the default first. */
    suspend fun patches(gameId: Int): List<GamePatch> =
        query(PatchesQuery(gameId)).game.patches.map { GamePatch(it.id, it.name, it.isDefault) }
    /** Plays the game with [patchId] from now on, or the original (null). */
    suspend fun setPatch(gameId: Int, patchId: Int?) {
        mutate(SetPatchMutation(gameId, Optional.present(patchId)))
    }
    /** Adds a game to this user's favourites, or takes it out. */
    /**
     * Sends what went wrong here to the server's log; only while signed in, and never with a
     * retry or a sign-in of its own (a report must not get in the player's way).
     */
    suspend fun reportClientEvents(client: String, events: List<media.sovereign.sovemu.core.ClientEvent>) {
        if (!auth.signedIn.value) return
        val inputs = events.map {
            ClientEventInput(
                kind = ClientEventKind.safeValueOf(it.kind),
                message = it.message,
                sessionId = Optional.presentIfNotNull(it.sessionId),
                at = Optional.present(it.at),
            )
        }
        apollo().mutation(ReportClientEventsMutation(client, inputs)).execute()
    }

    suspend fun setFavourite(gameId: Int, favourite: Boolean) {
        mutate(SetFavouriteMutation(gameId, favourite))
    }
    /** This user's collections, each marked with whether [gameId] is in it. */
    suspend fun collectionChoices(gameId: Int): List<CollectionChoice> =
        query(CollectionChoicesQuery(gameId)).let { d ->
            val member = d.game.collectionIds.toSet()
            d.collections.map { CollectionChoice(it.id, it.name, it.id in member) }
        }
    /** Makes a collection (names are 1 to 64 characters, unique per user); returns its id. */
    suspend fun createCollection(name: String): Int = mutate(CreateCollectionMutation(name.trim().take(64))).createCollection.id
    suspend fun setInCollection(collectionId: Int, gameId: Int, member: Boolean) {
        mutate(SetInCollectionMutation(collectionId, gameId, member))
    }
    suspend fun games(system: String): List<Game> =
        query(SystemGamesQuery(system)).system?.games?.map { it.gameFields } ?: throw ApiException("NOT_FOUND", "No such system")
    /** Starts [gameId]; [startId] names the start, for [startProgress] meanwhile. */
    suspend fun startSession(gameId: Int, startId: String): SessionInfo =
        mutate(StartSessionMutation(gameId, Optional.present(startId))).startSession.sessionFields
    /** How far the start [startId] has got. */
    suspend fun startProgress(startId: String): StartStatus = query(StartProgressQuery(startId)).startProgress.toStartStatus()
    /** Sends the complete offer SDP, naming this app ([client]); returns the answer SDP. */
    suspend fun offer(sessionId: String, sdp: String, client: String): String =
        mutate(SessionOfferMutation(sessionId, sdp, Optional.present(client))).sessionOffer
    suspend fun stopSession(sessionId: String) {
        mutate(StopSessionMutation(sessionId))
    }
    suspend fun states(gameId: Int): List<SaveSlot> = query(SavesQuery(gameId)).game.saves.map { it.saveFields }
    /** Changes a multi-disc game's disc (from 1). */
    suspend fun setDisc(sessionId: String, disc: Int) {
        mutate(SetDiscMutation(sessionId, disc))
    }
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
    /** This user's cheats for a game. */
    suspend fun cheats(gameId: Int): List<Cheat> = query(CheatsQuery(gameId)).game.cheats.map { it.cheatFields }
    /** Adds a cheat (on); a running session gets it at once. Returns the game's cheats. */
    suspend fun addCheat(gameId: Int, code: String, description: String): List<Cheat> =
        mutate(AddCheatMutation(gameId, code, description)).addCheat.map { it.cheatFields }
    suspend fun setCheatEnabled(gameId: Int, cheatId: Int, enabled: Boolean): List<Cheat> =
        mutate(SetCheatEnabledMutation(gameId, cheatId, enabled)).setCheatEnabled.map { it.cheatFields }
    suspend fun deleteCheat(gameId: Int, cheatId: Int) {
        mutate(DeleteCheatMutation(gameId, cheatId))
    }

    /** A system's button labels and pad (null for an unknown system), and the controllers' pads. */
    suspend fun controls(system: String): Pair<SystemControls?, List<PadLayout>> = query(ControlsQuery(system)).toControls()
}
