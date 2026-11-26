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

    /** The browser came back with [uri] (the phone's sign-in activity hands it over). */
    fun arrived(uri: String) {
        _redirect.value = uri
    }

    /** There was no browser to open. */
    fun noBrowserFound() {
        _noBrowser.value++
    }
}
