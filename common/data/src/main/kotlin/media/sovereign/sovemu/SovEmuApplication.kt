package media.sovereign.sovemu

import android.app.Application

/**
 * What every app's `Application` does, as Sovereign.Android's `SovereignApplication`: each app
 * extends it with its own `@HiltAndroidApp` class, which starts the Hilt graph.
 */
open class SovEmuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // From the start of the process, so a crash before any screen is recorded too.
        ClientEvents.recordCrashes(this)
    }
}
