package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.sovereign.sovemu.core.CustomHeader
import media.sovereign.sovemu.core.CustomHeaderRules

/** The words of the custom headers screen. */
object CustomHeadersText {
    const val HINT = "Sent with every request to this server, for a proxy in front of it " +
        "(Cloudflare Access and the like)."
    const val ADD = "Add a header"
    const val NAME = "Name"
    const val VALUE = "Value"
    const val SAVE = "Save"
    const val REMOVE = "Remove"
    const val CANCEL = "Cancel"
    const val DONE = "Done"

    /** What a row shows for a value: it is usually a credential. */
    const val HIDDEN = "••••••"
}

/**
 * The proxy headers sent with every request to the server, as Sovereign's setup screen keeps them:
 * [headers] listed by name (values hidden), each opening a form to change or remove it, and
 * `Add a header` until there are [CustomHeaderRules.MAX_HEADERS]. [onSave] keeps a new list;
 * the rules ([CustomHeaderRules.validate]) are checked before it is called, and say what to fix.
 * Back from the form goes back to the list.
 */
@Composable
fun CustomHeadersScreen(
    headers: List<CustomHeader>,
    onSave: (List<CustomHeader>) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The row being changed: -1 for a new header, null while the list shows.
    var editing by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(editing) { runCatching { first.requestFocus() } }

    fun open(index: Int) {
        editing = index
        name = headers.getOrNull(index)?.name.orEmpty()
        value = headers.getOrNull(index)?.value.orEmpty()
        error = null
    }

    if (editing != null) BackHandler { editing = null }

    LaunchBackdrop(modifier) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                CUSTOM_HEADERS,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(CustomHeadersText.HINT, color = Sov.Muted, fontSize = 14.sp)
            val row = editing
            if (row == null) {
                headers.forEachIndexed { i, header ->
                    FocusCard(
                        { open(i) },
                        Modifier.fillMaxWidth().height(60.dp)
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Row(
                            Modifier.fillMaxSize().background(
                                Sov.Panel,
                            ).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                header.name,
                                color = Sov.Slate100,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(CustomHeadersText.HIDDEN, color = Sov.Slate500, fontSize = 16.sp)
                        }
                    }
                }
                if (headers.size < CustomHeaderRules.MAX_HEADERS) {
                    SovButton(
                        CustomHeadersText.ADD,
                        { open(-1) },
                        Modifier.fillMaxWidth()
                            .then(
                                if (headers.isEmpty()) Modifier.focusRequester(first) else Modifier,
                            ),
                        variant = Variant.Outline,
                    )
                }
                SovButton(CustomHeadersText.DONE, onDone, Modifier.fillMaxWidth())
            } else {
                Field(
                    CustomHeadersText.NAME,
                    name,
                    {
                        name = it
                        error = null
                    },
                    modifier = Modifier.focusRequester(first),
                    // Typed exactly: no capitals or corrections a keyboard would add.
                    type = KeyboardType.Uri,
                )
                Field(
                    CustomHeadersText.VALUE,
                    value,
                    {
                        value = it
                        error = null
                    },
                    // A credential: no capitals, corrections or suggestions (shown, not masked).
                    type = KeyboardType.Password,
                )
                error?.let { ErrorLine(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SovButton(
                        CustomHeadersText.SAVE,
                        {
                            val n = CustomHeaderRules.cleanName(name)
                            val v = CustomHeaderRules.cleanValue(value)
                            error = CustomHeaderRules.validate(headers, n, v, row)
                            if (error == null) {
                                onSave(CustomHeaderRules.upsert(headers, n, v, row))
                                editing = null
                            }
                        },
                    )
                    if (row >= 0) {
                        SovButton(
                            CustomHeadersText.REMOVE,
                            {
                                onSave(CustomHeaderRules.removeAt(headers, row))
                                editing = null
                            },
                            variant = Variant.Outline,
                        )
                    }
                    SovButton(CustomHeadersText.CANCEL, { editing = null }, variant = Variant.Ghost)
                }
            }
        }
    }
}
