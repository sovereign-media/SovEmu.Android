package media.sovereign.sovemu.mobile

import androidx.compose.runtime.Composable
import media.sovereign.sovemu.core.CustomHeader
import media.sovereign.sovemu.core.KnownServer
import media.sovereign.sovemu.core.StoredAccount
import media.sovereign.sovemu.ui.ChooseServerScreen
import media.sovereign.sovemu.ui.CustomHeadersScreen
import media.sovereign.sovemu.ui.LaunchPages
import media.sovereign.sovemu.ui.LoginScreen
import media.sovereign.sovemu.ui.LoginViewModel
import media.sovereign.sovemu.ui.WhoIsWatchingScreen

/** The phone app's account screens: for touch, sign-in through the browser or a code. */
object MobileLaunchPages : LaunchPages {
    @Composable
    override fun Picker(
        accounts: List<StoredAccount>,
        server: String,
        onChoose: (StoredAccount) -> Unit,
        onAddUser: () -> Unit,
        onChangeServer: () -> Unit,
        focusOn: String?,
        current: String?,
    ) = WhoIsWatchingScreen(
        accounts,
        server,
        onChoose,
        onAddUser,
        onChangeServer,
        focusOn = focusOn,
        current = current,
    )

    @Composable
    override fun Servers(
        servers: List<KnownServer>,
        onChoose: (KnownServer) -> Unit,
        onRemove: (KnownServer) -> Unit,
        onAddServer: () -> Unit,
        error: String?,
        busy: Boolean,
    ) = ChooseServerScreen(servers, onChoose, onRemove, onAddServer, error = error, busy = busy)

    @Composable
    override fun Headers(
        headers: List<CustomHeader>,
        onSave: (List<CustomHeader>) -> Unit,
        onDone: () -> Unit,
    ) = CustomHeadersScreen(headers, onSave, onDone)

    @Composable
    override fun SignIn(
        model: LoginViewModel,
        onSignedIn: () -> Unit,
        addUser: Boolean,
        username: String,
        notice: String?,
        onCustomHeaders: () -> Unit,
        onChangeServer: (() -> Unit)?,
    ) = LoginScreen(
        model,
        onSignedIn,
        addUser = addUser,
        username = username,
        notice = notice,
        onChangeServer = onChangeServer,
        onCustomHeaders = onCustomHeaders,
    )
}
