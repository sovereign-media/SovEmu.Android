package media.sovereign.sovemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/**
 * How this surface types text, for a screen both surfaces share (the in-game menu's cheats, the
 * game panel's collections): a TV's field is a box the D-pad passes over until A, typed with the
 * TV's own keyboard (`TvTextInput`, `:common:ui-tv`); a phone's is a touch field
 * (`PhoneTextInput`, `:common:ui-mobile`). A surface's own screens use its field directly.
 */
interface TextInput {
    /**
     * [typing] is whether the field is being typed in: a TV swaps its box for the field while it
     * is, and a phone's field ignores it (it types when tapped). [onAction] is the keyboard's
     * [action] key.
     */
    @Composable
    fun Field(
        label: String,
        value: String,
        onValue: (String) -> Unit,
        typing: Boolean,
        onTyping: (Boolean) -> Unit,
        modifier: Modifier,
        mono: Boolean,
        action: ImeAction,
        onAction: () -> Unit,
    )
}

/** The surface's [TextInput]: each surface provides its own around the app. */
val LocalTextInput = staticCompositionLocalOf<TextInput> {
    error("No TextInput: the surface provides one around the app")
}

/** A text field drawn by the surface's [TextInput]. */
@Composable
fun TextInputField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    action: ImeAction = ImeAction.Done,
    onAction: () -> Unit = {},
) = LocalTextInput.current.Field(
    label, value, onValue, typing, onTyping, modifier, mono, action, onAction,
)
