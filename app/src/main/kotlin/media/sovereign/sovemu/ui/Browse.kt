package media.sovereign.sovemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import media.sovereign.sovemu.ApiException
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.parseColor

private val Surface = Color(0xFF0B0D12)
private val Panel = Color(0xFF151922)
private val Muted = Color(0xFF8B93A7)

fun SystemSummary.colour() = Color(parseColor(color))

/** Server address and sign-in. First-run setup happens in the web client. */
@Composable
fun ConnectScreen(settings: Settings, api: SovEmuApi, onSignedIn: () -> Unit) {
    var server by remember { mutableStateOf(settings.serverUrl.ifEmpty { "http://" }) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Surface).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(380.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SovEmu", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("Connect to your SovEmu server.", color = Muted)
            OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = Color(0xFFFCA5A5)) }
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    error = null
                    settings.serverUrl = server
                    scope.launch {
                        try {
                            if (api.status().setupRequired) {
                                error = "Finish first-run setup in a browser at $server first."
                            } else {
                                api.login(username, password)
                                onSignedIn()
                            }
                        } catch (e: Exception) {
                            error = (e as? ApiException)?.message ?: "Cannot reach the server: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Signing in…" else "Sign in") }
        }
    }
}

/** A horizontal carousel of systems with games, tinted by the focused system. */
@Composable
fun SystemsScreen(api: SovEmuApi, onOpen: (SystemSummary) -> Unit, onSignedOut: () -> Unit) {
    var systems by remember { mutableStateOf<List<SystemSummary>?>(null) }
    var focused by remember { mutableStateOf<SystemSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            systems = api.systems().filter { it.gameCount > 0 }
            focused = systems?.firstOrNull()
        } catch (e: ApiException) {
            if (e.status == 401) onSignedOut() else error = e.message
        } catch (e: Exception) {
            error = "Cannot reach the server: ${e.message}"
        }
    }
    val tint = focused?.colour() ?: Color(0xFF4169E1)
    Box(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.45f), Surface), radius = 1400f))
            .safeDrawingPadding(),
    ) {
        Row(Modifier.padding(24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SovEmu", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { scope.launch { runCatching { api.logout() }; onSignedOut() } }) { Text("Sign out", color = Muted) }
        }
        val list = systems
        when {
            error != null -> Text(error!!, color = Muted, modifier = Modifier.align(Alignment.Center))
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            list.isEmpty() -> Text("No games yet: add ROMs to the server's library.", color = Muted, modifier = Modifier.align(Alignment.Center))
            else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp)) {
                    items(list, key = { it.id }) { system ->
                        val isFocused = focused?.id == system.id
                        Box(
                            Modifier
                                .size(if (isFocused) 240.dp else 200.dp, if (isFocused) 150.dp else 125.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Brush.linearGradient(listOf(system.colour(), system.colour().copy(alpha = 0.4f))))
                                .border(if (isFocused) 3.dp else 0.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
                                .onFocusChanged { if (it.isFocused) focused = system }
                                .focusable()
                                .clickable { onOpen(system) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(system.short, fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
                focused?.let {
                    Text(it.name, fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text("${it.manufacturer} · ${it.year} · ${it.gameCount} game${if (it.gameCount == 1) "" else "s"}", color = Color(0xFFCBD5E1))
                }
            }
        }
    }
}

/** A system's games: a list on the left, the focused game's details on the right. */
@Composable
fun GamesScreen(api: SovEmuApi, system: SystemSummary, onPlay: (Game) -> Unit, onBack: () -> Unit) {
    var games by remember { mutableStateOf<List<Game>?>(null) }
    var focused by remember { mutableStateOf<Game?>(null) }
    LaunchedEffect(system.id) {
        games = runCatching { api.games(system.id) }.getOrDefault(emptyList())
        focused = games?.firstOrNull()
    }
    val colour = system.colour()
    Row(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(colour.copy(alpha = 0.35f), Surface), radius = 1600f))
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1.1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Systems", color = Muted) }
                Text(system.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            val list = games
            if (list == null) CircularProgressIndicator() else LazyColumn(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.3f)).padding(8.dp),
            ) {
                items(list, key = { it.id }) { game ->
                    val isFocused = focused?.id == game.id
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isFocused) colour else Color.Transparent)
                            .onFocusChanged { if (it.isFocused) focused = game }
                            .focusable()
                            .clickable { onPlay(game) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(game.title, fontSize = 18.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (game.tags.isNotEmpty()) Text(game.tags, fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }
        focused?.let { game ->
            Column(
                Modifier.weight(1f).align(Alignment.CenterVertically).clip(RoundedCornerShape(16.dp)).background(Panel).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(colour, colour.copy(alpha = 0.3f)))),
                    contentAlignment = Alignment.Center,
                ) { Text(system.short, fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color.White) }
                Text(game.title, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White)
                if (game.tags.isNotEmpty()) Text(game.tags, color = Muted)
                Text("Played ${game.playCount ?: 0} time${if (game.playCount == 1) "" else "s"}", color = Muted)
                Button(onClick = { onPlay(game) }, modifier = Modifier.fillMaxWidth()) { Text("Play") }
            }
        }
    }
    MaterialTheme.typography
}
