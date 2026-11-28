package media.sovereign.sovemu

import android.content.Context
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.network.okHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.sovereign.sovemu.core.AccountStore
import media.sovereign.sovemu.core.ApiException
import media.sovereign.sovemu.core.BearerInterceptor
import media.sovereign.sovemu.core.Binding
import media.sovereign.sovemu.core.BrowserRedirect
import media.sovereign.sovemu.core.BrowserSignInRequest
import media.sovereign.sovemu.core.BrowserSignInText
import media.sovereign.sovemu.core.Cheat
import media.sovereign.sovemu.core.CheatService
import media.sovereign.sovemu.core.CollectionChoice
import media.sovereign.sovemu.core.CollectionService
import media.sovereign.sovemu.core.ControllerMapping
import media.sovereign.sovemu.core.CustomHeader
import media.sovereign.sovemu.core.CustomHeaderInterceptor
import media.sovereign.sovemu.core.CustomHeaderRules
import media.sovereign.sovemu.core.DeviceCode
import media.sovereign.sovemu.core.DevicePoll
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameDecoration
import media.sovereign.sovemu.core.GamePatch
import media.sovereign.sovemu.core.HomeCollection
import media.sovereign.sovemu.core.HomeFeed
import media.sovereign.sovemu.core.IceServer
import media.sovereign.sovemu.core.KeyValue
import media.sovereign.sovemu.core.LONG_WAIT_HEADER
import media.sovereign.sovemu.core.LongWaitInterceptor
import media.sovereign.sovemu.core.OAuthException
import media.sovereign.sovemu.core.PadLayout
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.ServerService
import media.sovereign.sovemu.core.Session
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.SignInConfig
import media.sovereign.sovemu.core.SignInService
import media.sovereign.sovemu.core.StartStatus
import media.sovereign.sovemu.core.SystemControls
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.TokenClient
import media.sovereign.sovemu.core.Tokens
import media.sovereign.sovemu.core.Viewer
import media.sovereign.sovemu.core.bootstrap
import media.sovereign.sovemu.core.graphql.AddCheatMutation
import media.sovereign.sovemu.core.graphql.ApproveDeviceCodeMutation
import media.sovereign.sovemu.core.graphql.BackgroundQuery
import media.sovereign.sovemu.core.graphql.CheatsQuery
import media.sovereign.sovemu.core.graphql.ClientConfigQuery
import media.sovereign.sovemu.core.graphql.CollectionChoicesQuery
import media.sovereign.sovemu.core.graphql.ControllerMappingsQuery
import media.sovereign.sovemu.core.graphql.ControlsQuery
import media.sovereign.sovemu.core.graphql.CreateCollectionMutation
import media.sovereign.sovemu.core.graphql.DeleteCheatMutation
import media.sovereign.sovemu.core.graphql.GameDecorationQuery
import media.sovereign.sovemu.core.graphql.HomeQuery
import media.sovereign.sovemu.core.graphql.LoadStateMutation
import media.sovereign.sovemu.core.graphql.NameDeviceMutation
import media.sovereign.sovemu.core.graphql.PatchesQuery
import media.sovereign.sovemu.core.graphql.ReportClientEventsMutation
import media.sovereign.sovemu.core.graphql.ResetControllerMappingMutation
import media.sovereign.sovemu.core.graphql.SaveControllerMappingMutation
import media.sovereign.sovemu.core.graphql.SaveStateMutation
import media.sovereign.sovemu.core.graphql.SavesQuery
import media.sovereign.sovemu.core.graphql.SearchQuery
import media.sovereign.sovemu.core.graphql.SessionOfferMutation
import media.sovereign.sovemu.core.graphql.SetCheatEnabledMutation
import media.sovereign.sovemu.core.graphql.SetDiscMutation
import media.sovereign.sovemu.core.graphql.SetFavouriteMutation
import media.sovereign.sovemu.core.graphql.SetInCollectionMutation
import media.sovereign.sovemu.core.graphql.SetPatchMutation
import media.sovereign.sovemu.core.graphql.StartProgressQuery
import media.sovereign.sovemu.core.graphql.StartSessionMutation
import media.sovereign.sovemu.core.graphql.StopSessionMutation
import media.sovereign.sovemu.core.graphql.SystemGamesQuery
import media.sovereign.sovemu.core.graphql.SystemsQuery
import media.sovereign.sovemu.core.graphql.ViewerQuery
import media.sovereign.sovemu.core.graphql.type.ClientCapabilitiesInput
import media.sovereign.sovemu.core.graphql.type.ClientEventInput
import media.sovereign.sovemu.core.graphql.type.ClientEventKind
import media.sovereign.sovemu.core.iceServers
import media.sovereign.sovemu.core.message
import media.sovereign.sovemu.core.orThrow
import media.sovereign.sovemu.core.toControls
import media.sovereign.sovemu.core.toInput
import media.sovereign.sovemu.core.toMapping
import media.sovereign.sovemu.core.toStartStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * The server address, how to sign in there (its `bootstrapConfig`) and the accounts signed in
 * ([AccountStore], each refresh token sealed with a key in the Android Keystore,
 * [KeystoreSealer]), kept in the app's private preferences across launches. Nothing the app
 * stores leaves the device (`allowBackup="false"` and the data-extraction rules); the access
 * token lives only in memory.
 */
@Singleton
class Settings @Inject constructor(@ApplicationContext context: Context) {
    internal val prefs = context.getSharedPreferences("sovemu", Context.MODE_PRIVATE)

    /** The preferences as [AccountStore] reads and writes them. */
    val keyValue: KeyValue = object : KeyValue {
        override fun get(key: String) = prefs.getString(key, null)
        override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
        override fun remove(key: String) = prefs.edit().remove(key).apply()
    }

    private val _server = MutableStateFlow(prefs.getString("server", "") ?: "")

    /** The server the app talks to, as it changes ([serverUrl]). */
    val server: StateFlow<String> = _server.asStateFlow()

    var serverUrl: String
        get() = _server.value
        set(value) {
            val address = value.trim().trimEnd('/')
            prefs.edit().putString("server", address).apply()
            _server.value = address
        }

    /**
     * *Secure connections only*: a bare address is asked over https alone, and `http://` is
     * refused ([media.sovereign.sovemu.core.ServerAddress]). On by default, as Sovereign's.
     */
    var secureOnly: Boolean
        get() = prefs.getBoolean("secure_only", true)
        set(value) = prefs.edit().putBoolean("secure_only", value).apply()

    /**
     * The proxy headers sent with every request to the server ([CustomHeaderRules]). Kept with
     * the rest, never backed up or logged: a value is usually a credential.
     */
    var customHeaders: List<CustomHeader>
        get() = _headers.value
        set(value) {
            val blob = CustomHeaderRules.encode(value)
            prefs.edit().apply {
                if (blob.isEmpty()) remove("custom_headers") else putString("custom_headers", blob)
            }.apply()
            _headers.value = CustomHeaderRules.decode(blob)
        }

    private val _headers =
        MutableStateFlow(CustomHeaderRules.decode(prefs.getString("custom_headers", null)))

    /** [customHeaders], as they change. */
    val headers: StateFlow<List<CustomHeader>> = _headers.asStateFlow()

    /**
     * The browser sign-in in flight, from opening the browser to its redirect: kept here, in the
     * app's private storage, because Android may end the app while the person is in the browser,
     * and the redirect that starts it again must still be checked and exchanged. Its verifier is
     * a one-shot secret bound to a code only this app can see.
     */
    var browserSignIn: BrowserSignInRequest?
        get() = BrowserSignInRequest.restore(
            clientId = prefs.getString("browser.client", null) ?: return null,
            redirectUri = prefs.getString("browser.redirect", null) ?: return null,
            authorizationEndpoint = prefs.getString("browser.authorize", null) ?: return null,
            state = prefs.getString("browser.state", null) ?: return null,
            verifier = prefs.getString("browser.verifier", null) ?: return null,
        )
        set(value) {
            prefs.edit()
                .putString("browser.client", value?.clientId)
                .putString("browser.redirect", value?.redirectUri)
                .putString("browser.authorize", value?.authorizationEndpoint)
                .putString("browser.state", value?.state)
                .putString("browser.verifier", value?.pkce?.verifier)
                .apply()
        }

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
                deviceAuthorizationEndpoint = prefs.getString("signin.device", null),
                verificationUri = prefs.getString("signin.verify", null),
                authorizationEndpoint = prefs.getString("signin.authorize", null),
                mobileClientId = prefs.getString("signin.mobile", null),
                mobileRedirectUri = prefs.getString("signin.mobile_redirect", null),
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
                .putString("signin.device", value?.deviceAuthorizationEndpoint)
                .putString("signin.verify", value?.verificationUri)
                .putString("signin.authorize", value?.authorizationEndpoint)
                .putString("signin.mobile", value?.mobileClientId)
                .putString("signin.mobile_redirect", value?.mobileRedirectUri)
                .apply()
        }
}

/**
 * Calls to the SovEmu GraphQL API (`POST /graphql` on `SovEmu.Server`), through Apollo: the
 * operations and their models are generated in `core` from the server's schema. Requests run
 * and parse off the main thread (Apollo's own dispatcher).
 */
class SovEmuApi(
    private val settings: Settings,
    /** What this app calls its device on the server ([ClientEvents.deviceName]). */
    private val device: String = "SovEmu for Android",
) : CheatService,
    CollectionService,
    SignInService,
    ServerService {
    /**
     * The sign-in's token client: plain HTTP, without the bearer interceptor, but with the proxy
     * headers, which every request to the server carries (asking it how to sign in included:
     * that is the request a proxy must let through first).
     */
    private val plain: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(CustomHeaderInterceptor({ settings.customHeaders }, { settings.serverUrl }))
        .build()

    /** The sign-in: OAuth tokens from the endpoints the server's `bootstrapConfig` names. */
    private val tokenClient = TokenClient(plain, { settings.serverUrl }, { settings.signInConfig })

    /** The accounts signed in on this device, and the active one's tokens. */
    val session = Session(
        tokenClient,
        AccountStore(settings.keyValue, KeystoreSealer),
        server = { settings.serverUrl },
    )

    /**
     * Shared with Apollo, the image loader and the video players: GraphQL requests carry the
     * access token; `/media/…` URLs are signed and need nothing.
     */
    val client: OkHttpClient = plain.newBuilder()
        .addInterceptor(BearerInterceptor(session))
        .addInterceptor(LongWaitInterceptor)
        .build()

    /** One Apollo client per server address (the address changes at sign-in). */
    private var apollo: Pair<String, ApolloClient>? = null

    private fun apollo(): ApolloClient {
        val url = graphqlUrl()
        apollo?.let { (u, c) -> if (u == url) return c }
        return ApolloClient.Builder()
            .serverUrl(url)
            .okHttpClient(client)
            .build()
            .also { apollo = url to it }
    }

    private fun graphqlUrl(): String {
        val url = "${settings.serverUrl}/graphql"
        if (url.toHttpUrlOrNull() == null) {
            throw ApiException("BAD_REQUEST", "Invalid server address")
        }
        return url
    }

    /**
     * Asks the server how to sign in (`bootstrapConfig`), which also tells whether a SovEmu server
     * is at the address, and keeps the answer with it. Signed out: no bearer, no refresh.
     */
    suspend fun bootstrap(): SignInConfig {
        val url = graphqlUrl()
        val anonymous = signedOut?.takeIf { it.first == url }?.second
            ?: ApolloClient.Builder()
                .serverUrl(url)
                .okHttpClient(plain)
                .build()
                .also { signedOut = url to it }
        return bootstrap(anonymous, settings.serverUrl).also { settings.signInConfig = it }
    }

    override val server: String get() = settings.serverUrl

    override suspend fun bootstrapAt(address: String): SignInConfig {
        settings.serverUrl = address
        return bootstrap()
    }

    override fun setServer(address: String) {
        settings.serverUrl = address
    }

    override suspend fun setupRequired(): Boolean = status().setupRequired

    /** [bootstrap]'s Apollo client, per address: the plain HTTP client, without the bearer. */
    private var signedOut: Pair<String, ApolloClient>? = null

    // An access token the server rejects (revoked, or the clocks disagree) is refreshed and the
    // request sent once more.
    private suspend fun <D : Query.Data> query(q: Query<D>): D =
        session.retryingUnauthenticated { apollo().query(q).execute().orThrow() }
    private suspend fun <D : Mutation.Data> mutate(m: Mutation<D>): D =
        session.retryingUnauthenticated { apollo().mutation(m).execute().orThrow() }

    /** [mutate], for a mutation answered only once its work is done ([LongWaitInterceptor]). */
    private suspend fun <D : Mutation.Data> mutateWaiting(m: Mutation<D>): D =
        session.retryingUnauthenticated {
            apollo().mutation(m).addHttpHeader(LONG_WAIT_HEADER, "1").execute().orThrow()
        }

    /** An absolute URL for a path the API hands out (`/media/games/7/thumb`). */
    fun media(path: String?): String? = path?.let {
        if (it.startsWith("http")) {
            it
        } else {
            settings.serverUrl +
                it
        }
    }

    suspend fun status(): Viewer = query(ViewerQuery()).viewer.let {
        Viewer(it.setupRequired, it.user?.userFields)
    }

    /**
     * Signs in with a username and password (after [bootstrap]); a refusal is an `OAuthException`
     * with the server's reason.
     */
    override suspend fun signIn(username: String, password: String) {
        session.signIn(username, password)
    }

    /**
     * A browser sign-in to open (the server's own page), kept for its redirect
     * ([Settings.browserSignIn]); null when the server offers none.
     */
    override fun startBrowserSignIn(): BrowserSignInRequest? = settings.signInConfig
        ?.let { BrowserSignInRequest.create(settings.serverUrl, it) }
        .also { settings.browserSignIn = it }

    /**
     * Finishes a browser sign-in with the redirect [uri]: the code exchanged, as the mobile
     * client, and signed in. Anything else is an [OAuthException] in the app's own words
     * ([BrowserSignInText]); a full device ([AccountRefusal]) says so.
     */
    override suspend fun finishBrowserSignIn(uri: String) {
        val request = settings.browserSignIn
            ?: throw OAuthException("no_request", BrowserSignInText.FAILED, 0)
        val back = BrowserRedirect.parse(uri, request)
        // A redirect that is not this request's leaves it waiting for its own.
        if (back !is BrowserRedirect.StateMismatch) settings.browserSignIn = null
        if (back !is BrowserRedirect.Code) throw OAuthException("browser", back.message(), 0)
        val tokens = try {
            tokenClient.exchangeCode(back.code, request)
        } catch (e: OAuthException) {
            // A spent or mismatched code: protocol prose. A proxy's page or a 5xx says itself.
            if (e.status == 0 || e.status >= 500 || e.error.startsWith("http_")) throw e
            throw OAuthException(e.error, BrowserSignInText.FAILED, e.status)
        }
        session.signedInWith(tokens, request.clientId)
    }

    /** Signs a TV in as this account: the code its screen shows (*Sign in a TV*). */
    suspend fun approveDeviceCode(code: String) {
        mutate(ApproveDeviceCodeMutation(code))
    }

    /** A code to show, to sign in by approving it elsewhere (after [bootstrap]). */
    override suspend fun requestDeviceCode(): DeviceCode = tokenClient.requestDeviceCode()

    /** One poll for [code]'s approval. */
    override suspend fun pollDeviceCode(code: DeviceCode): DevicePoll =
        tokenClient.pollDeviceCode(code)

    /** Signs in with the tokens an approved code got. */
    override suspend fun signedInWith(tokens: Tokens) {
        session.signedInWith(tokens, tokenClient.deviceClientId)
    }

    /**
     * Names this sign-in's device on the server, for the admin's Devices page, when it has not
     * been named so yet (after signing in, and after an update changes the app's version).
     */
    suspend fun nameDevice() {
        if (session.namedDevice() == device) return
        mutate(NameDeviceMutation(device))
        session.setNamedDevice(device)
    }

    /**
     * Signs the active account out (revoked on the server, without waiting) and lands as a launch
     * does: the sign-in screen, the one account left, or the picker.
     */
    suspend fun signOut() = session.signOut()
    suspend fun iceServers(): List<IceServer> =
        iceServers(query(ClientConfigQuery()).clientConfig.iceServers)
    suspend fun systems(): List<SystemSummary> = query(SystemsQuery()).systems.map {
        it.systemFields
    }

    /**
     * The background behind every screen (a signed `/media/backgrounds/…` path), or null for
     * none.
     */
    suspend fun background(): String? = query(BackgroundQuery()).background?.url
    suspend fun home(): HomeFeed = query(HomeQuery()).let { d ->
        val h = d.home
        HomeFeed(
            h.featured.map { it.gameFields },
            h.recent.map { it.gameFields },
            h.added.map { it.gameFields },
            h.favourites.map { it.gameFields },
            h.topRated.map { it.gameFields },
            d.collections.map { c -> HomeCollection(c.id, c.name, c.games.map { it.gameFields }) },
        )
    }

    /** Games from every system matching every word of [text], best first (at most 48). */
    suspend fun search(text: String): List<Game> =
        query(SearchQuery(text)).search.map { it.gameFields }

    /** The artwork to frame a game's picture with, if its system has any. */
    suspend fun decoration(gameId: Int): GameDecoration? =
        query(GameDecorationQuery(gameId)).game.decoration?.let {
            GameDecoration(it.url, it.width, it.height, it.top, it.left, it.bottom, it.right)
        }

    /** A game's soft patches, the default first. */
    suspend fun patches(gameId: Int): List<GamePatch> =
        query(PatchesQuery(gameId)).game.patches.map {
            GamePatch(it.id, it.name, it.isDefault)
        }

    /** Plays the game with [patchId] from now on, or the original (null). */
    suspend fun setPatch(gameId: Int, patchId: Int?) {
        mutate(SetPatchMutation(gameId, Optional.present(patchId)))
    }

    /**
     * Sends what went wrong here to the server's log; only while signed in, and never with a
     * retry or a sign-in of its own (a report must not get in the player's way).
     */
    suspend fun reportClientEvents(
        client: String,
        events: List<media.sovereign.sovemu.core.ClientEvent>,
    ) {
        if (!session.signedIn) return
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

    /** Adds a game to this user's favourites, or takes it out. */
    suspend fun setFavourite(gameId: Int, favourite: Boolean) {
        mutate(SetFavouriteMutation(gameId, favourite))
    }

    /** This user's collections, each marked with whether [gameId] is in it. */
    override suspend fun collectionChoices(gameId: Int): List<CollectionChoice> =
        query(CollectionChoicesQuery(gameId)).let { d ->
            val member = d.game.collectionIds.toSet()
            d.collections.map { CollectionChoice(it.id, it.name, it.id in member) }
        }

    /** Makes a collection (names are 1 to 64 characters, unique per user); returns its id. */
    override suspend fun createCollection(name: String): Int =
        mutate(CreateCollectionMutation(name.trim().take(64))).createCollection.id
    override suspend fun setInCollection(collectionId: Int, gameId: Int, member: Boolean) {
        mutate(SetInCollectionMutation(collectionId, gameId, member))
    }
    suspend fun games(system: String): List<Game> =
        query(SystemGamesQuery(system)).system?.games?.map { it.gameFields }
            ?: throw ApiException("NOT_FOUND", "No such system")

    /**
     * Starts [gameId]; [startId] names the start, for [startProgress] meanwhile. With a [slot],
     * the game continues from that save state, loaded as it starts (one the server cannot load
     * is logged, and the game starts afresh).
     */
    suspend fun startSession(gameId: Int, startId: String, slot: Int? = null): SessionInfo =
        mutateWaiting(
            StartSessionMutation(
                gameId,
                Optional.present(startId),
                Optional.presentIfNotNull(slot),
            ),
        ).startSession.sessionFields

    /** How far the start [startId] has got. */
    suspend fun startProgress(startId: String): StartStatus =
        query(StartProgressQuery(startId)).startProgress.toStartStatus()

    /**
     * Sends the complete offer SDP, naming this app ([client]) and saying what this device can
     * decode and show ([capabilities], the whole document every time); returns the answer SDP.
     */
    suspend fun offer(
        sessionId: String,
        sdp: String,
        client: String,
        capabilities: ClientCapabilitiesInput,
    ): String = mutate(
        SessionOfferMutation(
            sessionId,
            sdp,
            Optional.present(client),
            Optional.present(capabilities),
        ),
    ).sessionOffer
    suspend fun stopSession(sessionId: String) {
        mutate(StopSessionMutation(sessionId))
    }
    suspend fun states(gameId: Int): List<SaveSlot> =
        query(SavesQuery(gameId)).game.saves.map { it.saveFields }

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
        query(ControllerMappingsQuery(Optional.present(system)))
            .controllerMappings
            .map { it.mappingFields.toMapping() }

    /**
     * Saves this user's mapping for a model on [system], or on every system when it is null. The
     * server allows names up to 128 characters.
     */
    suspend fun saveControllerMapping(
        controller: String,
        system: String?,
        name: String,
        bindings: List<Binding>,
    ): ControllerMapping = mutate(
        SaveControllerMappingMutation(
            controller,
            Optional.present(system),
            name.take(128),
            bindings.map { it.toInput() },
        ),
    )
        .saveControllerMapping.mappingFields.toMapping()

    /**
     * Removes this user's mapping for a model on [system] (or every system); returns whether there
     * was one.
     */
    suspend fun resetControllerMapping(controller: String, system: String?): Boolean =
        mutate(ResetControllerMappingMutation(controller, Optional.present(system)))
            .resetControllerMapping

    /** This user's cheats for a game. */
    override suspend fun cheats(gameId: Int): List<Cheat> =
        query(CheatsQuery(gameId)).game.cheats.map { it.cheatFields }

    /** Adds a cheat (on); a running session gets it at once. Returns the game's cheats. */
    override suspend fun addCheat(gameId: Int, code: String, description: String): List<Cheat> =
        mutate(AddCheatMutation(gameId, code, description)).addCheat.map {
            it.cheatFields
        }
    override suspend fun setCheatEnabled(gameId: Int, cheatId: Int, enabled: Boolean): List<Cheat> =
        mutate(SetCheatEnabledMutation(gameId, cheatId, enabled))
            .setCheatEnabled
            .map { it.cheatFields }
    override suspend fun deleteCheat(gameId: Int, cheatId: Int) {
        mutate(DeleteCheatMutation(gameId, cheatId))
    }

    /** A system's button labels and pad (null for an unknown system), and the controllers' pads. */
    suspend fun controls(system: String): Pair<SystemControls?, List<PadLayout>> =
        query(ControlsQuery(system)).toControls()
}
