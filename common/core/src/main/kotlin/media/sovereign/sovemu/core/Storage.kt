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
