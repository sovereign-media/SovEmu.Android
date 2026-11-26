package media.sovereign.sovemu.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.BrowserSignIn
import media.sovereign.sovemu.core.BrowserSignInText
import media.sovereign.sovemu.core.DeviceCode
import media.sovereign.sovemu.core.LaunchText
import media.sovereign.sovemu.core.PairingText
import media.sovereign.sovemu.core.PairingUi
import media.sovereign.sovemu.core.QrCode
import media.sovereign.sovemu.core.ServerAddress
import media.sovereign.sovemu.core.SignInConfig

/**
 * The server's address, then sign in. The address is checked first (its `bootstrapConfig`: is
 * there a SovEmu server, and can this app sign in there); then, as on Sovereign's TVs, the app
 * signs in either with a code approved on a phone or computer (*Sign in with your phone*, the
 * device grant), or with a username and password, the fallback. A server that has turned the
 * password off offers only the code. First-run setup (the admin account) happens in a browser.
 *
 * [model] holds the sign-in ([LoginViewModel]; *Add a server* is one made for a new address).
 * As the account screens open it: [addUser] heads the form *Add a user*, and makes the browser
 * ask who is signing in even when it is signed in already; [username] fills its field and
 * [notice] says why (a sign-in that ended); [onChangeServer] opens *Choose a server*, when the
 * device holds other servers; [onCustomHeaders] the proxy headers sent to the server.
 */
@Composable
fun LoginScreen(
    model: LoginViewModel,
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
    addUser: Boolean = false,
    username: String = "",
    notice: String? = null,
    onChangeServer: (() -> Unit)? = null,
    onCustomHeaders: (() -> Unit)? = null,
) {
    var server by remember {
        mutableStateOf(model.initialServer)
    }
    var username by remember { mutableStateOf(username) }
    var password by remember { mutableStateOf("") }
    var secureOnly by remember { mutableStateOf(model.secureOnly) }
    // Signing in itself (checking the address, the password, the browser, a code) is the flow's,
    // in the ViewModel; this screen keeps its fields and which way in it shows.
    val flow = model.flow
    val signedIn by model.signedIn.collectAsStateWithLifecycle()
    val headerCount by model.customHeaders.collectAsStateWithLifecycle()
    val signedInNow by rememberUpdatedState(onSignedIn)
    LaunchedEffect(signedIn) { if (signedIn) signedInNow() }
    val config by flow.config.collectAsStateWithLifecycle()
    val error by flow.error.collectAsStateWithLifecycle()
    val busy by flow.busy.collectAsStateWithLifecycle()
    val pairingState by flow.pairing.collectAsStateWithLifecycle()
    val ready = config?.problem == null && config != null
    val onTv = LocalForm.current.tv
    // A phone signs in on the server's own page in the browser, as Sovereign's phones do, with a
    // code as the other way in and no password typed into the app; a TV, where that page cannot
    // be reached, with its password form and a code. (A phone uses the form only on a server
    // with no browser sign-in.)
    val browserFirst = ready && !onTv && config!!.browser
    // Signing in with a code: shown on asking, or when it is the only way in here.
    var pairingAsked by remember { mutableStateOf(false) }
    val pairing = ready && config!!.pairing &&
        (pairingAsked || (!config!!.password && !(!onTv && config!!.browser)))
    val context = LocalContext.current

    /** Opens the server's own sign-in page in a Custom Tab, for the code flow with PKCE. */
    fun signInWithBrowser() {
        val request = flow.startBrowserSignIn() ?: return
        BrowserSignIn.open(
            context,
            if (addUser) request.authorizeUrlForAnotherUser else request.authorizeUrl,
        )
    }

    fun submit() {
        flow.submit(server, username, password, secureOnly)
    }

    // A code while the pairing screen shows; leaving it stops the poll.
    LaunchedEffect(pairing) {
        if (pairing) flow.startPairing() else flow.stopPairing()
    }

    if (pairing) {
        PairingScreen(
            pairingState,
            modifier = modifier,
            onNewCode = flow::startPairing,
            onUsePassword = when {
                browserFirst || config!!.password -> ({ pairingAsked = false })
                else -> null
            },
            back = if (browserFirst) BROWSER else PairingText.USE_PASSWORD,
            onChangeServer = {
                pairingAsked = false
                flow.forgetServer()
            },
        )
        return
    }

    // The field being typed in, if any. On a TV each field is picked on its own (A opens the
    // keyboard, its Done key closes it, nothing jumps to the next field). The app's keyboard
    // leaves the form in view above it; under a system keyboard on a TV only the field being
    // typed in shows, at the top, out of the keyboard's way.
    var typing by remember { mutableStateOf<LoginField?>(null) }
    val tv = LocalForm.current.tv && LocalTvKeyboard.current == null
    fun shown(field: LoginField?) = !tv || typing == null || typing == field
    val first = remember { FocusRequester() }
    LaunchedEffect(first) { runCatching { first.requestFocus() } }

    Box(
        modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1E2A4A), Sov.Surface), endY = 1100f),
            )
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = if (tv && typing != null) Alignment.TopCenter else Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 384.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Sov.Panel.copy(alpha = 0.8f))
                .border(1.dp, Sov.Edge, RoundedCornerShape(16.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (shown(null)) {
                Column {
                    Brand(size = 30)
                    Text(
                        if (addUser) LaunchText.ADD_A_USER else "Sign in to play.",
                        color = Sov.Muted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (shown(LoginField.SERVER)) {
                Field(
                    "Server address",
                    server,
                    {
                        server = it
                        flow.forgetServer()
                    },
                    typing = typing == LoginField.SERVER,
                    onTyping = { typing = if (it) LoginField.SERVER else null },
                    modifier = if (ready) Modifier else Modifier.focusRequester(first),
                    type = KeyboardType.Uri,
                )
            }
            // How the address is reached, while it is being entered: as Sovereign's setup screen.
            if (!ready && shown(null)) {
                Toggle(
                    secureOnly,
                    {
                        secureOnly = !secureOnly
                        model.secureOnly = secureOnly
                        flow.clearError()
                    },
                ) {
                    Text(
                        SECURE_ONLY,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (onCustomHeaders != null) {
                    val count = headerCount
                    SovButton(
                        if (count == 0) CUSTOM_HEADERS else "$CUSTOM_HEADERS ($count)",
                        onCustomHeaders,
                        Modifier.fillMaxWidth(),
                        variant = Variant.Ghost,
                        enabled = !busy,
                    )
                }
            }
            if (ready && !browserFirst) {
                if (shown(LoginField.USERNAME)) {
                    Field(
                        "Username",
                        username,
                        {
                            username = it
                            flow.clearError()
                        },
                        typing = typing == LoginField.USERNAME,
                        onTyping = { typing = if (it) LoginField.USERNAME else null },
                        modifier = Modifier.focusRequester(first),
                        type = KeyboardType.Email,
                    )
                }
                if (shown(LoginField.PASSWORD)) {
                    Field(
                        "Password",
                        password,
                        {
                            password = it
                            flow.clearError()
                        },
                        typing = typing == LoginField.PASSWORD,
                        onTyping = { typing = if (it) LoginField.PASSWORD else null },
                        type = KeyboardType.Password,
                        secret = true,
                    )
                }
            }
            if (shown(null) && notice != null && error == null) {
                Text(notice, color = Sov.Slate300, fontSize = 14.sp)
            }
            if (shown(null)) {
                error?.let {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x26EF4444))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    {
                        if (browserFirst && model.isServer(server)) {
                            signInWithBrowser()
                        } else {
                            submit()
                        }
                    },
                    Modifier.fillMaxWidth(),
                    icon = Lucide.LogIn,
                    enabled = !busy,
                )
                // The other way in, which needs no typing on a TV: a code to approve elsewhere.
                if (ready && config!!.pairing) {
                    SovButton(
                        if (onTv) PairingText.USE_CODE else CODE,
                        {
                            pairingAsked = true
                            flow.clearError()
                        },
                        Modifier.fillMaxWidth(),
                        variant = Variant.Outline,
                        icon = Lucide.Smartphone,
                        enabled = !busy,
                    )
                }
                if (onChangeServer != null) {
                    SovButton(
                        LaunchText.CHANGE_SERVER,
                        onChangeServer,
                        Modifier.fillMaxWidth(),
                        variant = Variant.Ghost,
                        icon = Lucide.Server,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/** The address's two settings, as Sovereign's setup screen names them. */
internal const val SECURE_ONLY = "Secure connections only"
internal const val CUSTOM_HEADERS = "Custom headers"

/** A phone's way back to the browser sign-in, and its name for signing in with a code. */
private const val BROWSER = "Sign in with your browser"
private const val CODE = "Sign in with a code"

/** The sign-in form's fields, one of which may be being typed in. */
enum class LoginField { SERVER, USERNAME, PASSWORD }

/**
 * A labelled input, as the web's Field and inputClass: a [TvTextField], so it is picked on its
 * own and its keyboard's action key (Done) closes the keyboard rather than moving on.
 */
@Composable
internal fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    type: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Sov.Slate200, fontSize = 14.sp)
        TvTextField(
            label, value, onValue,
            typing = typing,
            onTyping = onTyping,
            modifier = modifier,
            action = ImeAction.Done,
            onAction = { onTyping(false) },
            keyboardType = type,
            secret = secret,
        )
    }
}

/**
 * *Sign in with your phone*: the code to approve, where to approve it (also as a QR code), and
 * how long it has left. Waiting is the normal state, not an error, and is never shown as one.
 * [onUsePassword] is null when the server has no password sign-in.
 */
@Composable
fun PairingScreen(
    state: PairingUi,
    onNewCode: () -> Unit,
    onUsePassword: (() -> Unit)?,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
    back: String = PairingText.USE_PASSWORD,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(state is PairingUi.Failed) { runCatching { first.requestFocus() } }
    Box(
        modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1E2A4A), Sov.Surface), endY = 1100f),
            )
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 640.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Sov.Panel.copy(alpha = 0.8f))
                .border(1.dp, Sov.Edge, RoundedCornerShape(16.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                PairingText.TITLE,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            when (state) {
                PairingUi.Asking -> Text(PairingText.ASKING, color = Sov.Muted, fontSize = 16.sp)

                is PairingUi.Showing -> PairingCode(state)

                is PairingUi.Failed -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x26EF4444))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LucideIcon(Lucide.CircleAlert, tint = Sov.Red300, size = 16.dp)
                    Text(state.message, color = Sov.Red300, fontSize = 14.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state is PairingUi.Failed) {
                    SovButton(
                        PairingText.NEW_CODE,
                        onNewCode,
                        Modifier.fillMaxWidth().focusRequester(first),
                        icon = Lucide.RotateCcw,
                    )
                }
                if (onUsePassword != null) {
                    SovButton(
                        back,
                        onUsePassword,
                        if (state is PairingUi.Failed) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.fillMaxWidth().focusRequester(first)
                        },
                        variant = Variant.Outline,
                        icon = Lucide.LogIn,
                    )
                }
                SovButton(
                    "Change server",
                    onChangeServer,
                    if (state is PairingUi.Failed || onUsePassword != null) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.fillMaxWidth().focusRequester(first)
                    },
                    variant = Variant.Ghost,
                    icon = Lucide.Server,
                )
            }
        }
    }
}

/** The code, where to enter it, its QR code, and the line under it. */
@Composable
private fun PairingCode(state: PairingUi.Showing) {
    var secondsLeft by remember(state.deadline) {
        mutableStateOf((state.deadline - SystemClock.elapsedRealtime()) / 1_000)
    }
    LaunchedEffect(state.deadline) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft = (state.deadline - SystemClock.elapsedRealtime()) / 1_000
        }
    }
    val qr = remember(state.code.qrContent) { QrCode.encode(state.code.qrContent) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(PairingText.GO_TO, color = Sov.Slate300, fontSize = 16.sp)
            Text(
                state.code.shownUri,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                PairingText.ENTER,
                color = Sov.Slate300,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                state.code.grouped,
                color = Color.White,
                fontSize = 40.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
            )
            Text(
                (if (state.trouble) PairingText.TROUBLE else PairingText.WAITING) + " · " +
                    PairingText.expiresIn(secondsLeft),
                color = if (state.trouble) Sov.Amber200 else Sov.Muted,
                fontSize = 14.sp,
            )
        }
        QrImage(
            qr,
            Modifier.size(168.dp).semantics {
                contentDescription = "QR code for ${state.code.qrContent}"
            },
        )
    }
}

/** A QR code, one square per module, dark on a light margin so a phone's camera reads it. */
@Composable
private fun QrImage(qr: QrCode, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(10.dp)) {
        val cell = size.minDimension / qr.size
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.isDark(x, y)) {
                    drawRect(
                        Color.Black,
                        Offset(x * cell, y * cell),
                        Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}
