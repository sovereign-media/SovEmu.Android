package media.sovereign.sovemu

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The screensaver shows after a while without input, only while the app is in the foreground:
 * on Fire TV a stopped activity's screensaver went on playing, out of reach of any button.
 */
@RunWith(RobolectricTestRunner::class)
class IdleTest {
    private var now = 0L
    private val idle = Idle { now }

    private fun minutes(n: Int) {
        now += n * 60_000L
    }

    @Test
    fun showsAfterTheWaitAndAPressEndsIt() {
        idle.started()
        minutes(4)
        idle.check(5)
        assertFalse(idle.on)
        minutes(1)
        idle.check(5)
        assertTrue(idle.on)

        assertTrue("the press ended it", idle.input())
        assertFalse(idle.on)
        assertFalse("a second press is an ordinary one", idle.input())
    }

    @Test
    fun inputStartsTheWaitAgain() {
        idle.started()
        minutes(4)
        idle.input()
        minutes(4)
        idle.check(5)
        assertFalse(idle.on)
    }

    @Test
    fun stoppingEndsItAndItNeverStartsInTheBackground() {
        idle.started()
        minutes(5)
        idle.check(5)
        assertTrue(idle.on)

        idle.stopped()
        assertFalse(idle.on)
        minutes(60)
        idle.check(5)
        assertFalse("not while stopped", idle.on)

        // Back in the foreground, the whole wait starts again.
        idle.started()
        minutes(4)
        idle.check(5)
        assertFalse(idle.on)
    }

    /** A TV switched off can leave the device awake (a sound bar keeps HDMI up): the screensaver
     *  must not play on through the sound bar for ever. */
    @Test
    fun afterPlayingAWhileItGoesToSleepAndAPressWakesIt() {
        idle.started()
        minutes(5)
        idle.check(5)
        assertTrue(idle.on)
        minutes(SCREENSAVER_SLEEP_MINUTES - 1)
        idle.check(5)
        assertFalse(idle.asleep)
        minutes(1)
        idle.check(5)
        assertTrue(idle.on)
        assertTrue(idle.asleep)

        assertTrue(idle.input())
        assertFalse(idle.on)
        assertFalse(idle.asleep)
        // The next screensaver gets its whole time again.
        minutes(5)
        idle.check(5)
        assertTrue(idle.on)
        assertFalse(idle.asleep)
    }

    @Test
    fun zeroMinutesIsNever() {
        idle.started()
        minutes(600)
        idle.check(0)
        assertFalse(idle.on)
    }

    /** A second launch (another launcher's category) reuses the one activity, never stacks a new one. */
    @Test
    fun theActivityIsSingleTask() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }
}
