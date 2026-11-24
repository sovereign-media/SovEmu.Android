package media.sovereign.sovemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameIndex
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.formatAgo

/**
 * Home's panel for a game in "Continue playing": where the player left off (the auto save's
 * screenshot), when, and Continue, which takes the focus; "Game details" goes on to the game's
 * own panel on its system's page (Start over, the other saves, the description). Back closes it.
 */
@Composable
internal fun ContinuePanel(
    game: Game?,
    system: SystemSummary?,
    onClose: () -> Unit,
    onPlay: (Start) -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(game?.id) {
        if (game == null) return@LaunchedEffect
        // Its buttons attach a frame or two after it opens (or after coming back to Home).
        repeat(10) {
            withFrameNanos { }
            if (first.requestFocus()) return@LaunchedEffect
        }
    }
    SidePanel(game, onClose, modifier) { g ->
        Box {
            LeftOff(g, system)
            PanelClose(onClose, Modifier.align(Alignment.TopEnd))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val short = system?.short ?: g.system.uppercase()
            Column {
                SystemBadge(short, system?.colour() ?: Sov.Accent)
                Text(
                    g.title, color = Color.White, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 10.dp),
                )
                g.release?.let { Text(it, color = Sov.Amber200, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp)) }
            }
            val now = remember(g.id) { System.currentTimeMillis() / 1000 }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                g.autoSave?.let { Fact(Lucide.Save, "Saved ${formatAgo(it.savedAt.toLong(), now)}") }
                if (g.playCount > 0) {
                    Fact(Lucide.History, "Played ${g.playCount}× · last ${formatAgo(GameIndex.playedAt(g.lastPlayedAt), now)}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A game played without an auto save (it never got one) has nothing to continue.
                if (g.autoSave != null) {
                    SovButton("Continue", { onPlay(Start.Continue) }, Modifier.focusRequester(first), icon = Lucide.Play, filledIcon = true)
                } else {
                    SovButton("Play", { onPlay(Start.New) }, Modifier.focusRequester(first), icon = Lucide.Play, filledIcon = true)
                }
                SovButton("Game details", onDetails, variant = Variant.Outline, icon = Lucide.Info)
            }
        }
    }
}

/**
 * The auto save's screenshot, whole (letterboxed in a 16:9 frame, so Continue fits on a TV
 * screen without scrolling) and on black like a TV; the game's artwork if there is none.
 */
@Composable
private fun LeftOff(g: Game, system: SystemSummary?) {
    val shot = g.autoSave?.screenshotUrl
    Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
        if (shot != null) {
            Screenshot(shot, Modifier.fillMaxSize())
        } else {
            val brush = remember(system?.color) { tile(system?.colour() ?: Sov.Accent) }
            Box(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.Center) {
                Text(system?.short ?: g.system.uppercase(), color = Color.White.copy(alpha = 0.8f), fontSize = 60.sp, fontWeight = FontWeight.Black)
                Art(g.image ?: g.thumb, Modifier.fillMaxSize(), scale = if (g.image != null) ContentScale.Crop else ContentScale.Fit)
            }
        }
    }
}

/** A line of the panel's facts, as the game panel's. */
@Composable
private fun Fact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LucideIcon(icon, tint = Sov.Slate500, size = 16.dp)
        Text(text, color = Sov.Slate300, fontSize = 14.sp)
    }
