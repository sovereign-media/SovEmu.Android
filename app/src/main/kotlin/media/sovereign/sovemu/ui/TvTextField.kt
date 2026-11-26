package media.sovereign.sovemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A text field for a TV. At rest it is a box the D-pad passes over like any other (on a TV a
 * focused text field opens the keyboard at once, and the keyboard takes the D-pad, so a real field
 * could not be passed). A or a tap ([onTyping] true) swaps in the text field, focused, which opens
 * the keyboard; its action key, Back, or the keyboard closing swaps the box back, focused. (The
 * system spends the first Back on hiding the keyboard, so the field never sees that one.)
 *
 * Under a [TvKeyboardHost] (on a TV) the box stays, and the app's own keyboard types into it
 * along the bottom of the screen instead of the system's, which on Fire TV covers everything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    action: ImeAction = ImeAction.Done,
    onAction: () -> Unit = {},
    /** What the keyboard offers: an address, a password … (none of them capitalised). */
    keyboardType: KeyboardType = KeyboardType.Text,
    /** A password: dots in the box and in the field. */
    secret: Boolean = false,
) {
    val box = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val appKeyboard = LocalTvKeyboard.current
    var wasTyping by remember { mutableStateOf(false) }
    LaunchedEffect(typing) {
        withFrameNanos { }
        if (typing) {
            // The app's keyboard takes the focus itself.
            if (appKeyboard == null) runCatching { field.requestFocus() }
        } else if (wasTyping && appKeyboard?.request == null) {
            // Back to the box, unless another field has gone on typing (a Next key).
            runCatching { box.requestFocus() }
        }
        wasTyping = typing
    }
    if (appKeyboard != null) {
        val owner = remember { Any() }
        val currentValue by rememberUpdatedState(value)
        val currentOnValue by rememberUpdatedState(onValue)
        val currentOnAction by rememberUpdatedState(onAction)
        val currentOnTyping by rememberUpdatedState(onTyping)
        LaunchedEffect(typing, keyboardType, action) {
            if (typing) {
                appKeyboard.request = TvKeyboardRequest(
                    owner,
                    keyboardType,
                    action,
                    value = { currentValue },
                    onValue = { currentOnValue(it) },
                    // Closed first, so an action that moves on to another field (Next) wins.
                    onAction = {
                        currentOnTyping(false)
                        currentOnAction()
                    },
                    onClose = { currentOnTyping(false) },
                )
            } else {
                appKeyboard.release(owner)
            }
        }
        DisposableEffect(appKeyboard) { onDispose { appKeyboard.release(owner) } }
    } else {
        // The system keyboard closed while typing (Back, or its own close key): back to the D-pad.
        val keyboard = WindowInsets.isImeVisible
        var keyboardShown by remember { mutableStateOf(false) }
        LaunchedEffect(keyboard, typing) {
            if (!typing) {
                keyboardShown = false
            } else if (keyboard) {
                keyboardShown = true
            } else if (keyboardShown) {
                onTyping(false)
            }
        }
    }
    val style = TextStyle(
        color = Sov.Slate100,
        fontSize = 15.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    )
    val shape = RoundedCornerShape(8.dp)
    if (typing && appKeyboard == null) {
        BasicTextField(
            value, onValue,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Sov.Accent),
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                capitalization = when {
                    mono -> KeyboardCapitalization.Characters
                    keyboardType == KeyboardType.Text -> KeyboardCapitalization.Sentences
                    else -> KeyboardCapitalization.None
                },
                autoCorrectEnabled = false,
                keyboardType = keyboardType,
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
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Sov.Surface)
                        .border(2.dp, Sov.Accent, shape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
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
                .border(
                    if (focused || typing) 2.dp else 1.dp,
                    when {
                        typing -> Sov.Accent
                        focused -> Color.White.copy(alpha = 0.7f)
                        else -> Sov.Edge
                    },
                    shape,
                )
                .clickable(interactionSource = source, indication = null) { onTyping(true) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            val shown = if (secret) "•".repeat(value.length) else value
            when {
                // Typed into by the app's keyboard: the end of the text, and a cursor after it.
                typing -> Text(
                    buildAnnotatedString {
                        append(shown)
                        withStyle(SpanStyle(color = Sov.Accent)) { append("▏") }
                    },
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )

                value.isEmpty() -> Text(
                    if (focused) "$label · press A to type" else label,
                    color = Sov.Slate500,
                    fontSize = 14.sp,
                )

                else -> Text(shown, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
