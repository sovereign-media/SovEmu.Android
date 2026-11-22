package media.sovereign.sovemu.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import media.sovereign.sovemu.core.FrameLatency
import media.sovereign.sovemu.core.LatencyHistory
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontFamily
import media.sovereign.sovemu.core.StatsSampler
import media.sovereign.sovemu.core.GameStart
import media.sovereign.sovemu.core.StartScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import media.sovereign.sovemu.core.pipelineRows
import media.sovereign.sovemu.core.formatAgo
import media.sovereign.sovemu.core.slotRows
import media.sovereign.sovemu.core.SlotRow
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SAVE_SLOTS
import media.sovereign.sovemu.core.AUTO_SLOT
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import media.sovereign.sovemu.ClientEvents
import media.sovereign.sovemu.Controller
import media.sovereign.sovemu.LowLatencyWhilePlaying
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.Stream
import media.sovereign.sovemu.StreamView
import media.sovereign.sovemu.WebRtc
import media.sovereign.sovemu.core.Button
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameDecoration
import media.sovereign.sovemu.core.MappingBook
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.SystemSummary
import org.webrtc.PeerConnection

/** Systems whose picture keeps its own shape rather than a TV's 4:3. */
private val HANDHELDS = setOf("gb", "gbc", "gba", "gamegear")

/**
 * How a game starts: from a save slot ([Continue] is the auto save's), from scratch, or asking
 * (Continue or Start over) when there is an auto save.
 */
sealed interface Start {
    data class FromSlot(val slot: Int) : Start
    data object New : Start
    data object Ask : Start

    companion object {
        val Continue: Start = FromSlot(AUTO_SLOT)
    }
}

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
    /** The stats overlay is on (a setting, kept across games). */
    stats: Boolean,
    onStatsChange: (Boolean) -> Unit,
    /** Ask the TV for its game picture mode while playing (a setting). */
    gameMode: Boolean,
    onEnded: () -> Unit,
    onExit: () -> Unit,
    /** Paused (the in-game menu or the controllers screen is open): the screensaver may start. */
    onPaused: (Boolean) -> Unit = {},
) {
    val paused by rememberUpdatedState(onPaused)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SessionInfo?>(null) }
    /** A failure to start, or a lost connection, shown in place of the game. */
    var error by remember { mutableStateOf<String?>(null) }
    /** Still no picture NO_PICTURE_HINT_MS after the session was ready. */
    var slowStart by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var stream by remember { mutableStateOf<Stream?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var controllersOpen by remember { mutableStateOf(false) }
    /** The stats overlay's rows, label and value; null while it is off or has no sample yet. */
    var readout by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    /** The overlay's latency graph, and a count of its samples that redraws it once a second. */
    val latencyHistory = remember { LatencyHistory() }
    var latencySamples by remember { mutableIntStateOf(0) }
    /** The stream has connected once: a failure after that is a lost connection. */
    var everConnected by remember { mutableStateOf(false) }
    /** The disc in the tray (1-based), for a multi-disc game. */
    var disc by remember { mutableStateOf(1) }
    val book = remember(system.id) { MappingBook(system.id) }
    var controls by remember { mutableStateOf(Controls()) }
    /** Waiting for Continue or Start over (there is an auto save and the player was not asked yet). */
    var choice by remember { mutableStateOf<CompletableDeferred<Start>?>(null) }
    // Started at once, and owned by the app: leaving now still stops it once it exists.
    val starting = remember(game.id) { sessions.start(game.id) }

    // The whole frame, scaled into the box below: never cropped (StreamView).
    val renderer = remember { StreamView(context, WebRtc.egl.eglBaseContext) }
    // The artwork framing the picture (its system's decoration), fetched alongside the start.
    var decoration by remember(game.id) { mutableStateOf<GameDecoration?>(null) }

    LaunchedEffect(game.id) {
        // The mappings, labels and the question (if any) come while the session starts, rather
        // than each adding a round trip before the game.
        launch { decoration = attempt { api.decoration(game.id) }.getOrNull() }
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
            if (how is Start.FromSlot) attempt { api.loadState(session.id, how.slot) }.onFailure { e -> toast = "Could not load: ${e.message}" }
            info = session
            // WebRTC's factory and peer connection are slow to build: not on the main thread.
            // Built whatever happens, so it is closed whatever happens.
            val s = withContext(NonCancellable + Dispatchers.Default) {
                Stream(context, api, session.id, ClientEvents.clientName(context), ice.await(), controller, renderer) { state ->
                    if (state == PeerConnection.PeerConnectionState.FAILED) error = "Connection failed"
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> everConnected = true
                        PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            val kind = if (everConnected) "CONNECTION_LOST" else "CONNECTION_FAILED"
                            ClientEvents.report(kind, "WebRTC ${state.name.lowercase()}", session.id)
                        }
                        else -> {}
                    }
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
            error = "Could not start: ${e.message}"
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
    // The stats overlay: sampled once a second while it is on, as on the web, and each sample
    // sent to the worker, which logs it beside its own health lines (`client stats` in the
    // server's log), and echoed to logcat (`adb logcat -s SovEmu.Stats`). Off, none of it runs.
    val currentGameMode by rememberUpdatedState(gameMode)
    LaunchedEffect(stats, stream) {
        readout = null
        latencyHistory.clear()
        latencySamples = 0
        val s = stream
        if (!stats || s == null) return@LaunchedEffect
        val sampler = StatsSampler()
        var loggedMatch: FrameLatency.Match? = null
        val client = ClientEvents.clientName(context)
        while (true) {
            delay(1000)
            val reading = s.readStats() ?: break
            val sample = sampler.sample(reading, s.inputRtts.toList(), s.frameLatency.medianMs())
            // How the worker makes the stream follows, once it has said (old servers never do).
            val setup = s.decoder.value
            readout = sample.rows() + pipelineRows(s.pipeline.value, sample.decoder, setup)
            latencyHistory.add(sample.inputToFrameMs?.toDouble(), sample.estimateMs)
            latencySamples++
            s.sendStats(sample.encode(client, currentGameMode, setup))
            Log.i(STATS_TAG, sample.logfmt())
            // How the measurement matches frames across the decoder, once it has learned it.
            val match = s.frameLatency.match
            if (match != loggedMatch) {
                Log.i(STATS_TAG, "frame_match=${match.name.lowercase()}")
                loggedMatch = match
            }
        }
    }
    // The loading screen stays until the game's first picture: a console's BIOS can boot for
    // seconds after the session is ready. A long wait adds a hint.
    val pictureShown by remember(stream) { stream?.firstFrame ?: flowOf(false) }.collectAsStateWithLifecycle(false)
    LaunchedEffect(info, pictureShown) {
        if (info == null || pictureShown) return@LaunchedEffect
        delay(GameStart.NO_PICTURE_HINT_MS)
        slowStart = true
    }
    // A picture that stops while playing (not paused in the menu) is reported once it has been
    // still for STALL_SECONDS. Before the first picture, the game is still starting.
    LaunchedEffect(stream) {
        val s = stream ?: return@LaunchedEffect
        var last = renderer.frames
        var still = 0
        while (true) {
            delay(1000)
            val frames = renderer.frames
            val playing = everConnected && s.firstFrame.value && !menuOpen && !controllersOpen
            still = if (playing && frames == last) still + 1 else 0
            last = frames
            if (still == STALL_SECONDS) {
                ClientEvents.report("STREAM_STALLED", "no new picture for $STALL_SECONDS s", info?.id)
            }
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    // The TV's game picture mode and Wi-Fi without power saving, for as long as the player is open.
    LowLatencyWhilePlaying(gameMode)

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
        // handheld. The whole frame is scaled into this box, never cropped; its decoration (if
        // any) frames it.
        val aspect = info?.let { if (system.id in HANDHELDS && it.height > 0) it.width.toFloat() / it.height else null } ?: (4f / 3f)
        Decorated(decoration, aspect) {
            AndroidView({ renderer }, Modifier.fillMaxSize())
        }
        // Input goes to the game while it starts too (some wait for a button), and Back opens the
        // menu over this screen, to quit a game that never draws.
        GameStart.screen(game.title, error, ready = info != null, pictureShown, slowStart)?.let { Splash(game, system, it, choice) }
        if (touchControls && !menuOpen && !controllersOpen && info != null && error == null) {
            TouchController(controller, Modifier.fillMaxSize().safeDrawingPadding())
        }
        readout?.let {
            StatsOverlay(
                it,
                latencyHistory,
                { latencySamples },
                Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp),
            )
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
                saves = { attempt { api.states(game.id) }.getOrDefault(emptyList()) },
                cheats = { first -> CheatsPage(api, game.id, first = first) },
                discs = info?.discs ?: 1,
                disc = disc,
                onDisc = { n ->
                    scope.launch {
                        toast = info?.let { s ->
                            attempt { api.setDisc(s.id, n) }.fold({ disc = n; "Disc $n is in" }, { e -> "Could not change disc: ${e.message}" })
                        }
                        menuOpen = false
                    }
                },
                onSaveTo = { slot ->
                    scope.launch {
                        toast = info?.let { s -> attempt { api.saveState(s.id, slot) }.fold({ "Saved to slot $slot" }, { e -> "Save failed: ${e.message}" }) }
                        menuOpen = false
                    }
                },
                onLoadFrom = { slot ->
                    scope.launch {
                        val name = if (slot == AUTO_SLOT) "the auto save" else "slot $slot"
                        toast = info?.let { s -> attempt { api.loadState(s.id, slot) }.fold({ "Loaded $name" }, { e -> "Load failed: ${e.message}" }) }
                        menuOpen = false
                    }
                },
                onControllers = {
                    menuOpen = false
                    controllersOpen = true
                },
                stats = stats,
                onStatsChange = onStatsChange,
                onQuit = onExit,
            )
        }
    }
}

private enum class MenuPage { Main, Save, Load, Cheats, Disc }

/**
 * The in-game menu, as the web client's: a panel over the paused game. The focus starts on
 * Resume and cannot leave the panel; Back (B on a controller) resumes. Save and Load open a
 * picker of the game's slots in the same panel, each with its screenshot and age; Back there
 * returns to the menu.
 */
@Composable
private fun InGameMenu(
    game: Game,
    system: SystemSummary,
    onResume: () -> Unit,
    saves: suspend () -> List<SaveSlot>,
    cheats: @Composable (first: Modifier) -> Unit,
    /** A multi-disc game's disc count and the disc in the tray (1-based); 1 of 1 for most. */
    discs: Int,
    disc: Int,
    onDisc: (Int) -> Unit,
    onSaveTo: (Int) -> Unit,
    onLoadFrom: (Int) -> Unit,
    onControllers: () -> Unit,
    stats: Boolean,
    onStatsChange: (Boolean) -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(MenuPage.Main) }
    var slots by remember { mutableStateOf<List<SaveSlot>?>(null) }
    LaunchedEffect(page) {
        if (page == MenuPage.Save || page == MenuPage.Load) {
            slots = null
            slots = saves()
        }
    }
    BackHandler(enabled = page != MenuPage.Main) { page = MenuPage.Main }

    val first = remember { FocusRequester() }
    LaunchedEffect(page, slots == null) {
        repeat(10) {
            withFrameNanos { }
            if (first.requestFocus()) return@LaunchedEffect
        }
    }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(if (page == MenuPage.Main) 360.dp else 480.dp)
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
                Text(
                    when (page) {
                        MenuPage.Main -> game.title
                        MenuPage.Save -> "Save to a slot"
                        MenuPage.Load -> "Load a save"
                        MenuPage.Cheats -> "Cheats"
                        MenuPage.Disc -> "Change disc"
                    },
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            when (page) {
                MenuPage.Main -> {
                    MenuItem("Resume", Lucide.Play, Modifier.focusRequester(first), onClick = onResume)
                    MenuItem("Save state", Lucide.Check, onClick = { page = MenuPage.Save })
                    MenuItem("Load state", Lucide.History, onClick = { page = MenuPage.Load })
                    if (discs > 1) MenuItem("Change disc · disc $disc of $discs", Lucide.Disc3, onClick = { page = MenuPage.Disc })
                    MenuItem("Cheats", Lucide.Sparkles, onClick = { page = MenuPage.Cheats })
                    MenuItem("Controllers", Lucide.Gamepad2, onClick = onControllers)
                    MenuItem(
                        if (stats) "Hide stats" else "Show stats",
                        Lucide.Activity,
                        detail = STATS_WARNING,
                        onClick = { onStatsChange(!stats) },
                    )
                    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Sov.Edge))
                    MenuItem("Quit game", Lucide.LogOut, onClick = onQuit)
                }
                MenuPage.Cheats -> cheats(Modifier.focusRequester(first))
                MenuPage.Disc -> for (n in 1..discs) {
                    MenuItem(
                        if (n == disc) "Disc $n · in the console" else "Disc $n",
                        Lucide.Disc3,
                        if (n == disc) Modifier.focusRequester(first) else Modifier,
                    ) { onDisc(n) }
                }
                else -> {
                    val list = slots
                    if (list == null) {
                        Text("Loading saves…", color = Sov.Slate400, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                    } else {
                        val saving = page == MenuPage.Save
                        val rows = slotRows(list, if (saving) SAVE_SLOTS else listOf(AUTO_SLOT) + SAVE_SLOTS, emptyEnabled = saving)
                        val firstEnabled = rows.indexOfFirst { it.enabled }
                        rows.forEachIndexed { i, row ->
                            SlotItem(
                                row,
                                if (i == firstEnabled) Modifier.focusRequester(first) else Modifier,
                            ) { if (saving) onSaveTo(row.slot) else onLoadFrom(row.slot) }
                        }
                    }
                }
            }
        }
    }
}

/** A save slot in a picker: its screenshot (or a blank), name and age; dimmed when it cannot be picked. */
@Composable
private fun SlotItem(row: SlotRow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val now = remember { System.currentTimeMillis() / 1000 }
    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .graphicsLayer { alpha = if (row.enabled) 1f else 0.4f }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Sov.Accent.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, enabled = row.enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.height(56.dp).aspectRatio(4 / 3f).clip(RoundedCornerShape(6.dp)).background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            row.save?.screenshotUrl?.let { Screenshot(it, Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(row.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                row.save?.let { "Saved " + formatAgo(it.savedAt.toLong(), now) } ?: "Empty",
                color = Sov.Slate400, fontSize = 13.sp,
            )
        }
    }
}

/** While the game loads: its artwork, title and what is happening, and Continue / Start over when asked. */
@Composable
private fun Splash(game: Game, system: SystemSummary, screen: StartScreen, choice: CompletableDeferred<Start>?) {
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
                if (screen.busy) CircularProgressIndicator(color = Sov.Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                Text(screen.label, color = Sov.Slate300, fontSize = 14.sp, textAlign = TextAlign.Center)
                screen.hint?.let { Text(it, color = Sov.Slate400, fontSize = 13.sp, textAlign = TextAlign.Center) }
            }
        }
    }
}

private const val STATS_TAG = "SovEmu.Stats"

/** Seconds without a new video frame, while playing, before it is reported as a stall. */
private const val STALL_SECONDS = 3

/** Where stats are turned on, and on the overlay: they cost a little every second. */
internal const val STATS_WARNING = "Stats are logged while shown, and can affect game performance."


/**
 * Latency and stream health over the game, as the web client's: a panel in the top-left corner,
 * its rows in two columns (the first filled top to bottom, then the second) above a graph of the
 * last minute's latency. It never takes focus, so the game keeps its controllers.
 */
@Composable
private fun StatsOverlay(
    rows: List<Pair<String, String>>,
    history: LatencyHistory,
    /** Samples so far: read only by the graph's draw, which it redraws on each. */
    samples: () -> Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val half = (rows.size + 1) / 2
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatsColumn(rows.subList(0, half))
            StatsColumn(rows.subList(half, rows.size))
        }
        LatencyGraph(history, samples, Modifier.padding(top = 8.dp).fillMaxWidth().height(64.dp))
        Text(STATS_WARNING, Modifier.padding(top = 4.dp), color = Sov.Slate400, fontSize = 10.sp)
    }
}

/** Label and value pairs: labels muted on the left, values right-aligned. */
@Composable
private fun StatsColumn(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column { for ((label, _) in rows) Text(label, color = Sov.Slate400, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        Column(horizontalAlignment = Alignment.End) {
            for ((_, value) in rows) Text(value, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * The last minute of "Measured button→frame" (solid, accent) and "Estimated button→pixel"
 * (thin, muted), from 0 to [LatencyHistory.scaleMaxMs], with the scale's gridlines and the latest
 * measurement at the right. Everything is read while drawing, so a new sample only redraws.
 */
@Composable
private fun LatencyGraph(history: LatencyHistory, samples: () -> Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val label = remember { TextStyle(color = Sov.Slate500, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
    val latest = remember { TextStyle(color = Sov.Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold) }
    val measuredPath = remember { Path() }
    val estimatedPath = remember { Path() }
    Canvas(modifier) {
        samples()
        val top = history.scaleMaxMs()
        val latestText = history.latestMeasured?.let { measurer.measure("${it.roundToInt()} ms", latest) }
        val right = size.width - (latestText?.size?.width ?: 0) - 4.dp.toPx()
        fun y(ms: Double) = (size.height * (1 - ms / top)).toFloat()
        for (ms in doubleArrayOf(top, top / 2)) {
            drawLine(Sov.Edge, Offset(0f, y(ms)), Offset(right, y(ms)), strokeWidth = 1f)
            drawText(measurer.measure("${ms.roundToInt()}", label), topLeft = Offset(0f, y(ms)))
        }
        val step = right / (history.capacity - 1)
        // Newest at the right edge; a minute's worth fills the width.
        val first = history.capacity - history.size
        fun Path.trace(value: (Int) -> Double) {
            reset()
            var pen = false
            for (i in 0 until history.size) {
                val v = value(i)
                if (v.isNaN()) {
                    pen = false
                    continue
                }
                val x = (first + i) * step
                if (pen) lineTo(x, y(v)) else moveTo(x, y(v))
                pen = true
            }
        }
        estimatedPath.trace(history::estimated)
        drawPath(estimatedPath, Sov.Slate400, style = Stroke(width = 1.dp.toPx()))
        measuredPath.trace(history::measured)
        drawPath(measuredPath, Sov.Accent, style = Stroke(width = 2.dp.toPx()))
        latestText?.let {
            val v = history.latestMeasured ?: 0.0
            val at = (y(v) - it.size.height / 2).coerceIn(0f, size.height - it.size.height)
            drawText(it, topLeft = Offset(size.width - it.size.width, at))
        }
    }
}

/** A row of the in-game menu: focusable, with a ring and fill when focused. */
@Composable
private fun MenuItem(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    /** A second, smaller line under the label (a trade-off worth knowing before choosing it). */
    detail: String? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Sov.Accent.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LucideIcon(icon, tint = if (focused) Color.White else Sov.Slate400, size = 18.dp)
        Column(Modifier.padding(vertical = if (detail != null) 6.dp else 0.dp)) {
            Text(label, color = Color.White, fontSize = 16.sp)
            if (detail != null) Text(detail, color = Sov.Slate400, fontSize = 12.sp)
        }
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
