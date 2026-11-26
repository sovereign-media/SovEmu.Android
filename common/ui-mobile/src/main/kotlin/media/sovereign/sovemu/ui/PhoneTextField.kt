package media.sovereign.sovemu.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A text field for touch, in the app's colours: tap to type, and the keyboard's Done closes it
 * (nothing jumps to the next field). [placeholder] shows while it is empty; [secret] hides what is
 * typed (a password). Typed exactly as given: [type] picks a keyboard, and a URI, password or
 * email one adds no capitals or corrections.
 */
@Composable
fun PhoneTextField(
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    type: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder, color = Sov.Slate500) },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Sov.Slate100,
            unfocusedTextColor = Sov.Slate100,
            focusedContainerColor = Sov.Surface,
            unfocusedContainerColor = Sov.Surface,
            focusedBorderColor = Sov.Accent,
            unfocusedBorderColor = Sov.Edge,
            cursorColor = Sov.Accent,
        ),
    )
}
