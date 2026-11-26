package media.sovereign.sovemu.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * One proxy header the person added (a Cloudflare Access service token, a forward-auth header, a
 * shared secret), sent on every request to the server so it can sit behind an authenticating
 * proxy. Neither half is ever logged: a value is a credential.
 */
data class CustomHeader(val name: String, val value: String) {
    /** Never the value. */
    override fun toString() = "CustomHeader(name=$name)"
}

/**
 * The rules for proxy headers, as Sovereign.Android's (after its Roku channel's): at most
 * [MAX_HEADERS], names of RFC 7230 token characters only (a space or colon makes a malformed
 * request, a CR or LF is header injection), never a header the app sets itself, and one per name
 * whatever its case (HTTP names are case-insensitive: two would leave the proxy to choose).
 */
object CustomHeaderRules {
    const val MAX_HEADERS = 5

    private const val NAME_CHARS = "!#$%&'*+-.^_`|~"

    /**
     * Headers the app sets: a person's `Authorization` would fight the bearer token, and break
     * the one request that carries none (asking a server how to sign in, which is the request
     * these headers exist to get through a proxy); the rest frame or route the request.
     */
    val RESERVED_NAMES = setOf("authorization", "content-type", "content-length", "host")

    fun cleanName(text: String) = text.trim()

    /** Strips CR and LF (request smuggling) rather than refusing them, then trims. */
    fun cleanValue(text: String) = text.filterNot { it == '\r' || it == '\n' }.trim()

    /** Null when [name] is usable, else a sentence for the screen saying which rule it broke. */
    fun nameError(name: String): String? = when {
        name.isEmpty() -> "Enter a header name."

        !name.all(::isNameChar) ->
            "A header name can only contain letters, digits and ! # $ % & ' * + - . ^ _ ` | ~ " +
                "(no spaces or colons)."

        name.lowercase() in RESERVED_NAMES -> "\"$name\" is set by the app and cannot be changed."

        else -> null
    }

    fun valueError(value: String): String? = if (value.isEmpty()) "Enter a header value." else null

    /**
     * Everything that must hold before [upsert], in one answer: an edit ([replaceIndex] >= 0)
     * or a name already there never counts against the cap.
     */
    fun validate(
        entries: List<CustomHeader>,
        name: String,
        value: String,
        replaceIndex: Int = -1,
    ): String? {
        nameError(name)?.let { return it }
        valueError(value)?.let { return it }
        val new = replaceIndex < 0 && indexOfName(entries, name) < 0
        if (new && entries.size >= MAX_HEADERS) {
            return "You can add up to $MAX_HEADERS custom headers. Remove one first."
        }
        return null
    }

    /**
     * Adds a header, or replaces the one with that name (any case). An edit in place
     * ([replaceIndex]) keeps its row, and its old name goes with it.
     */
    fun upsert(
        entries: List<CustomHeader>,
        name: String,
        value: String,
        replaceIndex: Int = -1,
    ): List<CustomHeader> {
        val replacement = CustomHeader(name, value)
        val result = mutableListOf<CustomHeader>()
        var placed = false
        entries.forEachIndexed { i, current ->
            if (i == replaceIndex || current.name.equals(name, ignoreCase = true)) {
                if (!placed) {
                    result += replacement
                    placed = true
                }
            } else {
                result += current
            }
        }
        if (!placed) result += replacement
        return result
    }

    fun removeAt(entries: List<CustomHeader>, index: Int) = entries.filterIndexed { i, _ ->
        i !=
            index
    }

    fun indexOfName(entries: List<CustomHeader>, name: String) =
        entries.indexOfFirst { it.name.equals(name, ignoreCase = true) }

    /**
     * Every rule again over a list that came from storage, which is not trusted: anything that
     * would be refused at entry is dropped (first of a name wins), so a damaged store can never
     * keep the app from a server it could otherwise reach.
     */
    fun sanitize(entries: List<CustomHeader>): List<CustomHeader> {
        val result = mutableListOf<CustomHeader>()
        val seen = mutableSetOf<String>()
        for (entry in entries) {
            if (result.size >= MAX_HEADERS) break
            val name = cleanName(entry.name)
            val value = cleanValue(entry.value)
            if (nameError(name) != null || valueError(value) != null) continue
            if (!seen.add(name.lowercase())) continue
            result += CustomHeader(name, value)
        }
        return result
    }

    /** The stored form: a line per header, a tab between name and value (neither can hold one). */
    fun encode(entries: List<CustomHeader>): String =
        sanitize(entries).joinToString("\n") { "${it.name}\t${it.value}" }

    fun decode(blob: String?): List<CustomHeader> {
        if (blob.isNullOrBlank()) return emptyList()
        return sanitize(
            blob.split('\n').mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <=
                    0
                ) {
                    null
                } else {
                    CustomHeader(line.substring(0, tab), line.substring(tab + 1))
                }
            },
        )
    }

    private fun isNameChar(c: Char) =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in NAME_CHARS
}

/**
 * What to try, in order, for an address typed on the sign-in form, as Sovereign's: a bare host or
 * IP is asked over `https` first and `http` second, and *Secure connections only* ([secureOnly])
 * drops the second. A server with TLS is then never talked to in the clear without anyone
 * knowing. An address typed with a scheme is used as typed, except `http://` under secure-only,
 * which is a contradiction: nothing to try, and the form says why.
 */
object ServerAddress {
    private const val HTTPS = "https://"
    private const val HTTP = "http://"

    /** What the form says for an `http://` address while secure-only is on. */
    const val INSECURE =
        "This address uses http. Turn off Secure connections only to connect to it."

    fun candidates(input: String, secureOnly: Boolean): List<String> {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()
        return when {
            trimmed.startsWith(HTTPS, ignoreCase = true) -> listOf(trimmed)

            trimmed.startsWith(
                HTTP,
                ignoreCase = true,
            ) -> if (secureOnly) emptyList() else listOf(trimmed)

            secureOnly -> listOf(HTTPS + trimmed)

            else -> listOf(HTTPS + trimmed, HTTP + trimmed)
        }
    }

    /** Whether [input] names `http://`, which secure-only refuses. */
    fun isInsecure(input: String) = input.trim().startsWith(HTTP, ignoreCase = true)

    /** Whether [input] is (one way of writing) [server], the address already in use. */
    fun names(input: String, server: String) =
        server.isNotEmpty() && candidates(input, secureOnly = false).any { it == server }
}

/**
 * Adds the person's proxy headers ([headers]) to every request to the [server] (its host and
 * port), and to nothing else: a value is usually a credential, for the server's proxy only. One
 * client serves GraphQL, sign-in, artwork and video, so they reach all of those.
 */
class CustomHeaderInterceptor(
    private val headers: () -> List<CustomHeader>,
    private val server: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val all = headers()
        val target = server().toHttpUrlOrNull()
        if (all.isEmpty() || target == null || target.host != request.url.host ||
            target.port != request.url.port
        ) {
            return chain.proceed(request)
        }
        val builder = request.newBuilder()
        for (h in all) builder.header(h.name, h.value)
        return chain.proceed(builder.build())
    }
}
