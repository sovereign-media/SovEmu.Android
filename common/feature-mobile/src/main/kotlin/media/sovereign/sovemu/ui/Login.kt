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
import media.sovereign.sovemu.openBrowserSignIn

/**
 * The phone and tablet's sign-in, as Sovereign's phones: the server's address, then *Sign in*,
 * which opens the server's own page in the browser (the code flow with PKCE as the mobile client;
 * the password is never typed into the app), with a code approved on another device as the other
 * way in. Only on a server with no browser sign-in does the form ask for a username and password.
 * First-run setup (the admin account) happens in a browser.
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
    val browserFirst = ready && config!!.browser
    // Signing in with a code: shown on asking, or when it is the only way in here.
    var pairingAsked by remember { mutableStateOf(false) }
    val pairing = ready && config!!.pairing &&
        (pairingAsked || (!config!!.password && !config!!.browser))
    val context = LocalContext.current

    /** Opens the server's own sign-in page in a Custom Tab, for the code flow with PKCE. */
    fun signInWithBrowser() {
        val request = flow.startBrowserSignIn() ?: return
        openBrowserSignIn(
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
            run {
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
            run {
                Field(
                    "Server address",
                    server,
                    {
                        server = it
                        flow.forgetServer()
                    },
                    modifier = if (ready) Modifier else Modifier.focusRequester(first),
                    type = KeyboardType.Uri,
                )
            }
            // How the address is reached, while it is being entered: as Sovereign's setup screen.
            if (!ready) {
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
                run {
                    Field(
                        "Username",
                        username,
                        {
                            username = it
                            flow.clearError()
                        },
                        modifier = Modifier.focusRequester(first),
                        type = KeyboardType.Email,
                    )
                }
                run {
                    Field(
                        "Password",
                        password,
                        {
                            password = it
                            flow.clearError()
                        },
                        type = KeyboardType.Password,
                        secret = true,
                    )
                }
            }
            if (notice != null && error == null) {
                Text(notice, color = Sov.Slate300, fontSize = 14.sp)
            }
            run {
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
                // The other way in: a code to approve on another device.
                if (ready && config!!.pairing) {
                    SovButton(
                        CODE,
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

/** Where a phone's code is approved: not this phone. */
private const val GO_TO = "On another phone or a computer, go to"

/** A labelled input, as the web's Field: tap to type, the keyboard's Done closes it. */
@Composable
internal fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    type: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Sov.Slate200, fontSize = 14.sp)
        PhoneTextField(label, value, onValue, modifier, type, secret)
    }
}

/**
 * *Sign in with a code*: the code to approve on another device, where to approve it, and
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
                CODE,
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(GO_TO, color = Sov.Slate300, fontSize = 16.sp)
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
    }
}
