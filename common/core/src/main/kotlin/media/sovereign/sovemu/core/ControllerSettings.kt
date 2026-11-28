package media.sovereign.sovemu.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the controller settings ask the server; `SovEmuApi` implements it. */
interface ControllerMappingService {
    suspend fun controllerMappings(system: String?): List<ControllerMapping>
    suspend fun saveControllerMapping(
        controller: String,
        system: String?,
        name: String,
        bindings: List<Binding>,
    ): ControllerMapping
    suspend fun resetControllerMapping(controller: String, system: String?): Boolean
    suspend fun controls(system: String): Pair<SystemControls?, List<PadLayout>>
}

/**
 * The controller settings for one system at a time, as both ways in show them: the in-game
 * menu's *Controllers*, opened on the game's system, and the account menu's, opened on the first
 * system or the one last chosen. [select] another system and its mappings (this system's, and
 * every system's) and its controls (labels, the console's pad) load in its place; a choice made
 * while one was loading wins over it. [onChanged] hears every save and reset, so a game playing
 * reads its own mappings again.
 */
class ControllerSettingsModel(
    private val service: ControllerMappingService,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit = {},
) {
    /** One system's settings, once loaded; [book] and [controls] are null while they load. */
    data class State(
        val system: String? = null,
        val book: MappingBook? = null,
        val controls: SystemControls? = null,
        val layouts: List<PadLayout> = emptyList(),
        val error: String? = null,
    ) {
        val loading: Boolean get() = system != null && book == null && error == null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var loading: Job? = null

    /** Shows [system]'s settings, loading them (again, if it is the one shown). */
    fun select(system: String) {
        loading?.cancel()
        _state.value = State(system)
        loading = scope.launch {
            val book = MappingBook(system)
            attempt {
                book.refresh(service::controllerMappings)
                service.controls(system)
            }.onSuccess { (controls, layouts) ->
                if (_state.value.system == system) {
                    _state.value = State(system, book, controls, layouts)
                }
            }.onFailure { e ->
                if (_state.value.system == system) {
                    _state.value =
                        State(system, error = "Could not load the controls: ${e.message}")
                }
            }
        }
    }

    /**
     * Saves a model's mapping for [scope] (this system or every system), then reads back what is
     * in force for both scopes, into the book shown.
     */
    suspend fun save(model: String, scope: MappingScope, name: String, bindings: List<Binding>) {
        val book = _state.value.book ?: return
        service.saveControllerMapping(model, book.systemArg(scope), name, bindings)
        book.refresh(service::controllerMappings)
        onChanged()
    }

    /** Removes the user's own mapping for a model in [scope], then reads back what is in force. */
    suspend fun reset(model: String, scope: MappingScope) {
        val book = _state.value.book ?: return
        book.reset(
            model,
            scope,
            service::resetControllerMapping,
            service::controllerMappings,
        )
        onChanged()
    }
}
