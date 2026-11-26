package media.sovereign.sovemu.core

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every account signed in on this device and which one is active, in [store] (the app's private
 * preferences): the list ([StoredAccountCodec]), the active id, and each account's refresh token
 * sealed by [sealer] (AES-GCM under one Android Keystore key). Nothing in here is ever logged.
 *
 * **The upgrade signs nobody out.** On first read, a single-account install's sign-in (its sealed
 * token, or a plain one from before sealing, the client it was issued to, and the device name it
 * gave) becomes one account on [legacyServer], made active. A sealed token moves as it is, never
 * decrypted. The old keys are then removed, so this runs once. The account has no `sub` until
 * its first refresh.
 *
 * **A write to an account that is gone is dropped**: a refresh that started before a sign-out
 * lands after it, and must not bring the removed account's token back.
 */
class AccountStore(
    private val store: KeyValue,
    private val sealer: Sealer,
    private val legacyServer: () -> String?,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _accounts = MutableStateFlow<List<StoredAccount>>(emptyList())
    private var loaded = false

    /** Unsealed tokens, by account id, so a refresh does not unseal one each time. */
    private val tokens = HashMap<String, String>()

    /** Every stored account, on every server, in the order they were added. */
    val accounts: StateFlow<List<StoredAccount>>
        get() {
            all()
            return _accounts.asStateFlow()
        }

    @Synchronized
    fun all(): List<StoredAccount> {
        if (!loaded) {
            migrate()
            _accounts.value = StoredAccountCodec.decode(store.get(ACCOUNTS))
            loaded = true
        }
        return _accounts.value
    }

    fun account(id: String): StoredAccount? = all().firstOrNull { it.id == id }

    /** The account the app refreshes, or null while nobody has been chosen. */
    @Synchronized
    fun activeId(): String? {
        all()
        return store.get(ACTIVE)
    }

    /**
     * Makes [id] the active account (or none), stamped as used at [now]. An id that is not
     * stored clears the active account instead.
     */
    @Synchronized
    fun setActive(id: String?, now: Long) {
        val accounts = all()
        if (id == null || accounts.none { it.id == id }) {
            store.remove(ACTIVE)
            return
        }
        store.put(ACTIVE, id)
        save(accounts.map { if (it.id == id) it.copy(lastUsedAt = now) else it })
    }

    /**
     * Stores the session a sign-in just produced: replaces the account with the same [subject]
     * on [server], else adds one, unless a new account would not fit
     * ([StoredAccount.refusalFor]), when nothing changes.
     */
    @Synchronized
    fun upsert(
        subject: String,
        server: String,
        clientId: String?,
        userName: String?,
        now: Long,
    ): AccountUpsert {
        val accounts = all().toMutableList()
        val index = accounts.indexOfFirst { it.subject == subject && it.isOn(server) }
        val stored = if (index >= 0) {
            // A new sign-in is a new device on the server, with no name yet.
            store.remove(deviceKey(accounts[index].id))
            accounts[index].copy(
                server = server,
                clientId = clientId,
                userName = userName ?: accounts[index].userName,
                lastUsedAt = now,
            ).also { accounts[index] = it }
        } else {
            StoredAccount.refusalFor(accounts, server)?.let { return AccountUpsert.Refused(it) }
            StoredAccount(newId(), subject, server, clientId, userName, now).also { accounts += it }
        }
        save(accounts)
        return AccountUpsert.Stored(stored)
    }

    /**
     * What a refresh learned: the account's `sub` and username (a migrated sign-in has neither
     * until then) and the client its rotated token belongs to.
     */
    @Synchronized
    fun recordRefresh(id: String, idToken: IdToken, clientId: String?) {
        val accounts = all()
        val current = accounts.firstOrNull { it.id == id } ?: return
        val updated = current.copy(
            subject = current.subject ?: idToken.subject,
            userName = idToken.userName ?: current.userName,
            clientId = clientId,
        )
        if (updated != current) save(accounts.map { if (it.id == id) updated else it })
    }

    /** Removes one account and its token; the active id goes with it if it was this one. */
    fun remove(id: String) = removeAll(listOf(id))

    /** Removes every account in [ids] and their tokens: a server taken off the device. */
    @Synchronized
    fun removeAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        save(all().filterNot { it.id in ids })
        for (id in ids) {
            store.remove(tokenKey(id))
            store.remove(deviceKey(id))
            tokens.remove(id)
            if (store.get(ACTIVE) == id) store.remove(ACTIVE)
        }
    }

    /**
     * [id]'s refresh token, or null when there is none or it cannot be unsealed (a damaged entry,
     * or the key gone with a restore).
     */
    @Synchronized
    fun refreshToken(id: String): String? {
        all()
        tokens[id]?.let { return it }
        val sealed = store.get(tokenKey(id)) ?: return null
        return runCatching { sealer.unseal(sealed) }.getOrNull()?.also { tokens[id] = it }
    }

    /** Seals and stores [token] for [id], only while [id] is still a stored account. */
    @Synchronized
    fun setRefreshToken(id: String, token: String) {
        if (all().none { it.id == id }) return
        store.put(tokenKey(id), sealer.seal(token))
        tokens[id] = token
    }

    /** The device name last given to [id]'s sign-in on the server (`nameDevice`), if any. */
    @Synchronized
    fun namedDevice(id: String): String? = store.get(deviceKey(id))

    @Synchronized
    fun setNamedDevice(id: String, name: String) {
        if (all().any { it.id == id }) store.put(deviceKey(id), name)
    }

    private fun save(accounts: List<StoredAccount>) {
        store.put(ACCOUNTS, StoredAccountCodec.encode(accounts))
        _accounts.value = accounts
    }

    /** The single sign-in becomes the first account. See the class KDoc. */
    private fun migrate() {
        val sealed = store.get(LEGACY_SEALED)
            ?: store.get(LEGACY_PLAIN)?.let { plain ->
                runCatching { sealer.seal(plain) }.getOrNull()
            }
        val clientId = store.get(LEGACY_CLIENT)
        val device = store.get(LEGACY_DEVICE)
        if (sealed != null) {
            // A token with no server to refresh against is unusable: nothing to keep.
            legacyServer()?.takeIf { it.isNotBlank() }?.let { server ->
                val account = StoredAccount(newId(), null, server, clientId, null, 0L)
                val accounts = StoredAccountCodec.decode(store.get(ACCOUNTS)) + account
                store.put(ACCOUNTS, StoredAccountCodec.encode(accounts))
                store.put(tokenKey(account.id), sealed)
                device?.let { store.put(deviceKey(account.id), it) }
                store.put(ACTIVE, account.id)
            }
        }
        for (key in listOf(LEGACY_SEALED, LEGACY_PLAIN, LEGACY_CLIENT, LEGACY_DEVICE)) {
            if (store.get(key) != null) store.remove(key)
        }
    }

    companion object {
        const val ACCOUNTS = "accounts"
        const val ACTIVE = "active_account"

        /** Where a single-account install kept its sealed token, its plain one, client, name. */
        const val LEGACY_SEALED = "refresh_token_sealed"
        const val LEGACY_PLAIN = "refresh_token"
        const val LEGACY_CLIENT = "token_client"
        const val LEGACY_DEVICE = "named_device"

        fun tokenKey(id: String) = "refresh_token.$id"

        fun deviceKey(id: String) = "named_device.$id"
    }
}

/** What [AccountStore.upsert] did with a new session. */
sealed interface AccountUpsert {
    data class Stored(val account: StoredAccount) : AccountUpsert

    /** A new account that does not fit; nothing changed. */
    data class Refused(val refusal: AccountRefusal) : AccountUpsert
}
