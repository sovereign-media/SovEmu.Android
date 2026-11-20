package media.sovereign.sovemu.ui

import androidx.compose.foundation.background
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.ApiException
import media.sovereign.sovemu.core.OAuthException
import media.sovereign.sovemu.core.SignInConfig

/**
 * The server's address, then sign in. The address is checked first (its `bootstrapConfig`: is
 * there a SovEmu server, and can this app sign in there); only then does the form ask for a
 * username and password. First-run setup (the admin account) happens in a browser.
 */
@Composable
fun LoginScreen(settings: Settings, api: SovEmuApi, onSignedIn: () -> Unit) {
    var server by remember { mutableStateOf(settings.serverUrl.ifEmpty { "http://" }) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // How to sign in at the address in the field: null until it has been checked.
    var config by remember { mutableStateOf<SignInConfig?>(settings.signInConfig) }
    val ready = config?.problem == null && config != null
    val scope = rememberCoroutineScope()

    fun failed(e: Exception) {
        // The server's error_description (wrong password, wait N minutes …) as it is.
        error = when (e) {
            is OAuthException, is ApiException -> e.message
            else -> null
        } ?: "Cannot reach the server: ${e.message}"
    }

    /** Asks the address how to sign in; [quiet] keeps a failure off the screen (on launch). */
    suspend fun check(address: String, quiet: Boolean = false) {
        settings.serverUrl = address
        try {
            val answer = api.bootstrap()
            config = answer
            error = answer.problem
            if (answer.problem == null && api.status().setupRequired) {
                config = null
                error = "Finish first-run setup in a browser at $address first."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!quiet) failed(e)
        }
    }

    // A stored address is asked again on every launch, so its sign-in details stay current.
    LaunchedEffect(Unit) {
        if (settings.serverUrl.isNotEmpty()) check(settings.serverUrl, quiet = true)
    }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        val address = server.trim().let { if ("://" in it) it else "http://$it" }
        scope.launch {
            try {
                if (!ready || address.trimEnd('/') != settings.serverUrl) {
                    check(address)
                } else {
                    api.signIn(username.trim(), password)
                    onSignedIn()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            } finally {
                busy = false
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1E2A4A), Sov.Surface), endY = 1100f))
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 384.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Sov.Panel.copy(alpha = 0.8f))
                .border(1.dp, Sov.Edge, RoundedCornerShape(16.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Brand(size = 30)
                Text("Sign in to play.", color = Sov.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Field(
                "Server address", server,
                {
                    server = it
                    error = null
                    config = null
                },
                KeyboardType.Uri,
                onDone = if (ready) null else ::submit,
            )
            if (ready) {
                Field("Username", username, { username = it; error = null })
                Field("Password", password, { password = it; error = null }, KeyboardType.Password, secret = true, onDone = ::submit)
            }
            error?.let {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0x26EF4444)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LucideIcon(Lucide.CircleAlert, tint = Sov.Red300, size = 16.dp)
                    Text(it, color = Sov.Red300, fontSize = 14.sp)
                }
            }
            SovButton(
                when {
                    !ready && busy -> "Checking…"
                    !ready -> "Continue"
                    busy -> "Signing in…"
                    else -> "Sign in"
                },
                ::submit,
                Modifier.fillMaxWidth(),
                icon = Lucide.LogIn,
                enabled = !busy,
            )
        }
    }
}

/** A labelled input, as the web's Field and inputClass. */
@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    type: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Sov.Slate200, fontSize = 14.sp)
        BasicTextField(
            value, onValue,
            singleLine = true,
            textStyle = TextStyle(color = Sov.Slate100, fontSize = 16.sp),
            cursorBrush = SolidColor(Sov.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (onDone != null) ImeAction.Go else ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Sov.Surface)
                        .border(if (focused) 2.dp else 1.dp, if (focused) Sov.Accent else Sov.Edge, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) { inner() }
            },
        )
    }
}
