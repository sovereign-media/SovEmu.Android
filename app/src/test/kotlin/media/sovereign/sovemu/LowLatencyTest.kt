package media.sovereign.sovemu

import android.content.Context
import android.net.wifi.WifiManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The TV's game mode and the Wi-Fi lock follow the player: on while it is open, off after. */
@RunWith(RobolectricTestRunner::class)
class LowLatencyTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val prefersMinimalPostProcessing
        get() = rule.activity.window.attributes.preferMinimalPostProcessing
    private val wifiLocks
        get() = shadowOf(
            rule.activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager,
        ).activeLockCount

    @Test
    fun onlyWhilePlaying() {
        var playing by mutableStateOf(false)
        rule.setContent { if (playing) LowLatencyWhilePlaying() }
        assertFalse("the menus keep the TV's own picture", prefersMinimalPostProcessing)
        assertEquals(0, wifiLocks)

        playing = true
        rule.waitForIdle()
        assertTrue("a game asks the TV for its game mode", prefersMinimalPostProcessing)
        assertEquals("and keeps Wi-Fi out of power saving", 1, wifiLocks)

        playing = false
        rule.waitForIdle()
        assertFalse("leaving the game gives the picture back", prefersMinimalPostProcessing)
        assertEquals("and lets Wi-Fi save power again", 0, wifiLocks)
    }
}
