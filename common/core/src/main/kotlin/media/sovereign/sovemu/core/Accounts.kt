package media.sovereign.sovemu.core

/**
 * One account signed in on this device, as Sovereign.Android keeps them: several stay signed in
 * at once, each with its own sealed refresh token in [AccountStore], and one is active. Only the
 * active one is ever refreshed: nothing spends the server's token endpoint on an account nobody
 * has picked.
 *
 * [id] is minted on the device (a UUID) when the account is first stored and never changes; it
 * is not the server's user id. [subject] is the ID token's `sub`, the server's user id: signing
 * in to an account already on the device (the same `sub` on the same server) replaces that
 * account's session rather than adding a second one. It is null only on the sign-in moved over
 * from a single-account install, until its first refresh brings an ID token. [clientId] is the
 * OAuth client the refresh token was issued to, which every refresh and the revocation must use
 * (null: the device client, as a migrated sign-in always was). [userName] labels the account
 * (the ID token's `preferred_username`). [lastUsedAt] is when it was last made active (wall
 * clock, ms), for the picker's initial focus.
 */
data class StoredAccount(
    val id: String,
    val subject: String?,
    val server: String,
    val clientId: String?,
    val userName: String?,
    val lastUsedAt: Long,
) {
    /** What the account is shown as: its username, or `?` before one is known. */
    val label: String get() = userName?.takeIf { it.isNotBlank() } ?: "?"

    /** The first two characters of [label], uppercased, for the account's disc. */
    val initials: String get() = label.take(2).uppercase()

    /** Whether this account belongs to the server at [address] ([sameServer]). */
    fun isOn(address: String?): Boolean = sameServer(server, address)

    companion object {
        /** At most five accounts per server: the picker's two rows of three, one `Add user`. */
        const val MAX_PER_SERVER = 5

        /** At most three servers per device, so *Choose a server* fits on one screen. */
        const val MAX_SERVERS = 3

        /**
         * Why a **new** account on [server] would not fit beside [accounts] (every account on
         * the device), or null when it fits. A sign-in that replaces an account already stored
         * always fits. Nobody is ever signed out to make room.
         */
        fun refusalFor(accounts: List<StoredAccount>, server: String): AccountRefusal? {
            val here = accounts.count { it.isOn(server) }
            if (here >= MAX_PER_SERVER) return AccountRefusal.ServerFull
            val servers = accounts.map { serverKey(it.server) }.distinct().size
            if (here == 0 && servers >= MAX_SERVERS) return AccountRefusal.DeviceFull
            return null
        }

        /**
         * Whether two server addresses are the same server: case-insensitive, without the scheme
         * and without a trailing slash, so a server reached over `https` one day and `http` the
         * next keeps its accounts.
         */
        fun sameServer(a: String?, b: String?): Boolean {
            if (a.isNullOrBlank() || b.isNullOrBlank()) return false
            return serverKey(a) == serverKey(b)
        }

        /** The comparable form of a server address ([sameServer]). */
        fun serverKey(address: String): String {
            val trimmed = address.trim().trimEnd('/').lowercase()
            val scheme = trimmed.indexOf("://")
            return if (scheme >= 0) trimmed.substring(scheme + 3) else trimmed
        }
    }
}

/**
 * Why a sign-in that would add an account was refused. Its new token is revoked, and whoever was
 * signed in stays signed in.
 */
enum class AccountRefusal(val message: String) {
    /** Its server already has [StoredAccount.MAX_PER_SERVER] accounts on this device. */
    ServerFull("This device can't hold another user."),

    /** It is a new server, and the device already holds [StoredAccount.MAX_SERVERS]. */
    DeviceFull(KnownServers.CAP_LINE),

    /** The server's answer had no ID token with a `sub`: there is nothing to key it on. */
    NoIdentity("The server didn't say who signed in. Try again."),
    ;

    /** As the sign-in screen shows it. */
    fun exception(): OAuthException = OAuthException(ACCOUNT_REFUSED, message, 0)

    companion object {
        /** [OAuthException.error] of a refused account. */
        const val ACCOUNT_REFUSED = "account_refused"
    }
}

/** What a launch does with the accounts stored for the current server. */
enum class LaunchAccounts {
    /** None: the sign-in screen. */
    Login,

    /** One: straight to Home as it, as a single-account install always did. */
    Restore,

    /** Two or more: *Who's watching?*, with no token request until someone is chosen. */
    Picker,
    ;

    companion object {
        fun decide(accounts: Int): LaunchAccounts = when {
            accounts <= 0 -> Login
            accounts == 1 -> Restore
            else -> Picker
        }
    }
}

/**
 * One server the device holds accounts for, as *Choose a server* lists it: [address] is the one
 * in use's own address, else its first account's; [inUse] marks the server the app talks to now.
 */
data class KnownServer(val address: String, val accounts: List<StoredAccount>, val inUse: Boolean) {
    val key: String get() = StoredAccount.serverKey(address)

    /** `1 user` or `N users`, then `  ·  in use` on the current one. */
    val countLine: String
        get() = KnownServers.countLabel(accounts.size) + if (inUse) KnownServers.IN_USE else ""

    val removeTitle: String get() = "Remove $address?"

    /** Names everyone removing it signs out. */
    val removeMessage: String
        get() = KnownServers.REMOVE_PREFIX + accounts.joinToString(", ") { it.label } + "."
}

/** *Choose a server*'s list and the decisions around it, as Sovereign.Android's. */
object KnownServers {
    const val TITLE = "Choose a server"
    const val HINT = "Each server keeps its own users on this device."
    const val ADD_A_SERVER = "Add a server"
    const val IN_USE = "  ·  in use"
    const val REMOVE_PREFIX = "Everyone signed in to it on this device will be signed out: "
    const val REMOVE = "Remove"
    const val CANCEL = "Cancel"

    /** In `Add a server`'s place when the device holds [StoredAccount.MAX_SERVERS]. */
    const val CAP_LINE = "This device holds 3 servers. Remove one to add another."

    fun countLabel(count: Int): String = if (count == 1) "1 user" else "$count users"

    fun canAddServer(servers: Int): Boolean = servers < StoredAccount.MAX_SERVERS

    /**
     * Every server [accounts] are on, in the order they were added, [current] marked in use. A
     * current server with no account is not listed: the list is of servers with someone to pick.
     */
    fun list(accounts: List<StoredAccount>, current: String?): List<KnownServer> {
        val groups = LinkedHashMap<String, MutableList<StoredAccount>>()
        for (account in accounts) {
            groups.getOrPut(StoredAccount.serverKey(account.server)) { mutableListOf() } += account
        }
        val currentKey = current?.takeIf { it.isNotBlank() }?.let(StoredAccount::serverKey)
        return groups.map { (key, members) ->
            val inUse = key == currentKey
            KnownServer(
                address = if (inUse) current!!.trim().trimEnd('/') else members.first().server,
                accounts = members,
                inUse = inUse,
            )
        }
    }

    /**
     * The server a removal of [removed] leaves the cursor on, and the one the app moves to when
     * it was in use: the next in the list, else the one before, else none (server setup).
     */
    fun successor(servers: List<KnownServer>, removed: KnownServer): KnownServer? {
        val index = servers.indexOfFirst { it.key == removed.key }
        if (index < 0) return null
        return servers.getOrNull(index + 1) ?: servers.getOrNull(index - 1)
    }
}

/**
 * The stored form of the account list: one string, one record per line and one field per tab
 * behind a version line, every field escaped and every optional one marked present (`+`) or
 * absent (`-`). A stored blob is not trusted: anything malformed yields a shorter list, never an
 * error, so a damaged record cannot keep the app from the accounts that decode.
 */
object StoredAccountCodec {
    private const val VERSION = "v1"
    private const val FIELDS = 6

    fun encode(accounts: List<StoredAccount>): String = buildString {
        append(VERSION)
        for (a in accounts) {
            append('\n')
            append(
                listOf(
                    present(a.id),
                    optional(a.subject),
                    present(a.server),
                    optional(a.clientId),
                    optional(a.userName),
                    present(a.lastUsedAt.toString()),
                ).joinToString("\t"),
            )
        }
    }

    fun decode(blob: String?): List<StoredAccount> {
        if (blob.isNullOrBlank()) return emptyList()
        val lines = blob.split('\n')
        if (lines.first() != VERSION) return emptyList()
        val seen = HashSet<String>()
        // One record per id: two sharing one token key would sign each other out.
        return lines.drop(1).mapNotNull(::record).filter { seen.add(it.id) }
    }

    private fun record(line: String): StoredAccount? {
        val parts = line.split('\t')
        if (parts.size != FIELDS) return null
        val fields = parts.map { field(it) ?: return null }
        val id = fields[0].value?.takeIf { it.isNotBlank() } ?: return null
        val server = fields[2].value?.takeIf { it.isNotBlank() } ?: return null
        val lastUsed = fields[5].value?.toLongOrNull() ?: return null
        return StoredAccount(
            id,
            fields[1].value,
            server,
            fields[3].value,
            fields[4].value,
            lastUsed,
        )
    }

    private class Field(val value: String?)

    private fun field(raw: String): Field? = when {
        raw == "-" -> Field(null)
        raw.startsWith("+") -> unescape(raw.substring(1))?.let(::Field)
        else -> null
    }

    private fun present(value: String) = "+" + escape(value)

    private fun optional(value: String?) = if (value == null) "-" else present(value)

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '%' -> append("%25")
                '\t' -> append("%09")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(c)
            }
        }
    }

    private fun unescape(value: String): String? {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            if (i + 3 > value.length) return null
            out.append(
                when (value.substring(i + 1, i + 3)) {
                    "25" -> '%'
                    "09" -> '\t'
                    "0A" -> '\n'
                    "0D" -> '\r'
                    else -> return null
                },
            )
            i += 3
        }
        return out.toString()
    }
}

/**
 * What the app reads off an ID token: who signed in ([subject], `sub`) and their username
 * (`preferred_username`). Not verified: the server checks every request regardless, and this only
 * keys and labels an account on the device. The access token is never decoded.
 */
data class IdToken(val subject: String?, val userName: String?) {
    companion object {
        private val SUBJECT = Regex("\"sub\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(\\d+))")
        private val USERNAME = Regex("\"preferred_username\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        val EMPTY = IdToken(null, null)

        /** Reads [token]; a missing or malformed one is [EMPTY], never a throw. */
        fun read(token: String?): IdToken {
            if (token.isNullOrBlank()) return EMPTY
            val parts = token.split('.')
            if (parts.size < 2) return EMPTY
            val payload = runCatching {
                String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            }.getOrNull() ?: return EMPTY
            val subject = SUBJECT.find(payload)?.let { m ->
                m.groupValues[1].ifEmpty { m.groupValues[2] }
            }?.takeIf { it.isNotBlank() }
            val userName = USERNAME.find(payload)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            return IdToken(subject, userName)
        }
    }
}
