package media.sovereign.sovemu

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.sovereign.sovemu.core.ApiException
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameIndex
import media.sovereign.sovemu.core.GamePatch
import media.sovereign.sovemu.core.HomeFeed
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.User

/** What a screen shows while its data loads. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * The app's data, fetched once and kept: the signed-in user, the systems, the home feed and each
 * opened system's games (indexed on [compute]). Screens read these flows, so going back to a
 * screen never waits for the network or re-sorts a list. A game session ending refreshes what it
 * changed (play counts, recently played).
 *
 * Every request belongs to the current sign-in: [clear] cancels them, so a late answer from the
 * previous account can neither show its data nor sign the next one out.
 */
class Store(
    private val api: SovEmuApi,
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** How often the lists are fetched again, well inside a signed URL's day. */
    private val refreshEvery: Duration = 6.hours,
) {
    private val _user = MutableStateFlow<User?>(null)
    private val _systems = MutableStateFlow<Load<List<SystemSummary>>>(Load.Loading)
    private val _home = MutableStateFlow<Load<HomeFeed>>(Load.Loading)
    private val _background = MutableStateFlow<String?>(null)
    private val _signedOut = MutableStateFlow(false)

    val user: StateFlow<User?> = _user.asStateFlow()
    val systems: StateFlow<Load<List<SystemSummary>>> = _systems.asStateFlow()
    val home: StateFlow<Load<HomeFeed>> = _home.asStateFlow()

    /**
     * The server's background behind every screen (the admin's choice), or null for none. Read
     * once per sign-in: with `random` that is one per visit, as on the web, and Coil keeps the
     * picture by its path, so it outlives the URL's signature.
     */
    val background: StateFlow<String?> = _background.asStateFlow()

    /** Set when the server says the sign-in has expired: the app goes back to sign-in. */
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    private val games = HashMap<String, MutableStateFlow<Load<GameIndex>>>()
    private var signIn: Job = SupervisorJob(scope.coroutineContext[Job])
    private var freshening: Job? = null

    private fun launch(block: suspend CoroutineScope.() -> Unit) =
        scope.launch(signIn, block = block)

    fun refresh() {
        launch { _user.value = attempt { api.status().user }.onFailure(::note).getOrNull() }
        launch { load(_systems) { api.systems() } }
        launch { load(_home) { api.home() } }
        launch { _background.value = attempt { api.background() }.onFailure(::note).getOrNull() }
        // Best effort: an unnamed sign-in is only shown as "App".
        launch { attempt { api.nameDevice() }.onFailure(::note) }
        keepFresh()
    }

    /**
     * The file URLs in these lists are signed for a day (`SOVEMU_ASSET_URL_HOURS`), and a TV may
     * keep the app open for longer: every [refreshEvery] the lists are fetched again, quietly
     * (what is on screen stays until the new copy arrives), so their artwork and videos keep
     * loading.
     */
    private fun keepFresh() {
        if (freshening?.isActive == true) return
        freshening = launch {
            while (true) {
                delay(refreshEvery)
                launch { load(_systems) { api.systems() } }
                launch { load(_home) { api.home() } }
                for ((system, flow) in games.entries.toList()) {
                    launch {
                        loadGames(system, flow, quiet = true)
                    }
                }
            }
        }
    }

    /** A system's games, fetched the first time they are asked for. */
    fun games(system: String): StateFlow<Load<GameIndex>> = games.getOrPut(system) {
        MutableStateFlow<Load<GameIndex>>(Load.Loading).also { flow ->
            launch { loadGames(system, flow) }
        }
    }

    /**
     * A random game with a video snap, for the screensaver: a system picked in proportion to its
     * games (so a big library shows more), then one of its games. Loads (and keeps) that system's
     * list the first time; null while there is nothing to show.
     */
    suspend fun randomVideoGame(): Game? {
        val withGames = (_systems.value as? Load.Ready)?.value?.filter {
            it.gameCount > 0
        }.orEmpty()
        if (withGames.isEmpty()) return null
        var pick = Random.nextInt(withGames.sumOf { it.gameCount })
        val system = withGames.first { s ->
            pick -= s.gameCount
            pick < 0
        }
        val index = games(system.id).first { it !is Load.Loading } as? Load.Ready ?: return null
        return index.value.games.filter { it.video != null }.randomOrNull()
    }

    /** After a game: its play count and the home rows changed. */
    fun played(system: String) {
        launch { load(_home) { api.home() } }
        games[system]?.let { flow -> launch { loadGames(system, flow, quiet = true) } }
    }

    /**
     * Marks [game] as a favourite or not, at once in its system's list (so the panel's heart and
     * the Favourites filter follow without waiting), then on the server; a failure puts it back.
     * Home is fetched again either way, for its Favourites row. Returns the error message, if any.
     */
    suspend fun setFavourite(game: Game, favourite: Boolean): String? {
        markFavourite(game.system, game.id, favourite)
        val error = attempt { api.setFavourite(game.id, favourite) }.exceptionOrNull()
        if (error != null) markFavourite(game.system, game.id, !favourite)
        launch { load(_home) { api.home() } }
        return error?.let(::note)
    }

    /**
     * Plays [game] with [patch] (null: the original), at once in its system's list (the panel and
     * its saves follow: a patched game's saves are its own), then on the server; a failure puts
     * it back. Home is fetched again (its auto saves may differ). Returns the error, if any.
     */
    suspend fun setPatch(game: Game, patch: GamePatch?): String? {
        val before = game.patch
        update(game.system, game.id) { it.copy(patch = patch?.name) }
        val error = attempt { api.setPatch(game.id, patch?.id) }.exceptionOrNull()
        if (error != null) update(game.system, game.id) { it.copy(patch = before) }
        launch { load(_home) { api.home() } }
        return error?.let(::note)
    }

    /** Collections changed (a game went in or out, or one was made): Home shows a row for each. */
    fun collectionsChanged() {
        launch { load(_home) { api.home() } }
    }

    private suspend fun markFavourite(system: String, id: Int, favourite: Boolean) =
        update(system, id) {
            it.copy(favourite = favourite)
        }

    /** Changes one game in its system's list (if that list is loaded). */
    private suspend fun update(system: String, id: Int, change: (Game) -> Game) {
        val flow = games[system] ?: return
        val index = (flow.value as? Load.Ready)?.value ?: return
        val list = index.games.map { if (it.id == id) change(it) else it }
        flow.value = Load.Ready(withContext(compute) { GameIndex(list) })
    }

    /** Signed out: forget everything, and cancel what the old sign-in still has in flight. */
    fun clear() {
        signIn.cancel()
        freshening = null
        signIn = SupervisorJob(scope.coroutineContext[Job])
        _user.value = null
        _systems.value = Load.Loading
        _home.value = Load.Loading
        _background.value = null
        games.clear()
        _signedOut.value = false
    }

    private suspend fun loadGames(
        system: String,
        flow: MutableStateFlow<Load<GameIndex>>,
        quiet: Boolean = false,
    ) {
        if (!quiet) flow.value = Load.Loading
        attempt { api.games(system) }
            .onSuccess { list -> flow.value = Load.Ready(withContext(compute) { GameIndex(list) }) }
            .onFailure { e -> note(e).let { if (!quiet) flow.value = Load.Failed(it) } }
    }

    private suspend fun <T> load(flow: MutableStateFlow<Load<T>>, fetch: suspend () -> T) {
        attempt { fetch() }
            .onSuccess { flow.value = Load.Ready(it) }
            .onFailure { e ->
                note(e).let {
                    if (flow.value !is Load.Ready) {
                        flow.value =
                            Load.Failed(it)
                    }
                }
            }
    }

    /** The message to show for [e]; an expired sign-in also signs the app out. */
    private fun note(e: Throwable): String {
        if (e is ApiException) {
            if (e.unauthenticated) _signedOut.value = true
            return e.message ?: "Request failed"
        }
        return "Cannot reach the server: ${e.message}"
    }
}
