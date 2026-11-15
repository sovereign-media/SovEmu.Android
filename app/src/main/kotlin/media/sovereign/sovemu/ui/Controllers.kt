package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import media.sovereign.sovemu.Controller
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.Button
import media.sovereign.sovemu.core.Controllers
import media.sovereign.sovemu.core.InputDeviceInfo
import media.sovereign.sovemu.core.Mapping
import media.sovereign.sovemu.core.MappingBook
import media.sovereign.sovemu.core.MappingScope
import media.sovereign.sovemu.core.highlight
import media.sovereign.sovemu.core.Protocol
import media.sovereign.sovemu.core.RebindCapture

private val Panel = Color(0xFF151922)
private val Muted = Color(0xFF8B93A7)
private val Focus = Color(0xFF4169E1)

/**
 * The in-game controller settings: every connected device with its player, and per device its
 * bindings, rebound by pressing an input on it. Mappings save to the server per controller
 * model; players are for this session only. Works with a controller: D-pad to move, A to pick,
 * B (Back) to go back.
 */
@Composable
fun ControllersScreen(
    api: SovEmuApi,
    controller: Controller,
    book: MappingBook,
    controls: Controls,
    systemShort: String,
    onClose: () -> Unit,
) {
    val devices by controller.devices.collectAsState()
    var selected by remember { mutableStateOf<Int?>(null) }
    val device = devices.firstOrNull { it.device == selected }
    // A controller unplugged while open: back to the list.
    LaunchedEffect(device == null) { if (device == null) selected = null }

    BackHandler { if (selected != null) selected = null else onClose() }

    Column(
        (if (device == null) Modifier.width(440.dp).fillMaxHeight(0.92f) else Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.96f))
            .clip(RoundedCornerShape(16.dp)).background(Panel).padding(12.dp),
    ) {
        if (device == null) {
            DeviceList(devices, controller, onOpen = { selected = it }, onClose = onClose)
        } else {
            Bindings(api, controller, book, controls, systemShort, device, onBack = { selected = null })
        }
    }
}

@Composable
private fun ColumnScope.DeviceList(devices: List<InputDeviceInfo>, controller: Controller, onOpen: (Int) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    Text("Controllers", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
    Text(
        "Each controller is its own player. The keyboard and touch controls are player 1 unless moved.",
        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
    )
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        if (devices.isEmpty()) {
            Text("No controller or keyboard connected.", color = Color.White, modifier = Modifier.padding(8.dp))
        }
        devices.forEachIndexed { i, d ->
            Column(Modifier.padding(top = 12.dp)) {
                Text(d.name, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                Text(
                    if (d.isKeyboard) "Keyboard" else "${d.model.family.title} · ${d.model.id}",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusButton(
                        "Player ${d.port?.let { it + 1 } ?: "—"}",
                        Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) { controller.assign(d.device, ((d.port ?: -1) + 1) % Protocol.MAX_PORTS) }
                    FocusButton("Buttons", Modifier.weight(1f)) { onOpen(d.device) }
                }
            }
        }
    }
    FocusButton("Done", Modifier.fillMaxWidth().then(if (devices.isEmpty()) Modifier.focusRequester(first) else Modifier), onClick = onClose)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * One device's bindings, for the game's system or for every system: the system's buttons with
 * its own labels (all sixteen RetroPad names for every system), and the console's pad next to
 * the player's controller, lighting the focused button on both.
 */
@Composable
private fun ColumnScope.Bindings(
    api: SovEmuApi,
    controller: Controller,
    book: MappingBook,
    controls: Controls,
    systemShort: String,
    device: InputDeviceInfo,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model = device.model
    var mappingScope by remember { mutableStateOf(MappingScope.SYSTEM) }
    var mapping by remember(model.id, mappingScope) { mutableStateOf(book.mapping(model.id, mappingScope)) }
    var canReset by remember(model.id, mappingScope) { mutableStateOf(book.canReset(model.id, mappingScope)) }
    var inForce by remember(model.id) { mutableStateOf(MappingBook.sourceText(book.entry(model.id, MappingScope.SYSTEM))) }
    var capturing by remember { mutableStateOf<Button?>(null) }
    var focused by remember { mutableStateOf<Button?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    /** After a save or reset: fetch both scopes again, so the game and this page use what is now in force. */
    suspend fun refresh() {
        book.refresh(api::controllerMappings)
        controller.mixer.setMappings(book.forSystem)
        mapping = book.mapping(model.id, mappingScope)
        canReset = book.canReset(model.id, mappingScope)
        inForce = MappingBook.sourceText(book.entry(model.id, MappingScope.SYSTEM))
    }

    fun save(m: Mapping) {
        val system = book.systemArg(mappingScope)
        scope.launch {
            message = runCatching {
                api.saveControllerMapping(model.id, system, device.name, m.bindings)
                refresh()
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
                // For this system it is in force at once; an every-system one may sit under this system's.
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
    val lit = (capturing ?: focused)?.let { highlight(it, mapping) }
    val pad = if (device.isKeyboard) null else {
        controls.layouts.firstOrNull { it.id == model.family.layoutId } ?: controls.layouts.firstOrNull { it.id == "generic" }
    }

    Text(device.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 4.dp, end = 8.dp))
    Text("In force here: $inForce", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((s, label) in listOf(MappingScope.SYSTEM to "This system ($systemShort)", MappingScope.ALL to "All systems")) {
            FocusButton(
                (if (mappingScope == s) "● " else "○ ") + label,
                Modifier.weight(1f),
            ) {
                controller.cancelCapture()
                capturing = null
                message = null
                mappingScope = s
            }
        }
    }
    val back = Controllers.inputLabel(model.id, model.family, RebindCapture.backInput(model.id))
    val prompt = capturing?.let { "Press a button for ${labelOf[it] ?: Controllers.buttonLabel(it)} · tap $back to cancel, hold it to bind $back" }
    Text(
        prompt ?: message ?: "Pick a button, then press what should drive it.",
        color = if (prompt != null) Color.White else Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )

    val list: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { i, (button, label) ->
                val inputs = mapping.inputs(button)
                val bound = if (inputs.isEmpty()) "—" else inputs.joinToString(", ") { Controllers.inputLabel(model.id, model.family, it) }
                FocusButton(
                    label,
                    Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    trailing = if (capturing == button) "←  …" else "←  $bound",
                    onFocused = { if (it) focused = button else if (focused == button) focused = null },
                ) { rebind(button) }
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
                for ((layout, keys) in parts) PadDiagram(layout, keys, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((layout, keys) in parts) PadDiagram(layout, keys, Modifier.weight(1f).fillMaxWidth())
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
            // Landscape: the pads beside the buttons, side by side or stacked, whichever draws larger.
            val area = w * 0.6f
            val sideBySide = minOf(area.value / 2 / 160, h.value / 100) >= minOf(area.value / 160, h.value / 2 / 100)
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Only the user's own mapping in this scope can be reset; what applies next is the server's.
        if (canReset) FocusButton("Reset to defaults", Modifier.weight(1f)) {
            controller.cancelCapture()
            capturing = null
            val s = mappingScope
            scope.launch {
                message = runCatching {
                    book.reset(model.id, s, api::resetControllerMapping, api::controllerMappings)
                    controller.mixer.setMappings(book.forSystem)
                    mapping = book.mapping(model.id, s)
                    canReset = book.canReset(model.id, s)
                    inForce = MappingBook.sourceText(book.entry(model.id, MappingScope.SYSTEM))
                }.fold(
                    {
                        runCatching { first.requestFocus() }
                        "Back to the defaults"
                    },
                    { e -> "Not reset: ${e.message}" },
                )
            }
        }
        FocusButton("Back", Modifier.weight(1f)) {
            controller.cancelCapture()
            onBack()
        }
    }
}

/** A text button that shows clearly when it has focus (for D-pad navigation). */
@Composable
internal fun FocusButton(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onFocused: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    TextButton(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                if (focused != it.hasFocus) onFocused?.invoke(it.hasFocus)
                focused = it.hasFocus
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Focus.copy(alpha = 0.45f) else Color.Transparent),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}
