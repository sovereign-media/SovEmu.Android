package media.sovereign.sovemu.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What signing in needs from the API (`SovEmuApi`, or a fake in tests). */
interface SignInService {
    /** The address the app talks to, as the user last gave it. */
    val server: String

    /** Asks [address] how to sign in (`bootstrapConfig`), and keeps it as the server. */
    suspend fun bootstrapAt(address: String): SignInConfig

    /** No account exists yet: first-run setup happens in a browser. */
    suspend fun setupRequired(): Boolean

    suspend fun signIn(username: String, password: String)
    fun startBrowserSignIn(): BrowserSignInRequest?
    suspend fun finishBrowserSignIn(uri: String)
    suspend fun requestDeviceCode(): DeviceCode
    suspend fun pollDeviceCode(code: DeviceCode): DevicePoll
    suspend fun signedInWith(tokens: Tokens)
}

/** Where signing in with a code stands, for the pairing screen. */
sealed interface PairingUi {
    /** Asking the server for a code. */
    data object Asking : PairingUi

    /**
     * A code on screen, waiting for approval until [deadline] (the flow's clock, ms). [trouble]:
     * the last poll got no answer; the code is still good and the next poll is the retry.
     */
    data class Showing(val code: DeviceCode, val deadline: Long, val trouble: Boolean = false) : PairingUi

    /** Stopped: why, for people. */
    data class Failed(val message: String) : PairingUi
}

/**
 * Signing in, out of the sign-in screen: checking an address, the password, the browser's
 * redirect, and a code's pairing loop. The screen keeps only its fields and which way in it
 * shows, and draws [config], [error], [busy] and [pairing]. [onSignedIn] runs once signed in.
 *
 * [now] is a monotonic clock in ms and [sleep] waits that long, for the pairing loop.
 */
class SignInFlow(
    private val service: SignInService,
    private val scope: CoroutineScope,
    stored: SignInConfig?,
    private val onSignedIn: () -> Unit,
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val _config = MutableStateFlow(stored)
    /** How to sign in at the server: null until an address has been checked. */
    val config: StateFlow<SignInConfig?> = _config.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.Asking)
    val pairing: StateFlow<PairingUi> = _pairing.asStateFlow()
    private var pairingJob: Job? = null

    /** Whether [config] lets anything sign in. */
    val ready: Boolean get() = _config.value?.let { it.problem == null } == true

    /** The address being edited: what was checked no longer applies. */
    fun forgetServer() {
        _config.value = null
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    /** Says why something the screen did failed (no browser to open, say). */
    fun fail(message: String) {
        _error.value = message
    }

    /** Asks [address] how to sign in; [quiet] keeps a failure off the screen (on launch). */
    suspend fun check(address: String, quiet: Boolean = false) {
        try {
            val answer = service.bootstrapAt(address)
            _config.value = answer
            _error.value = answer.problem
            if (answer.problem == null && service.setupRequired()) {
                _config.value = null
                _error.value = "Finish first-run setup in a browser at $address first."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!quiet) failed(e)
        }
    }

    /**
     * The form's button: checks [address] while it is new (or not yet checked), else signs in
     * with [username] and [password].
     */
    fun submit(address: String, username: String, password: String) = busyWhile {
        if (!ready || address.trimEnd('/') != service.server) check(address) else {
            service.signIn(username.trim(), password)
            onSignedIn()
        }
    }

    /** A browser sign-in to open, kept for its redirect; null when the server offers none. */
    fun startBrowserSignIn(): BrowserSignInRequest? {
        _error.value = null
        return service.startBrowserSignIn()
    }

    /** The browser came back with [uri]: a code exchanged and signed in, or why not. */
    fun finishBrowserSignIn(uri: String) = busyWhile {
        service.finishBrowserSignIn(uri)
        onSignedIn()
    }

    /** Asks for a code and waits for its approval; a new call starts again with a new code. */
    fun startPairing() {
        pairingJob?.cancel()
        pairingJob = scope.launch { pair() }
    }

    /** Leaving the pairing screen: nothing polls behind it. */
    fun stopPairing() {
        pairingJob?.cancel()
        pairingJob = null
    }

    private suspend fun pair() {
        _pairing.value = PairingUi.Asking
        val code = try {
            service.requestDeviceCode()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _pairing.value = PairingUi.Failed((e as? OAuthException)?.message ?: "Cannot reach the server: ${e.message}")
            return
        }
        _pairing.value = PairingUi.Showing(code, now() + code.expiresInSeconds * 1_000)
        val result = awaitApproval(
            code,
            poll = { service.pollDeviceCode(code) },
            now = now,
            sleep = sleep,
            reachable = { ok -> (_pairing.value as? PairingUi.Showing)?.let { _pairing.value = it.copy(trouble = !ok) } },
        )
        when (result) {
            is PairingResult.Approved -> try {
                service.signedInWith(result.tokens)
                onSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _pairing.value = PairingUi.Failed(e.message ?: "Signing in failed.")
            }
            is PairingResult.Failed -> _pairing.value = PairingUi.Failed(result.failure.message)
        }
    }

    private fun busyWhile(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            } finally {
                _busy.value = false
            }
        }
    }

    /** The server's own words (wrong password, wait N minutes …) as they are; else unreachable. */
    private fun failed(e: Exception) {
        _error.value = when (e) {
            is OAuthException, is ApiException -> e.message
            else -> null
        } ?: "Cannot reach the server: ${e.message}"
    }
}
