package media.sovereign.sovemu

import android.view.KeyEvent
import media.sovereign.sovemu.core.Keys
import org.junit.Assert.assertEquals
import org.junit.Test

/** core's copies of `KeyEvent.KEYCODE_*` (core is plain JVM) match the SDK's. */
class KeycodesTest {
    @Test
    fun coreKeycodesAreTheSdks() {
        val pairs = listOf(
            "DPAD_UP" to (Keys.Android.DPAD_UP to KeyEvent.KEYCODE_DPAD_UP),
            "DPAD_DOWN" to (Keys.Android.DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN),
            "DPAD_LEFT" to (Keys.Android.DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT),
            "DPAD_RIGHT" to (Keys.Android.DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT),
            "DIGIT_0" to (Keys.Android.DIGIT_0 to KeyEvent.KEYCODE_0),
            "DIGIT_9" to (Keys.Android.DIGIT_9 to KeyEvent.KEYCODE_9),
            "A" to (Keys.Android.A to KeyEvent.KEYCODE_A),
            "Z" to (Keys.Android.Z to KeyEvent.KEYCODE_Z),
            "COMMA" to (Keys.Android.COMMA to KeyEvent.KEYCODE_COMMA),
            "PERIOD" to (Keys.Android.PERIOD to KeyEvent.KEYCODE_PERIOD),
            "ALT_LEFT" to (Keys.Android.ALT_LEFT to KeyEvent.KEYCODE_ALT_LEFT),
            "ALT_RIGHT" to (Keys.Android.ALT_RIGHT to KeyEvent.KEYCODE_ALT_RIGHT),
            "SHIFT_LEFT" to (Keys.Android.SHIFT_LEFT to KeyEvent.KEYCODE_SHIFT_LEFT),
            "SHIFT_RIGHT" to (Keys.Android.SHIFT_RIGHT to KeyEvent.KEYCODE_SHIFT_RIGHT),
            "TAB" to (Keys.Android.TAB to KeyEvent.KEYCODE_TAB),
            "SPACE" to (Keys.Android.SPACE to KeyEvent.KEYCODE_SPACE),
            "ENTER" to (Keys.Android.ENTER to KeyEvent.KEYCODE_ENTER),
            "DEL" to (Keys.Android.DEL to KeyEvent.KEYCODE_DEL),
            "GRAVE" to (Keys.Android.GRAVE to KeyEvent.KEYCODE_GRAVE),
            "MINUS" to (Keys.Android.MINUS to KeyEvent.KEYCODE_MINUS),
            "EQUALS" to (Keys.Android.EQUALS to KeyEvent.KEYCODE_EQUALS),
            "LEFT_BRACKET" to (Keys.Android.LEFT_BRACKET to KeyEvent.KEYCODE_LEFT_BRACKET),
            "RIGHT_BRACKET" to (Keys.Android.RIGHT_BRACKET to KeyEvent.KEYCODE_RIGHT_BRACKET),
            "BACKSLASH" to (Keys.Android.BACKSLASH to KeyEvent.KEYCODE_BACKSLASH),
            "SEMICOLON" to (Keys.Android.SEMICOLON to KeyEvent.KEYCODE_SEMICOLON),
            "APOSTROPHE" to (Keys.Android.APOSTROPHE to KeyEvent.KEYCODE_APOSTROPHE),
            "SLASH" to (Keys.Android.SLASH to KeyEvent.KEYCODE_SLASH),
            "PAGE_UP" to (Keys.Android.PAGE_UP to KeyEvent.KEYCODE_PAGE_UP),
            "PAGE_DOWN" to (Keys.Android.PAGE_DOWN to KeyEvent.KEYCODE_PAGE_DOWN),
            "BUTTON_A" to (Keys.Android.BUTTON_A to KeyEvent.KEYCODE_BUTTON_A),
            "BUTTON_B" to (Keys.Android.BUTTON_B to KeyEvent.KEYCODE_BUTTON_B),
            "BUTTON_X" to (Keys.Android.BUTTON_X to KeyEvent.KEYCODE_BUTTON_X),
            "BUTTON_Y" to (Keys.Android.BUTTON_Y to KeyEvent.KEYCODE_BUTTON_Y),
            "BUTTON_L1" to (Keys.Android.BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_L1),
            "BUTTON_R1" to (Keys.Android.BUTTON_R1 to KeyEvent.KEYCODE_BUTTON_R1),
            "BUTTON_L2" to (Keys.Android.BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_L2),
            "BUTTON_R2" to (Keys.Android.BUTTON_R2 to KeyEvent.KEYCODE_BUTTON_R2),
            "BUTTON_THUMBL" to (Keys.Android.BUTTON_THUMBL to KeyEvent.KEYCODE_BUTTON_THUMBL),
            "BUTTON_THUMBR" to (Keys.Android.BUTTON_THUMBR to KeyEvent.KEYCODE_BUTTON_THUMBR),
            "BUTTON_START" to (Keys.Android.BUTTON_START to KeyEvent.KEYCODE_BUTTON_START),
            "BUTTON_SELECT" to (Keys.Android.BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_SELECT),
            "ESCAPE" to (Keys.Android.ESCAPE to KeyEvent.KEYCODE_ESCAPE),
            "FORWARD_DEL" to (Keys.Android.FORWARD_DEL to KeyEvent.KEYCODE_FORWARD_DEL),
            "CTRL_LEFT" to (Keys.Android.CTRL_LEFT to KeyEvent.KEYCODE_CTRL_LEFT),
            "CTRL_RIGHT" to (Keys.Android.CTRL_RIGHT to KeyEvent.KEYCODE_CTRL_RIGHT),
            "CAPS_LOCK" to (Keys.Android.CAPS_LOCK to KeyEvent.KEYCODE_CAPS_LOCK),
            "META_LEFT" to (Keys.Android.META_LEFT to KeyEvent.KEYCODE_META_LEFT),
            "META_RIGHT" to (Keys.Android.META_RIGHT to KeyEvent.KEYCODE_META_RIGHT),
            "MOVE_HOME" to (Keys.Android.MOVE_HOME to KeyEvent.KEYCODE_MOVE_HOME),
            "MOVE_END" to (Keys.Android.MOVE_END to KeyEvent.KEYCODE_MOVE_END),
            "INSERT" to (Keys.Android.INSERT to KeyEvent.KEYCODE_INSERT),
            "F1" to (Keys.Android.F1 to KeyEvent.KEYCODE_F1),
            "F12" to (Keys.Android.F12 to KeyEvent.KEYCODE_F12),
            "NUMPAD_0" to (Keys.Android.NUMPAD_0 to KeyEvent.KEYCODE_NUMPAD_0),
            "NUMPAD_9" to (Keys.Android.NUMPAD_9 to KeyEvent.KEYCODE_NUMPAD_9),
            "NUMPAD_ENTER" to (Keys.Android.NUMPAD_ENTER to KeyEvent.KEYCODE_NUMPAD_ENTER),
        )
        for ((name, codes) in pairs) assertEquals(name, codes.second, codes.first)
        // The ranges core relies on are contiguous in the SDK.
        assertEquals(KeyEvent.KEYCODE_Z - KeyEvent.KEYCODE_A, 25)
        assertEquals(KeyEvent.KEYCODE_F12 - KeyEvent.KEYCODE_F1, 11)
        assertEquals(KeyEvent.KEYCODE_NUMPAD_9 - KeyEvent.KEYCODE_NUMPAD_0, 9)
        assertEquals("KeyQ", Keys.keyboardCode(KeyEvent.KEYCODE_Q))
        assertEquals("Digit5", Keys.keyboardCode(KeyEvent.KEYCODE_5))
        assertEquals("F5", Keys.keyboardCode(KeyEvent.KEYCODE_F5))
        assertEquals("Numpad3", Keys.keyboardCode(KeyEvent.KEYCODE_NUMPAD_3))
    }
}
