package media.sovereign.sovemu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The filter drawer's dropdowns, as the web client's Select and MultiSelect: a labelled field
// that shows what is picked and opens a menu of choices.

/** One choice in a filter's menu, with how many games have it when that helps. */
data class FilterOption<T>(val value: T, val label: String, val count: Int? = null)

/** A filter with one choice: the menu closes on a pick. */
@Composable
fun <T> ChoiceField(
    label: String,
    options: List<FilterOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = options.firstOrNull { it.value == selected }?.label ?: options.first().label
    Field(label, summary, { expanded = true }, modifier) {
        Menu(expanded, { expanded = false }) {
            for (o in options) {
                MenuItem(o, o.value == selected) {
                    expanded = false
                    onSelect(o.value)
                }
            }
        }
    }
}

/** A filter with any number of values: the menu stays open while they are ticked. */
@Composable
fun <T> MultiSelectField(
    label: String,
    options: List<FilterOption<T>>,
    selected: Set<T>,
    onChange: (Set<T>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = when (selected.size) {
        0 -> "Any"
        1 -> options.firstOrNull { it.value in selected }?.label ?: "1 selected"
        else -> "${selected.size} selected"
    }
    Field(label, summary, { expanded = true }, modifier) {
        Menu(expanded, { expanded = false }) {
            for (o in options) {
                val on = o.value in selected
                MenuItem(o, on) { onChange(if (on) selected - o.value else selected + o.value) }
            }
            if (selected.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Clear", fontSize = 14.sp) },
                    onClick = {
                        expanded = false
                        onChange(emptySet())
                    },
                    leadingIcon = { LucideIcon(Lucide.X, size = 14.dp) },
                    colors = MenuDefaults.itemColors(
                        textColor = Sov.Slate400,
                        leadingIconColor = Sov.Slate400,
                    ),
                    modifier = Modifier.height(40.dp),
                )
            }
        }
    }
}

/** The field's label above a box showing the [summary]; [menu] is anchored under it. */
@Composable
private fun Field(
    label: String,
    summary: String,
    onOpen: () -> Unit,
    modifier: Modifier,
    menu: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(label)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .background(Color.White.copy(alpha = if (focused) 0.1f else 0.05f))
                    .border(
                        if (focused) 2.dp else 1.dp,
                        Color.White.copy(alpha = if (focused) 0.6f else 0.15f),
                        shape,
                    )
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        role = Role.DropdownList,
                        onClick = onOpen,
                    )
                    .semantics { contentDescription = "$label: $summary" }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    summary,
                    color = if (summary == "Any") Sov.Slate400 else Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                LucideIcon(Lucide.ChevronDown, tint = Sov.Slate400, size = 16.dp)
            }
            menu()
        }
    }
}

@Composable
private fun Menu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) =
    DropdownMenu(
        expanded,
        onDismiss,
        modifier = Modifier.heightIn(max = 420.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = Sov.Panel,
        border = BorderStroke(1.dp, Sov.Edge),
    ) { content() }

@Composable
private fun <T> MenuItem(option: FilterOption<T>, on: Boolean, onClick: () -> Unit) =
    DropdownMenuItem(
        text = {
            Text(option.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        onClick = onClick,
        leadingIcon = {
            Box(Modifier.size(14.dp)) { if (on) LucideIcon(Lucide.Check, size = 14.dp) }
        },
        trailingIcon = option.count?.let { n ->
            {
                Text(
                    "$n",
                    color = Sov.Slate500,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
        colors = MenuDefaults.itemColors(textColor = Sov.Slate200),
        modifier = Modifier.height(40.dp),
    )
