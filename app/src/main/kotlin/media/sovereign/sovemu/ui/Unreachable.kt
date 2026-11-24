package media.sovereign.sovemu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What Home shows when the server could not be reached: why, and the two ways on from there.
 * Sign out is always offered here because it needs nothing from the server (the sign-in is
 * forgotten on the device first), so an app pointed at a server that has gone, or moved, is
 * never stuck on this screen.
 */
@Composable
fun Unreachable(
    server: String,
    message: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(retry) { retry.requestFocus() }
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Can't reach the server", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(server, color = Sov.Slate300, fontSize = 15.sp, textAlign = TextAlign.Center)
            Text(message, color = Sov.Red300, fontSize = 14.sp, textAlign = TextAlign.Center)
            Text(
                "Try again, or sign out to sign in to another server.",
                color = Sov.Slate400,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SovButton("Try again", onRetry, Modifier.focusRequester(retry))
                SovButton("Sign out", onSignOut, variant = Variant.Outline, icon = Lucide.LogOut)
            }
        }
    }
}
