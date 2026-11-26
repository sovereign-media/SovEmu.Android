package media.sovereign.sovemu.baselineprofile.mobile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which classes and methods the phone app runs on the way through its most travelled
 * screens, so ART compiles them ahead of time instead of interpreting them on first sight: that
 * is why the first fling of a grid stutters and the second does not.
 *
 * The walk is what a person does on opening the app on a phone: land on Home, scroll its rows,
 * open a system, fling its games, open a game's panel, come back, and search. Not the player
 * (starting a game needs a core and a real ROM, and its cost is decoding, not class loading),
 * nor the menus.
 *
 * **Sign the app in first**, to a server with games (the debug build): the generator installs
 * the release-shaped build over it, which keeps the sign-in, and the run uninstalls it at the
 * end, so sign in again before each run. Every step waits for what it needs and carries on
 * regardless; check the generated file names `SystemKt`, `HomeKt` and `SystemPartsKt`,
 * not only that the build passed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // Home: its hero and its rows, scrolled down until a system's card shows ("Systems" is
        // also the tab bar's, so it says nothing about the rows having loaded).
        device.wait(Until.hasObject(By.text("Systems")), 15_000)
        var tries = 0
        while (!device.wait(Until.hasObject(By.text(GAMES)), 5_000) && tries++ < 3) {
            settle { swipe(up = true, steps = 40) }
        }
        // Clear of the tab bar, which can cover a caption low on the screen.
        val low = device.findObject(By.text(GAMES))?.visibleBounds?.bottom ?: 0
        if (low > device.displayHeight * 2 / 3) settle { swipe(up = true, steps = 40) }
        // A system: its card (the line under it ends "… · 14 games"), its grid flung, a game's
        // panel opened, then back to Home. The caption is not what is tapped: the tile above
        // its two lines is.
        device.findObject(By.text(GAMES))?.let { caption ->
            val line = caption.visibleBounds
            device.click(line.centerX(), line.top - line.height() * 3)
            device.wait(Until.gone(By.text(GAMES)), 10_000)
            device.waitForIdle()
            repeat(2) { settle { swipe(up = true, steps = 10) } }
            settle { swipe(up = false, steps = 10) }
            settle { device.click(device.displayWidth / 4, device.displayHeight / 2) }
            settle { device.pressBack() }
            settle { device.pressBack() }
        }
        // Search, from the tab bar.
        click(By.text("Search"))
        device.waitForIdle()
        settle { device.pressBack() }
    }

    /**
     * Drags the screen's middle up (to scroll down) or down; fewer [steps] is a faster fling.
     * By the screen's coordinates, not a node's: a list's node goes stale as the page recomposes.
     */
    private fun MacrobenchmarkScope.swipe(up: Boolean, steps: Int) {
        val x = device.displayWidth / 2
        val low = device.displayHeight * 3 / 4
        val high = device.displayHeight / 4
        if (up) device.swipe(x, low, x, high, steps) else device.swipe(x, high, x, low, steps)
    }

    /**
     * Clicks what [selector] finds, finding it again if it moved or redrew under the click;
     * false if there was nothing to click.
     */
    private fun MacrobenchmarkScope.click(selector: BySelector): Boolean {
        repeat(3) {
            try {
                val found = device.findObject(selector) ?: return false
                found.click()
                return true
            } catch (_: StaleObjectException) {
                device.waitForIdle(500)
            }
        }
        return false
    }

    private inline fun MacrobenchmarkScope.settle(step: () -> Unit) {
        step()
        device.waitForIdle(500)
    }

    private companion object {
        const val PACKAGE = "media.sovereign.sovemu.mobile"
        val GAMES: Pattern = Pattern.compile(".* · \\d+ games?")
    }
}
