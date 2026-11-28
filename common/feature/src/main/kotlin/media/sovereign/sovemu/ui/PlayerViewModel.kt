package media.sovereign.sovemu.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.sovereign.sovemu.ClientEvents
import media.sovereign.sovemu.Controller
import media.sovereign.sovemu.Sessions
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.Store
import media.sovereign.sovemu.Stream
import media.sovereign.sovemu.core.AUTO_SLOT
import media.sovereign.sovemu.core.Binding
import media.sovereign.sovemu.core.CheatsModel
import media.sovereign.sovemu.core.ControllerMapping
import media.sovereign.sovemu.core.ForwardOnly
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameDecoration
import media.sovereign.sovemu.core.InputSequence
import media.sovereign.sovemu.core.MappingBook
import media.sovereign.sovemu.core.MappingScope
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.StartStatus
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.attempt
import media.sovereign.sovemu.core.describeStart
import media.sovereign.sovemu.core.newStartId
import org.webrtc.PeerConnection
import org.webrtc.VideoSink

/**
 * One game, from its start to its stop, as Sovereign.Android's `PlayerViewModel`: everything the
 * player does with the server. The session starts at once ([Sessions], owned by the app), the
 * question (Continue or Start over) is asked while it starts, and the start's progress is polled;
 * the decoration, mappings and controller diagrams come meanwhile. The in-game menu's calls
 * (discs, saves, cheats, mappings) are here too. The screen keeps what needs a window: it builds
 * the [Stream] with [stream] (the video view, the controllers) and closes it as it goes; the
 * session stops when this is cleared (the player left), its saves written first.
 */
@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
class PlayerViewModel @AssistedInject constructor(
    @Assisted private val game: Game,
    @Assisted private val system: SystemSummary,
    @Assisted private val start: Start,
    private val api: SovEmuApi,
    private val sessions: Sessions,
    private val store: Store,
) : ViewModel() {
    private val startId = newStartId()

    /**
     * The save the start itself continues from, when it was chosen before the game started
     * (Continue, or a slot from the game panel's saves): loaded as the game loads, not by a
     * request of its own once it is ready. A choice asked for meanwhile ([Start.Ask]) is loaded
     * afterwards.
     */
    private val startedFrom = (start as? Start.FromSlot)?.slot

    /** Started at once, and owned by the app: leaving now still stops it once it exists. */
    private val starting = sessions.start(game.id, startId, startedFrom)

    /** The mappings in force for this system and for all, read as the game starts. */
    val book = MappingBook(system.id)

    private val _session = MutableStateFlow<SessionInfo?>(null)

    /** The session, once it has started (and the question, if any, was answered). */
    val session: StateFlow<SessionInfo?> = _session.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Why the game could not start. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _progress = MutableStateFlow<StartStatus?>(null)
    val progress: StateFlow<StartStatus?> = _progress.asStateFlow()

    private val _bar = MutableStateFlow(0.0)

    /** The loading bar, which only moves forward. */
    val bar: StateFlow<Double> = _bar.asStateFlow()

    private val _choice = MutableStateFlow<CompletableDeferred<Start>?>(null)

    /** Waiting for Continue or Start over (there is an auto save and nobody was asked yet). */
    val choice: StateFlow<CompletableDeferred<Start>?> = _choice.asStateFlow()

    private val _decoration = MutableStateFlow<GameDecoration?>(null)

    /** The artwork framing the picture (its system's decoration), if any. */
    val decoration: StateFlow<GameDecoration?> = _decoration.asStateFlow()

    private val _controls = MutableStateFlow(Controls())

    /** The system's labels and the pads' diagrams, for the controllers screen. */
    val controls: StateFlow<Controls> = _controls.asStateFlow()

    private val _mappings = MutableStateFlow<List<ControllerMapping>?>(null)

    /** This system's mappings, once read, for the controllers' mixer. */
    val mappings: StateFlow<List<ControllerMapping>?> = _mappings.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** Something to say for a moment (the default controls, a save that failed). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _saveShot = MutableStateFlow<String?>(null)

    /**
     * The screenshot of the save being continued, behind the loading screen; null when the game
     * starts afresh or the save has none.
     */
    val saveShot: StateFlow<String?> = _saveShot.asStateFlow()

    private val _disc = MutableStateFlow(1)

    /** The disc in the tray (1-based), for a multi-disc game. */
    val disc: StateFlow<Int> = _disc.asStateFlow()

    private val ice = viewModelScope.async {
        attempt { api.iceServers() }.getOrDefault(emptyList())
    }

    init {
        // The mappings, labels and the question (if any) come while the session starts, rather
        // than each adding a round trip before the game.
        viewModelScope.launch {
            _decoration.value = attempt { api.decoration(game.id) }.getOrNull()
        }
        viewModelScope.launch {
            attempt { book.refresh(api::controllerMappings) }
                .onSuccess { _mappings.value = book.forSystem }
                .onFailure { e -> _notice.value = "Default controls: ${e.message}" }
        }
        viewModelScope.launch {
            attempt { api.controls(system.id) }
                .onSuccess { (sys, layouts) -> _controls.value = Controls(sys, layouts) }
        }
        // The start's progress, polled while the session starts (as the web does). A failed poll
        // keeps the last word; the bar only moves forward.
        viewModelScope.launch {
            val forward = ForwardOnly()
            while (!starting.isCompleted) {
                attempt { api.startProgress(startId) }.onSuccess {
                    _progress.value = it
                    _bar.value = forward.next(describeStart(it).fraction)
                }
                delay(250)
            }
        }
        viewModelScope.launch { prepare() }
    }

    /** How the game starts, then the session: a save loaded into it, if one was chosen. */
    private suspend fun prepare() {
        try {
            var saves: List<SaveSlot>? = null
            val how = if (start == Start.Ask) {
                saves = attempt { api.states(game.id) }.getOrDefault(emptyList())
                if (saves.any { it.slot == 0 }) {
                    CompletableDeferred<Start>().also { _choice.value = it }.await()
                        .also { _choice.value = null }
                } else {
                    Start.New
                }
            } else {
                start
            }
            if (how is Start.FromSlot) {
                // The save's own screenshot, so the player sees where they will be: the auto
                // save's comes with the game, another slot's with the saves (asked for only
                // when they were not, and not waited for).
                val known = saves
                if (how.slot == AUTO_SLOT && game.autoSave != null) {
                    _saveShot.value = game.autoSave?.screenshotUrl
                } else if (known != null) {
                    _saveShot.value = known.firstOrNull { it.slot == how.slot }?.screenshotUrl
                } else {
                    viewModelScope.launch {
                        attempt { api.states(game.id) }.onSuccess { all ->
                            _saveShot.value = all.firstOrNull { it.slot == how.slot }?.screenshotUrl
                        }
                    }
                }
            }
            val session = starting.await()
            if (how is Start.FromSlot && how.slot != startedFrom) {
                attempt { api.loadState(session.id, how.slot) }
                    .onFailure { e -> _notice.value = "Could not load: ${e.message}" }
            }
            _session.value = session
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = "Could not start: ${e.message}"
        }
    }

    /** This game's input packet numbers, over all its connections ([InputSequence]). */
    private val sequence = InputSequence()

    /**
     * Builds a stream for this game, drawing into [video] and sending [controller]'s input, with
     * its offer under way ([Stream.prepare]): the first while the game is still loading, to be
     * connected once it is ready. WebRTC's factory and peer connection are slow to build, so not
     * on the main thread, and built whatever happens, so the caller can close it whatever
     * happens.
     */
    suspend fun stream(
        context: Context,
        controller: Controller,
        video: VideoSink,
        onState: (PeerConnection.PeerConnectionState) -> Unit,
        /** Told of the stream as soon as it exists, even if the caller has gone by then. */
        built: (Stream) -> Unit,
    ): Stream {
        val servers = ice.await()
        return withContext(NonCancellable + Dispatchers.Default) {
            Stream(
                context,
                api,
                ClientEvents.clientName(context),
                servers,
                controller,
                video,
                sequence,
                onState = onState,
            ).also {
                built(it)
                it.prepare()
            }
        }
    }

    /** Takes the notice to show (it is shown once). */
    fun noticeShown() {
        _notice.value = null
    }

    /** The game's save slots, for the menu's Save and Load pages. */
    suspend fun saves(): List<SaveSlot> = attempt { api.states(game.id) }.getOrDefault(emptyList())

    /** The cheats page's state and calls. */
    fun cheats() = CheatsModel(api, game.id, viewModelScope)

    /** Puts disc [n] in the tray; says how it went. */
    suspend fun setDisc(n: Int): String? = _session.value?.let { s ->
        attempt { api.setDisc(s.id, n) }.fold(
            {
                _disc.value = n
                "Disc $n is in"
            },
            { e -> "Could not change disc: ${e.message}" },
        )
    }

    suspend fun saveTo(slot: Int): String? = _session.value?.let { s ->
        attempt { api.saveState(s.id, slot) }.fold(
            { "Saved to slot $slot" },
            { e -> "Save failed: ${e.message}" },
        )
    }

    suspend fun loadFrom(slot: Int, name: String): String? = _session.value?.let { s ->
        attempt { api.loadState(s.id, slot) }.fold(
            { "Loaded $name" },
            { e -> "Load failed: ${e.message}" },
        )
    }

    /** Saves a mapping for [model] in [scope], then reads back what is in force. */
    suspend fun saveMapping(
        model: String,
        scope: MappingScope,
        name: String,
        bindings: List<Binding>,
    ) {
        api.saveControllerMapping(model, book.systemArg(scope), name, bindings)
        book.refresh(api::controllerMappings)
    }

    /** Removes the user's mapping for [model] in [scope], then reads back what is in force. */
    suspend fun resetMapping(model: String, scope: MappingScope) {
        book.reset(model, scope, api::resetControllerMapping, api::controllerMappings)
    }

    /** The player has gone: the session stops (its saves written first), and Home learns of it. */
    override fun onCleared() {
        sessions.stop(starting) { store.played(system.id) }
    }

    @AssistedFactory
    interface Factory {
        fun create(game: Game, system: SystemSummary, start: Start): PlayerViewModel
    }
}
