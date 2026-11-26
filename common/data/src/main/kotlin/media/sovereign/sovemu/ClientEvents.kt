package media.sovereign.sovemu

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.sovereign.sovemu.core.ClientEventQueue

/**
 * What goes wrong on this device, sent to the server's log (`client event`), so a fault seen on
 * a TV can be read with `kubectl logs` instead of adb: a connection that failed or dropped, a
 * picture that stalled, a controller with no known layout, and the last run's crash.
 *
 * Events gather for two seconds and go in one call; a report that fails is dropped.
 */
object ClientEvents {
    private const val TAG = "SovEmu.Events"
    private const val GATHER_MS = 2000L
    private const val CRASH_PREF = "last_crash"

    private val queue = ClientEventQueue()
    private var api: SovEmuApi? = null
    private var scope: CoroutineScope? = null
    private var client = "android"
    private var pending: Job? = null

    /** Starts sending, and reports the crash that ended the last run, if any. */
    fun attach(context: Context, api: SovEmuApi, scope: CoroutineScope) {
        this.api = api
        this.scope = scope
        client = clientName(context)
        val prefs = context.getSharedPreferences("sovemu", Context.MODE_PRIVATE)
        prefs.getString(CRASH_PREF, null)?.let {
            prefs.edit().remove(CRASH_PREF).apply()
            report("SCREEN_ERROR", "the last run crashed: $it")
        }
    }

    /**
     * Keeps an uncaught exception for the next run to report (nothing can be sent while crashing).
     */
    fun recordCrashes(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("sovemu", Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val at =
                e.stackTrace.firstOrNull { it.className.startsWith("media.sovereign") }
                    ?: e.stackTrace.firstOrNull()
            prefs.edit().putString(
                CRASH_PREF,
                "${e.javaClass.simpleName}: ${e.message} at $at",
            ).commit()
            previous?.uncaughtException(thread, e)
        }
    }

    /** Reports something that went wrong; [kind] is a `ClientEventKind` name. */
    fun report(kind: String, message: String, sessionId: String? = null) {
        if (!queue.add(kind, message, sessionId)) return
        Log.i(TAG, "$kind $message")
        val scope = scope ?: return
        if (pending?.isActive == true) return
        pending = scope.launch {
            delay(GATHER_MS)
            // `attempt`, not runCatching: a report dropped on failure, but cancellation still
            // stops it.
            for (batch in queue.take()) attempt { api?.reportClientEvents(client, batch) }
        }
    }

    /** `android 0.1.0 (MiTV-AFMU1, Android 11)`. */
    fun clientName(context: Context): String =
        "android ${version(context)} (${Build.MODEL}, Android ${Build.VERSION.RELEASE})"

    /** `SovEmu for Android 0.1.0 · MiTV-AFMU1`: this sign-in, on the admin's Devices page. */
    fun deviceName(context: Context): String =
        "SovEmu for Android ${version(context)} · ${Build.MODEL}"

    private fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()
        ?: "?"
}
