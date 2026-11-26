package media.sovereign.sovemu.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// A game's cheats and collections, as the in-game menu's Cheats page and a game's Collections
// drawer show them: the state and what changes it, out of the composables (which only draw it
// and forward taps), so it can be tested on the JVM with a fake service.

/** The cheat calls [CheatsModel] needs (`SovEmuApi`, or a fake in tests). */
interface CheatService {
    suspend fun cheats(gameId: Int): List<Cheat>
    suspend fun addCheat(gameId: Int, code: String, description: String): List<Cheat>
    suspend fun setCheatEnabled(gameId: Int, cheatId: Int, enabled: Boolean): List<Cheat>
    suspend fun deleteCheat(gameId: Int, cheatId: Int)
}

/**
 * This user's cheats for one game. [cheats] is null until loaded; [message] says why the last
 * change failed (null once one works). Every change answers with the game's cheats after it,
 * which replace the list. Work runs in [scope]: the screen's, so it stops when the menu closes.
 */
class CheatsModel(
    private val service: CheatService,
    private val gameId: Int,
    private val scope: CoroutineScope,
) {
    private val _cheats = MutableStateFlow<List<Cheat>?>(null)
    val cheats: StateFlow<List<Cheat>?> = _cheats.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load() {
        scope.launch {
            _cheats.value = attempt { service.cheats(gameId) }
                .getOrElse { e ->
                    _message.value = "Could not load cheats: ${e.message}"
                    emptyList()
                }
        }
    }

    fun toggle(cheat: Cheat) = change { service.setCheatEnabled(gameId, cheat.id, !cheat.enabled) }

    fun delete(cheat: Cheat) = change {
        service.deleteCheat(gameId, cheat.id)
        service.cheats(gameId)
    }

    /** Adds a cheat; codes are upper case, and the name may be empty. Nothing for an empty code. */
    fun add(code: String, name: String) {
        val c = code.trim().uppercase()
        if (c.isEmpty()) return
        val n = name.trim()
        change { service.addCheat(gameId, c, n) }
    }

    private fun change(block: suspend () -> List<Cheat>) {
        scope.launch {
            attempt { block() }
                .onSuccess {
                    _cheats.value = it
                    _message.value = null
                }
                .onFailure { e -> _message.value = e.message }
        }
    }
}

/** The collection calls [CollectionsModel] needs (`SovEmuApi`, or a fake in tests). */
interface CollectionService {
    suspend fun collectionChoices(gameId: Int): List<CollectionChoice>
    suspend fun createCollection(name: String): Int
    suspend fun setInCollection(collectionId: Int, gameId: Int, member: Boolean)
}

/**
 * This user's collections, each saying whether one game is in it. A toggle shows at once and is
 * undone if the server refuses it; [onChanged] runs after each change that worked, so Home can
 * show its rows. [error] says what went wrong last (null once something works).
 */
class CollectionsModel(
    private val service: CollectionService,
    private val gameId: Int,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit = {},
) {
    private val _choices = MutableStateFlow<List<CollectionChoice>?>(null)
    val choices: StateFlow<List<CollectionChoice>?> = _choices.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun load() {
        attempt { service.collectionChoices(gameId) }
            .onSuccess { _choices.value = it }
            .onFailure { _error.value = it.message ?: "Could not load the collections" }
    }

    fun toggle(choice: CollectionChoice) {
        val now = !choice.member
        set(choice.id, now)
        scope.launch {
            attempt { service.setInCollection(choice.id, gameId, now) }
                .onSuccess {
                    _error.value = null
                    onChanged()
                }
                .onFailure { e ->
                    set(choice.id, !now)
                    _error.value = "Could not change ${choice.name}: ${e.message}"
                }
        }
    }

    /**
     * Makes a collection named [name] with the game in it. [onFailed] gets the name back, for the
     * field the screen cleared at once.
     */
    fun create(name: String, onFailed: (String) -> Unit = {}) {
        val wanted = name.trim()
        if (wanted.isEmpty()) return
        scope.launch {
            attempt {
                val id = service.createCollection(wanted)
                service.setInCollection(id, gameId, true)
                id
            }.onSuccess { id ->
                _error.value = null
                _choices.value = (_choices.value.orEmpty() + CollectionChoice(id, wanted, true))
                    .sortedBy { it.name.lowercase() }
                onChanged()
            }.onFailure { e ->
                _error.value = e.message ?: "Could not create the collection"
                onFailed(wanted)
            }
        }
    }

    private fun set(id: Int, member: Boolean) {
        _choices.value = _choices.value?.map { if (it.id == id) it.copy(member = member) else it }
    }
}
