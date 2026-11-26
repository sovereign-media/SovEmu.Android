package media.sovereign.sovemu

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControllerTest {
    /** An ID no device has: what a pad's last events carry once it has reconnected as another. */
    private val gone = 4242

    @Test
    fun aButtonFromADeviceAndroidNoLongerKnowsAddsNoController() {
        assertNull(InputDevice.getDevice(gone))
        val controller = Controller()
        val press = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0, 0, gone, 0)

        assertFalse(controller.onKey(press))
        assertTrue(controller.devices.value.isEmpty())
        assertFalse(controller.mixer.isPad(gone))
    }

    @Test
    fun aStickFromADeviceAndroidNoLongerKnowsAddsNoController() {
        val controller = Controller()
        val move = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0.5f, 0.5f, 0).apply {
            source = InputDevice.SOURCE_JOYSTICK
        }
        // Robolectric's events come from device 0, which no device has either.
        assertNull(InputDevice.getDevice(move.deviceId))

        assertFalse(controller.onMotion(move))
        assertTrue(controller.devices.value.isEmpty())
    }
}
