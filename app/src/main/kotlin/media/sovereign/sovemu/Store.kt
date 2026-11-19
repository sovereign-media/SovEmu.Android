package media.sovereign.sovemu

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.sovereign.sovemu.core.ApiException
import media.sovereign.sovemu.core.GameIndex
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
) {
    private val _user = MutableStateFlow<User?>(null)
    private val _systems = MutableStateFlow<Load<List<SystemSummary>>>(Load.Loading)
    private val _home = MutableStateFlow<Load<HomeFeed>>(Load.Loading)
    private val _signedOut = MutableStateFlow(false)

    val user: StateFlow<User?> = _user.asStateFlow()
    val systems: StateFlow<Load<List<SystemSummary>>> = _systems.asStateFlow()
    val home: StateFlow<Load<HomeFeed>> = _home.asStateFlow()
    /** Set when the server says the sign-in has expired: the app goes back to sign-in. */
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    private val games = HashMap<String, MutableStateFlow<Load<GameIndex>>>()
    private var signIn: Job = SupervisorJob(scope.coroutineContext[Job])

    private fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(signIn, block = block)

    fun refresh() {
        launch { _user.value = attempt { api.status().user }.onFailure(::note).getOrNull() }
        launch { load(_systems) { api.systems() } }
        launch { load(_home) { api.home() } }
    }

    /** A system's games, fetched the first time they are asked for. */
    fun games(system: String): StateFlow<Load<GameIndex>> =
        games.getOrPut(system) {
            MutableStateFlow<Load<GameIndex>>(Load.Loading).also { flow -> launch { loadGames(system, flow) } }
        }

    /** After a game: its play count and the home rows changed. */
    fun played(system: String) {
        launch { load(_home) { api.home() } }
        games[system]?.let { flow -> launch { loadGames(system, flow, quiet = true) } }
    }

    /** Signed out: forget everything, and cancel what the old sign-in still has in flight. */
    fun clear() {
        signIn.cancel()
        signIn = SupervisorJob(scope.coroutineContext[Job])
        _user.value = null
        _systems.value = Load.Loading
        _home.value = Load.Loading
        games.clear()
        _signedOut.value = false
    }

    private suspend fun loadGames(system: String, flow: MutableStateFlow<Load<GameIndex>>, quiet: Boolean = false) {
        if (!quiet) flow.value = Load.Loading
        attempt { api.games(system) }
            .onSuccess { list -> flow.value = Load.Ready(withContext(compute) { GameIndex(list) }) }
            .onFailure { e -> note(e).let { if (!quiet) flow.value = Load.Failed(it) } }
    }

    private suspend fun <T> load(flow: MutableStateFlow<Load<T>>, fetch: suspend () -> T) {
        attempt { fetch() }
            .onSuccess { flow.value = Load.Ready(it) }
            .onFailure { e -> note(e).let { if (flow.value !is Load.Ready) flow.value = Load.Failed(it) } }
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
