package media.sovereign.sovemu.core

/**
 * Signing in with a code (RFC 8628, the device grant), as Sovereign's TVs do: the app shows a
 * short code, the person approves it on a phone or computer signed in to the server (at
 * [DeviceCode.verificationUri], the server's `/device` page), and the app's poll of the token
 * endpoint then gets its tokens. The password form is the fallback.
 */

/**
 * A code to show, from the server's device authorization endpoint. [deviceCode] is the
 * credential the poll presents: never shown.
 */
data class DeviceCode(
    val deviceCode: String,
    /** What the person types: eight characters, grouped by the server as `ABCD-EFGH`. */
    val userCode: String,
    /** Where they type it. */
    val verificationUri: String,
    /** The same page with the code filled in: what the QR code holds, when the server sends it. */
    val verificationUriComplete: String?,
    val expiresInSeconds: Long,
    /** How long to wait between polls: the server's, never a guess. */
    val intervalSeconds: Long,
) {
    /** The code as it goes on screen, grouped in fours however the server sent it. */
    val grouped: String
        get() {
            val bare = userCode.filter { it != '-' && it != ' ' }
            return if (bare.length == 8) bare.take(4) + "-" + bare.drop(4) else userCode
        }

    /** What the QR code holds: the page with the code in it, else the page. */
    val qrContent: String get() = verificationUriComplete ?: verificationUri

    /** The page without its scheme, as a person types it (`192.168.1.10:8080/device`). */
    val shownUri: String get() = verificationUri.substringAfter("://")

    override fun toString() = "DeviceCode(userCode=$userCode, verificationUri=$verificationUri)"

    companion object {
        /** RFC 8628's default, for a server that sends no `interval`. */
        const val DEFAULT_INTERVAL_SECONDS = 5L
    }
}

/** One answer from the token endpoint to the device_code grant. */
sealed interface DevicePoll {
    /** `authorization_pending`: nobody has approved it yet. Not a failure: keep waiting. */
    data object Pending : DevicePoll

    /** `slow_down` (or a 429): wait longer from now on (RFC 8628 section 3.5: five seconds more). */
    data object SlowDown : DevicePoll

    /** The request got no answer (offline, a server error). Conclude nothing: the next poll is the retry. */
    data object NoAnswer : DevicePoll

    /** Approved: the tokens. */
    data class Approved(val tokens: Tokens) : DevicePoll

    /** The server said no, for good. */
    data class Refused(val failure: PairingFailure) : DevicePoll
}

/** Why a code stopped without signing in. */
enum class PairingFailure {
    /** The code's lifetime ran out (`expired_token`, or no approval before it): offer a new one. */
    Expired,

    /** The person said no (`access_denied`). */
    Denied,

    /** Anything else the server refused. */
    Failed;

    /** What the screen says, for people. */
    val message: String
        get() = when (this) {
            Expired -> "That code has expired."
            Denied -> "The sign-in was declined."
            Failed -> "The server refused the sign-in."
        }
}

/** How waiting for an approval ended. */
sealed interface PairingResult {
    data class Approved(val tokens: Tokens) : PairingResult
    data class Failed(val failure: PairingFailure) : PairingResult
}

/**
 * Polls for [code]'s approval until the server answers for good or the code's lifetime runs
 * out. Waits [DeviceCode.intervalSeconds] before each poll (first too, so the request that got
 * the code is not doubled), five seconds longer after each `slow_down`, and treats a poll that
 * got no answer as nothing happening. [reachable] is told whether the last poll got an answer,
 * for the screen's line while it is having trouble. Cancel the caller to stop.
 *
 * [now] is a monotonic clock in milliseconds; [sleep] waits that many.
 */
suspend fun awaitApproval(
    code: DeviceCode,
    poll: suspend () -> DevicePoll,
    now: () -> Long,
    sleep: suspend (Long) -> Unit,
    reachable: (Boolean) -> Unit = {},
): PairingResult {
    val deadline = now() + code.expiresInSeconds.coerceAtLeast(1) * 1_000
    var interval = code.intervalSeconds.coerceAtLeast(1) * 1_000
    while (true) {
        val left = deadline - now()
        if (left <= 0) return PairingResult.Failed(PairingFailure.Expired)
        sleep(minOf(interval, left))
        if (now() >= deadline) return PairingResult.Failed(PairingFailure.Expired)
        when (val answer = poll()) {
            DevicePoll.Pending -> reachable(true)
            DevicePoll.NoAnswer -> reachable(false)
            DevicePoll.SlowDown -> {
                reachable(true)
                interval += SLOW_DOWN_STEP_MS
            }
            is DevicePoll.Approved -> return PairingResult.Approved(answer.tokens)
            is DevicePoll.Refused -> return PairingResult.Failed(answer.failure)
        }
    }
}

/** RFC 8628 section 3.5: `slow_down` adds five seconds to the interval, for good. */
const val SLOW_DOWN_STEP_MS = 5_000L

/** The screen's words, the same as Sovereign's TVs where they say the same thing. */
object PairingText {
    const val TITLE = "Sign in with your phone"
    const val GO_TO = "On your phone or computer, go to"
    const val ENTER = "and enter this code:"
    const val WAITING = "Waiting for you to approve…"
    const val TROUBLE = "Having trouble reaching the server…"
    const val ASKING = "Asking the server for a code…"
    const val NEW_CODE = "Get a new code"
    const val USE_PASSWORD = "Use a password instead"
    const val USE_CODE = "Sign in with your phone"

    /** `Expires in 9:41`. */
    fun expiresIn(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "Expires in ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
