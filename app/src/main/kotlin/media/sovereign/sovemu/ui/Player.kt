package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.Controller
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.Stream
import media.sovereign.sovemu.WebRtc
import media.sovereign.sovemu.core.Button
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.SystemSummary
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * The player: starts the session, streams it, and overlays a touch controller. Back (or
 * Select+Start on a gamepad) opens the in-game menu: resume, save/load slot 1, controllers
 * (players and button mappings), quit. The user's controller mappings load as the game starts.
 */
@Composable
fun PlayerScreen(api: SovEmuApi, system: SystemSummary, game: Game, controller: Controller, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SessionInfo?>(null) }
    var status by remember { mutableStateOf("Starting ${game.title}…") }
    var menuOpen by remember { mutableStateOf(false) }
    var stream by remember { mutableStateOf<Stream?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showTouch by remember { mutableStateOf(true) }
    var controllersOpen by remember { mutableStateOf(false) }

    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(WebRtc.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }

    fun quit() {
        scope.launch {
            stream?.close()
            info?.let { runCatching { api.stopSession(it.id) } }
            onExit()
        }
    }

    LaunchedEffect(game.id) {
        // A controller without a saved mapping (or every one, if this fails) uses the defaults.
        runCatching { api.controllerMappings() }
            .onSuccess { controller.mixer.setMappings(it) }
            .onFailure { e -> toast = "Default controls: ${e.message}" }
        try {
            val config = runCatching { api.config() }.getOrNull()
            val session = api.startSession(game.id)
            info = session
            status = ""
            val s = Stream(context, api, session.id, config?.iceServers.orEmpty(), controller, renderer) { state ->
                if (state == PeerConnection.PeerConnectionState.FAILED) status = "Connection failed"
            }
            stream = s
            s.connect()
        } catch (e: Exception) {
            status = "Could not start: ${e.message}"
        }
    }

    // Select+Start opens the menu; hide the touch controller once a gamepad is used.
    LaunchedEffect(Unit) {
        while (true) {
            if (!menuOpen && !controllersOpen && controller.menuCombo) menuOpen = true
            delay(50)
        }
    }
    // With the menu open the game gets nothing, and controllers navigate the menu instead.
    LaunchedEffect(menuOpen, controllersOpen, stream) {
        val paused = menuOpen || controllersOpen
        stream?.suspended = paused
        controller.toGame = !paused
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stream?.close()
            renderer.release()
        }
    }

    BackHandler { menuOpen = !menuOpen }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ renderer }, Modifier.fillMaxSize())
        if (status.isNotEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (status.startsWith("Starting")) CircularProgressIndicator()
                Text(status, color = Color.White, modifier = Modifier.padding(16.dp))
            }
        }
        if (showTouch && !menuOpen && !controllersOpen) TouchController(controller, Modifier.fillMaxSize().safeDrawingPadding())
        toast?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (controllersOpen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                ControllersScreen(api, controller) {
                    controllersOpen = false
                    menuOpen = true
                }
            }
        } else if (menuOpen) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(Modifier.width(320.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF151922)).padding(12.dp)) {
                    Text(game.title, color = Color(0xFF8B93A7), modifier = Modifier.padding(8.dp))
                    MenuItem("Resume", Modifier.focusRequester(first)) { menuOpen = false }
                    MenuItem("Save state (slot 1)") {
                        scope.launch {
                            toast = info?.let { runCatching { api.saveState(it.id, 1) }.fold({ "Saved" }, { e -> "Save failed: ${e.message}" }) }
                            menuOpen = false
                        }
                    }
                    MenuItem("Load state (slot 1)") {
                        scope.launch {
                            toast = info?.let { runCatching { api.loadState(it.id, 1) }.fold({ "Loaded" }, { e -> "Load failed: ${e.message}" }) }
                            menuOpen = false
                        }
                    }
                    MenuItem("Continue from auto save") {
                        scope.launch {
                            toast = info?.let { runCatching { api.loadState(it.id, 0) }.fold({ "Continued" }, { e -> "No auto save: ${e.message}" }) }
                            menuOpen = false
                        }
                    }
                    MenuItem("Controllers") {
                        menuOpen = false
                        controllersOpen = true
                    }
                    MenuItem(if (showTouch) "Hide touch controls" else "Show touch controls") {
                        showTouch = !showTouch
                        menuOpen = false
                    }
                    MenuItem("Quit game") { quit() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    FocusButton(label, modifier.fillMaxWidth(), onClick = onClick)

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
