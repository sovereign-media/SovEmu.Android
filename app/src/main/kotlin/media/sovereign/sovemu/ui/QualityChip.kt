package media.sovereign.sovemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.sovereign.sovemu.core.QualityNotice

/** The glyph for a notice's icon name; none for a name this app does not know. */
private fun glyphFor(icon: String): ImageVector? = when (icon) {
    "wifi-alert" -> Lucide.WifiAlert
    "wifi-up" -> Lucide.WifiUp
    "gauge" -> Lucide.Gauge
    else -> null
}

private val ChipShape = RoundedCornerShape(20.dp)

/**
 * The notice of a change in the stream's quality: Sovereign's player message chip on a phone (a
 * glyph before the line, gray-950 at 75% under a 2 dp white/40 edge, which reads on a bright
 * picture and on black alike; two lines at most, following the system text size). The caller
 * puts it at the very top edge of the screen, not Sovereign's 12% down, so it stays out of the
 * game. It never takes focus.
 */
@Composable
fun QualityChip(notice: QualityNotice, modifier: Modifier = Modifier) {
    Row(
        modifier
            .widthIn(max = 560.dp)
            .heightIn(min = 40.dp)
            .clip(ChipShape)
            .background(Color(0xBF030712))
            .border(2.dp, Color.White.copy(alpha = 0.4f), ChipShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyphFor(notice.icon)?.let { LucideIcon(it) }
        Text(
            notice.line,
            color = Color.White,
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
