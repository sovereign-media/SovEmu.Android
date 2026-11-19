package media.sovereign.sovemu.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.attempt
import media.sovereign.sovemu.core.Cheat

/**
 * This user's cheats for the game, as the in-game menu's Cheats page: each one turns on or off
 * with A, and has a delete button beside it; a form below adds one (code and an optional name,
 * typed with the on-screen keyboard). A running session applies every change at once.
 */
@Composable
fun CheatsPage(api: SovEmuApi, gameId: Int, modifier: Modifier = Modifier, first: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var cheats by remember { mutableStateOf<List<Cheat>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    /** The field being typed in ("code" or "name"), or none. */
    var typing by remember { mutableStateOf<String?>(null) }
    val codeField = remember { FocusRequester() }
    val addButton = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(gameId) {
        cheats = attempt { api.cheats(gameId) }.getOrElse { e -> message = "Could not load cheats: ${e.message}"; emptyList() }
    }
    fun run(block: suspend () -> List<Cheat>) {
        scope.launch {
            attempt { block() }.onSuccess { cheats = it; message = null }.onFailure { e -> message = e.message }
        }
    }

    Column(modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val list = cheats
        when {
            list == null -> Text("Loading cheats…", color = Sov.Slate400, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
            list.isEmpty() -> Text("No cheats for this game yet. Add one below.", color = Sov.Slate400, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
        }
        list?.forEachIndexed { i, cheat ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CheatRow(cheat, Modifier.weight(1f).then(if (i == 0) first else Modifier)) {
                    run { api.setCheatEnabled(gameId, cheat.id, !cheat.enabled) }
                }
                IconAction(Lucide.X, "Delete") {
                    run {
                        api.deleteCheat(gameId, cheat.id)
                        api.cheats(gameId)
                    }
                }
            }
        }
        Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(1.dp).background(Sov.Edge))
        Eyebrow("Add a cheat", Modifier.padding(horizontal = 8.dp))
        fun add() {
            val c = code.trim()
            if (c.isEmpty()) {
                // Nothing typed yet: start typing the code.
                typing = "code"
            } else {
                // Codes are upper case; the keyboard capitalises as it types, and this catches the rest.
                // Both are read now: the request runs after the fields below are cleared.
                val newCode = c.uppercase()
                val newName = name.trim()
                run { api.addCheat(gameId, newCode, newName) }
                code = ""
                name = ""
                // Done typing: the keyboard goes, and the focus waits on Add for the next one.
                typing = null
                keyboard?.hide()
                // After the field hands its focus back (a frame), Add takes it.
                scope.launch {
                    withFrameNanos { }
                    withFrameNanos { }
                    runCatching { addButton.requestFocus() }
                }
            }
        }
        // The keyboard's action key goes from the code to the name, then adds the cheat.
        CheatField(
            "Code (Game Genie, Action Replay, raw)", code, { code = it }, mono = true,
            typing = typing == "code", onTyping = { typing = if (it) "code" else null },
            modifier = Modifier.focusRequester(codeField),
            action = ImeAction.Next, onAction = { typing = "name" },
        )
        CheatField(
            "Name (optional)", name, { name = it },
            typing = typing == "name", onTyping = { typing = if (it) "name" else null },
            action = ImeAction.Done, onAction = ::add,
        )
        SovButton(
            "Add cheat",
            ::add,
            // With no cheats yet the page starts here, not in a field (which would open the keyboard at once).
            Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(addButton).then(if (list.isNullOrEmpty()) first else Modifier),
            icon = Lucide.Check,
            large = false,
        )
        message?.let { Text(it, color = Sov.Red300, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
    }
}

/** A cheat: its name (or code), the code, and an on/off switch; A toggles it. */
@Composable
private fun CheatRow(cheat: Cheat, modifier: Modifier, onToggle: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Sov.Accent.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onToggle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(cheat.description.ifBlank { cheat.code }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (cheat.description.isNotBlank()) {
                Text(cheat.code, color = Sov.Slate400, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(cheat.enabled)
    }
}

/** A switch's look (the row is what takes the press). */
@Composable
private fun Switch(on: Boolean) =
    Box(
        Modifier.size(44.dp, 24.dp).clip(CircleShape).background(if (on) Sov.Accent else Color.White.copy(alpha = 0.15f)).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White)) }

/** A small focusable icon button beside a row. */
@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (focused) Color(0x55EF4444) else Color.White.copy(alpha = 0.06f))
            .clickable(interactionSource = source, indication = null, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LucideIcon(icon, tint = if (focused) Color.White else Sov.Slate400, size = 18.dp) }
}

/**
 * A text field for a TV. At rest it is a box the D-pad passes over like any other (on a TV a
 * focused text field opens the keyboard at once, and the keyboard takes the D-pad, so a real field
 * could not be passed). A or a tap ([onTyping] true) swaps in the text field, focused, which opens
 * the keyboard; its action key, Back, or leaving it swaps the box back, focused.
 */
@Composable
private fun CheatField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    action: ImeAction = ImeAction.Done,
    onAction: () -> Unit = {},
) {
    val box = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    var wasTyping by remember { mutableStateOf(false) }
    LaunchedEffect(typing) {
        withFrameNanos { }
        if (typing) runCatching { field.requestFocus() }
        else if (wasTyping) runCatching { box.requestFocus() }
        wasTyping = typing
    }
    val style = TextStyle(color = Sov.Slate100, fontSize = 15.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    val shape = RoundedCornerShape(8.dp)
    if (typing) {
        BasicTextField(
            value, onValue,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Sov.Accent),
            keyboardOptions = KeyboardOptions(
                capitalization = if (mono) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences,
                autoCorrectEnabled = false,
                imeAction = action,
            ),
            keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).focusRequester(field)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyUp && e.key == Key.Back) {
                        onTyping(false)
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(shape).background(Sov.Surface).border(2.dp, Sov.Accent, shape).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (value.isEmpty()) Text(label, color = Sov.Slate500, fontSize = 14.sp)
                    inner()
                }
            },
        )
    } else {
        val source = remember { MutableInteractionSource() }
        val focused by source.collectIsFocusedAsState()
        Box(
            modifier.fillMaxWidth().padding(horizontal = 4.dp).focusRequester(box)
                .clip(shape).background(Sov.Surface)
                .border(if (focused) 2.dp else 1.dp, if (focused) Color.White.copy(alpha = 0.7f) else Sov.Edge, shape)
                .clickable(interactionSource = source, indication = null) { onTyping(true) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(if (focused) "$label · press A to type" else label, color = Sov.Slate500, fontSize = 14.sp)
            } else {
                Text(value, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
