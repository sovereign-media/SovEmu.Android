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
)

/** A fixed [height] and the width [width] has now, read at layout time (not in composition). */
private fun Modifier.animatedWidth(width: State<Dp>, height: Dp): Modifier =
    layout { measurable, _ ->
        val w = width.value.roundToPx()
        val h = height.roundToPx()
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(w, h) { placeable.place(0, 0) }
    }.clipToBounds()

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

/**
 * A screen inside the navigation, after the web's SideNav: on a TV or a wide screen a floating
 * dock on the left edge (icons at rest, labelled pills while it has the focus) under the
 * wordmark; on a phone a tab bar along the bottom.
 */
@Composable
fun NavFrame(
    section: Section,
    nav: Nav,
    user: User?,
    systems: List<SystemSummary>,
    modifier: Modifier = Modifier,
    /**
     * The screen paints its own full-screen background: skip this one (one less fill per
     * frame).
     */
    paintsBackground: Boolean = false,
    content: @Composable () -> Unit,
) {
    val wide = LocalForm.current.wide
    Box(
        modifier
            .fillMaxSize()
            .then(if (paintsBackground) Modifier else Modifier.background(Sov.Surface)),
    ) {
        // The server's background over the surface colour, as on the web: a transparent image,
        // cropped to cover the screen.
        if (!paintsBackground) {
            LocalBackground.current?.let { Art(it, Modifier.fillMaxSize(), ContentScale.Crop) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = if (wide) 64.dp else 0.dp, bottom = if (wide) 0.dp else 56.dp),
        ) { content() }
        if (wide) {
            Brand(Modifier.statusBarsPadding().padding(16.dp).clickable(onClick = nav.home))
            Dock(
                section,
                nav,
                user,
                systems,
                Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
            )
        } else {
            TabBar(section, nav, user, systems, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
fun Brand(modifier: Modifier = Modifier, size: Int = 18) = Row(
    modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    LucideIcon(Lucide.Gamepad2, tint = Sov.Accent, size = (size + 6).dp)
    Text("Sov", color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Black)
    Text("Emu", color = Sov.Accent, fontSize = size.sp, fontWeight = FontWeight.Black)
}

@Composable
private fun Dock(
    section: Section,
    nav: Nav,
    user: User?,
    systems: List<SystemSummary>,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    // Read at layout time (animatedWidth), so opening the dock re-lays it out on each frame
    // without recomposing its entries.
    val width = animateDpAsState(if (open) 192.dp else 44.dp, tween(200), label = "dock")
    Column(
        modifier
            .onFocusChanged { open = it.hasFocus }
            .clip(RoundedCornerShape(24.dp))
            .background(Sov.Surface.copy(alpha = 0.9f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DockEntry(Lucide.House, "Home", section == Section.Home, width, open, onClick = nav.home)
        DockEntry(
            Lucide.Search,
            "Search",
            section == Section.Search,
            width,
            open,
            onClick = nav.search,
        )
        SystemsMenu(systems, nav) { show ->
            DockEntry(
                Lucide.Gamepad2,
                "Systems",
                section == Section.Systems,
                width,
                open,
                onClick = show,
            )
        }
        Box(
            Modifier
                .animatedWidth(width, 9.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .background(Color.White.copy(alpha = 0.1f)),
        )
        // Always there, user or not: signing out is the way to another server, so it must not
        // wait for this one to answer.
        val name = user?.username
        AccountMenu(name, nav) { show ->
            DockEntry(
                Lucide.LogOut,
                name ?: "Account",
                false,
                width,
                open,
                avatar = name?.let(::initials),
                onClick = show,
            )
        }
    }
}

@Composable
private fun DockEntry(
    icon: ImageVector?,
    label: String,
    current: Boolean,
    width: State<Dp>,
    open: Boolean,
    avatar: String? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier
            .animatedWidth(width, 44.dp)
            .clip(CircleShape)
            .background(
                when {
                    current -> Sov.Accent
                    focused -> Color.White.copy(alpha = 0.1f)
                    else -> Color.Transparent
                },
            )
            .borderIf(focused, 2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
            // Closed, the dock shows only icons: the label still names the entry.
            .semantics { if (!open) contentDescription = label }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (avatar != null) {
                Avatar(avatar)
            } else {
                LucideIcon(icon!!, tint = if (current || focused) Color.White else Sov.Slate400)
            }
        }
        if (open) {
            Text(
                label,
                color = if (current || focused) Color.White else Sov.Slate400,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}

@Composable
private fun Avatar(letters: String) = Box(
    Modifier.size(28.dp).clip(CircleShape).background(Sov.Accent),
    contentAlignment = Alignment.Center,
) {
    Text(letters, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun TabBar(
    section: Section,
    nav: Nav,
    user: User?,
    systems: List<SystemSummary>,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Sov.Surface.copy(alpha = 0.95f))
            .navigationBarsPadding()
            .height(56.dp)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TabItem(
            Lucide.House,
            "Home",
            section == Section.Home,
            Modifier.weight(1f),
            onClick = nav.home,
        )
        TabItem(
            Lucide.Search,
            "Search",
            section == Section.Search,
            Modifier.weight(1f),
            onClick = nav.search,
        )
        Box(Modifier.weight(1f)) {
            SystemsMenu(systems, nav) { show ->
                TabItem(
                    Lucide.Gamepad2,
                    "Systems",
                    section == Section.Systems,
                    Modifier.fillMaxWidth(),
                    onClick = show,
                )
            }
        }
        Box(Modifier.weight(1f)) {
            val name = user?.username
            AccountMenu(name, nav) { show ->
                TabItem(
                    Lucide.LogOut,
                    name ?: "Account",
                    false,
                    Modifier.fillMaxWidth(),
                    onClick = show,
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    icon: ImageVector,
    label: String,
    current: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) = Column(
    modifier
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
) {
    LucideIcon(icon, tint = if (current) Color.White else Sov.Slate400)
    Text(
        label,
        color = if (current) Color.White else Sov.Slate400,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

/** The Systems menu: systems with games, under their makers, each with its count or a status. */
@Composable
private fun SystemsMenu(
    systems: List<SystemSummary>,
    nav: Nav,
    anchor: @Composable (show: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        anchor { expanded = true }
        val groups = remember(systems) { groupByMaker(systems.filter { it.gameCount > 0 }) }
        Menu(expanded, { expanded = false }, "Systems") {
            if (groups.isEmpty()) {
                Text(
                    "No games yet.",
                    color = Sov.Slate500,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp, 8.dp),
                )
            }
            for ((maker, group) in groups) {
                Text(
                    maker.uppercase(),
                    color = Sov.Slate500,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                for (s in group) {
                    MenuRow(s.name, onClick = {
                        expanded = false
                        nav.system(s)
                    }, leading = {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(s.colour()))
                    }, trailing = s.badge() ?: s.gameCount.toString())
                }
            }
        }
    }
}

@Composable
private fun AccountMenu(
    username: String?,
    nav: Nav,
    anchor: @Composable (show: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        anchor { expanded = true }
        Menu(expanded, { expanded = false }, username?.let { "Signed in as $it" } ?: "Signed in") {
            MenuRow(
                if (nav.music()) "Music: on" else "Music: off",
                onClick = nav.toggleMusic,
                leading = { LucideIcon(Lucide.Music, tint = Sov.Slate300, size = 16.dp) },
            )
            MenuRow(
                nav.screensaver().let {
                    if (it == 0) "Screensaver: off" else "Screensaver: after $it min"
                },
                onClick = nav.nextScreensaver,
                leading = { LucideIcon(Lucide.Play, tint = Sov.Slate300, size = 16.dp) },
            )
            MenuRow(
                if (nav.scanlines()) "Scan lines: on" else "Scan lines: off",
                onClick = nav.toggleScanlines,
                leading = { LucideIcon(Lucide.Menu, tint = Sov.Slate300, size = 16.dp) },
            )
            MenuRow(
                if (nav.gameMode()) "TV game mode: on" else "TV game mode: off",
                onClick = nav.toggleGameMode,
                leading = { LucideIcon(Lucide.Gamepad2, tint = Sov.Slate300, size = 16.dp) },
                detail = GAME_MODE_DETAIL,
            )
            // A TV signs itself in with a code; this approves one from a phone or tablet.
            if (!LocalForm.current.tv) {
                MenuRow(
                    TvSignIn.TITLE,
                    onClick = {
                        expanded = false
                        nav.signInTv()
                    },
                    leading = { LucideIcon(Lucide.Tv, tint = Sov.Slate300, size = 16.dp) },
                )
            }
            MenuRow(
                "Sign out",
                onClick = {
                    expanded = false
                    nav.signOut()
                },
                leading = { LucideIcon(Lucide.LogOut, tint = Sov.Slate300, size = 16.dp) },
            )
        }
    }
}

/** A menu pane: the web's dropdown (panel colour, edge, heading). */
@Composable
private fun Menu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    heading: String,
    content: @Composable () -> Unit,
) {
    val wide = LocalForm.current.wide
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = if (wide) DpOffset(56.dp, (-44).dp) else DpOffset(0.dp, 0.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = Sov.Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Sov.Edge),
        modifier = Modifier.widthIn(min = 208.dp).heightIn(max = 480.dp),
    ) {
        Text(
            heading.uppercase(),
            color = Sov.Slate500,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        HorizontalDivider(color = Sov.Edge)
        content()
    }
}

/** What the TV game mode setting trades, shown under it. */
internal const val GAME_MODE_DETAIL =
    "Less input lag, for the whole app. Some TVs blink once as it switches."

/** A menu row; a [detail] goes on a second, smaller line (a setting's trade-off). */
@Composable
private fun MenuRow(
    label: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    trailing: String? = null,
    detail: String? = null,
) = DropdownMenuItem(
    text = {
        Column {
            Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(
                    detail,
                    color = Sov.Slate500,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                )
            }
        }
    },
    onClick = onClick,
    leadingIcon = leading,
    trailingIcon = trailing?.let { { Text(it, color = Sov.Slate500, fontSize = 12.sp) } },
    colors = MenuDefaults.itemColors(textColor = Sov.Slate300),
    modifier = if (detail == null) {
        Modifier.height(40.dp)
    } else {
        Modifier.heightIn(min = 56.dp).widthIn(max = 320.dp)
    },
)
