package com.openipc.pixelpilot.rc;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Reads a connected Android gamepad / joystick and forwards normalized axes to a
 * listener.
 *
 * <p>Unlike a fixed mapping, every input slot is bound to an axis through a
 * persisted binding table, so the user can calibrate the controller once and
 * have it survive restarts. The calibration UI calls {@link #startLearning(int)}
 * and then waits for the user to move the axis they want to assign.</p>
 *
 * <p>All axes are normalized so that <b>rest == 0</b>:
 * bipolar axes (sticks, hats) map [min..max] to [-1..1] around their midpoint,
 * while unipolar axes (triggers, 0..1) map [min..max] to [0..1] so an untouched
 * trigger reports 0 and is therefore treated as "ignore" downstream.</p>
 *
 * The host Activity must forward {@link android.app.Activity#onGenericMotionEvent}
 * here.
 */
public class GamepadManager {

    public static final int SLOT_LX = 0;
    public static final int SLOT_LY = 1;
    public static final int SLOT_RX = 2;
    public static final int SLOT_RY = 3;
    public static final int SLOT_AUX1 = 4;
    public static final int SLOT_AUX2 = 5;
    public static final int SLOT_AUX3 = 6;
    public static final int SLOT_AUX4 = 7;
    public static final int SLOTS = 8;

    private static final String PREFS = "rc_gamepad";
    private static final String KEY_MAIN = "main_axis";
    private static final String KEY_AUX = "aux_axis";

    /** Axes offered to the calibration UI, in a sensible display order. */
    public static final int[] CANDIDATE_AXES = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_THROTTLE, MotionEvent.AXIS_RUDDER,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_GENERIC_1, MotionEvent.AXIS_GENERIC_2,
            MotionEvent.AXIS_GENERIC_3, MotionEvent.AXIS_GENERIC_4,
    };

    private static final int[] DEFAULT_MAIN = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
    };
    private static final int[] DEFAULT_AUX = {
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
    };

    public interface AxesListener {
        /**
         * @param lx  left stick X  (-1..1)
         * @param ly  left stick Y  (-1..1)
         * @param rx  right stick X (-1..1)
         * @param ry  right stick Y (-1..1)
         * @param ch5 aux 1 (0..1 for triggers, -1..1 for bipolar axes)
         * @param ch6 aux 2
         * @param ch7 aux 3
         * @param ch8 aux 4
         */
        void onAxes(float lx, float ly, float rx, float ry,
                    float ch5, float ch6, float ch7, float ch8);
    }

    public interface CalibListener {
        /** Live normalized values, parallel to {@link #CANDIDATE_AXES}. */
        void onLiveValues(float[] values);

        /** The user moved an axis while learning this slot. */
        void onBindingLearned(int slot, int axis);
    }

    private final int[] bindings = new int[SLOTS];
    private final float[] lastValues = new float[CANDIDATE_AXES.length];
    private final SharedPreferences prefs;

    private AxesListener listener;
    private CalibListener calibListener;
    private long lastSeenMs = 0;

    private boolean learning = false;
    private int learnSlot = -1;
    private final float[] learnBaseline = new float[CANDIDATE_AXES.length];

    public GamepadManager(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        resetToDefaults();
        loadBindings();
    }

    public void setListener(AxesListener l) {
        this.listener = l;
    }

    public void setCalibListener(CalibListener l) {
        this.calibListener = l;
    }

    public boolean isConnected() {
        return (System.currentTimeMillis() - lastSeenMs) < 2000;
    }

    // ---- binding table ------------------------------------------------------

    public int getBinding(int slot) {
        return bindings[slot];
    }

    public void setBinding(int slot, int axis) {
        bindings[slot] = axis;
        saveBindings();
    }

    public void resetToDefaults() {
        System.arraycopy(DEFAULT_MAIN, 0, bindings, SLOT_LX, 4);
        System.arraycopy(DEFAULT_AUX, 0, bindings, SLOT_AUX1, 4);
    }

    public void resetToDefaultsAndSave() {
        resetToDefaults();
        saveBindings();
    }

    public static String axisName(int axis) {
        return MotionEvent.axisToString(axis);
    }

    // ---- learning -----------------------------------------------------------

    /** Arm learning for a slot; the next clearly-moved axis becomes its binding. */
    public void startLearning(int slot) {
        learnSlot = slot;
        System.arraycopy(lastValues, 0, learnBaseline, 0, lastValues.length);
        learning = true;
    }

    public void cancelLearning() {
        learning = false;
        learnSlot = -1;
    }

    public boolean isLearning() {
        return learning;
    }

    // ---- input --------------------------------------------------------------

    /** Forward from Activity.onGenericMotionEvent. Returns true if consumed. */
    public boolean onGenericMotionEvent(MotionEvent event) {
        int src = event.getSource();
        boolean isGamepad = (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (!isGamepad) {
            return false;
        }

        InputDevice dev = InputDevice.getDevice(event.getDeviceId());
        lastSeenMs = System.currentTimeMillis();

        for (int i = 0; i < CANDIDATE_AXES.length; i++) {
            lastValues[i] = readNormalized(dev, event, CANDIDATE_AXES[i]);
        }

        if (learning) {
            handleLearning();
        }
        if (calibListener != null) {
            calibListener.onLiveValues(lastValues.clone());
        }

        if (listener == null) {
            return false;
        }

        float lx = readBound(dev, event, SLOT_LX);
        float ly = readBound(dev, event, SLOT_LY);
        float rx = readBound(dev, event, SLOT_RX);
        float ry = readBound(dev, event, SLOT_RY);
        float a1 = readBound(dev, event, SLOT_AUX1);
        float a2 = readBound(dev, event, SLOT_AUX2);
        float a3 = readBound(dev, event, SLOT_AUX3);
        float a4 = readBound(dev, event, SLOT_AUX4);
        listener.onAxes(lx, ly, rx, ry, a1, a2, a3, a4);
        return true;
    }

    private void handleLearning() {
        int best = -1;
        float bestDelta = 0f;
        for (int i = 0; i < CANDIDATE_AXES.length; i++) {
            float d = Math.abs(lastValues[i] - learnBaseline[i]);
            if (d > bestDelta) {
                bestDelta = d;
                best = CANDIDATE_AXES[i];
            }
        }
        // Require a decisive movement so noise never steals a binding.
        if (best >= 0 && bestDelta > 0.25f) {
            setBinding(learnSlot, best);
            learning = false;
            int slot = learnSlot;
            if (calibListener != null) {
                calibListener.onBindingLearned(slot, best);
            }
        }
    }

    private float readBound(InputDevice dev, MotionEvent event, int slot) {
        int axis = bindings[slot];
        return applyDeadzone(dev, axis, readNormalized(dev, event, axis));
    }

    /** Normalize a raw axis value so that the resting position reads 0. */
    private float readNormalized(InputDevice dev, MotionEvent event, int axis) {
        float raw = event.getAxisValue(axis);
        if (dev != null) {
            InputDevice.MotionRange r = dev.getMotionRange(axis);
            if (r != null) {
                float min = r.getMin();
                float max = r.getMax();
                if (max > min) {
                    if (min < 0f) {
                        // bipolar (sticks / hats): mid-point is rest
                        float mid = (min + max) / 2f;
                        float half = (max - min) / 2f;
                        return clamp((raw - mid) / half);
                    }
                    // unipolar (triggers): min is rest
                    return clamp((raw - min) / (max - min));
                }
            }
        }
        return clamp(raw);
    }

    private float applyDeadzone(InputDevice dev, int axis, float value) {
        if (dev != null) {
            InputDevice.MotionRange range = dev.getMotionRange(axis);
            if (range != null) {
                float flat = Math.max(range.getFlat(), 0f);
                if (Math.abs(value) <= flat) return 0f;
            }
        }
        return value;
    }

    private static float clamp(float v) {
        if (v > 1f) return 1f;
        if (v < -1f) return -1f;
        return v;
    }

    // ---- persistence ---------------------------------------------------------

    private void loadBindings() {
        int[] main = parseAxisList(prefs.getString(KEY_MAIN, ""));
        if (main != null && main.length == 4) {
            System.arraycopy(main, 0, bindings, SLOT_LX, 4);
        }
        int[] aux = parseAxisList(prefs.getString(KEY_AUX, ""));
        if (aux != null && aux.length == 4) {
            System.arraycopy(aux, 0, bindings, SLOT_AUX1, 4);
        }
    }

    private void saveBindings() {
        prefs.edit()
                .putString(KEY_MAIN, joinAxis(bindings, SLOT_LX, 4))
                .putString(KEY_AUX, joinAxis(bindings, SLOT_AUX1, 4))
                .apply();
    }

    private static int[] parseAxisList(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(",");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static String joinAxis(int[] src, int offset, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append(src[offset + i]);
        }
        return sb.toString();
    }
}
