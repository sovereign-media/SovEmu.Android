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

/** A fixed [height] and the width [width] has now, read at layout time (not in composition). */
private fun Modifier.animatedWidth(width: State<Dp>, height: Dp): Modifier =
    layout { measurable, _ ->
        val w = width.value.roundToPx()
        val h = height.roundToPx()
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(w, h) { placeable.place(0, 0) }
    }.clipToBounds()

/**
 * A TV screen inside the navigation, after the web's SideNav: a floating dock on the left edge
 * (icons at rest, labelled pills while it has the focus) under the wordmark.
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
                .padding(start = 64.dp),
        ) { content() }
        Brand(Modifier.statusBarsPadding().padding(16.dp).clickable(onClick = nav.home))
        Dock(
            section,
            nav,
            user,
            systems,
            Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
        )
    }
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
                "Controllers",
                onClick = {
                    expanded = false
                    nav.controllers()
                },
                leading = { LucideIcon(Lucide.Gamepad2, tint = Sov.Slate300, size = 16.dp) },
            )
            MenuRow(
                LaunchText.CHANGE_USER_OR_SERVER,
                onClick = {
                    expanded = false
                    nav.accounts()
                },
                leading = { LucideIcon(Lucide.Users, tint = Sov.Slate300, size = 16.dp) },
            )
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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(56.dp, (-44).dp),
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

/** A menu row. */
@Composable
private fun MenuRow(
    label: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    trailing: String? = null,
) = DropdownMenuItem(
    text = { Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    onClick = onClick,
    leadingIcon = leading,
    trailingIcon = trailing?.let { { Text(it, color = Sov.Slate500, fontSize = 12.sp) } },
    colors = MenuDefaults.itemColors(textColor = Sov.Slate300),
    modifier = Modifier.height(40.dp),
)
