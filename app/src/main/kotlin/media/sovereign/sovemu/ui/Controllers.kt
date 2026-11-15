package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import media.sovereign.sovemu.core.MappingSource
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
fun ControllersScreen(api: SovEmuApi, controller: Controller, onClose: () -> Unit) {
    val devices by controller.devices.collectAsState()
    var selected by remember { mutableStateOf<Int?>(null) }
    val device = devices.firstOrNull { it.device == selected }
    // A controller unplugged while open: back to the list.
    LaunchedEffect(device == null) { if (device == null) selected = null }

    BackHandler { if (selected != null) selected = null else onClose() }

    Column(
        Modifier.width(440.dp).fillMaxHeight(0.92f).clip(RoundedCornerShape(16.dp)).background(Panel).padding(12.dp),
    ) {
        if (device == null) {
            DeviceList(devices, controller, onOpen = { selected = it }, onClose = onClose)
        } else {
            Bindings(api, controller, device, onBack = { selected = null })
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

@Composable
private fun ColumnScope.Bindings(api: SovEmuApi, controller: Controller, device: InputDeviceInfo, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = device.model
    var mapping by remember(model.id) { mutableStateOf(controller.mixer.mapping(model.id)) }
    var source by remember(model.id) { mutableStateOf(controller.mixer.source(model.id)) }
    var capturing by remember { mutableStateOf<Button?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun save(m: Mapping) {
        scope.launch {
            message = runCatching { api.saveControllerMapping(model.id, device.name, m.bindings) }.fold(
                { saved ->
                    val stored = Mapping.of(saved.bindings)
                    controller.mixer.setMapping(model.id, stored, saved.source)
                    mapping = stored
                    source = saved.source
                    "Saved"
                },
                { e -> "Not saved: ${e.message}" },
            )
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
                source = MappingSource.USER
                controller.mixer.setMapping(model.id, m)
                save(m)
            },
            onCancel = { timedOut ->
                capturing = null
                message = if (timedOut) "No input: nothing changed" else null
            },
            onRejected = { message = it },
        )
    }

    Text(device.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
    Text(
        when (source) {
            MappingSource.USER -> "Yours: saved for every ${if (device.isKeyboard) "keyboard" else "controller like this one"}"
            MappingSource.ADMIN, MappingSource.ENVIRONMENT -> "Default: set on this server"
            else -> "Default"
        },
        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp),
    )
    val back = Controllers.inputLabel(model.id, model.family, RebindCapture.backInput(model.id))
    val prompt = capturing?.let { "Press a button for ${Controllers.buttonLabel(it)} · tap $back to cancel, hold it to bind $back" }
    Text(
        prompt ?: message ?: "Pick a button, then press what should drive it.",
        color = if (prompt != null) Color.White else Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
    )
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Controllers.REMAP_ORDER.forEachIndexed { i, button ->
            val inputs = mapping.inputs(button)
            val bound = if (inputs.isEmpty()) "—" else inputs.joinToString(", ") { Controllers.inputLabel(model.id, model.family, it) }
            FocusButton(
                Controllers.buttonLabel(button),
                Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                trailing = if (capturing == button) "…" else bound,
            ) { rebind(button) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Only the user's own mapping can be reset; the defaults may be the server's (an admin's).
        if (source == MappingSource.USER) FocusButton("Reset to defaults", Modifier.weight(1f)) {
            controller.cancelCapture()
            capturing = null
            scope.launch {
                message = runCatching {
                    controller.mixer.resetMapping(model.id, api::resetControllerMapping, api::controllerMappings)
                }.fold(
                    { inForce ->
                        mapping = inForce
                        source = controller.mixer.source(model.id)
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
internal fun FocusButton(label: String, modifier: Modifier = Modifier, trailing: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TextButton(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.hasFocus }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Focus.copy(alpha = 0.45f) else Color.Transparent),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}
