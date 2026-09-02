package com.openipc.pixelpilot.rc;

import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Reads a connected Android gamepad / joystick and forwards normalized axes to a
 * listener. Left stick (AXIS_X/Y) and right stick (AXIS_RX/RY) drive the main
 * four channels through the same stick model; left/right triggers (AXIS_BRAKE/
 * AXIS_GAS) and the D-pad (AXIS_HAT_X/Y) are exposed as aux channels 5-8.
 *
 * The host Activity must forward {@link android.app.Activity#onGenericMotionEvent}
 * here.
 */
public class GamepadManager {

    public interface AxesListener {
        /**
         * @param lx  left stick X  (-1..1)
         * @param ly  left stick Y  (-1..1, up = -1)
         * @param rx  right stick X (-1..1)
         * @param ry  right stick Y (-1..1, up = -1)
         * @param ch5 left trigger  (0..1)  -> normalized by listener
         * @param ch6 right trigger (0..1)
         * @param ch7 hat X         (-1..1)
         * @param ch8 hat Y         (-1..1)
         */
        void onAxes(float lx, float ly, float rx, float ry,
                    float ch5, float ch6, float ch7, float ch8);
    }

    private AxesListener listener;
    private long lastSeenMs = 0;

    public void setListener(AxesListener l) {
        this.listener = l;
    }

    public boolean isConnected() {
        return (System.currentTimeMillis() - lastSeenMs) < 2000;
    }

    /** Forward from Activity.onGenericMotionEvent. Returns true if consumed. */
    public boolean onGenericMotionEvent(MotionEvent event) {
        int src = event.getSource();
        boolean isGamepad = (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (!isGamepad || listener == null) {
            return false;
        }

        InputDevice dev = InputDevice.getDevice(event.getDeviceId());
        float lx = applyDeadzone(dev, MotionEvent.AXIS_X, event.getAxisValue(MotionEvent.AXIS_X));
        float ly = applyDeadzone(dev, MotionEvent.AXIS_Y, event.getAxisValue(MotionEvent.AXIS_Y));
        float rx = readAxis(dev, MotionEvent.AXIS_RX, MotionEvent.AXIS_Z, event);
        float ry = readAxis(dev, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ, event);

        float brake = event.getAxisValue(MotionEvent.AXIS_BRAKE);   // 0..1
        float gas = event.getAxisValue(MotionEvent.AXIS_GAS);       // 0..1
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);    // -1..1
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);    // -1..1

        lastSeenMs = System.currentTimeMillis();
        listener.onAxes(lx, ly, rx, ry, brake, gas, hatX, hatY);
        return true;
    }

    private float readAxis(InputDevice dev, int primary, int fallback, MotionEvent event) {
        if (dev != null && dev.getMotionRange(primary) != null) {
            return applyDeadzone(dev, primary, event.getAxisValue(primary));
        }
        return applyDeadzone(dev, fallback, event.getAxisValue(fallback));
    }

    private float applyDeadzone(InputDevice dev, int axis, float value) {
        if (dev != null) {
            InputDevice.MotionRange range = dev.getMotionRange(axis);
            if (range != null) {
                float flat = range.getFlat();
                if (Math.abs(value) <= flat) return 0f;
            }
        }
        return value;
    }
}
