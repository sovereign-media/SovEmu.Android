package media.sovereign.sovemu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import media.sovereign.sovemu.BrowserSignIn
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.AuthState
import media.sovereign.sovemu.core.KnownServers
import media.sovereign.sovemu.core.LaunchAccounts
import media.sovereign.sovemu.core.LaunchText
import media.sovereign.sovemu.core.ServerChooser
import media.sovereign.sovemu.core.ServerMove
import media.sovereign.sovemu.core.attempt

/** A page of the account screens. */
sealed interface LaunchPage {
    /** *Who's watching?* */
    data object Picker : LaunchPage

    /** *Choose a server*. */
    data object Servers : LaunchPage

    /** The proxy headers sent to the server. */
    data object Headers : LaunchPage

    /**
     * The sign-in form: [addUser] for *Add a user*, [username] and [expired] after a sign-in
     * ended, [newServer] for *Add a server*, which puts [returnTo] back as the server on Back.
     */
    data class SignIn(
        val addUser: Boolean = false,
        val username: String = "",
        val expired: Boolean = false,
        val newServer: Boolean = false,
        val returnTo: String? = null,
    ) : LaunchPage

    companion object {
        /**
         * Where the app opens when nobody is signed in, for [state]: the picker while choosing;
         * after a sign-in ended, the sign-in form with its username (over the picker when others
         * remain here); else the sign-in form.
         */
        fun start(state: AuthState, api: SovEmuApi): List<LaunchPage> =
            waitingForBrowser(startFor(state, api))

        /** Opened over the app (*Change user or server*): the picker. */
        fun overApp(): List<LaunchPage> = waitingForBrowser(listOf(Picker))

        /**
         * A browser sign-in that came back after the app was ended needs a sign-in form to take
         * its redirect: one goes on top when [pages] end without one.
         */
        private fun waitingForBrowser(pages: List<LaunchPage>): List<LaunchPage> =
            if (BrowserSignIn.redirect.value != null && pages.last() !is SignIn) {
                pages + SignIn(addUser = true)
            } else {
                pages
            }

        private fun startFor(state: AuthState, api: SovEmuApi): List<LaunchPage> = when (state) {
            AuthState.Choosing -> listOf(Picker)

            AuthState.Expired -> {
                val ended = SignIn(
                    username = api.session.lastExpired?.userName.orEmpty(),
                    expired = true,
                )
                if (api.session.accountsHere().isEmpty()) listOf(ended) else listOf(Picker, ended)
            }

            else -> listOf(SignIn())
        }
    }
}

/**
 * The account screens, as Sovereign.Android's: *Who's watching?*, *Choose a server* and the
 * sign-in form, each over the last; Back goes down the stack. Opened [fromAccount] (the account
 * menu's *Change user or server*), the picker marks the account signed in now, and choosing it,
 * or Back at the bottom, calls [onReturn]. Signing in, choosing someone else or another server
 * moves the app's session, and the activity follows it.
 */
@Composable
fun LaunchScreens(
    settings: Settings,
    api: SovEmuApi,
    start: List<LaunchPage>,
    modifier: Modifier = Modifier,
    fromAccount: Boolean = false,
    onReturn: () -> Unit = {},
) {
    val stack = remember { mutableStateListOf(*start.toTypedArray()) }
    val scope = rememberCoroutineScope()
    val chooser = remember(api) { ServerChooser(api, api.session) }
    val all by api.session.allAccounts.collectAsStateWithLifecycle()
    val auth by api.session.state.collectAsStateWithLifecycle()
    // The server is not a flow: read again whenever a move changes it.
    var server by remember { mutableStateOf(settings.serverUrl) }
    val here = all.filter { it.isOn(server) }
    val servers = KnownServers.list(all, server)
    var busy by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    val current = (auth as? AuthState.Authenticated)?.accountId

    fun pop() {
        val top = stack.lastOrNull()
        if (top is LaunchPage.SignIn && top.newServer && top.returnTo != null &&
            settings.serverUrl != top.returnTo
        ) {
            // A new address was tried and abandoned: the server it replaced comes back.
            api.setServer(top.returnTo)
            server = top.returnTo
            scope.launch { attempt { api.bootstrap() } }
        }
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onReturn()
    }

    fun landed(move: ServerMove) {
        busy = false
        server = settings.serverUrl
        when (move) {
            ServerMove.Stayed -> if (stack.lastOrNull() == LaunchPage.Servers) pop()

            is ServerMove.Moved -> {
                stack.clear()
                stack.add(
                    if (move.landing ==
                        LaunchAccounts.Login
                    ) {
                        LaunchPage.SignIn()
                    } else {
                        LaunchPage.Picker
                    },
                )
            }

            ServerMove.NoServer -> {
                stack.clear()
                stack.add(LaunchPage.SignIn(newServer = true))
            }

            is ServerMove.Failed -> serverError = move.message
        }
    }

    if (stack.size > 1 || fromAccount) BackHandler(onBack = ::pop)

    // Each page its own state: a sign-in form's fields never carry into the next one.
    val page = stack.lastOrNull() ?: LaunchPage.SignIn()
    key(stack.size, page) {
        when (page) {
            LaunchPage.Picker -> WhoIsWatchingScreen(
                accounts = here,
                server = server,
                onChoose = { account ->
                    if (account.id == current) {
                        onReturn()
                    } else {
                        scope.launch { api.session.activate(account.id) }
                    }
                },
                onAddUser = { stack.add(LaunchPage.SignIn(addUser = true)) },
                onChangeServer = {
                    serverError = null
                    stack.add(LaunchPage.Servers)
                },
                modifier = modifier,
                focusOn = current.takeIf { fromAccount } ?: here.maxByOrNull { it.lastUsedAt }?.id,
                current = current.takeIf { fromAccount },
            )

            LaunchPage.Servers -> ChooseServerScreen(
                servers = servers,
                onChoose = { choice ->
                    busy = true
                    serverError = null
                    scope.launch { landed(chooser.choose(choice)) }
                },
                onRemove = { gone ->
                    busy = true
                    serverError = null
                    scope.launch { landed(chooser.remove(gone, servers)) }
                },
                onAddServer = { stack.add(LaunchPage.SignIn(newServer = true, returnTo = server)) },
                modifier = modifier,
                error = serverError,
                busy = busy,
            )

            LaunchPage.Headers -> {
                var headers by remember { mutableStateOf(settings.customHeaders) }
                CustomHeadersScreen(
                    headers,
                    onSave = {
                        settings.customHeaders = it
                        headers = settings.customHeaders
                    },
                    onDone = ::pop,
                    modifier = modifier,
                )
            }

            is LaunchPage.SignIn -> LoginScreen(
                settings,
                api,
                // Signing in again as the account signed in now moves nothing: back to where it was.
                onSignedIn = { if (fromAccount) onReturn() },
                modifier = modifier,
                addUser = page.addUser,
                username = page.username,
                notice = LaunchText.EXPIRED.takeIf { page.expired },
                newServer = page.newServer,
                onCustomHeaders = { stack.add(LaunchPage.Headers) },
                onChangeServer = if (!page.newServer && !page.addUser && servers.isNotEmpty()) {
                    {
                        serverError = null
                        stack.add(LaunchPage.Servers)
                    }
                } else {
                    null
                },
            )
        }
    }
}
