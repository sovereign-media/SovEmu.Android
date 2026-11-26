package media.sovereign.sovemu.core

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** Where the app stands with the server, as Sovereign.Android's `AuthState`. */
sealed interface AuthState {
    /** No account to use here: the sign-in screen. */
    data object SignedOut : AuthState

    /**
     * Signed in as [accountId] (a [StoredAccount.id]). The id is part of the state so that a
     * switch from one account to another is a change every collector sees.
     */
    data class Authenticated(val accountId: String) : AuthState

    /** Accounts are stored and none is active: *Who's watching?* is up. Nothing refreshes. */
    data object Choosing : AuthState

    /**
     * The active account's sign-in ended (the server refused its refresh token) and it has been
     * removed; [Session.lastExpired] names it, for the sign-in screen to fill its username in.
     */
    data object Expired : AuthState
}

/**
 * The one owner of the app's tokens, and of which signed-in account holds them, as
 * Sovereign.Android's `SessionManager`. Several accounts stay signed in ([AccountStore]); one is
 * active, and only it is ever refreshed: a launch with two or more makes no token request until
 * someone is chosen.
 *
 * One lock covers refreshing and switching, so a refresh always writes the rotated token it
 * brings back to the account it began for (refresh tokens rotate, and a reused one revokes the
 * sign-in), and callers that arrive during a refresh wait for it and use its token. A token goes
 * only to the server that issued it: an account that is not on the current [server] is never
 * refreshed, revoked or sent, only set aside.
 *
 * [now] is a monotonic clock (ms), for the access token's expiry; [clock] the wall clock, for when
 * an account was last used. A sign-out's revocation is sent on [revokes] without waiting: a
 * sign-out works offline.
 */
class Session(
    private val client: TokenClient,
    private val accounts: AccountStore,
    private val server: () -> String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clock: () -> Long = System::currentTimeMillis,
    private val revokes: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Access(
        val accountId: String,
        val server: String,
        val token: String,
        val expiresAt: Long,
    )

    @Volatile private var access: Access? = null
    private val lock = Mutex()
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)

    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Every account on the device, on every server, as it changes. */
    val allAccounts: StateFlow<List<StoredAccount>> get() = accounts.accounts

    /** The account the last [AuthState.Expired] removed. */
    @Volatile var lastExpired: StoredAccount? = null
        private set

    /** Whether an account is signed in and active. */
    val signedIn: Boolean get() = _state.value is AuthState.Authenticated

    /** The active account, or null with nobody chosen. */
    val active: StoredAccount? get() = accounts.activeId()?.let(accounts::account)

    /** The accounts stored for the current server. */
    fun accountsHere(): List<StoredAccount> = accounts.all().filter { it.isOn(server()) }

    /**
     * Decides where the app opens ([LaunchAccounts]) for the current server, without a token
     * request: no account signs out, one is made active, two or more leave the picker up. Also
     * where a sign-out or a change of server lands.
     */
    suspend fun launch(): LaunchAccounts {
        val here = accountsHere()
        val decision = LaunchAccounts.decide(here.size)
        when (decision) {
            LaunchAccounts.Login -> switchTo(null, AuthState.SignedOut)
            LaunchAccounts.Restore -> activate(here.single().id)
            LaunchAccounts.Picker -> switchTo(null, AuthState.Choosing)
        }
        return decision
    }

    @Volatile private var started = false

    /**
     * [launch], once for the life of the process: a window made again (a language change, say)
     * keeps whoever is signed in rather than going back to the picker.
     */
    suspend fun start() {
        if (started) return
        started = true
        launch()
    }

    /**
     * Makes [accountId] the active account: the picker's choice. The previous account's access
     * token is dropped first, so nothing after this goes out as it; the first request refreshes.
     */
    suspend fun activate(accountId: String) {
        switchTo(accountId, AuthState.Authenticated(accountId))
    }

    /**
     * Sets nobody active and shows the picker, without signing anyone out: *Switch user*, and
     * the picker that returns after the app sat idle.
     */
    suspend fun choose() {
        if (accountsHere().isEmpty()) launch() else switchTo(null, AuthState.Choosing)
    }

    /** Signs in with a username and password; throws [OAuthException] with the server's reason. */
    suspend fun signIn(username: String, password: String) {
        signedInWith(client.signIn(username, password), client.deviceClientId)
    }

    /**
     * Keeps the tokens a sign-in got, as [clientId] (the client they were issued to): the
     * account with the same `sub` here is replaced, else one is added and made active. A new
     * account that does not fit ([StoredAccount.refusalFor]), or tokens that say nobody signed
     * in, are refused ([AccountRefusal.exception]) with the new token revoked, and whoever was
     * signed in stays so.
     */
    suspend fun signedInWith(tokens: Tokens, clientId: String?) {
        val refreshToken = tokens.refreshToken
            ?: throw OAuthException("invalid_response", "The server granted no refresh token", 200)
        val here = server()
        val id = IdToken.read(tokens.idToken)
        val subject = id.subject ?: run {
            revoke(refreshToken, clientId)
            throw AccountRefusal.NoIdentity.exception()
        }
        val account = when (
            val upsert = accounts.upsert(
                subject,
                here,
                clientId,
                id.userName,
                clock(),
            )
        ) {
            is AccountUpsert.Stored -> upsert.account

            is AccountUpsert.Refused -> {
                revoke(refreshToken, clientId)
                throw upsert.refusal.exception()
            }
        }
        lock.withLock {
            accounts.setRefreshToken(account.id, refreshToken)
            accounts.setActive(account.id, clock())
            access = Access(account.id, here, tokens.accessToken, expiry(tokens))
            _state.value = AuthState.Authenticated(account.id)
        }
    }

    /**
     * The access token to send, refreshed first when it is about to expire; null with nobody
     * signed in here (also when the refresh just found the sign-in revoked). A network failure is
     * thrown.
     */
    suspend fun accessToken(): String? {
        current()?.takeIf { it.fresh() }?.let { return it.token }
        return refresh(stale = current()?.token)
    }

    /**
     * A new access token for the active account, after [stale] (null: none) was rejected or
     * expired, unless another caller has already replaced it. Null when there is none to have.
     */
    suspend fun refresh(stale: String?): String? = lock.withLock {
        current()?.let { if (it.token != stale && it.fresh()) return@withLock it.token }
        val account = accounts.activeId()?.let(accounts::account) ?: return@withLock null
        // Another server's account: its token is never sent here (the address changed under it).
        if (!account.isOn(server())) return@withLock null
        val refreshToken = accounts.refreshToken(account.id)
        if (refreshToken == null) {
            // Stored, but nothing can be unsealed (a damaged entry, the key lost with a restore):
            // nothing can renew it, which is what an expiry is.
            expire(account)
            return@withLock null
        }
        val tokens = try {
            client.refresh(refreshToken, account.clientId)
        } catch (e: OAuthException) {
            // Spent, revoked, or a client the server no longer has: nothing can renew it.
            if (!e.invalidGrant && e.error != "invalid_client") throw e
            expire(account)
            return@withLock null
        }
        tokens.refreshToken?.let { accounts.setRefreshToken(account.id, it) }
        accounts.recordRefresh(account.id, IdToken.read(tokens.idToken), account.clientId)
        access = Access(account.id, account.server, tokens.accessToken, expiry(tokens))
        tokens.accessToken
    }

    /**
     * Runs a GraphQL [call]; when it fails `UNAUTHENTICATED`, refreshes the access token and
     * runs it once more. When there is nothing to refresh with, the first failure is thrown.
     */
    suspend fun <T> retryingUnauthenticated(call: suspend () -> T): T {
        val sent = current()?.token
        try {
            return call()
        } catch (e: ApiException) {
            if (!e.unauthenticated || refresh(stale = sent) == null) throw e
        }
        return call()
    }

    /**
     * The server refused a token it had just issued: a request was refused again after a
     * refresh that worked. That ends the active account's sign-in, as a refused refresh does.
     */
    suspend fun refusedFreshToken() {
        lock.withLock { accounts.activeId()?.let(accounts::account)?.let { expire(it) } }
    }

    /**
     * Signs the active account out: its token revoked on its own server (without waiting) and the
     * account removed; every other account stays signed in. Lands as a launch does ([launch]):
     * the sign-in screen, the one account left, or the picker.
     */
    suspend fun signOut(): LaunchAccounts {
        lock.withLock {
            val account = accounts.activeId()?.let(accounts::account)
            // An account on another server is set aside, never revoked or removed from here.
            if (account != null && account.isOn(server())) {
                accounts.refreshToken(account.id)?.let { revoke(it, account.clientId) }
                accounts.remove(account.id)
            }
            accounts.setActive(null, clock())
            access = null
            _state.value = AuthState.SignedOut
        }
        return launch()
    }

    /**
     * Takes a server off the device (*Choose a server*): every account on [address] is signed
     * out, each token revoked without waiting. Returns whether the active account was among them
     * (then nobody is signed in, and the caller lands where it moves to).
     */
    suspend fun removeServer(address: String): Boolean {
        val doomed = accounts.all().filter { it.isOn(address) }
        if (doomed.isEmpty()) return false
        return lock.withLock {
            // Each to the server it belongs to, which is this one only while it is current.
            if (StoredAccount.sameServer(address, server())) {
                doomed.forEach { a -> accounts.refreshToken(a.id)?.let { revoke(it, a.clientId) } }
            }
            val wasActive = doomed.any { it.id == accounts.activeId() }
            accounts.removeAll(doomed.map { it.id })
            if (wasActive) {
                access = null
                _state.value = AuthState.SignedOut
            }
            wasActive
        }
    }

    /** The device name last given to the active account's sign-in, if any. */
    fun namedDevice(): String? = accounts.activeId()?.let(accounts::namedDevice)

    fun setNamedDevice(name: String) {
        accounts.activeId()?.let { accounts.setNamedDevice(it, name) }
    }

    /**
     * The access token, only while it is the active account's and the current server's: never
     * the previous viewer's after a switch, never another server's after the address changed.
     */
    private fun current(): Access? {
        val a = access ?: return null
        if (a.accountId != accounts.activeId() || !StoredAccount.sameServer(a.server, server())) {
            return null
        }
        return a
    }

    private suspend fun switchTo(accountId: String?, state: AuthState) {
        lock.withLock {
            access = null
            accounts.setActive(accountId, clock())
            _state.value = state
        }
    }

    /** Removes [account], whose token the server refused; when it was active, [AuthState.Expired]. */
    private fun expire(account: StoredAccount) {
        val wasActive = accounts.activeId() == account.id
        accounts.remove(account.id)
        if (wasActive) {
            access = null
            lastExpired = account
            _state.value = AuthState.Expired
        }
    }

    /** Revokes [refreshToken] without waiting; a revocation nothing answers is dropped. */
    private fun revoke(refreshToken: String, clientId: String?) {
        revokes.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                client.revoke(refreshToken, clientId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The server lets an unused sign-in expire on its own.
            }
        }
    }

    private fun expiry(tokens: Tokens) = now() + tokens.expiresIn * 1000

    private fun Access.fresh() = expiresAt - now() > AuthDefaults.REFRESH_MARGIN_MS

    /** The access token for a request to [url]: only one to the server that issued it. */
    internal fun tokenFor(url: okhttp3.HttpUrl, token: String): String? {
        val a = access ?: return null
        val issuer = a.server.toHttpUrlOrNull() ?: return null
        return token.takeIf { issuer.host == url.host && issuer.port == url.port }
    }
}

object AuthDefaults {
    /** Refresh an access token with less than this left (ms). */
    const val REFRESH_MARGIN_MS = 60_000L
}

/**
 * Adds `Authorization: Bearer` to GraphQL requests (`…/graphql`) to the server that issued the
 * token, refreshing it first when it is about to expire. Other requests (`/media/…` URLs are
 * signed) go as they are. Runs on OkHttp's threads, so waiting for a refresh blocks only the
 * request that needs it.
 */
class BearerInterceptor(private val session: Session) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.endsWith("/graphql")) return chain.proceed(request)
        val token = try {
            runBlocking { session.accessToken() }?.let { session.tokenFor(request.url, it) }
        } catch (e: OAuthException) {
            throw IOException(e.message, e)
        } catch (e: ApiException) {
            throw IOException(e.message, e)
        }
        val signed = token?.let {
            request.newBuilder().header("Authorization", "Bearer $it").build()
        }
        return chain.proceed(signed ?: request)
    }
}
