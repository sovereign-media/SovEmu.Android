package media.sovereign.sovemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.core.TvSignIn

/**
 * *Sign in a TV*, on a phone or tablet (Sovereign's `clients-sign-in-a-tv.md`): type the code a
 * TV's *Sign in with your phone* screen shows, confirm, and the TV signs in as this account, with
 * nobody signing in twice. [approve] sends the code (`approveDeviceCode`); [onDone] goes back.
 * An error keeps the code on screen, so a typo is fixed rather than typed again; approval is never
 * automatic, not even for a pasted code.
 */
@Composable
fun SignInTvScreen(
    server: String,
    name: String,
    approve: suspend (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var code by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var approved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The approved line shows for a moment, then back to where the person came from.
    LaunchedEffect(approved) {
        if (approved) {
            delay(1_500)
            onDone()
        }
    }

    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Sov.Panel.copy(alpha = 0.8f))
                .border(1.dp, Sov.Edge, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(TvSignIn.TITLE, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            when {
                approved -> Text(TvSignIn.APPROVED, color = Sov.Slate200, fontSize = 16.sp)
                confirming -> {
                    Text(TvSignIn.confirmation(server, name), color = Sov.Slate200, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SovButton(
                            if (sending) "Approving…" else TvSignIn.APPROVE,
                            {
                                sending = true
                                scope.launch {
                                    try {
                                        approve(TvSignIn.format(code))
                                        approved = true
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = TvSignIn.failure(e)
                                        confirming = false
                                    } finally {
                                        sending = false
                                    }
                                }
                            },
                            Modifier.weight(1f),
                            enabled = !sending,
                        )
                        SovButton(TvSignIn.CANCEL, { confirming = false }, Modifier.weight(1f), variant = Variant.Outline, enabled = !sending)
                    }
                }
                else -> {
                    Text(TvSignIn.INTRO, color = Sov.Slate300, fontSize = 15.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(TvSignIn.FIELD_LABEL, color = Sov.Slate200, fontSize = 14.sp)
                        // Kept exactly as typed: rewriting the text while it is typed (upper case,
                        // a dash) fights the keyboard and drops keystrokes, as Sovereign.Android
                        // found. It is normalised only to check it and to send it.
                        TvTextField(
                            TvSignIn.FIELD_LABEL, code, { code = it.take(MAX_TYPED); error = null },
                            typing = typing,
                            onTyping = { typing = it },
                            mono = true,
                            onAction = { typing = false },
                            keyboardType = KeyboardType.Ascii,
                        )
                    }
                    error?.let { Text(it, color = Sov.Red300, fontSize = 14.sp) }
                    SovButton(
                        TvSignIn.CONTINUE, { confirming = true; error = null }, Modifier.fillMaxWidth(),
                        enabled = TvSignIn.isComplete(code),
                    )
                }
            }
        }
    }
}

/** Enough for the code with a dash and a space or two; anything longer is not a code. */
private const val MAX_TYPED = 16
