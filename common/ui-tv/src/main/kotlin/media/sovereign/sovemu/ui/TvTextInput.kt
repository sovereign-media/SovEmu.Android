package media.sovereign.sovemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/** A TV's [TextInput]: the [TvTextField], typed with the TV's own keyboard. */
object TvTextInput : TextInput {
    @Composable
    override fun Field(
        label: String,
        value: String,
        onValue: (String) -> Unit,
        typing: Boolean,
        onTyping: (Boolean) -> Unit,
        modifier: Modifier,
        mono: Boolean,
        action: ImeAction,
        onAction: () -> Unit,
    ) = TvTextField(
        label, value, onValue, typing, onTyping, modifier,
        mono = mono, action = action, onAction = onAction,
    )
}
