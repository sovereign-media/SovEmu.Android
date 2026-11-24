package media.sovereign.sovemu.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A panel on the right edge over a dimmed screen, shown while [item] is set: it slides in and
 * slides out when [item] goes back to null, still showing the last item. A tap on the dimmed
 * screen closes it; Back is the screen's to handle, since the screen knows where the focus goes
 * next.
 *
 * Nothing behind an open panel can have the focus: the D-pad cannot leave it, and wherever else
 * the focus lands (the grid recomposing as a game is left, a restored card, the rail) it is
 * brought back to [focus], the panel's first button. Off while a drawer is over it ([trapFocus]).
 */
@Composable
internal fun <T : Any> SidePanel(
    item: T?,
    onClose: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
    trapFocus: Boolean = true,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    // The last item stays drawn while the panel slides out: a plain holder, not state (writing
    // state read in this same composition would recompose it again).
    val last = remember { arrayOfNulls<Any>(1) }
    if (item != null) last[0] = item
    val open = item != null
    // Whether the focus is in the panel, as its focus group reports.
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(open, trapFocus, inside) {
        if (!open || !trapFocus || inside) return@LaunchedEffect
        // A moment first: the screen may be placing it on a button of its own (back from a
        // drawer); and the panel's buttons attach a frame or two after it opens.
        repeat(2) { withFrameNanos { } }
        while (!focus.requestFocus()) withFrameNanos { }
    }
    AnimatedVisibility(item != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            item != null,
            enter = slideInHorizontally(tween(260)) { it },
            exit = slideOutHorizontally(tween(200)) { it },
        ) {
            @Suppress("UNCHECKED_CAST")
            val shown = (item ?: last[0] as T?) ?: return@AnimatedVisibility
            Column(
                Modifier.fillMaxHeight().widthIn(max = 448.dp).fillMaxWidth()
                    .background(Sov.Surface.copy(alpha = 0.96f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f))
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .onFocusChanged { inside = it.hasFocus }
                    // Traps the focus only while open: sliding out, it must let the focus go
                    // back to the card, and a drawer over it ([trapFocus] off) must be able to take it.
                    .focusProperties { onExit = { if (item != null && trapFocus) cancelFocusChange() } }
                    .focusGroup(),
            ) { content(shown) }
        }
    }
}

/** The panel's close button, over its picture's top-right corner (touch only: Back closes on a TV). */
@Composable
internal fun PanelClose(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.focusProperties { canFocus = false }.statusBarsPadding().padding(12.dp).size(36.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) { LucideIcon(Lucide.X) }
}
