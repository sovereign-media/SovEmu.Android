package media.sovereign.sovemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Which item of a screen last had the focus, kept with the screen's saved state. Coming back to
 * the screen (Back from a system or a game) puts the focus back there, as a console menu does.
 * Plain fields, not snapshot state: remembering the focus never recomposes anything.
 */
class FocusMemory internal constructor(var last: String?) {
    /** The item to focus once it is composed again; null when there is none to restore. */
    var pending: String? = last

    /** Whether nothing has been focused on this screen yet (a fresh visit): focus the default. */
    val fresh: Boolean get() = last == null

    /** The items composed right now, by key. */
    internal val requesters = HashMap<String, FocusRequester>()

    /** Focuses [key] if it is composed (on screen or just off it); else it takes the focus when it is. */
    fun focus(key: String): Boolean {
        val requester = requesters[key]
        if (requester == null || !runCatching { requester.requestFocus(); true }.getOrDefault(false)) {
            pending = key
            return false
        }
        return true
    }
}

@Composable
fun rememberFocusMemory(): FocusMemory =
    rememberSaveable(saver = Saver<FocusMemory, String>(save = { it.last ?: "" }, restore = { FocusMemory(it.ifEmpty { null }) })) {
        FocusMemory(null)
    }

/**
 * Remembers in [memory] that [key] had the focus, and takes the focus back when the screen is
 * returned to with [key] last focused. [initial] takes it on a fresh visit (the screen's default).
 */
@Composable
fun Modifier.remembersFocus(memory: FocusMemory, key: String, initial: Boolean = false): Modifier {
    val requester = remember { FocusRequester() }
    DisposableEffect(key) {
        memory.requesters[key] = requester
        onDispose { if (memory.requesters[key] === requester) memory.requesters.remove(key) }
    }
    LaunchedEffect(key) {
        if (memory.pending == key || (initial && memory.fresh && memory.pending == null)) {
            memory.pending = null
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) memory.last = key }
}
