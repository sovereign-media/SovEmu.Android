package media.sovereign.sovemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A text field for a TV. At rest it is a box the D-pad passes over like any other (on a TV a
 * focused text field opens the keyboard at once, and the keyboard takes the D-pad, so a real field
 * could not be passed). A or a tap ([onTyping] true) swaps in the text field, focused, which opens
 * the keyboard; its action key, Back, or leaving it swaps the box back, focused.
 */
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
