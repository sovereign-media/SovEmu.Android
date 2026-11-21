package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Asks before Back on the home screen leaves the app, since one press too many on a remote
 * otherwise drops back to the TV's launcher. Drawn in the activity's window like the in-game
 * menu (Back was lost in a `Dialog` window on a Mi TV); it holds the focus, starts on Stay so a
 * repeated press never quits by accident, and Back again closes it.
 */
@Composable
fun QuitConfirm(onStay: () -> Unit, onQuit: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onStay)
    val stay = remember { FocusRequester() }
    // The buttons are composed in this frame; focus them once they are attached.
    LaunchedEffect(stay) {
        repeat(10) {
            withFrameNanos { }
            if (stay.requestFocus()) return@LaunchedEffect
        }
    }
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Sov.Panel)
                .border(1.dp, Sov.Edge, RoundedCornerShape(16.dp))
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Quit SovEmu?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SovButton("Stay", onStay, Modifier.focusRequester(stay))
                SovButton("Quit", onQuit, variant = Variant.Outline)
            }
        }
    }
}
