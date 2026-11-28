package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import media.sovereign.sovemu.Controller
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.attempt
import media.sovereign.sovemu.core.Binding
import media.sovereign.sovemu.core.Button
import media.sovereign.sovemu.core.ControllerSettingsModel
import media.sovereign.sovemu.core.Controllers
import media.sovereign.sovemu.core.Identify
import media.sovereign.sovemu.core.InputDeviceInfo
import media.sovereign.sovemu.core.Mapping
import media.sovereign.sovemu.core.MappingBook
import media.sovereign.sovemu.core.MappingScope
import media.sovereign.sovemu.core.Protocol
import media.sovereign.sovemu.core.RebindCapture
import media.sovereign.sovemu.core.SystemStick
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.highlight
import media.sovereign.sovemu.ui.ChoiceField
import media.sovereign.sovemu.ui.FilterOption

/**
 * The controller settings, one component for both ways in: the in-game menu's *Controllers*,
 * opened on the game's system, and the account menu's, opened on the system last chosen. Every
 * connected device with its player, a choice of [systems] whose mappings to edit, and per device
 * its bindings, rebound by pressing an input on it. Mappings save to the server per controller
 * model, for the chosen system or for every system ([settings] holds them and tells a game
 * playing of each change); players are for this session only. Works with a controller: D-pad to
 * move, A to pick, B (Back) to go back.
 */
@Composable
fun ControllerSettings(
    controller: Controller,
    settings: ControllerSettingsModel,
    systems: List<SystemSummary>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Another system was chosen (the account menu remembers it). */
    onSystem: (String) -> Unit = {},
) {
    val state by settings.state.collectAsStateWithLifecycle()
    val devices by controller.devices.collectAsState()
    var selected by remember { mutableStateOf<Int?>(null) }
    val device = devices.firstOrNull { it.device == selected }
    // A controller unplugged while open: back to the list.
    LaunchedEffect(device == null) { if (device == null) selected = null }

    BackHandler { if (selected != null) selected = null else onClose() }

    val book = state.book
    Column(
        // The device list is as tall as its devices (centred by the overlay; it scrolls if it ever
        // outgrows the screen); the bindings page takes the screen for its diagrams.
        modifier
            .then(
                if (device == null || book == null) {
                    Modifier.width(560.dp)
                } else {
                    Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.96f)
                },
            )
            .clip(RoundedCornerShape(16.dp))
            .background(Sov.Panel)
            .border(1.dp, Sov.Edge, RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        if (device == null) {
            DeviceList(
                devices,
                controller,
                state,
                onOpen = { selected = it },
                onClose = onClose,
            )
        } else if (book == null) {
            // Another system chosen on the bindings page: its mappings load in place.
            Text(
                state.error ?: "Loading ${systemName(systems, state.system)}…",
                color = if (state.error != null) Sov.Amber200 else Sov.Slate300,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            // Another system's bindings start afresh: nothing of the last one's page carries over.
            key(state.system) {
                Bindings(
                    controller,
                    book,
                    Controls(state.controls, state.layouts),
                    systems,
                    device,
                    onSystem = { id ->
                        settings.select(id)
                        onSystem(id)
                    },
                    save = settings::save,
                    reset = settings::reset,
                    onBack = { selected = null },
                )
            }
        }
    }
}

/** A system's full name (`Super Nintendo`), or its id when it is not among [systems]. */
private fun systemName(systems: List<SystemSummary>, id: String?): String =
    systems.firstOrNull { it.id == id }?.name ?: id.orEmpty()

/** The systems [ControllerSettings] offers: those with games, and [current] whatever it holds. */
fun controllerSystems(systems: List<SystemSummary>, current: String?): List<SystemSummary> =
    systems.filter { it.gameCount > 0 || it.id == current }

@Composable
private fun ColumnScope.DeviceList(
    devices: List<InputDeviceInfo>,
    controller: Controller,
    state: ControllerSettingsModel.State,
    onOpen: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    /** Controllers *Identify* could neither rumble nor light up on this device: each says why. */
    var cannot by remember { mutableStateOf(emptySet<Int>()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LucideIcon(Lucide.Gamepad2, tint = Sov.Accent, size = 24.dp)
        Text("Controllers", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
    Text(
        "Each controller is its own player; choose another for this session. " +
            "A player is only ever one device. " +
            "The keyboard and touch controls are player 1 unless moved.",
        color = Sov.Muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
    )
    state.error?.let {
        Text(it, color = Sov.Amber200, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
    Column(
        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (devices.isEmpty()) {
            Text(
                "No controller or keyboard connected.",
                color = Sov.Slate300,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        devices.forEachIndexed { i, d ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Sov.Surface.copy(alpha = 0.6f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Sov.Accent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        LucideIcon(Lucide.Gamepad2, tint = Sov.Accent, size = 20.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            if (d.isKeyboard) "Keyboard" else d.model.family.title,
                            color = Sov.Slate400,
                            fontSize = 12.sp,
                        )
                    }
                    if (!d.isKeyboard) {
                        SovButton(
                            "Identify",
                            {
                                // Neither rumble nor lights: say so, rather than do nothing.
                                cannot = if (controller.identify(d.device, scope)) {
                                    cannot - d.device
                                } else {
                                    cannot + d.device
                                }
                            },
                            variant = Variant.Glass,
                            icon = Lucide.Vibrate,
                            large = false,
                        )
                    }
                    SovButton(
                        if (state.loading) "Loading…" else "Buttons",
                        { if (state.book != null) onOpen(d.device) },
                        variant = Variant.Glass,
                        icon = Lucide.SlidersHorizontal,
                        large = false,
                    )
                }
                if (d.device in cannot) {
                    Text(
                        Identify.unsupported(d.name),
                        color = Sov.Amber200,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Eyebrow("Player", Modifier.padding(end = 6.dp))
                    for (port in 0 until Protocol.MAX_PORTS) {
                        Toggle(
                            on = d.port == port,
                            onClick = { controller.assign(d.device, port) },
                            modifier = if (i == 0 && port == (d.port ?: 0)) {
                                Modifier.focusRequester(first)
                            } else {
                                Modifier
                            },
                        ) {
                            Text(
                                "${port + 1}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
    SovButton(
        "Done",
        onClose,
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .then(if (devices.isEmpty()) Modifier.focusRequester(first) else Modifier),
        icon = Lucide.Check,
    )
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * One device's bindings, for the game's system or for every system: the system's buttons with
 * its own labels (all sixteen RetroPad names for every system), and the console's pad next to
 * the player's controller, lighting the focused button on both.
 */
@Composable
private fun ColumnScope.Bindings(
    controller: Controller,
    book: MappingBook,
    controls: Controls,
    /** The systems the dropdown offers, by their full names. */
    systems: List<SystemSummary>,
    device: InputDeviceInfo,
    /** Another system was chosen: its mappings load in place of [book]'s. */
    onSystem: (String) -> Unit,
    /** Saves a model's [Mapping]'s bindings for a scope, and reads back what is in force. */
    save: suspend (
        model: String,
        scope: MappingScope,
        name: String,
        bindings: List<Binding>,
    ) -> Unit,
    /** Removes the user's own mapping for a model in a scope, and reads back what is in force. */
    reset: suspend (model: String, scope: MappingScope) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model = device.model
    var mappingScope by remember { mutableStateOf(MappingScope.SYSTEM) }
    var mapping by remember(model.id, mappingScope) {
        mutableStateOf(book.mapping(model.id, mappingScope))
    }
    var canReset by remember(model.id, mappingScope) {
        mutableStateOf(book.canReset(model.id, mappingScope))
    }
    var capturing by remember { mutableStateOf<Button?>(null) }
    var focused by remember { mutableStateOf<Button?>(null) }
    var focusedStick by remember { mutableStateOf<SystemStick?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    /** After a save or reset: this page shows what is now in force. */
    fun refreshed() {
        mapping = book.mapping(model.id, mappingScope)
        canReset = book.canReset(model.id, mappingScope)
    }

    fun save(m: Mapping) {
        val s = mappingScope
        scope.launch {
            message = attempt {
                save(model.id, s, device.name, m.bindings)
                refreshed()
            }.fold({ "Saved" }, { e -> "Not saved: ${e.message}" })
        }
    }

    fun rebind(button: Button) {
        message = null
        capturing = button
        controller.startCapture(
            model,
            onInput = { input ->
                capturing = null
                val m = mapping.rebind(button, input)
                mapping = m
                // For this system it is in force at once; an every-system one may sit under this
                // system's.
                if (mappingScope == MappingScope.SYSTEM) controller.mixer.setMapping(model.id, m)
                save(m)
            },
            onCancel = { timedOut ->
                capturing = null
                message = if (timedOut) "No input: nothing changed" else null
            },
            onRejected = { message = it },
        )
    }

    val system = controls.system
    // The rows: the system's buttons with its labels, or every RetroPad button for every system.
    val rows = if (mappingScope == MappingScope.SYSTEM && system != null) {
        system.buttons.map { it.button to it.label }
    } else {
        Controllers.REMAP_ORDER.map { it to Controllers.buttonLabel(it) }
    }
    val labelOf = rows.toMap()
    // The system's analog controls, after its buttons: shown, never rebound (sticks are not
    // remapped).
    val sticks = if (mappingScope == MappingScope.SYSTEM && !device.isKeyboard) {
        system?.sticks.orEmpty()
    } else {
        emptyList()
    }
    val lit = capturing?.let { highlight(it, mapping) }
        ?: focusedStick?.highlight()
        ?: focused?.let { highlight(it, mapping) }
    val pad = if (device.isKeyboard) {
        null
    } else {
        controls.layouts.firstOrNull { it.id == model.family.layoutId }
            ?: controls.layouts.firstOrNull { it.id == "generic" }
    }

    // Header: the device, and whose mappings these are: one system's, or every system's.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Sov.Accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(Lucide.Gamepad2, tint = Sov.Accent, size = 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        ChoiceField(
            "System",
            listOf(FilterOption<String?>(null, "All systems")) +
                systems.map { FilterOption<String?>(it.id, it.name) },
            selected = if (mappingScope == MappingScope.ALL) null else book.system,
            onSelect = { id ->
                controller.cancelCapture()
                capturing = null
                message = null
                when (id) {
                    null -> mappingScope = MappingScope.ALL
                    book.system -> mappingScope = MappingScope.SYSTEM
                    else -> onSystem(id)
                }
            },
            modifier = Modifier.width(280.dp),
        )
    }
    val back = Controllers.inputLabel(model.id, model.family, RebindCapture.backInput(model.id))
    // What to do now: a bright banner while waiting for an input, else a quiet hint (or the
    // result).
    val waiting = capturing
    Row(
        Modifier
            .padding(vertical = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (waiting != null) {
                    Sov.Accent.copy(alpha = 0.25f)
                } else {
                    Color.White.copy(alpha = 0.04f)
                },
            )
            .borderIf(waiting != null, 1.dp, Sov.Accent, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LucideIcon(
            if (waiting != null) Lucide.Gamepad2 else Lucide.CircleAlert,
            tint = if (waiting != null) Color.White else Sov.Slate400,
            size = 18.dp,
        )
        if (waiting != null) {
            Text("Press a button for ", color = Color.White, fontSize = 15.sp)
            KeyCap(labelOf[waiting] ?: Controllers.buttonLabel(waiting), accent = true)
            Text(
                "  ·  tap $back to cancel, hold it to bind $back",
                color = Sov.Slate300,
                fontSize = 13.sp,
            )
        } else {
            Text(
                message ?: "Pick a button, then press what should drive it.",
                color = Sov.Slate300,
                fontSize = 14.sp,
            )
        }
    }

    val list: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEachIndexed { i, (button, label) ->
                BindingRow(
                    label = label,
                    inputs = mapping.inputs(button).map {
                        Controllers.inputLabel(model.id, model.family, it)
                    },
                    capturing = capturing == button,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onFocused = {
                        if (it) {
                            focused = button
                        } else if (focused == button) {
                            focused = null
                        }
                    },
                ) { rebind(button) }
            }
            for (stick in sticks) {
                BindingRow(
                    label = stick.label,
                    inputs = listOf(stick.stickName),
                    capturing = false,
                    onFocused = {
                        if (it) {
                            focusedStick = stick
                        } else if (focusedStick == stick) {
                            focusedStick = null
                        }
                    },
                ) {
                    message =
                        "${stick.label} is always the ${stick.stickName.lowercase()}: " +
                        "sticks are not remapped"
                }
            }
        }
    }
    val diagrams: @Composable (Modifier, Boolean) -> Unit = { modifier, sideBySide ->
        val parts = listOfNotNull(
            system?.layout?.let { it to setOfNotNull(lit?.systemKey) },
            pad?.let { it to lit?.controllerKeys.orEmpty() },
        )
        if (sideBySide) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((layout, keys) in parts) {
                    DiagramPanel { PadDiagram(layout, keys, Modifier.fillMaxSize().padding(12.dp)) }
                }
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((layout, keys) in parts) {
                    DiagramPanel { PadDiagram(layout, keys, Modifier.fillMaxSize().padding(12.dp)) }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val w = this.maxWidth
        val h = this.maxHeight
        val hasDiagrams = system != null || pad != null
        if (!hasDiagrams) {
            list(Modifier.fillMaxSize())
        } else if (w > h) {
            // Landscape: the pads beside the buttons, side by side or stacked, whichever draws
            // larger.
            val area = w * 0.6f
            val sideBySide = minOf(area.value / 2 / 160, h.value / 100) >=
                minOf(area.value / 160, h.value / 2 / 100)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                diagrams(Modifier.weight(1.5f).fillMaxHeight(), sideBySide)
                list(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Portrait: the pads stacked above the buttons.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                diagrams(Modifier.fillMaxWidth().height(minOf(w * 0.7f * 2, h * 0.55f)), false)
                list(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        // Only the user's own mapping in this scope can be reset; what applies next is the
        // server's.
        if (canReset) {
            SovButton("Reset to defaults", {
                controller.cancelCapture()
                capturing = null
                val s = mappingScope
                scope.launch {
                    message = attempt {
                        reset(model.id, s)
                        mapping = book.mapping(model.id, s)
                        canReset = book.canReset(model.id, s)
                    }.fold(
                        {
                            runCatching { first.requestFocus() }
                            "Back to the defaults"
                        },
                        { e -> "Not reset: ${e.message}" },
                    )
                }
            }, variant = Variant.Outline, icon = Lucide.RotateCcw)
        }
        SovButton("Back", {
            controller.cancelCapture()
            onBack()
        }, icon = Lucide.ChevronLeft)
    }
}

/** A diagram on its own panel, sharing the row or column with the other. */
@Composable
private fun RowScope.DiagramPanel(content: @Composable () -> Unit) = Box(
    Modifier
        .weight(1f)
        .fillMaxHeight()
        .clip(RoundedCornerShape(12.dp))
        .background(Sov.Surface.copy(alpha = 0.6f)),
) { content() }

@Composable
private fun ColumnScope.DiagramPanel(content: @Composable () -> Unit) = Box(
    Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(Sov.Surface.copy(alpha = 0.6f)),
) { content() }

/** A key cap: how an input or a console button is shown (accent while it is being bound). */
@Composable
private fun KeyCap(text: String, accent: Boolean = false) = Text(
    text,
    color = Color.White,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold,
    maxLines = 1,
    modifier = Modifier.clip(RoundedCornerShape(6.dp))
        .background(if (accent) Sov.Accent else Color.White.copy(alpha = 0.12f))
        .border(
            1.dp,
            Color.White.copy(alpha = if (accent) 0.4f else 0.15f),
            RoundedCornerShape(6.dp),
        )
        .padding(horizontal = 8.dp, vertical = 3.dp),
)

/**
 * One of the system's buttons and what drives it: the console's label, then the bound inputs as
 * key caps. Focus fills it with the accent (and lights the button on both diagrams); waiting for
 * an input, it says so.
 */
@Composable
private fun BindingRow(
    label: String,
    inputs: List<String>,
    capturing: Boolean,
    modifier: Modifier = Modifier,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocused(focused) }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    capturing -> Sov.Accent.copy(alpha = 0.35f)
                    focused -> Sov.Accent.copy(alpha = 0.22f)
                    else -> Color.Transparent
                },
            )
            .borderIf(
                focused || capturing,
                2.dp,
                if (capturing) Sov.Accent else Color.White.copy(alpha = 0.6f),
                RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        when {
            capturing -> Text("Press an input…", color = Color.White, fontSize = 13.sp)
            inputs.isEmpty() -> Text("Not bound", color = Sov.Slate500, fontSize = 13.sp)
            else -> inputs.take(3).forEach { KeyCap(it) }
        }
    }
}
