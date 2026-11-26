package media.sovereign.sovemu.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which classes and methods the app runs on the way through its most travelled screens,
 * so ART compiles them ahead of time instead of interpreting them on first sight: on a TV stick
 * that is why the first scroll of a grid stutters and the second does not.
 *
 * The walk is what a person does on opening the app on a TV: land on Home, cross its rows, open
 * a system, scroll its games, open a game's panel, come back, and search. Not the player (starting
 * a game needs a core and a real ROM, and its cost is decoding, not class loading), nor the menus.
 *
 * **Sign the app in first**, to a server with games (the debug build, with *Sign in with your
 * phone*): the generator installs the release-shaped build over it, which keeps the sign-in, and
 * the run uninstalls it at the end, so sign in again before each run. Every step waits for what
 * it needs and carries on regardless; check the generated file names `SystemKt` and `HomeKt`,
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
        // Home: its hero and its rows.
        device.wait(Until.hasObject(By.text("Systems")), 15_000)
        repeat(4) { press { device.pressDPadDown() } }
        repeat(3) { press { device.pressDPadRight() } }
        // A system: its card (the line under it ends "… · 14 games"), its grid scrolled, a
        // game's panel opened, then back to Home.
        device.findObject(By.text(GAMES))?.let { card ->
            card.click()
            device.wait(Until.gone(By.text("Systems")), 10_000)
            device.waitForIdle()
            repeat(6) { press { device.pressDPadDown() } }
            repeat(3) { press { device.pressDPadRight() } }
            press { device.pressDPadCenter() }
            press { device.pressBack() }
            press { device.pressBack() }
        }
        // Search, from the rail.
        device.findObject(By.desc("Search"))?.click() ?: device.findObject(By.text("Search"))?.click()
        device.waitForIdle()
        press { device.pressBack() }
    }

    private inline fun MacrobenchmarkScope.press(key: () -> Unit) {
        key()
        device.waitForIdle(500)
    }

    private companion object {
        const val PACKAGE = "media.sovereign.sovemu"
        val GAMES: Pattern = Pattern.compile(".* · \\d+ games?")
    }
}
