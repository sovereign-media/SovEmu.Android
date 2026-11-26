package media.sovereign.sovemu.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a search across every system answered. */
sealed interface SearchResults {
    data class Found(val games: List<Game>) : SearchResults
    data class Failed(val message: String) : SearchResults
}

/**
 * Search across every system, as the search screen types it: asked after a pause in typing
 * ([delayMs]), and a new query cancels the one before, so only the latest answer is shown.
 * [results] is null for an empty query (or before its answer).
 */
class GameSearch(
    private val search: suspend (String) -> List<Game>,
    private val scope: CoroutineScope,
    private val delayMs: Long = DELAY_MS,
) {
    private val _results = MutableStateFlow<SearchResults?>(null)
    val results: StateFlow<SearchResults?> = _results.asStateFlow()
    private var job: Job? = null

    fun query(text: String) {
        job?.cancel()
        val q = text.trim()
        if (q.isEmpty()) {
            _results.value = null
            return
        }
        job = scope.launch {
            delay(delayMs)
            _results.value = attempt { search(q) }.fold(
                { SearchResults.Found(it) },
                { SearchResults.Failed(it.message ?: "Search failed") },
            )
        }
    }

    companion object {
        /** The pause in typing before asking. */
        const val DELAY_MS = 250L
    }
}
