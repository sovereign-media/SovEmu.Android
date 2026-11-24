package media.sovereign.sovemu.ui

import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's own on-screen keyboard for TVs. Fire TV's system keyboard is a full-screen panel no
 * app can shrink or move, which hides the form being filled in; this one is a strip along the
 * bottom of the screen, below whatever is being typed into, driven by the D-pad. A [TvTextField]
 * asks for it while it is being typed in (on a TV, under a [TvKeyboardHost]); A presses a key,
 * Back closes it, and a hardware keyboard types into it too.
 */

/** A key on the keyboard. */
sealed interface TvKey {
    /** Types its text (a letter, digit or symbol). */
    data class Text(val text: String) : TvKey
    data object Shift : TvKey
    /** Between the letters and the symbols. */
    data object Page : TvKey
    data object Space : TvKey
    data object Delete : TvKey
    /** The field's action: Done, Search, Next … */
    data object Action : TvKey
}

/** Which keys show: the letters (shifted or not) or the symbols. */
enum class TvKeyPage { LETTERS, SYMBOLS }

/**
 * The keys in rows, for a field of [type]: digits, three rows of letters with the punctuation
 * the field is likely to want (an address's `:` `/` `.`, an email's `@`), then the controls.
 * The symbols page has the rest.
 */
fun tvKeyRows(page: TvKeyPage, shift: Boolean, type: KeyboardType): List<List<TvKey>> {
    fun keys(s: String) = s.map { TvKey.Text(it.toString()) }
    val controls = listOf(TvKey.Shift, TvKey.Page, TvKey.Space, TvKey.Delete, TvKey.Action)
    if (page == TvKeyPage.SYMBOLS) {
        return listOf(
            keys("1234567890"),
            keys("!@#\$%^&*()"),
            keys("-_=+[]{};:"),
            keys("'\"\\|,.<>/?"),
            keys("`~€£") + controls,
        )
    }
    val extras = when (type) {
        KeyboardType.Uri -> listOf(":", "/", ".", "-")
        KeyboardType.Email -> listOf("@", ".", "_", "-")
        else -> listOf(",", ".", "'", "-")
    }
    val letters = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { row ->
        keys(if (shift) row.uppercase() else row)
    }
    return listOf(
        keys("1234567890"),
        letters[0],
        letters[1] + TvKey.Text(extras[0]),
        letters[2] + extras.drop(1).map { TvKey.Text(it) },
        controls,
    )
}

/** What a key's cap shows. */
fun tvKeyLabel(key: TvKey, page: TvKeyPage, shift: Boolean, action: ImeAction): String = when (key) {
    is TvKey.Text -> key.text
    TvKey.Shift -> if (shift) "⇧ on" else "⇧"
    TvKey.Page -> if (page == TvKeyPage.LETTERS) "#+=" else "abc"
    TvKey.Space -> "Space"
    TvKey.Delete -> "⌫"
    TvKey.Action -> when (action) {
        ImeAction.Search -> "Search"
        ImeAction.Next -> "Next"
        ImeAction.Go -> "Go"
        else -> "Done"
    }
}

/** The text after typing [key] into [value]: only letters, digits, symbols, Space and Delete change it. */
fun tvKeyTyped(value: String, key: TvKey): String = when (key) {
    is TvKey.Text -> value + key.text
    TvKey.Space -> "$value "
    TvKey.Delete -> value.dropLast(1)
    else -> value
}

/** What a text field gives the keyboard while it is being typed in. */
class TvKeyboardRequest(
    /** Whose request it is: a field clears the request only while it is still its own. */
    val owner: Any,
    val type: KeyboardType,
    val action: ImeAction,
    val value: () -> String,
    val onValue: (String) -> Unit,
    /** The action key: the field closes, then runs its action. */
    val onAction: () -> Unit,
    /** Back: the field closes. */
    val onClose: () -> Unit,
)

/** The keyboard's one request at a time (the field being typed in), shared through [LocalTvKeyboard]. */
@Stable
class TvKeyboardState {
    var request by mutableStateOf<TvKeyboardRequest?>(null)

    /** Lets go of the keyboard if [owner] still holds it. */
    fun release(owner: Any) {
        if (request?.owner === owner) request = null
    }
}

/** The app's keyboard, where one is hosted (on a TV); null elsewhere, where fields use the system's. */
val LocalTvKeyboard = staticCompositionLocalOf<TvKeyboardState?> { null }

/**
 * Hosts the app's keyboard under [content]: while a field types, the keyboard takes a strip at
 * the bottom and the content the rest of the screen above it, so nothing is hidden behind it.
 */
@Composable
fun TvKeyboardHost(enabled: Boolean, content: @Composable () -> Unit) {
    val state = remember { TvKeyboardState() }
    CompositionLocalProvider(LocalTvKeyboard provides if (enabled) state else null) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            state.request?.let { TvKeyboard(it) }
        }
    }
}

private val KeyHeight = 40.dp
private val KeyWidth = 52.dp
private val KeyGap = 6.dp

/** The keyboard: a strip of keys, the focus kept inside while it is open. */
@Composable
private fun TvKeyboard(request: TvKeyboardRequest, modifier: Modifier = Modifier) {
    var page by remember(request) { mutableStateOf(TvKeyPage.LETTERS) }
    var shift by remember(request) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(request) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    BackHandler(onBack = request.onClose)

    fun press(key: TvKey) {
        when (key) {
            TvKey.Shift -> shift = !shift
            TvKey.Page -> page = if (page == TvKeyPage.LETTERS) TvKeyPage.SYMBOLS else TvKeyPage.LETTERS
            TvKey.Action -> request.onAction()
            else -> {
                request.onValue(tvKeyTyped(request.value(), key))
                // Shift is for the next letter, as on a phone.
                if (key is TvKey.Text) shift = false
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Sov.Panel)
            .border(1.dp, Sov.Edge)
            .padding(vertical = 12.dp)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            // A hardware keyboard types too: its characters, Backspace and Enter. (Only a real
            // keyboard's: some remotes send Enter for select, which must press the focused key.)
            .onPreviewKeyEvent { e ->
                val typing = e.nativeKeyEvent.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                if (!typing || e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed) {
                    return@onPreviewKeyEvent false
                }
                when {
                    e.key == Key.Backspace -> { press(TvKey.Delete); true }
                    e.key == Key.Enter || e.key == Key.NumPadEnter -> { press(TvKey.Action); true }
                    e.utf16CodePoint >= ' '.code -> {
                        request.onValue(request.value() + e.utf16CodePoint.toChar())
                        true
                    }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        tvKeyRows(page, shift, request.type).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                row.forEachIndexed { c, key ->
                    val width = when (key) {
                        TvKey.Space -> KeyWidth * 4
                        TvKey.Action -> KeyWidth * 2
                        is TvKey.Text -> KeyWidth
                        else -> KeyWidth * 1.5f
                    }
                    KeyCap(
                        tvKeyLabel(key, page, shift, request.action),
                        width,
                        accent = key == TvKey.Action || (key == TvKey.Shift && shift),
                        modifier = if (r == 1 && c == 0) Modifier.focusRequester(first) else Modifier,
                    ) { press(key) }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, width: Dp, accent: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .width(width)
            .height(KeyHeight)
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    accent -> Sov.Accent
                    else -> Sov.Surface
                },
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) Sov.Slate950 else Sov.Slate100,
            fontSize = if (label.length > 2) 14.sp else 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
