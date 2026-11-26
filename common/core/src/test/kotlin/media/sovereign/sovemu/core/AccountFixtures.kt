package media.sovereign.sovemu.core

import java.util.Base64

/** Preferences in a map. */
class MemoryKeyValue : KeyValue {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

/** A reversible stand-in for the Keystore's AES-GCM: marks what it sealed. */
class FakeSealer : Sealer {
    override fun seal(plaintext: String) = "sealed:" + plaintext.reversed()
    override fun unseal(sealed: String): String {
        require(sealed.startsWith("sealed:")) { "not ours" }
        return sealed.removePrefix("sealed:").reversed()
    }
}

/** An ID token as SovEmu's server signs one (the signature is not checked by the app). */
fun idToken(subject: String, userName: String? = null): String {
    val claims = buildString {
        append("""{"iss":"http://sovemu","sub":"$subject"""")
        if (userName != null) append(""","preferred_username":"$userName"""")
        append("}")
    }
    val encoder = Base64.getUrlEncoder().withoutPadding()
    return encoder.encodeToString("""{"alg":"ES256"}""".toByteArray()) + "." +
        encoder.encodeToString(claims.toByteArray()) + ".signature"
}
