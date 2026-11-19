package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.attempt
import media.sovereign.sovemu.core.CollectionChoice
import media.sovereign.sovemu.core.Game

/**
 * A game's collections, in a drawer from the right (as the filters): each of this user's
 * collections as a toggle that puts the game in it or takes it out, and a field to make a new
 * one with the game in it. [onChanged] runs after every change, so Home can show the rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionsDrawer(api: SovEmuApi, game: Game, onChanged: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<CollectionChoice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(game.id) {
        attempt { api.collectionChoices(game.id) }
            .onSuccess { choices = it }
            .onFailure { error = it.message ?: "Could not load the collections" }
        // The first collection, or the field when there are none yet.
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    fun toggle(c: CollectionChoice) {
        val now = !c.member
        choices = choices?.map { if (it.id == c.id) it.copy(member = now) else it }
        scope.launch {
            attempt { api.setInCollection(c.id, game.id, now) }
                .onSuccess { error = null; onChanged() }
                .onFailure { e ->
                    choices = choices?.map { if (it.id == c.id) it.copy(member = !now) else it }
                    error = "Could not change ${c.name}: ${e.message}"
                }
        }
    }

    fun create() {
        // The field is cleared at once, so the value is taken first.
        val wanted = name.trim()
        typing = false
        if (wanted.isEmpty()) return
        name = ""
        scope.launch {
            attempt {
                val id = api.createCollection(wanted)
                api.setInCollection(id, game.id, true)
                id
            }.onSuccess { id ->
                error = null
                choices = (choices.orEmpty() + CollectionChoice(id, wanted, true)).sortedBy { it.name.lowercase() }
                onChanged()
            }.onFailure { e ->
                name = wanted
                error = e.message ?: "Could not create the collection"
            }
        }
    }

    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 384.dp).fillMaxWidth().background(Sov.Surface.copy(alpha = 0.97f))
                .border(1.dp, Color.White.copy(alpha = 0.1f)).clickable(remember { MutableInteractionSource() }, null) {}
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            Row(
                Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LucideIcon(Lucide.FolderHeart)
                Text("Collections", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(1f))
                Box(
                    Modifier.focusProperties { canFocus = false }.size(36.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { LucideIcon(Lucide.X, tint = Sov.Slate400) }
            }
            Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(game.title, color = Sov.Slate300, fontSize = 14.sp)
                val list = choices
                when {
                    list == null && error == null -> Text("Loading…", color = Sov.Slate500, fontSize = 14.sp)
                    list.isNullOrEmpty() -> Text("No collections yet: name one below to start it with this game.", color = Sov.Slate500, fontSize = 14.sp)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEachIndexed { i, c ->
                            Toggle(c.member, { toggle(c) }, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                                Text(c.name, color = Sov.Slate200, fontSize = 14.sp)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("New collection")
                    TvTextField(
                        "Name", name, { name = it.take(64) }, typing, { typing = it },
                        if (choices.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier,
                        action = ImeAction.Done,
                        onAction = ::create,
                    )
                    SovButton("Create", ::create, icon = Lucide.FolderPlus, variant = Variant.Outline, enabled = name.isNotBlank(), large = false)
                }
                error?.let { Text(it, color = Sov.Red300, fontSize = 14.sp) }
            }
        }
    }
}
