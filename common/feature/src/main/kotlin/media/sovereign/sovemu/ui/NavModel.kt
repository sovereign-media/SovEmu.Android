package media.sovereign.sovemu.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.sovereign.sovemu.core.LaunchText
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.TvSignIn
import media.sovereign.sovemu.core.User
import media.sovereign.sovemu.core.badge
import media.sovereign.sovemu.core.groupByMaker

/** Which section a screen belongs to, lit in the navigation. */
enum class Section { Home, Search, Systems }

/** Where the navigation can go. */
class Nav(
    val home: () -> Unit,
    val search: () -> Unit,
    val system: (SystemSummary) -> Unit,
    /** Whether the CRT scan lines are on, and the switch. */
    val scanlines: () -> Boolean,
    val toggleScanlines: () -> Unit,
    /** Whether the menus' background music is on, and the switch. */
    val music: () -> Boolean,
    val toggleMusic: () -> Unit,
    /**
     * Minutes without input before the screensaver (0: never), and the switch through the
     * choices.
     */
    val screensaver: () -> Int,
    val nextScreensaver: () -> Unit,
    /** Whether games ask the TV for its game picture mode, and the switch. */
    val gameMode: () -> Boolean,
    val toggleGameMode: () -> Unit,
    val signOut: () -> Unit,
    /** *Sign in a TV*: approve a TV's code with this sign-in (phones and tablets). */
    val signInTv: () -> Unit = {},
    /** *Change user or server*: *Who's watching?*, with *Choose a server* one step on. */
    val accounts: () -> Unit = {},
    /** *Controllers*: every controller's player and buttons, for any system. */
    val controllers: () -> Unit = {},
)

/** Up to two initials for an account's avatar: "Bruce Lee" → "BL", "mum" → "M". */
fun initials(name: String): String {
    val parts = name.trim().split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
    val letters = if (parts.size > 1) {
        "${parts[0][0]}${parts[1][0]}"
    } else {
        (parts.firstOrNull() ?: "?").take(1)
    }
    return letters.uppercase()
}
