package com.openipc.pixelpilot.rc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Dual virtual joystick overlay.
 *
 * Reports normalized stick position (-1..1) for the left and right pads via
 * {@link StickListener#onStick(String, float, float)}. The pad assigned as the
 * throttle side keeps its last vertical position when the finger is lifted
 * (spring-centered sticks return to 0).
 */
public class VirtualJoystickView extends View {

    public static final int LEFT = 0;
    public static final int RIGHT = 1;

    public interface StickListener {
        void onStick(String side, float nx, float ny);
    }

    private StickListener listener;
    private int throttleSide = LEFT; // Mode 2 -> throttle on left stick
    private boolean locked = false;
    private float opacity = 0.5f;

    private float padRadius;
    private float stickRadius;
    private float[] cx = new float[2];
    private float[] cy = new float[2];

    // Current displayed normalized offset per pad (sticky for throttle).
    private final float[] padNx = new float[2];
    private final float[] padNy = new float[2];

    // Active pointer -> pad mapping.
    private final android.util.SparseIntArray pointerPad = new android.util.SparseIntArray();

    private final Paint basePaint = new Paint();
    private final Paint stickPaint = new Paint();
    private final Paint ringPaint = new Paint();

    public VirtualJoystickView(Context context) {
        super(context);
        init();
    }

    public VirtualJoystickView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VirtualJoystickView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        basePaint.setColor(Color.parseColor("#335577"));
        basePaint.setStyle(Paint.Style.FILL);
        stickPaint.setColor(Color.parseColor("#88ccff"));
        stickPaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(Color.parseColor("#aaddff"));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3f);
        setWillNotDraw(false);
    }

    public void setStickListener(StickListener l) {
        this.listener = l;
    }

    public void setThrottleSide(int side) {
        this.throttleSide = (side == RIGHT) ? RIGHT : LEFT;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        if (locked) {
            pointerPad.clear();
        }
    }

    public boolean isLocked() {
        return locked;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0.1f, Math.min(1f, opacity));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        padRadius = Math.min(w, h) * 0.20f;
        stickRadius = padRadius * 0.45f;
        cx[LEFT] = w * 0.24f;
        cy[LEFT] = h * 0.62f;
        cx[RIGHT] = w * 0.76f;
        cy[RIGHT] = h * 0.62f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (locked) return false;

        int action = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pad = (event.getX(pointerIndex) < getWidth() / 2f) ? LEFT : RIGHT;
                pointerPad.put(pointerId, pad);
                updatePad(pad, event.getX(pointerIndex), event.getY(pointerIndex));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pid = event.getPointerId(i);
                    int pad = pointerPad.get(pid, -1);
                    if (pad == -1) continue;
                    updatePad(pad, event.getX(i), event.getY(i));
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int pad = pointerPad.get(pointerId, -1);
                pointerPad.delete(pointerId);
                if (pad != -1 && pad != throttleSide) {
                    // spring-centered stick returns to neutral
                    padNx[pad] = 0f;
                    padNy[pad] = 0f;
                    report(pad);
                }
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void updatePad(int pad, float x, float y) {
        float dx = x - cx[pad];
        float dy = y - cy[pad];
        float dist = (float) Math.hypot(dx, dy);
        if (dist > padRadius) {
            dx = dx / dist * padRadius;
            dy = dy / dist * padRadius;
        }
        padNx[pad] = dx / padRadius;
        padNy[pad] = dy / padRadius;
        report(pad);
    }

    private void report(int pad) {
        if (listener != null) {
            listener.onStick(pad == LEFT ? "left" : "right", padNx[pad], padNy[pad]);
        }
    }

    /** Reset both sticks to neutral (used on disable / link loss). */
    public void resetSticks() {
        padNx[LEFT] = padNx[RIGHT] = 0f;
        padNy[LEFT] = padNy[RIGHT] = 0f;
        pointerPad.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int alpha = (int) (opacity * 255);
        basePaint.setAlpha((int) (alpha * 0.6));
        stickPaint.setAlpha(alpha);
        ringPaint.setAlpha(alpha);

        for (int pad = 0; pad < 2; pad++) {
            canvas.drawCircle(cx[pad], cy[pad], padRadius, basePaint);
            canvas.drawCircle(cx[pad], cy[pad], padRadius, ringPaint);
            float sx = cx[pad] + padNx[pad] * padRadius;
            float sy = cy[pad] + padNy[pad] * padRadius;
            canvas.drawCircle(sx, sy, stickRadius, stickPaint);
        }
    }
}
