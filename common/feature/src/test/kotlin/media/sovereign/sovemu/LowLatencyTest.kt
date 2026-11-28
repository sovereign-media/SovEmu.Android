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

/**
 * The TV's game mode is the whole app's while its setting is on (each switch blinks some TVs), so
 * entering and leaving a game no longer toggles it; the Wi-Fi lock still follows the player.
 */
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
    fun gameModeIsAskedForOutsideAGame() {
        rule.setContent { TvGameMode(enabled = true) }
        rule.waitForIdle()
        assertTrue("the menus ask the TV for its game mode too", prefersMinimalPostProcessing)
        assertEquals("but Wi-Fi may save power", 0, wifiLocks)
    }

    @Test
    fun gameModeOffLeavesTheTvAlone() {
        var enabled by mutableStateOf(true)
        rule.setContent { TvGameMode(enabled) }
        rule.waitForIdle()
        enabled = false
        rule.waitForIdle()
        assertFalse("turning the setting off gives the picture back", prefersMinimalPostProcessing)
    }

    @Test
    fun enteringAndLeavingAGameKeepsGameModeAndTogglesOnlyWifi() {
        var playing by mutableStateOf(false)
        rule.setContent {
            TvGameMode(enabled = true)
            if (playing) rememberLowLatencyWifi()
        }
        rule.waitForIdle()
        assertEquals(0, wifiLocks)

        playing = true
        rule.waitForIdle()
        assertTrue(prefersMinimalPostProcessing)
        assertEquals("a game keeps Wi-Fi out of power saving", 1, wifiLocks)

        playing = false
        rule.waitForIdle()
        assertTrue("leaving the game does not switch the TV back", prefersMinimalPostProcessing)
        assertEquals("but lets Wi-Fi save power again", 0, wifiLocks)
    }

    @Test
    fun theWifiLockDoesNotNeedGameMode() {
        rule.setContent {
            TvGameMode(enabled = false)
            rememberLowLatencyWifi()
        }
        rule.waitForIdle()
        assertFalse("no picture mode switch, so no blink", prefersMinimalPostProcessing)
        assertEquals("Wi-Fi still out of power saving", 1, wifiLocks)
    }
}
