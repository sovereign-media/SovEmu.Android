package media.sovereign.sovemu.firetv

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestTest {
    /**
     * A second launch (another launcher's category, or a redirect) reuses the one activity,
     * never stacks a new one.
     */
    @Test
    fun theActivityIsSingleTask() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }
}
