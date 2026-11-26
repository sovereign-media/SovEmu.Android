package media.sovereign.sovemu.core

/**
 * Seals a secret for storage on the device and unseals it again. The app's is AES-GCM under a
 * key in the Android Keystore, which never leaves the device and is destroyed with its data; an
 * interface so the storage logic is tested off-device, where the Keystore does not exist.
 */
interface Sealer {
    fun seal(plaintext: String): String

    /** Throws on something this key did not seal, or a damaged one. */
    fun unseal(sealed: String): String
}

/** A device's small key-value storage (the app's private preferences). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * The refresh token, kept sealed ([Sealer]) in [store], as Sovereign keeps its. A token stored
 * in plain text by an earlier version is sealed the first time it is read and the plain copy
 * removed, so an update signs nobody out. A sealed token that cannot be opened (the key gone,
 * the value damaged) reads as signed out: the app asks to sign in again rather than failing.
 * The token is kept in memory once read, so a refresh does not unseal it each time.
 */
class SealedToken(private val store: KeyValue, private val sealer: Sealer) {
    private var cached: String? = null
    private var read = false

    var value: String?
        @Synchronized get() {
            if (read) return cached
            cached = store.get(SEALED)?.let { sealed ->
                runCatching { sealer.unseal(sealed) }.getOrElse {
                    store.remove(SEALED)
                    null
                }
            } ?: store.get(PLAIN)?.also { plain ->
                // An earlier version's plain token: sealed now, the plain copy gone.
                store.put(SEALED, sealer.seal(plain))
                store.remove(PLAIN)
            }
            read = true
            return cached
        }

        @Synchronized set(token) {
            if (token == null) store.remove(SEALED) else store.put(SEALED, sealer.seal(token))
            store.remove(PLAIN)
            cached = token
            read = true
        }

    companion object {
        /** Where the sealed token is kept. */
        const val SEALED = "refresh_token_sealed"

        /** Where versions before sealing kept it, in plain text. */
        const val PLAIN = "refresh_token"
    }
}
