package media.sovereign.sovemu.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import media.sovereign.sovemu.BrowserSignIn
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.AuthState
import media.sovereign.sovemu.core.BrowserSignInText
import media.sovereign.sovemu.core.CustomHeader
import media.sovereign.sovemu.core.KnownServer
import media.sovereign.sovemu.core.KnownServers
import media.sovereign.sovemu.core.ServerAddress
import media.sovereign.sovemu.core.ServerChooser
import media.sovereign.sovemu.core.ServerMove
import media.sovereign.sovemu.core.SignInFlow
import media.sovereign.sovemu.core.StoredAccount

/** What *Who's watching?* draws. */
data class AccountPickerUiState(
    /** This server's accounts, in the order they were added. */
    val accounts: List<StoredAccount> = emptyList(),
    val server: String = "",
    /** The account signed in now, if any. */
    val currentId: String? = null,
    /** The account used last, where focus starts. */
    val lastUsedId: String? = null,
)

/** *Who's watching?*, as Sovereign.Android's: this server's accounts, and choosing one. */
@HiltViewModel
class AccountPickerViewModel @Inject constructor(private val api: SovEmuApi, settings: Settings) :
    ViewModel() {
    val uiState: StateFlow<AccountPickerUiState> =
        combine(api.session.allAccounts, api.session.state, settings.server) { all, auth, server ->
            pickerState(all, auth, server)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            pickerState(api.session.allAccounts.value, api.session.state.value, settings.serverUrl),
        )

    /** Makes [account] the active one: the app follows the session. */
    fun choose(account: StoredAccount) {
        viewModelScope.launch { api.session.activate(account.id) }
    }

    private fun pickerState(all: List<StoredAccount>, auth: AuthState, server: String) =
        all.filter { it.isOn(server) }.let { here ->
            AccountPickerUiState(
                accounts = here,
                server = server,
                currentId = (auth as? AuthState.Authenticated)?.accountId,
                lastUsedId = here.maxByOrNull { it.lastUsedAt }?.id,
            )
        }
}

/** What *Choose a server* draws. */
data class ChooseServerUiState(
    val servers: List<KnownServer> = emptyList(),
    /** A choice or a removal is in flight: further presses wait for it. */
    val busy: Boolean = false,
    /** Why the last choice did not move the app. */
    val error: String? = null,
)

/**
 * *Choose a server*, as Sovereign.Android's: the servers the device holds accounts for; choosing
 * or removing one ([ServerChooser]) answers where the app landed.
 */
@HiltViewModel
class ChooseServerViewModel @Inject constructor(api: SovEmuApi, settings: Settings) : ViewModel() {
    private val chooser = ServerChooser(api, api.session)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val servers = combine(api.session.allAccounts, settings.server, KnownServers::list)

    val uiState: StateFlow<ChooseServerUiState> =
        combine(servers, busy, error, ::ChooseServerUiState).stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ChooseServerUiState(
                KnownServers.list(api.session.allAccounts.value, settings.serverUrl),
            ),
        )

    fun choose(server: KnownServer, onMove: (ServerMove) -> Unit) = act(onMove) {
        chooser.choose(server)
    }

    fun remove(server: KnownServer, onMove: (ServerMove) -> Unit) = act(onMove) {
        chooser.remove(server, uiState.value.servers)
    }

    private fun act(onMove: (ServerMove) -> Unit, move: suspend () -> ServerMove) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            val moved = move()
            busy.value = false
            if (moved is ServerMove.Failed) error.value = moved.message
            onMove(moved)
        }
    }
}

/** The proxy headers, as Sovereign.Android's `CustomHeadersViewModel`. */
@HiltViewModel
class CustomHeadersViewModel @Inject constructor(private val settings: Settings) : ViewModel() {
    val headers: StateFlow<List<CustomHeader>> = settings.headers

    /** Keeps [headers], the rules applied again on the way in. */
    fun save(headers: List<CustomHeader>) {
        settings.customHeaders = headers
    }
}

/**
 * The sign-in form, as Sovereign.Android's `LoginViewModel`: [flow] signs in (the address, the
 * password, the browser's redirect, a code's pairing loop) and lives as long as the form does.
 * [newServer] is *Add a server*: an empty address, the stored one not asked. A browser
 * sign-in's redirect is taken here, also one that arrived before the form (the app was ended
 * while the browser was open).
 */
@HiltViewModel(assistedFactory = LoginViewModel.Factory::class)
class LoginViewModel @AssistedInject constructor(
    @Assisted val newServer: Boolean,
    api: SovEmuApi,
    private val settings: Settings,
) : ViewModel() {
    private val _signedIn = MutableStateFlow(false)

    /** Set once a sign-in here has worked. */
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    val flow = SignInFlow(
        api,
        viewModelScope,
        if (newServer) null else settings.signInConfig,
        onSignedIn = { _signedIn.value = true },
        now = SystemClock::elapsedRealtime,
    )

    /** The address the form starts with. */
    val initialServer: String = if (newServer) "" else settings.serverUrl

    var secureOnly: Boolean
        get() = settings.secureOnly
        set(value) {
            settings.secureOnly = value
        }

    /** How many proxy headers are kept, as the form's button counts them. */
    val customHeaders: StateFlow<Int> = settings.headers.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.customHeaders.size)

    /** Whether [input] is the address already in use. */
    fun isServer(input: String) = ServerAddress.names(input, settings.serverUrl)

    init {
        // A stored address is asked again each time the form opens, so its details stay current.
        if (!newServer && settings.serverUrl.isNotEmpty()) {
            viewModelScope.launch { flow.check(settings.serverUrl, quiet = true) }
        }
        viewModelScope.launch {
            BrowserSignIn.redirect.collect {
                if (it != null) BrowserSignIn.take()?.let(flow::finishBrowserSignIn)
            }
        }
        viewModelScope.launch {
            BrowserSignIn.noBrowser.drop(1).collect { flow.fail(BrowserSignInText.NO_BROWSER) }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(newServer: Boolean): LoginViewModel
    }
}

/**
 * A ViewModel store that lives as long as the composable that remembers it, cleared when it
 * leaves: for a page stack made afresh (a sign-out, another account's app), whose ViewModels must
 * not outlive it the way a store kept in the activity's would (a stale sign-in form would go on
 * taking browser redirects).
 */
@Composable
fun rememberViewModelStoreOwner(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}
