package media.sovereign.sovemu.core

/**
 * *Sign in a TV* on a phone or tablet (Sovereign's `clients-sign-in-a-tv.md`, sovereign#1516):
 * the person types the code a TV's *Sign in with your phone* screen shows, and the app approves
 * it with its own sign-in (the `approveDeviceCode` mutation), so nobody signs in twice. The pure
 * half: the words, the code's formatting, and what each answer means.
 */
object TvSignIn {
    /** The account menu's entry, and the screen's title. */
    const val TITLE = "Sign in a TV"
    const val INTRO = "Enter the code your TV is showing."
    const val FIELD_LABEL = "TV code"
    const val CONTINUE = "Continue"
    const val APPROVE = "Approve"
    const val CANCEL = "Cancel"
    const val APPROVED = "Your TV is signing in."

    /** The code's characters, without its dash. */
    const val CODE_LENGTH = 8
    private const val GROUP = 4

    /** `Sign in a TV to <server> as <name>?` */
    fun confirmation(server: String, name: String) = "Sign in a TV to $server as $name?"

    /** The code's characters: letters and digits only, upper case, at most eight. */
    fun normalize(input: String): String = input.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        .uppercase()
        .take(CODE_LENGTH)

    /** The code as the TV shows it (`QG3W-RY4W`), the dash after the fourth character. */
    fun format(input: String): String {
        val code = normalize(input)
        return if (code.length > GROUP) code.take(GROUP) + "-" + code.drop(GROUP) else code
    }

    /** Whether all eight characters are there: `Continue`'s gate. */
    fun isComplete(input: String) = normalize(input).length == CODE_LENGTH

    /** What the screen says when the approval failed with [e] (the server's code and message). */
    fun failure(e: Exception): String = when ((e as? ApiException)?.code) {
        "NOT_FOUND" -> "No TV is waiting with that code. Check the code, and that the TV is connected to this server."
        "TOO_MANY_REQUESTS" -> "Too many codes just now. Try again in a minute."
        "UNAUTHENTICATED" -> "Sign in again to sign in a TV."
        else -> e.message ?: "The TV could not be signed in."
    }
}
