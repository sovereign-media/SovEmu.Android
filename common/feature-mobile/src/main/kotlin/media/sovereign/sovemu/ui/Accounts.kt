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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.sovereign.sovemu.core.KnownServer
import media.sovereign.sovemu.core.KnownServers
import media.sovereign.sovemu.core.LaunchText
import media.sovereign.sovemu.core.StoredAccount
import media.sovereign.sovemu.core.pickerFocusMoves

/**
 * *Who's watching?*, as Sovereign.Android's: a tile per account signed in on this device for
 * this server, then `Add user` (hidden at [StoredAccount.MAX_PER_SERVER]), three to a row, each
 * row centred. Under them *Change server*, with the server beside it, opens *Choose a server*.
 *
 * Focus starts on [focusOn] (the account used last, or the one signed in now when opened from the
 * account menu, whose tile reads [LaunchText.SIGNED_IN_NOW] under [current]). Every D-pad move is
 * stated per tile ([pickerFocusMoves]): a centred short row sits between the columns of a full
 * one, where the geometric search can land on the wrong row. Down off the bottom row reaches
 * *Change server*, and Up from it returns to the tile it came from.
 */
@Composable
fun WhoIsWatchingScreen(
    accounts: List<StoredAccount>,
    server: String,
    onChoose: (StoredAccount) -> Unit,
    onAddUser: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
    focusOn: String? = null,
    current: String? = null,
) {
    val canAdd = accounts.size < StoredAccount.MAX_PER_SERVER
    val tiles: List<StoredAccount?> = remember(accounts, canAdd) {
        accounts + if (canAdd) listOf(null) else emptyList()
    }
    val columns = LaunchText.PICKER_COLUMNS
    val requesters = remember(tiles.size) { List(tiles.size) { FocusRequester() } }
    val moves = remember(tiles.size) { pickerFocusMoves(tiles.size, columns) }
    val changeServer = remember { FocusRequester() }
    // The tile focus last sat on, for Up off Change server: read only in a focus move, so a
    // plain holder (snapshot state would recompose the grid as the cursor walked it).
    val last = remember { IntArray(1) }
    val start = accounts.indexOfFirst { it.id == focusOn }.coerceAtLeast(0)
    LaunchedEffect(requesters) {
        runCatching { requesters.getOrNull(start)?.requestFocus() }
    }
    val tile = 96.dp

    LaunchBackdrop(modifier) {
        Text(
            LaunchText.WHO_IS_WATCHING,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        tiles.chunked(columns).forEachIndexed { row, members ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                members.forEachIndexed { column, account ->
                    val index = row * columns + column
                    val move = moves[index]
                    val tileModifier = Modifier
                        .focusRequester(requesters[index])
                        .onFocusChanged { if (it.hasFocus) last[0] = index }
                        .focusProperties {
                            left = move.left?.let { requesters[it] } ?: FocusRequester.Cancel
                            right = move.right?.let { requesters[it] } ?: FocusRequester.Cancel
                            up = move.up?.let { requesters[it] } ?: FocusRequester.Cancel
                            down = move.down?.let { requesters[it] } ?: changeServer
                        }
                    if (account == null) {
                        AccountTile(
                            label = LaunchText.ADD_USER,
                            size = tile,
                            onClick = onAddUser,
                            modifier = tileModifier,
                            add = true,
                        )
                    } else {
                        AccountTile(
                            label = account.label,
                            size = tile,
                            onClick = { onChoose(account) },
                            modifier = tileModifier,
                            initials = account.initials,
                            caption = LaunchText.SIGNED_IN_NOW.takeIf { account.id == current },
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 480.dp),
        ) {
            SovButton(
                LaunchText.CHANGE_SERVER,
                onChangeServer,
                Modifier.focusRequester(changeServer)
                    .focusProperties {
                        up = requesters.getOrNull(last[0]) ?: FocusRequester.Default
                    },
                variant = Variant.Ghost,
                icon = Lucide.Server,
            )
            Text(
                server,
                color = Sov.Slate500,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** An account's tile: its disc (initials) and name; or, [add], `Add user`'s. */
@Composable
private fun AccountTile(
    label: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initials: String = "",
    caption: String? = null,
    add: Boolean = false,
) {
    // FocusCard draws inside its own bounds, so the tile states its size: the disc, the name, and
    // room for the caption.
    FocusCard(
        onClick,
        modifier.size(width = size + 24.dp, height = size + 88.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(size).clip(CircleShape).then(
                    if (add) {
                        Modifier.border(2.dp, Sov.Edge, CircleShape)
                    } else {
                        Modifier.background(
                            Brush.linearGradient(listOf(Sov.Accent, Color(0xFF1E3A8A))),
                        )
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (add) {
                    LucideIcon(Lucide.UserPlus, tint = Sov.Slate300, size = size / 3)
                } else {
                    Text(
                        initials,
                        color = Color.White,
                        fontSize = (size.value / 3).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                label,
                color = Sov.Slate100,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (caption != null) {
                Text(caption, color = Sov.Slate500, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * *Choose a server*, as Sovereign.Android's: every server the device holds an account for, the
 * one in use marked; choosing one moves the app there, and each row's remove button asks first,
 * naming everyone it signs out (Cancel has the cursor). `Add a server` opens the sign-in form for
 * a new address; at [StoredAccount.MAX_SERVERS] its place says how to free one. [error] says why
 * the last choice did not move the app; [busy] makes every control inert while one is in flight.
 */
@Composable
fun ChooseServerScreen(
    servers: List<KnownServer>,
    onChoose: (KnownServer) -> Unit,
    onRemove: (KnownServer) -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    busy: Boolean = false,
) {
    var confirming by remember { mutableStateOf<KnownServer?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(first) { runCatching { first.requestFocus() } }
    Box(modifier.fillMaxSize()) {
        LaunchBackdrop {
            Text(
                KnownServers.TITLE,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                KnownServers.HINT,
                color = Sov.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                servers.forEachIndexed { i, server ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // FocusCard draws inside its own bounds: the row states its height.
                        val initial = if (i == 0) Modifier.focusRequester(first) else Modifier
                        FocusCard(
                            { if (!busy) onChoose(server) },
                            Modifier.weight(1f).height(72.dp).then(initial),
                        ) {
                            Row(
                                Modifier.fillMaxSize().background(Sov.Panel)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                LucideIcon(Lucide.Server, tint = Sov.Slate300, size = 22.dp)
                                Column {
                                    Text(
                                        server.address,
                                        color = Sov.Slate100,
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(server.countLine, color = Sov.Slate500, fontSize = 13.sp)
                                }
                            }
                        }
                        SovButton(
                            KnownServers.REMOVE,
                            { if (!busy) confirming = server },
                            Modifier.padding(start = 8.dp),
                            variant = Variant.Ghost,
                            icon = Lucide.Trash2,
                        )
                    }
                }
                error?.let { ErrorLine(it) }
                if (KnownServers.canAddServer(servers.size)) {
                    SovButton(
                        KnownServers.ADD_A_SERVER,
                        { if (!busy) onAddServer() },
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        variant = Variant.Outline,
                        icon = Lucide.Server,
                    )
                } else {
                    Text(KnownServers.CAP_LINE, color = Sov.Slate500, fontSize = 14.sp)
                }
            }
        }
        confirming?.let { server ->
            RemoveServerConfirm(
                server,
                onCancel = { confirming = null },
                onRemove = {
                    confirming = null
                    onRemove(server)
                },
            )
        }
    }
}

/**
 * Asks before removing [server], naming everyone it signs out. Drawn in the activity's window
 * (never a Dialog window, where Back was lost on a Mi TV); it holds focus, Cancel first, and
 * Back cancels.
 */
@Composable
private fun RemoveServerConfirm(server: KnownServer, onCancel: () -> Unit, onRemove: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(cancel) { runCatching { cancel.requestFocus() } }
    BackHandler(onBack = onCancel)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Sov.Panel)
                .border(1.dp, Sov.Edge, RoundedCornerShape(16.dp))
                .padding(28.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                server.removeTitle,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(server.removeMessage, color = Sov.Slate300, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SovButton(
                    KnownServers.CANCEL,
                    onCancel,
                    Modifier.focusRequester(cancel),
                    variant = Variant.Outline,
                )
                SovButton(KnownServers.REMOVE, onRemove, icon = Lucide.Trash2)
            }
        }
    }
}
