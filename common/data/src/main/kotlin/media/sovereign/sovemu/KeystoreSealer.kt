package media.sovereign.sovemu

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import media.sovereign.sovemu.core.Sealer

/**
 * AES-GCM under a key held in the Android Keystore, as Sovereign seals its refresh tokens: the
 * key never leaves the device and is destroyed with the app's data, so a copied value cannot be
 * opened elsewhere. Each seal has a fresh IV, stored before the ciphertext.
 *
 * No user authentication is required to use the key: a TV has no lock screen, and requiring one
 * would make the token unreadable on the devices this app is for. The key is resolved once:
 * loading the Keystore provider takes tens of milliseconds on a Fire TV Stick.
 */
internal object KeystoreSealer : Sealer {
    private const val ALIAS = "sovemu_refresh_token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val SEPARATOR = ":"

    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                generateKey()
            }
    }

    override fun seal(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    override fun unseal(sealed: String): String {
        val parts = sealed.split(SEPARATOR, limit = 2)
        require(parts.size == 2) { "Malformed sealed token" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
