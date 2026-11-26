package media.sovereign.sovemu

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a phone's browser sign-in stands, between [BrowserSignInActivity] and the sign-in screen
 * (which may be a new one: Android can end the app while the person is in the browser).
 */
object BrowserSignIn {
    private val _redirect = MutableStateFlow<String?>(null)
    private val _noBrowser = MutableStateFlow(0)

    /** The redirect the browser came back with, until the sign-in screen takes it ([take]). */
    val redirect: StateFlow<String?> = _redirect.asStateFlow()

    /**
     * Counts sign-ins that found no browser on the device to open. (A tab the person closed
     * is a cancel, and says nothing.)
     */
    val noBrowser: StateFlow<Int> = _noBrowser.asStateFlow()

    /** Takes the waiting redirect, if any: it is handled once. */
    fun take(): String? = _redirect.value.also { _redirect.value = null }

    internal fun arrived(uri: String) {
        _redirect.value = uri
    }

    internal fun noBrowserFound() {
        _noBrowser.value++
    }

    /** Opens the server's sign-in page at [authorizeUrl], through [BrowserSignInActivity]. */
    fun open(context: Context, authorizeUrl: String) {
        context.startActivity(
            Intent(context, BrowserSignInActivity::class.java)
                .putExtra(BrowserSignInActivity.EXTRA_AUTHORIZE_URL, authorizeUrl),
        )
    }
}

/**
 * Carries one browser sign-in from the press to the redirect, as Sovereign's phones do (AppAuth's
 * `AuthorizationManagementActivity`, screenless): opens the authorize URL in a Custom Tab (the
 * system browser, never a WebView) and, when it resumes again, holds the redirect
 * ([BrowserRedirectActivity] delivered it through [onNewIntent]) or nothing, which is the person
 * closing the tab: a cancel, which says nothing. `singleTask` in the phone app's manifest, so a
 * redirect from a full browser in its own task brings this one, and the app under it, forward.
 */
class BrowserSignInActivity : ComponentActivity() {
    private var opened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opened = savedInstanceState?.getBoolean(KEY_OPENED) ?: false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!opened) {
            // No URL is a redirect with no sign-in waiting for it: nobody is asking.
            val url = intent.getStringExtra(EXTRA_AUTHORIZE_URL) ?: return finish()
            opened = true
            if (!openBrowser(url)) {
                BrowserSignIn.noBrowserFound()
                finish()
            }
            return
        }
        intent.data?.let { BrowserSignIn.arrived(it.toString()) }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_OPENED, opened)
    }

    /** False only when the device has no browser to hand the sign-in to. */
    private fun openBrowser(url: String): Boolean = try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    internal companion object {
        const val EXTRA_AUTHORIZE_URL = "media.sovereign.sovemu.AUTHORIZE_URL"
        private const val KEY_OPENED = "opened"
    }
}

/**
 * Receives the redirect (`media.sovereign.sovemu:/oauth2redirect?…`) from the browser and hands
 * it to the waiting [BrowserSignInActivity], clearing the Custom Tab above it. Its own activity,
 * declared only in the phone app, so the TV apps never register the scheme and the app's own
 * activity never sees a redirect. Nothing is checked here: the state and the code are judged
 * against the request when it is exchanged, so a URI another app fires at the scheme is refused
 * there.
 */
class BrowserRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, BrowserSignInActivity::class.java)
                .setData(intent?.data)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
