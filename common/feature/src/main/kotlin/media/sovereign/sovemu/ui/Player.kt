package media.sovereign.sovemu.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.sovereign.sovemu.ClientEvents
import media.sovereign.sovemu.Controller
import media.sovereign.sovemu.Sessions
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.Stream
import media.sovereign.sovemu.StreamView
import media.sovereign.sovemu.WebRtc
import media.sovereign.sovemu.attempt
import media.sovereign.sovemu.core.AUTO_SLOT
import media.sovereign.sovemu.core.Button
import media.sovereign.sovemu.core.CheatsModel
import media.sovereign.sovemu.core.ForwardOnly
import media.sovereign.sovemu.core.FrameLatency
import media.sovereign.sovemu.core.Game
import media.sovereign.sovemu.core.GameDecoration
import media.sovereign.sovemu.core.GameModeState
import media.sovereign.sovemu.core.GameStart
import media.sovereign.sovemu.core.LatencyHistory
import media.sovereign.sovemu.core.LoadingBackground
import media.sovereign.sovemu.core.MappingBook
import media.sovereign.sovemu.core.PeerLink
import media.sovereign.sovemu.core.PeerState
import media.sovereign.sovemu.core.Protocol
import media.sovereign.sovemu.core.QualityNotice
import media.sovereign.sovemu.core.SAVE_SLOTS
import media.sovereign.sovemu.core.SaveSlot
import media.sovereign.sovemu.core.SessionInfo
import media.sovereign.sovemu.core.SlotRow
import media.sovereign.sovemu.core.StartScreen
import media.sovereign.sovemu.core.StartStatus
import media.sovereign.sovemu.core.StatsSampler
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.describeStart
import media.sovereign.sovemu.core.formatAgo
import media.sovereign.sovemu.core.keepConnected
import media.sovereign.sovemu.core.loadingBackground
import media.sovereign.sovemu.core.networkLabel
import media.sovereign.sovemu.core.networkRows
import media.sovereign.sovemu.core.newStartId
import media.sovereign.sovemu.core.pipelineRows
import media.sovereign.sovemu.core.qualityNotice
import media.sovereign.sovemu.core.slotRows
import media.sovereign.sovemu.core.starsText
import media.sovereign.sovemu.core.streamInfo
import media.sovereign.sovemu.core.year
import media.sovereign.sovemu.minimalPostProcessingSupported
import media.sovereign.sovemu.rememberLowLatencyWifi
import org.webrtc.PeerConnection

/** Systems whose picture keeps its own shape rather than a TV's 4:3: the handhelds, and the
 *  Vectrex's portrait tube. */
private val HANDHELDS = setOf(
    "gb", "gbc", "gba", "gamegear", "lynx", "ngp", "ngpc", "wswan", "wswanc", "virtualboy",
    "vectrex",
)

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
    model: PlayerViewModel,
    system: SystemSummary,
    game: Game,
    start: Start,
    controller: Controller,
    touchControls: Boolean,
    /** The stats overlay is on (a setting, kept across games). */
    stats: Boolean,
    onStatsChange: (Boolean) -> Unit,
    /** The TV game mode setting (asked for app-wide, TvGameMode), for the stats. */
    gameMode: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Paused (the in-game menu or the controllers screen is open): the screensaver may start. */
    onPaused: (Boolean) -> Unit = {},
) {
    val paused by rememberUpdatedState(onPaused)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The session and its start are the ViewModel's; the stream and the window are this screen's.
    val info by model.session.collectAsStateWithLifecycle()
    val startError by model.error.collectAsStateWithLifecycle()

    /** A lost connection, shown in place of the game (a failure to start is the ViewModel's). */
    var lost by remember { mutableStateOf<String?>(null) }
    val error = startError ?: lost

    /** The server's word on how far the start has got (null until it has said), and the bar. */
    val progress by model.progress.collectAsStateWithLifecycle()
    val bar by model.bar.collectAsStateWithLifecycle()

    /** How long the ready session has waited for its first picture. */
    var waitedMs by remember { mutableLongStateOf(0L) }
    var menuOpen by remember { mutableStateOf(false) }
    var stream by remember { mutableStateOf<Stream?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var controllersOpen by remember { mutableStateOf(false) }

    /** The stats overlay's rows, label and value; null while it is off or has no sample yet. */
    var readout by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    /** The stream's quality changed: the notice at the top of the screen, for a moment. */
    var quality by remember { mutableStateOf<QualityNotice?>(null) }

    /** The overlay's latency graph, and a count of its samples that redraws it once a second. */
    val latencyHistory = remember { LatencyHistory() }
    var latencySamples by remember { mutableIntStateOf(0) }

    /** The stream has connected once: a failure after that is a lost connection. */
    var everConnected by remember { mutableStateOf(false) }

    /** Connections given up on and made again so far ([Reconnect]). */
    var reconnects by remember { mutableIntStateOf(0) }

    /** Reconnecting gave up before the game's first picture. */
    var connectionFailed by remember { mutableStateOf(false) }

    /** The game has drawn, on this connection or an earlier one: a reconnect keeps it shown. */
    var pictured by remember { mutableStateOf(false) }

    /** The disc in the tray (1-based), for a multi-disc game. */
    val disc by model.disc.collectAsStateWithLifecycle()
    val book = model.book
    val controls by model.controls.collectAsStateWithLifecycle()

    /**
     * Waiting for Continue or Start over (there is an auto save and the player was not asked yet).
     */
    val choice by model.choice.collectAsStateWithLifecycle()

    /** The screenshot of the save being continued, behind the loading screen. */
    val saveShot by model.saveShot.collectAsStateWithLifecycle()

    // The whole frame, scaled into the box below: never cropped (StreamView).
    val renderer = remember { StreamView(context, WebRtc.egl.eglBaseContext) }
    // The artwork framing the picture (its system's decoration), fetched alongside the start.
    val decoration by model.decoration.collectAsStateWithLifecycle()
    // The mappings in force go to the controllers' mixer as they arrive; anything the ViewModel
    // has to say shows for a moment.
    val mappings by model.mappings.collectAsStateWithLifecycle()
    LaunchedEffect(mappings) { mappings?.let(controller::setMappings) }
    val notice by model.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        notice?.let {
            toast = it
            model.noticeShown()
        }
    }

    // The game's stream, once its session has started: built by the ViewModel (it holds the API
    // and the ICE servers) into this screen's video view. A connection that fails, stays
    // disconnected or never connects is made again to the same session ([keepConnected]), up to
    // Reconnect.ATTEMPTS times; only one that never recovers is reported, and ends the game.
    LaunchedEffect(info) {
        val session = info ?: return@LaunchedEffect
        keepConnected({ attempt ->
            reconnects = attempt
            if (attempt > 0 && pictured) toast = "Reconnecting…"
            val state = MutableStateFlow(PeerState.NEW)
            val s = model.stream(context, session, controller, renderer) { webrtc ->
                state.value = PeerState.valueOf(webrtc.name)
                if (webrtc == PeerConnection.PeerConnectionState.CONNECTED) everConnected = true
            }
            stream = s
            object : PeerLink {
                override val state: StateFlow<PeerState> = state
                override suspend fun connect() = s.connect()
                override fun close() = s.close()
            }
        })
        val kind = if (everConnected) "CONNECTION_LOST" else "CONNECTION_FAILED"
        ClientEvents.report(kind, "no connection after $reconnects reconnects", session.id)
        if (pictured) lost = "Lost the connection to the game" else connectionFailed = true
    }
    // Select+Start's press, the guide button or the on-screen menu button (Controller), for as
    // long as the screen is open.
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
    // The stream's stats: sampled once a second from the moment the stream exists, as on the
    // web, and each sample sent to the worker once the channel is open. Before the first picture
    // the worker checks the connection with them (the round trip and the path, #243); then it
    // adapts the video bitrate to how this device keeps up and logs them beside its own health
    // lines (`client stats` in the server's log). With the overlay on
    // they are also shown, and echoed to logcat (`adb logcat -s SovEmu.Stats`).
    val currentGameMode by rememberUpdatedState(gameMode)
    // Wi-Fi without power saving, for as long as the player is open (the TV's game mode is the
    // whole app's: TvGameMode); whether it is held goes to the worker with the stats.
    val wifiLock = rememberLowLatencyWifi()
    // Keyed on the stream alone: the overlay's switch only changes what is shown, and restarting
    // the loop would lose the sampler's last reading, costing the worker a second or more of
    // the input it adapts by.
    val showStats by rememberUpdatedState(stats)
    LaunchedEffect(stats) {
        if (!stats) {
            readout = null
            latencyHistory.clear()
            latencySamples = 0
        }
    }
    // One tick a second for each connection, for all that counts time (each was a timer of its
    // own): the wait for the first picture, a picture that stops, and the stats.
    LaunchedEffect(stream) {
        readout = null
        latencyHistory.clear()
        latencySamples = 0
        waitedMs = 0
        val s = stream ?: return@LaunchedEffect
        val sampler = StatsSampler()
        var loggedMatch: FrameLatency.Match? = null
        var audioTick = 0
        val client = ClientEvents.clientName(context)
        // Counted again from each new connection, as the web does; a long wait adds a hint.
        val since = SystemClock.uptimeMillis()
        var lastFrames = renderer.frames
        var still = 0
        while (true) {
            delay(1000)
            if (info != null && !pictured && !s.firstFrame.value) {
                waitedMs = SystemClock.uptimeMillis() - since
            }
            // A picture that stops while playing (not paused in the menu) is reported once it has
            // been still for STALL_SECONDS. Before the first picture, the game is still starting.
            val frames = renderer.frames
            val playing = everConnected && s.firstFrame.value && !menuOpen && !controllersOpen
            still = if (playing && frames == lastFrames) still + 1 else 0
            lastFrames = frames
            if (still == STALL_SECONDS) {
                ClientEvents.report(
                    "STREAM_STALLED",
                    "no new picture for $STALL_SECONDS s",
                    info?.id,
                )
            }
            val reading = s.readStats() ?: break
            val sample = sampler.sample(reading, s.inputRtts.toList(), s.frameLatency.medianMs())
            // How the worker makes the stream follows, once it has said (old servers never do).
            val setup = s.decoder.value
            // Whether the TV's game mode took effect, not only whether it was asked for: read
            // each second, since the display can change (another HDMI input, another TV).
            val gameModeState =
                GameModeState.of(currentGameMode, minimalPostProcessingSupported(context))
            s.sendStats(sample.encode(client, gameModeState.applied, wifiLock.value, setup))
            // The audio's delays, every ten seconds, overlay or not.
            if (++audioTick % 10 == 0) sample.audioLogfmt()?.let { Log.i(STATS_TAG, it) }
            if (!showStats) continue
            readout = sample.rows() + ("TV game mode" to gameModeState.label) +
                pipelineRows(s.pipeline.value, sample.decoder, setup) +
                networkRows(
                    reading.local,
                    reading.remote,
                    s.link.value,
                    s.pipeline.value?.video?.estimateKbps ?: 0,
                )
            latencyHistory.add(
                sample.inputToFrameMs?.toDouble(),
                sample.estimateMs,
                sample.audioBufferMs,
            )
            latencySamples++
            Log.i(STATS_TAG, sample.logfmt())
            // How the measurement matches frames across the decoder, once it has learned it.
            val match = s.frameLatency.match
            if (match != loggedMatch) {
                Log.i(STATS_TAG, "frame_match=${match.name.lowercase()}")
                loggedMatch = match
            }
        }
    }
    // The worker changed the stream's quality (its pipeline says so): a notice for a moment.
    LaunchedEffect(stream) {
        val s = stream ?: return@LaunchedEffect
        var before: Protocol.Pipeline? = null
        s.pipeline.collect { now ->
            now ?: return@collect
            qualityNotice(before, now)?.let { quality = it }
            before = now
        }
    }
    LaunchedEffect(quality) {
        if (quality != null) {
            delay(QUALITY_NOTICE_MS)
            quality = null
        }
    }
    // The loading screen stays until the game's first picture: a console's BIOS can boot for
    // seconds after the session is ready.
    val streamPicture by remember(stream) { stream?.firstFrame ?: flowOf(false) }
        .collectAsStateWithLifecycle(false)
    LaunchedEffect(streamPicture) { if (streamPicture) pictured = true }
    val pictureShown = pictured || streamPicture

    // The worker's check of the connection, which comes between connecting and the picture.
    val link by remember(stream) { stream?.link ?: flowOf(null) }
        .collectAsStateWithLifecycle(null)
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    // The window's part goes with the screen; the session stops when the ViewModel is cleared.
    DisposableEffect(Unit) {
        onDispose {
            stream?.close()
            renderer.release()
        }
    }

    // Before the game is up there is nothing to resume: Back leaves.
    BackHandler { if (info == null) onExit() else menuOpen = !menuOpen }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // The console's shape, centred, as on the web: 4:3 for a TV console, the screen's own for a
        // handheld. The whole frame is scaled into this box, never cropped; its decoration (if
        // any) frames it.
        val aspect = info?.let {
            if (system.id in HANDHELDS && it.height > 0) it.width.toFloat() / it.height else null
        } ?: (4f / 3f)
        Decorated(decoration, aspect) {
            AndroidView({ renderer }, Modifier.fillMaxSize())
        }
        // Input goes to the game while it starts too (some wait for a button), and Back opens the
        // menu over this screen, to quit a game that never draws.
        GameStart.screen(
            game.title,
            error,
            progress,
            bar,
            ready = info != null,
            pictureShown,
            waitedMs,
            link,
            reconnects,
            connectionFailed,
        )?.let {
            Splash(
                game,
                system,
                it,
                choice,
                background = loadingBackground(saveShot, game.image, game.cover ?: game.thumb),
                status = info?.let { s -> streamInfo(s.core, s.width, s.height, s.fps) },
                network = networkLabel(link?.path ?: ""),
                covered = menuOpen || controllersOpen,
            )
        }
        if (touchControls && !menuOpen && !controllersOpen && info != null && error == null &&
            !connectionFailed
        ) {
            TouchController(controller, Modifier.fillMaxSize().safeDrawingPadding())
        }
        readout?.let {
            StatsOverlay(
                it,
                latencyHistory,
                { latencySamples },
                Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp),
            )
        }
        quality?.let {
            QualityChip(
                it,
                Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
            )
        }
        toast?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (controllersOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .safeDrawingPadding(),
                contentAlignment = Alignment.Center,
            ) {
                ControllersScreen(
                    controller,
                    book,
                    controls,
                    system.short,
                    save = model::saveMapping,
                    reset = model::resetMapping,
                    onClose = {
                        controllersOpen = false
                        menuOpen = true
                    },
                )
            }
        } else if (menuOpen) {
            InGameMenu(
                game, system,
                onResume = { menuOpen = false },
                saves = model::saves,
                cheats = { first ->
                    // The page draws; its state and the calls behind it are this screen's, loaded
                    // as the page opens.
                    val cheatsModel = remember(game.id) { model.cheats() }
                    LaunchedEffect(cheatsModel) { cheatsModel.load() }
                    val cheats by cheatsModel.cheats.collectAsStateWithLifecycle()
                    val message by cheatsModel.message.collectAsStateWithLifecycle()
                    CheatsPage(
                        cheats,
                        message,
                        cheatsModel::toggle,
                        cheatsModel::delete,
                        cheatsModel::add,
                        first = first,
                    )
                },
                discs = info?.discs ?: 1,
                disc = disc,
                onDisc = { n ->
                    scope.launch {
                        toast = model.setDisc(n)
                        menuOpen = false
                    }
                },
                onSaveTo = { slot ->
                    scope.launch {
                        toast = model.saveTo(slot)
                        menuOpen = false
                    }
                },
                onLoadFrom = { slot ->
                    scope.launch {
                        val name = if (slot == AUTO_SLOT) "the auto save" else "slot $slot"
                        toast = model.loadFrom(slot, name)
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
                status = info?.let { s -> streamInfo(s.core, s.width, s.height, s.fps) },
                network = networkLabel(link?.path ?: ""),
            )
        }
    }
}

private enum class MenuPage { Main, Save, Load, Cheats, Disc }

/**
 * The in-game menu, as the web client's: a panel over the paused game, in the loading screen's
 * [GameOverlay] with the game's own frame behind (its [status] and [network] as the loading
 * screen shows them), the panel centred where the loading screen's bar is. The focus starts on
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
    status: String?,
    network: String?,
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
    GameOverlay(game, system, status, network, modifier) {
        Column(
            Modifier
                .align(Alignment.Center)
                .width(if (page == MenuPage.Main) 360.dp else 480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Sov.Panel)
                .border(1.dp, Sov.Edge, RoundedCornerShape(16.dp))
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The game's badge and title are the overlay's, bottom left: a page names itself.
            val heading = when (page) {
                MenuPage.Main -> null
                MenuPage.Save -> "Save to a slot"
                MenuPage.Load -> "Load a save"
                MenuPage.Cheats -> "Cheats"
                MenuPage.Disc -> "Change disc"
            }
            heading?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
            when (page) {
                MenuPage.Main -> {
                    MenuItem(
                        "Resume",
                        Lucide.Play,
                        Modifier.focusRequester(first),
                        onClick = onResume,
                    )
                    MenuItem("Save state", Lucide.Check, onClick = { page = MenuPage.Save })
                    MenuItem("Load state", Lucide.History, onClick = { page = MenuPage.Load })
                    if (discs > 1) {
                        MenuItem(
                            "Change disc · disc $disc of $discs",
                            Lucide.Disc3,
                            onClick = { page = MenuPage.Disc },
                        )
                    }
                    MenuItem("Cheats", Lucide.Sparkles, onClick = { page = MenuPage.Cheats })
                    MenuItem("Controllers", Lucide.Gamepad2, onClick = onControllers)
                    MenuItem(
                        if (stats) "Hide stats" else "Show stats",
                        Lucide.Activity,
                        detail = STATS_WARNING,
                        onClick = { onStatsChange(!stats) },
                    )
                    Box(
                        Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Sov.Edge),
                    )
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
                        Text(
                            "Loading saves…",
                            color = Sov.Slate400,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(12.dp),
                        )
                    } else {
                        val saving = page == MenuPage.Save
                        val rows = slotRows(
                            list,
                            if (saving) SAVE_SLOTS else listOf(AUTO_SLOT) + SAVE_SLOTS,
                            emptyEnabled = saving,
                        )
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

/**
 * A save slot in a picker: its screenshot (or a blank), name and age; dimmed when it cannot be
 * picked.
 */
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
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = row.enabled,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .height(56.dp)
                .aspectRatio(4 / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            row.save?.screenshotUrl?.let { Screenshot(it, Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(row.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                row.save?.let { "Saved " + formatAgo(it.savedAt.toLong(), now) } ?: "Empty",
                color = Sov.Slate400,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * While the game loads (#243, as the web's `LoadingScreen`): a darkened picture behind
 * ([background]: the save being continued, the game, its box blurred, or the system's colour)
 * under the [GameOverlay], with the bar dead centre saying what the start is doing and a spinner
 * bottom right, which a failure replaces with the alert. Continue / Start over, when asked, is
 * centred in the bar's place. While the menu is [covered] over it, only the picture is drawn:
 * the menu brings the overlay.
 */
@Composable
private fun Splash(
    game: Game,
    system: SystemSummary,
    screen: StartScreen,
    choice: CompletableDeferred<Start>?,
    background: LoadingBackground?,
    status: String?,
    network: String?,
    covered: Boolean,
) {
    val colour = system.colour()
    val failed = screen.fraction == null
    Box(Modifier.fillMaxSize()) {
        when {
            background == null -> {
                val brush = remember(colour) { tile(colour, 0.25f) }
                Box(Modifier.fillMaxSize().background(brush))
            }

            // Box art is not a picture of the game: a 32 px copy, scaled up, blurs it into a
            // backdrop (no blur effect: TVs are fill-rate bound).
            background.blurred -> Art(background.url, Modifier.fillMaxSize(), sizePx = 32)

            else -> Art(
                background.url,
                Modifier.fillMaxSize(),
                filterQuality = if (background.pixelated) {
                    FilterQuality.None
                } else {
                    FilterQuality.Low
                },
            )
        }
        if (covered) return@Box
        GameOverlay(
            game,
            system,
            status,
            network,
            corner = {
                if (failed) {
                    LucideIcon(Lucide.CircleAlert, tint = Sov.Red300, size = 40.dp)
                } else {
                    CircularProgressIndicator(
                        Modifier.size(40.dp),
                        color = Color.White.copy(alpha = 0.8f),
                        strokeWidth = 3.dp,
                    )
                }
            },
        ) {
            if (choice == null) {
                StartProgress(screen, failed, Modifier.align(Alignment.Center))
            } else {
                val first = remember { FocusRequester() }
                // Once, as the question shows: Continue is where the player starts.
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SovButton(
                        "Continue",
                        { choice.complete(Start.Continue) },
                        Modifier.focusRequester(first),
                        icon = Lucide.Play,
                        filledIcon = true,
                    )
                    SovButton(
                        "Start over",
                        { choice.complete(Start.New) },
                        variant = Variant.Outline,
                        icon = Lucide.RotateCcw,
                    )
                }
            }
        }
    }
}

/**
 * The loading screen's overlay, shared with the in-game menu: a scrim over whatever is behind
 * (the loading picture, or the paused game's own frame), the game top left (its title and
 * stars, its system and year) with where the player is ([network]) in a chip top right, the
 * stream's [status] bottom left with [corner] bottom right (the spinner), and [content] (the
 * start's bar, or the menu's panel) in the TV-safe area between.
 */
@Composable
private fun GameOverlay(
    game: Game,
    system: SystemSummary,
    status: String?,
    network: String?,
    modifier: Modifier = Modifier,
    corner: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    // An even scrim, deeper along the top and the bottom where the text sits: 55%, and about
    // 80% at either edge. One layer, drawn once.
    Box(
        modifier.fillMaxSize().drawBehind {
            drawRect(SCRIM)
            val band = size.height * 0.4f
            drawRect(
                Brush.verticalGradient(listOf(SCRIM, Color.Transparent), 0f, band),
                Offset.Zero,
                Size(size.width, band),
            )
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, SCRIM),
                    size.height - band,
                    size.height,
                ),
                Offset(0f, size.height - band),
                Size(size.width, band),
            )
        },
    ) {
        // TV-safe insets on a TV (about 5% of the screen), and a margin on a phone.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val across = max(24.dp, maxWidth * 0.05f)
            val down = max(24.dp, maxHeight * 0.05f)
            Box(Modifier.fillMaxSize().padding(horizontal = across, vertical = down)) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        // The title, then its rating: a long title gives way, not the stars.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                game.title,
                                color = Color.White,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            game.rating?.let { Rating(it) }
                        }
                        // The system by its name, as the screensaver gives it, and the year.
                        Text(
                            listOfNotNull(system.name, game.year).joinToString(" · "),
                            color = Sov.Slate400,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    network?.let {
                        Spacer(Modifier.width(24.dp))
                        NetworkChip(it)
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(Modifier.weight(1f)) {
                        status?.let { Text(it, color = Sov.Slate500, fontSize = 13.sp) }
                    }
                    Spacer(Modifier.width(24.dp))
                    corner()
                }
                content()
            }
        }
    }
}

/** A game's score, as the details panel gives it: a star and "4 out of 5". */
@Composable
private fun Rating(rating: Double, modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LucideIcon(Lucide.Star, tint = Sov.Amber200, size = 20.dp)
        Text(starsText(rating), color = Sov.Slate300, fontSize = 16.sp, maxLines = 1)
    }
}

/**
 * Where the player is, `Home network` or `Remote`, once the worker has checked the connection:
 * a chip of its own, so the detail line keeps to what the start is doing.
 */
@Composable
private fun NetworkChip(label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = Sov.Slate100,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** The bar, what the start is doing and its detail, then how to leave it: centred. */
@Composable
private fun StartProgress(screen: StartScreen, failed: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 560.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        screen.fraction?.let { StartBar(it, Modifier.widthIn(max = 400.dp).fillMaxWidth()) }
        Text(
            screen.label,
            color = if (failed) Sov.Red300 else Sov.Slate100,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (screen.detail.isNotEmpty()) {
            Text(
                screen.detail,
                color = Sov.Slate300,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
        // How to leave a start that is slow, stuck or failed: B goes to the game.
        Text(
            GameStart.MENU_HINT,
            color = Sov.Slate400,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** The loading screen's scrim: black at 55%, twice along the bottom. */
private val SCRIM = Color.Black.copy(alpha = 0.55f)

/**
 * The start's bar, gliding to each new [fraction] (polled four times a second), and never back
 * ([ForwardOnly], its one owner, over the whole start): a phase that reports less than the last
 * (a check of the connection that starts again, the pair changed) holds it where it is.
 */
@Composable
private fun StartBar(fraction: Double, modifier: Modifier = Modifier) {
    val forward = remember { ForwardOnly() }
    var furthest by remember { mutableFloatStateOf(fraction.toFloat()) }
    LaunchedEffect(fraction) { furthest = forward.next(fraction).toFloat() }
    val shown by animateFloatAsState(furthest, tween(250), label = "start")
    LinearProgressIndicator(
        progress = { shown },
        modifier = modifier.height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = Sov.Accent,
        trackColor = Color.White.copy(alpha = 0.15f),
        drawStopIndicator = {},
    )
}

private const val STATS_TAG = "SovEmu.Stats"

/** The colour of each graphed value's line, by its row's label: the graph's key. */
private val GRAPH_KEY = mapOf(
    "Measured button→frame" to Sov.Accent,
    "Estimated button→pixel" to Sov.Slate400,
    "Audio delay" to Sov.Amber200,
)

/** The stats panel's rows: 10 sp monospace on a 12 sp line, with no extra padding around it. */
private val STATS_TEXT = TextStyle(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontFamily = FontFamily.Monospace,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Seconds without a new video frame, while playing, before it is reported as a stall. */
private const val STALL_SECONDS = 3

/** How long a notice of a change in quality stays at the top of the screen (Sovereign's 5 s). */
private const val QUALITY_NOTICE_MS = 5000L

/** Where stats are turned on, and on the overlay: they cost a little every second. */
internal const val STATS_WARNING = "Showing stats can affect game performance."

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
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val half = (rows.size + 1) / 2
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatsColumn(rows.subList(0, half))
            StatsColumn(rows.subList(half, rows.size))
        }
        LatencyGraph(history, samples, Modifier.padding(top = 6.dp).fillMaxWidth().height(48.dp))
        Text(
            STATS_WARNING,
            Modifier.padding(top = 3.dp),
            color = Sov.Slate400,
            fontSize = 9.sp,
            lineHeight = 11.sp,
        )
    }
}

/** Label and value pairs: labels muted on the left, values right-aligned. */
@Composable
private fun StatsColumn(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    // Small and tight: the panel sits over the game, so it should cover as little as it can.
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column { for ((label, _) in rows) Text(label, color = Sov.Slate400, style = STATS_TEXT) }
        Column(horizontalAlignment = Alignment.End) {
            for ((label, value) in rows) {
                // The graph's key: a jewel in its line's colour beside each value it draws.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GRAPH_KEY[label]?.let {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(it))
                    }
                    Text(value, color = Color.White, style = STATS_TEXT)
                }
            }
        }
    }
}

/**
 * The last minute of "Measured button→frame" (solid, accent), "Estimated button→pixel" (thin,
 * muted) from 0 to [LatencyHistory.scaleMaxMs], and "Audio delay" (thin, amber) from 0 to its own
 * [LatencyHistory.audioScaleMaxMs], with the two scales' marks on the gridlines and the latest
 * measurement at the right. Each line's colour is the jewel beside its
 * value in the rows above ([GRAPH_KEY]). Everything is read while drawing, so a new sample only
 * redraws.
 */
@Composable
private fun LatencyGraph(
    history: LatencyHistory,
    samples: () -> Int,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val label = remember {
        TextStyle(color = Sov.Slate500, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
    val audioLabel = remember {
        TextStyle(color = Sov.Amber200, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
    val latest = remember {
        TextStyle(
            color = Sov.Accent,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
    val measuredPath = remember { Path() }
    val estimatedPath = remember { Path() }
    val audioPath = remember { Path() }
    Canvas(modifier) {
        samples()
        val top = history.scaleMaxMs()
        val latestText = history.latestMeasured?.let {
            measurer.measure("${it.roundToInt()} ms", latest)
        }
        val right = size.width - (latestText?.size?.width ?: 0) - 4.dp.toPx()
        fun y(ms: Double) = (size.height * (1 - ms / top)).toFloat()
        // The audio's delay has a scale of its own (it runs several times the button's): its
        // marks sit beside the button scale's on the same two gridlines, in the audio's colour.
        val audioTop = history.audioScaleMaxMs()
        for (share in doubleArrayOf(1.0, 0.5)) {
            val ms = top * share
            drawLine(Sov.Edge, Offset(0f, y(ms)), Offset(right, y(ms)), strokeWidth = 1f)
            val mark = measurer.measure("${ms.roundToInt()}", label)
            drawText(mark, topLeft = Offset(0f, y(ms)))
            audioTop?.let {
                val audioMark = measurer.measure("${(it * share).roundToInt()}", audioLabel)
                drawText(audioMark, topLeft = Offset(mark.size.width + 4.dp.toPx(), y(ms)))
            }
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
        if (audioTop != null) {
            // Traced on the audio's scale, then drawn like the others.
            audioPath.trace { i -> history.audio(i) * top / audioTop }
            drawPath(audioPath, Sov.Amber200, style = Stroke(width = 1.dp.toPx()))
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
internal fun TouchController(controller: Controller, modifier: Modifier) {
    Box(modifier.padding(24.dp)) {
        Column(
            Modifier.align(Alignment.BottomStart),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Pad("▲", Button.UP, controller)
            Row {
                Pad("◀", Button.LEFT, controller)
                Box(Modifier.size(56.dp))
                Pad("▶", Button.RIGHT, controller)
            }
            Pad("▼", Button.DOWN, controller)
        }
        Row(
            Modifier.align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Pad("Select", Button.SELECT, controller, width = 72.dp, height = 32.dp)
            Pad("Start", Button.START, controller, width = 72.dp, height = 32.dp)
        }
        // The way out on a phone: opens the in-game menu (as Back does).
        Box(
            Modifier.align(Alignment.TopCenter).size(44.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f)).clickable { controller.requestMenu() },
            contentAlignment = Alignment.Center,
        ) { LucideIcon(Lucide.Menu, tint = Color.White.copy(alpha = 0.9f)) }
        Column(
            Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Pad("X", Button.X, controller, round = true)
            Row {
                Pad("Y", Button.Y, controller, round = true)
                Box(Modifier.size(56.dp))
                Pad("A", Button.A, controller, round = true)
            }
            Pad("B", Button.B, controller, round = true)
        }
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Pad("L", Button.L, controller, width = 88.dp, height = 40.dp)
            Pad("R", Button.R, controller, width = 88.dp, height = 40.dp)
        }
    }
}

@Composable
private fun Pad(
    label: String,
    button: Button,
    controller: Controller,
    round: Boolean = false,
    width: Dp = 56.dp,
    height: Dp = 56.dp,
) {
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
                    // When the overlay goes mid-press, Compose ends the gesture with a cancel
                    // event; if its coroutine is cancelled without one, the button must still
                    // be let go, never left held in the game.
                    try {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        pressed = false
                        controller.setTouch(button, false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}
