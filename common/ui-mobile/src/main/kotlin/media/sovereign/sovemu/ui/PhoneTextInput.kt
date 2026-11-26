package media.sovereign.sovemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/** A phone's [TextInput]: the touch [PhoneTextField], which types when tapped. */
object PhoneTextInput : TextInput {
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
    ) = PhoneTextField(
        label,
        value,
        onValue,
        modifier,
        mono = mono,
        action = action,
        onAction = onAction,
    )
}
