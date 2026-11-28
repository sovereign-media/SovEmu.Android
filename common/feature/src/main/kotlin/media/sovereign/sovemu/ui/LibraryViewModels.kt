package media.sovereign.sovemu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import media.sovereign.sovemu.Load
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.Store
import media.sovereign.sovemu.controllersSystem
import media.sovereign.sovemu.core.CollectionsModel
import media.sovereign.sovemu.core.ControllerSettingsModel
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameIndex
import media.sovereign.sovemu.core.GamePatch
import media.sovereign.sovemu.core.GameSearch
import media.sovereign.sovemu.core.GameSort
import media.sovereign.sovemu.core.HomeFeed
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SearchResults
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.attempt
import media.sovereign.sovemu.sort

/** Home, as Sovereign.Android's `HomeViewModel`: the feed and the systems, from [Store]. */
@HiltViewModel
class HomeViewModel @Inject constructor(private val store: Store, settings: Settings) :
    ViewModel() {
    val feed: StateFlow<Load<HomeFeed>> = store.home
    val systems: StateFlow<Load<List<SystemSummary>>> = store.systems
    val server: StateFlow<String> = settings.server

    /** Asks for everything again (Home's *Try again*). */
    fun retry() = store.refresh()
}

/** What a game's side panel needs from the server, while it is open. */
interface GamePanelData {
    /** The open game's patches, asked for after a pause; empty while none is open. */
    val patches: StateFlow<List<GamePatch>>

    /** Its saves, which change with its patch. */
    val saves: StateFlow<List<SaveSlot>>

    /** The panel is open on [gameId] (null: closed), playing [patch]. */
    fun show(gameId: Int?, patch: String?)

    /** The collections drawer's state and calls, for [gameId]. */
    fun collections(gameId: Int): CollectionsModel
}

/**
 * One system's games: the list ([Store.games]), its sort (kept across launches), favourites,
 * patches and collections, and the open panel's patches and saves ([GamePanelData]), asked for
 * after a pause so moving quickly through games does not ask for each.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = SystemViewModel.Factory::class)
class SystemViewModel @AssistedInject constructor(
    @Assisted systemId: String,
    private val store: Store,
    private val settings: Settings,
    private val api: SovEmuApi,
) : ViewModel(),
    GamePanelData {
    val games: StateFlow<Load<GameIndex>> = store.games(systemId)

    private val _sort = MutableStateFlow(settings.sort)
    val sort: StateFlow<GameSort> = _sort.asStateFlow()

    fun setSort(sort: GameSort) {
        _sort.value = sort
        settings.sort = sort
    }

    suspend fun setFavourite(game: Game, favourite: Boolean): String? =
        store.setFavourite(game, favourite)

    suspend fun setPatch(game: Game, patch: GamePatch?): String? = store.setPatch(game, patch)

    private val panel = MutableStateFlow<Pair<Int, String?>?>(null)

    override val patches: StateFlow<List<GamePatch>> = panel.map { it?.first }
        .distinctUntilChanged()
        .transformLatest { id ->
            emit(emptyList())
            if (id != null) {
                delay(PAUSE_MS)
                emit(attempt { api.patches(id) }.getOrDefault(emptyList()))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    override val saves: StateFlow<List<SaveSlot>> = panel
        .transformLatest { open ->
            emit(emptyList())
            if (open != null) {
                delay(PAUSE_MS)
                emit(attempt { api.states(open.first) }.getOrDefault(emptyList()))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    override fun show(gameId: Int?, patch: String?) {
        panel.value = gameId?.let { it to patch }
    }

    override fun collections(gameId: Int) =
        CollectionsModel(api, gameId, viewModelScope, store::collectionsChanged)

    @AssistedFactory
    interface Factory {
        fun create(systemId: String): SystemViewModel
    }

    private companion object {
        /** How long a panel stays on a game before its patches and saves are asked for. */
        const val PAUSE_MS = 150L
    }
}

/** Search, as Sovereign.Android's `SearchViewModel`: the latest query, after a pause in typing. */
@HiltViewModel
class SearchViewModel @Inject constructor(api: SovEmuApi) : ViewModel() {
    private val search = GameSearch(api::search, viewModelScope)
    val results: StateFlow<SearchResults?> = search.results

    fun query(text: String) = search.query(text)
}

/** *Sign in a TV*, as Sovereign.Android's `SignInTvViewModel`: approves a TV's code as this user. */
@HiltViewModel
class SignInTvViewModel @Inject constructor(
    private val api: SovEmuApi,
    store: Store,
    settings: Settings,
) : ViewModel() {
    val server: StateFlow<String> = settings.server
    val userName: StateFlow<String> = store.user
        .map { it?.username.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.user.value?.username.orEmpty())

    suspend fun approve(code: String) = api.approveDeviceCode(code)
}

/**
 * The account menu's *Controllers* ([ControllerSettings]): the same settings as the in-game
 * menu's, outside a game, opened on the system last chosen here (kept in [Settings]), else the
 * first offered.
 */
@HiltViewModel
class ControllerSettingsViewModel @Inject constructor(
    api: SovEmuApi,
    private val settings: Settings,
) : ViewModel() {
    val controllerSettings = ControllerSettingsModel(api, viewModelScope)

    /** Shows a system once [offered] are known: the one last chosen, if still offered. */
    fun open(offered: List<String>) {
        if (controllerSettings.state.value.system != null || offered.isEmpty()) return
        controllerSettings.select(
            settings.controllersSystem?.takeIf {
                it in offered
            } ?: offered[0],
        )
    }

    fun chose(system: String) {
        settings.controllersSystem = system
    }
}
