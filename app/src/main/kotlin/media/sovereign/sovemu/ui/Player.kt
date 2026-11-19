package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import media.sovereign.sovemu.Sessions
import media.sovereign.sovemu.attempt
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.Controller
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.Stream
import media.sovereign.sovemu.StreamView
import media.sovereign.sovemu.WebRtc
import media.sovereign.sovemu.core.Button
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.MappingBook
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.SystemSummary
import org.webrtc.PeerConnection

/** Systems whose picture keeps its own shape rather than a TV's 4:3. */
private val HANDHELDS = setOf("gb", "gbc", "gba", "gamegear")

/** How a game starts: from its auto save, from scratch, or asking when there is an auto save. */
enum class Start { Continue, New, Ask }

/**
 * The player: starts the session, streams it, and shows on-screen controls when the device has
 * nothing else to play with ([touchControls], from [media.sovereign.sovemu.InputPresence]).
 *
 * Every way out goes through the in-game menu (resume, saves, controllers, quit): Back, the
 * guide/home button, Select+Start, or the on-screen menu button. Leaving stops the session,
 * even one still starting ([Sessions]); the worker writes its saves first.
 */
@Composable
fun PlayerScreen(
    api: SovEmuApi,
    sessions: Sessions,
    system: SystemSummary,
    game: Game,
    start: Start,
    controller: Controller,
    touchControls: Boolean,
    onEnded: () -> Unit,
    onExit: () -> Unit,
    /** Paused (the in-game menu or the controllers screen is open): the screensaver may start. */
    onPaused: (Boolean) -> Unit = {},
) {
    val paused by rememberUpdatedState(onPaused)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SessionInfo?>(null) }
    var status by remember { mutableStateOf("Starting ${game.title}…") }
    var menuOpen by remember { mutableStateOf(false) }
    var stream by remember { mutableStateOf<Stream?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var controllersOpen by remember { mutableStateOf(false) }
    val book = remember(system.id) { MappingBook(system.id) }
    var controls by remember { mutableStateOf(Controls()) }
    /** Waiting for Continue or Start over (there is an auto save and the player was not asked yet). */
    var choice by remember { mutableStateOf<CompletableDeferred<Start>?>(null) }
    // Started at once, and owned by the app: leaving now still stops it once it exists.
    val starting = remember(game.id) { sessions.start(game.id) }

    // The whole frame, scaled into the box below: never cropped (StreamView).
    val renderer = remember { StreamView(context, WebRtc.egl.eglBaseContext) }

    LaunchedEffect(game.id) {
        // The mappings, labels and the question (if any) come while the session starts, rather
        // than each adding a round trip before the game.
        launch {
            attempt { book.refresh(api::controllerMappings) }
                .onSuccess { controller.mixer.setMappings(book.forSystem) }
                .onFailure { e -> toast = "Default controls: ${e.message}" }
        }
        // The system's labels and the diagrams, for the controllers screen.
        launch { attempt { api.controls(system.id) }.onSuccess { (sys, layouts) -> controls = Controls(sys, layouts) } }
        val ice = async { attempt { api.iceServers() }.getOrDefault(emptyList()) }
        try {
            val how = if (start == Start.Ask) {
                val saves = attempt { api.states(game.id) }.getOrDefault(emptyList())
                if (saves.any { it.slot == 0 }) CompletableDeferred<Start>().also { choice = it }.await().also { choice = null } else Start.New
            } else {
                start
            }
            val session = starting.await()
            if (how == Start.Continue) attempt { api.loadState(session.id, 0) }.onFailure { e -> toast = "Could not load: ${e.message}" }
            info = session
            status = ""
            // WebRTC's factory and peer connection are slow to build: not on the main thread.
            // Built whatever happens, so it is closed whatever happens.
            val s = withContext(NonCancellable + Dispatchers.Default) {
                Stream(context, api, session.id, ice.await(), controller, renderer) { state ->
                    if (state == PeerConnection.PeerConnectionState.FAILED) status = "Connection failed"
                }
            }
            if (!isActive) {
                s.close()
                return@LaunchedEffect
            }
            stream = s
            s.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Could not start: ${e.message}"
        }
    }

    // Select+Start (polled from the mixer), and the guide button or on-screen menu button.
    LaunchedEffect(Unit) {
        while (true) {
            if (!menuOpen && !controllersOpen && controller.menuCombo) menuOpen = true
            delay(50)
        }
    }
    LaunchedEffect(Unit) {
        controller.menuRequests.collect { if (!controllersOpen) menuOpen = !menuOpen }
    }
    // With the menu open the game gets nothing, and controllers navigate the menu instead.
    LaunchedEffect(menuOpen, controllersOpen, stream) {
        val isPaused = menuOpen || controllersOpen
        stream?.suspended = isPaused
        controller.toGame = !isPaused
        paused(isPaused)
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    val ended by rememberUpdatedState(onEnded)
    DisposableEffect(Unit) {
        onDispose {
            stream?.close()
            renderer.release()
            sessions.stop(starting) { ended() }
        }
    }

    // Before the game is up there is nothing to resume: Back leaves.
    BackHandler { if (info == null) onExit() else menuOpen = !menuOpen }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The console's shape, centred, as on the web: 4:3 for a TV console, the screen's own for a
        // handheld. The whole frame is scaled into this box, never cropped.
        val aspect = info?.let { if (system.id in HANDHELDS && it.height > 0) it.width.toFloat() / it.height else null } ?: (4f / 3f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView({ renderer }, Modifier.aspectRatio(aspect))
        }
        if (status.isNotEmpty()) Splash(game, system, status, choice)
        if (touchControls && !menuOpen && !controllersOpen && status.isEmpty()) {
            TouchController(controller, Modifier.fillMaxSize().safeDrawingPadding())
        }
        toast?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (controllersOpen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                ControllersScreen(api, controller, book, controls, system.short) {
                    controllersOpen = false
                    menuOpen = true
                }
            }
        } else if (menuOpen) {
            InGameMenu(
                game, system,
                onResume = { menuOpen = false },
                onSave = {
                    scope.launch {
                        toast = info?.let { s -> attempt { api.saveState(s.id, 1) }.fold({ "Saved to slot 1" }, { e -> "Save failed: ${e.message}" }) }
                        menuOpen = false
                    }
                },
                onLoad = {
                    scope.launch {
                        toast = info?.let { s -> attempt { api.loadState(s.id, 1) }.fold({ "Loaded slot 1" }, { e -> "Load failed: ${e.message}" }) }
                        menuOpen = false
                    }
                },
                onLoadAuto = {
                    scope.launch {
                        toast = info?.let { s -> attempt { api.loadState(s.id, 0) }.fold({ "Loaded the auto save" }, { e -> "No auto save: ${e.message}" }) }
                        menuOpen = false
                    }
                },
                onControllers = {
                    menuOpen = false
                    controllersOpen = true
                },
                onQuit = onExit,
            )
        }
    }
}

/**
 * The in-game menu, as the web client's: a panel over the paused game. The focus starts on
 * Resume and cannot leave the panel; Back (B on a controller) resumes.
 */
@Composable
private fun InGameMenu(
    game: Game,
    system: SystemSummary,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onLoadAuto: () -> Unit,
    onControllers: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Sov.Panel)
                .border(1.dp, Sov.Edge, RoundedCornerShape(16.dp))
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemBadge(system.short, system.colour())
                Text(game.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MenuItem("Resume", Lucide.Play, Modifier.focusRequester(first), onResume)
            MenuItem("Save to slot 1", Lucide.Check, onClick = onSave)
            MenuItem("Load slot 1", Lucide.History, onClick = onLoad)
            MenuItem("Load the auto save", Lucide.RotateCcw, onClick = onLoadAuto)
            MenuItem("Controllers", Lucide.Gamepad2, onClick = onControllers)
            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Sov.Edge))
            MenuItem("Quit game", Lucide.LogOut, onClick = onQuit)
        }
    }
}

/** While the game loads: its artwork, title and what is happening, and Continue / Start over when asked. */
@Composable
private fun Splash(game: Game, system: SystemSummary, status: String, choice: CompletableDeferred<Start>?) {
    val colour = system.colour()
    val brush = remember(colour) { tile(colour, 0.25f) }
    Box(Modifier.fillMaxSize().background(Sov.Surface)) {
        Box(Modifier.fillMaxSize().background(brush).graphicsLayer { alpha = 0.35f })
        Art(game.image ?: game.thumb, Modifier.fillMaxSize(), alpha = 0.25f, sizePx = if (game.image == null) 32 else null)
        Column(
            Modifier.align(Alignment.Center).padding(32.dp).widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SystemBadge(system.short, colour)
            Text(game.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            if (choice != null) {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SovButton("Continue", { choice.complete(Start.Continue) }, Modifier.focusRequester(first), icon = Lucide.Play, filledIcon = true)
                    SovButton("Start over", { choice.complete(Start.New) }, variant = Variant.Outline, icon = Lucide.RotateCcw)
                }
            } else {
                if (status.startsWith("Starting")) CircularProgressIndicator(color = Sov.Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                Text(status, color = Sov.Slate300, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A row of the in-game menu: focusable, with a ring and fill when focused. */
@Composable
private fun MenuItem(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Sov.Accent.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LucideIcon(icon, tint = if (focused) Color.White else Sov.Slate400, size = 18.dp)
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}

/** An on-screen pad: D-pad bottom left, face buttons bottom right, Select/Start centre. */
@Composable
private fun TouchController(controller: Controller, modifier: Modifier) {
    Box(modifier.padding(24.dp)) {
        Column(Modifier.align(Alignment.BottomStart), horizontalAlignment = Alignment.CenterHorizontally) {
            Pad("▲", Button.UP, controller)
            Row {
                Pad("◀", Button.LEFT, controller)
                Box(Modifier.size(56.dp))
                Pad("▶", Button.RIGHT, controller)
            }
            Pad("▼", Button.DOWN, controller)
        }
        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Pad("Select", Button.SELECT, controller, width = 72.dp, height = 32.dp)
            Pad("Start", Button.START, controller, width = 72.dp, height = 32.dp)
        }
        // The way out on a phone: opens the in-game menu (as Back does).
        Box(
            Modifier.align(Alignment.TopCenter).size(44.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f)).clickable { controller.requestMenu() },
            contentAlignment = Alignment.Center,
        ) { LucideIcon(Lucide.Menu, tint = Color.White.copy(alpha = 0.9f)) }
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.CenterHorizontally) {
            Pad("X", Button.X, controller, round = true)
            Row {
                Pad("Y", Button.Y, controller, round = true)
                Box(Modifier.size(56.dp))
                Pad("A", Button.A, controller, round = true)
            }
            Pad("B", Button.B, controller, round = true)
        }
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Pad("L", Button.L, controller, width = 88.dp, height = 40.dp)
            Pad("R", Button.R, controller, width = 88.dp, height = 40.dp)
        }
    }
}

@Composable
private fun Pad(label: String, button: Button, controller: Controller, round: Boolean = false, width: Dp = 56.dp, height: Dp = 56.dp) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width, height)
            .clip(if (round) CircleShape else RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = if (pressed) 0.45f else 0.18f))
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    controller.setTouch(button, true)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    pressed = false
                    controller.setTouch(button, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
