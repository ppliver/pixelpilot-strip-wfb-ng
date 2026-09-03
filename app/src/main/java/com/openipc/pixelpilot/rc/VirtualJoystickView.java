package com.openipc.pixelpilot.rc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Dual virtual joystick overlay.
 *
 * In normal (locked) mode the pads act as flight controls and report normalized
 * stick position (-1..1) via {@link StickListener#onStick(String,float,float)}.
 *
 * In layout/adjust (unlocked) mode the user can drag each pad to reposition it
 * and use the +/- buttons to resize. The new layout is reported through
 * {@link LayoutChangeListener} so the activity can persist it.
 */
public class VirtualJoystickView extends View {
    private static final String TAG = "VirtualJoystick";

    public static final int LEFT = 0;
    public static final int RIGHT = 1;

    public interface StickListener {
        void onStick(String side, float nx, float ny);
    }

    public interface LayoutChangeListener {
        void onLayoutChanged(int pad, float cxPct, float cyPct, float radiusPct);
    }

    private StickListener listener;
    private LayoutChangeListener layoutListener;
    private int throttleSide = LEFT; // Mode 2 -> throttle on left stick

    // locked=true  -> pads fixed, normal flight input
    // locked=false -> layout adjust mode: drag to move, +/- to resize
    private boolean locked = true;
    private float opacity = 0.5f;

    // When a physical gamepad is connected we disable touch "打杆" (stick input)
    // and instead mirror the gamepad axes onto the sticks as pure visual feedback.
    private boolean gamepadMirror = false;

    // Screen region (in this view's local coordinates) where a fresh touch must
    // pass through to the layers below (the floating second video window) instead
    // of driving the sticks. Null = consume touches everywhere. Only honoured in
    // flight (locked) mode so the pads can still be repositioned when unlocked.
    private Rect passthroughRect = null;

    // Layout: percentages for persistence (0..1)
    private final float[] cxPct = {0.24f, 0.76f};
    private final float[] cyPct = {0.62f, 0.62f};
    private float radiusPct = 0.20f;

    // Cached absolute values computed in onSizeChanged
    private float padRadius;
    private float stickRadius;
    private final float[] cx = new float[2];
    private final float[] cy = new float[2];

    // Current displayed normalized offset per pad.
    private final float[] padNx = new float[2];
    private final float[] padNy = new float[2];

    // Active pointer -> pad mapping.
    private final android.util.SparseIntArray pointerPad = new android.util.SparseIntArray();

    // Per-axis auto-centre flags (driven by the channel config: throttle holds).
    private final boolean[] autoCenterX = {true, true};
    private final boolean[] autoCenterY = {true, true};

    // Layout adjust mode state
    private static final int ADJUST_NONE = -1;
    private static final int ADJUST_MOVE = 0;
    private static final int ADJUST_ENLARGE = 1;
    private static final int ADJUST_SHRINK = 2;
    private final int[] adjustAction = {ADJUST_NONE, ADJUST_NONE}; // per pad
    private final float[] moveStartCx = new float[2];
    private final float[] moveStartCy = new float[2];
    private final float[] moveStartX = new float[2];
    private final float[] moveStartY = new float[2];

    private final Paint basePaint = new Paint();
    private final Paint stickPaint = new Paint();
    private final Paint ringPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint btnPaint = new Paint();
    private final Paint btnTextPaint = new Paint();

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
        textPaint.setColor(Color.parseColor("#aaddff"));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
        btnPaint.setColor(Color.parseColor("#224466"));
        btnPaint.setStyle(Paint.Style.FILL);
        btnPaint.setAntiAlias(true);
        btnTextPaint.setColor(Color.WHITE);
        btnTextPaint.setTextAlign(Paint.Align.CENTER);
        btnTextPaint.setAntiAlias(true);
        setWillNotDraw(false);
    }

    public void setStickListener(StickListener l) {
        this.listener = l;
    }

    public void setLayoutChangeListener(LayoutChangeListener l) {
        this.layoutListener = l;
    }

    public void setThrottleSide(int side) {
        this.throttleSide = (side == RIGHT) ? RIGHT : LEFT;
        // Throttle lives on the vertical axis of this pad and must hold its value.
        autoCenterY[this.throttleSide] = false;
    }

    /**
     * Controls whether each axis springs back to neutral when the finger is lifted.
     * Driven by the per-channel "auto centre" setting (throttle = false).
     */
    public void setAxisAutoCenter(int pad, boolean xAuto, boolean yAuto) {
        if (pad != LEFT && pad != RIGHT) return;
        autoCenterX[pad] = xAuto;
        autoCenterY[pad] = yAuto;
    }

    /**
     * Load a persisted layout. Percentages are relative to view width/height;
     * radius is relative to min(width,height).
     */
    public void setLayout(int pad, float cxPct, float cyPct, float radiusPct) {
        if (pad != LEFT && pad != RIGHT) return;
        this.cxPct[pad] = clamp01(cxPct);
        this.cyPct[pad] = clamp01(cyPct);
        this.radiusPct = clamp(radiusPct, 0.08f, 0.45f);
        recalcLayout();
        invalidate();
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        pointerPad.clear();
        for (int pad = 0; pad < 2; pad++) {
            adjustAction[pad] = ADJUST_NONE;
            boolean changed = false;
            if (padNx[pad] != 0f) {
                padNx[pad] = 0f;
                changed = true;
            }
            if (padNy[pad] != 0f) {
                padNy[pad] = 0f;
                changed = true;
            }
            if (changed) report(pad);
        }
        invalidate();
    }

    public boolean isLocked() {
        return locked;
    }

    /**
     * Enable/disable gamepad mirror mode.
     *
     * <p>When enabled the finger can no longer drive the sticks (打杆 disabled) because a
     * physical gamepad is in control; the stick knobs only reflect the external gamepad
     * axes pushed through {@link #setExternalAxes(int, float, float)} as visual feedback.</p>
     */
    public void setGamepadMirror(boolean on) {
        this.gamepadMirror = on;
        pointerPad.clear();
        for (int pad = 0; pad < 2; pad++) {
            adjustAction[pad] = ADJUST_NONE;
            padNx[pad] = 0f;
            padNy[pad] = 0f;
        }
        invalidate();
    }

    public boolean isGamepadMirror() {
        return gamepadMirror;
    }

    /**
     * Push external (gamepad) axis values for visual feedback only.
     * Does NOT report to the stick listener — the gamepad already drives RC directly —
     * and only applies while in flight (locked) mode.
     */
    public void setExternalAxes(int pad, float nx, float ny) {
        if (!gamepadMirror || !locked) return;
        if (pad != LEFT && pad != RIGHT) return;
        padNx[pad] = clamp(nx, -1f, 1f);
        padNy[pad] = clamp(ny, -1f, 1f);
        invalidate();
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0.1f, Math.min(1f, opacity));
        invalidate();
    }

    /**
     * Mark a screen region (in this view's local coordinates) where touches must fall
     * through to the layers below instead of being consumed by the sticks.
     *
     * <p>The RC overlay sits above the floating second video window, so without this the
     * window would become undraggable. Only flight (locked) mode honours it — in layout
     * (unlocked) mode the stick pads take priority so they can still be repositioned.</p>
     *
     * @param r region to pass through, or null to consume touches everywhere
     */
    public void setPassthroughRect(@Nullable Rect r) {
        this.passthroughRect = (r == null || r.isEmpty()) ? null : new Rect(r);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recalcLayout();
    }

    private void recalcLayout() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        padRadius = Math.min(w, h) * radiusPct;
        stickRadius = padRadius * 0.45f;
        cx[LEFT] = w * cxPct[LEFT];
        cy[LEFT] = h * cyPct[LEFT];
        cx[RIGHT] = w * cxPct[RIGHT];
        cy[RIGHT] = h * cyPct[RIGHT];
        textPaint.setTextSize(padRadius * 0.35f);
        btnTextPaint.setTextSize(padRadius * 0.30f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);

        // Let a fresh touch inside the passthrough region fall through to the layers
        // below (floating second video window). Once a stick is being driven we keep
        // consuming so an in-flight gesture is never stolen mid-way.
        boolean isDown = (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_POINTER_DOWN);
        if (locked && isDown && pointerPad.size() == 0 && passthroughRect != null) {
            if (passthroughRect.contains((int) event.getX(pointerIndex),
                    (int) event.getY(pointerIndex))) {
                return false;
            }
        }

        if (locked) {
            return handleFlightTouch(event, action, pointerIndex, pointerId);
        } else {
            return handleAdjustTouch(event, action, pointerIndex, pointerId);
        }
    }

    private boolean handleFlightTouch(MotionEvent event, int action, int pointerIndex, int pointerId) {
        // Gamepad connected: touch打杆 disabled, sticks are visual-only mirror.
        if (gamepadMirror) {
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pad = (event.getX(pointerIndex) < getWidth() / 2f) ? LEFT : RIGHT;
                pointerPad.put(pointerId, pad);
                Log.d(TAG, "pointer down pid=" + pointerId + " pad=" + (pad == LEFT ? "L" : "R")
                        + " pointers=" + pointerPad.size());
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
            case MotionEvent.ACTION_POINTER_UP: {
                releasePointer(pointerId, true);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE: {
                // When the gesture ends or is canceled we must release every pointer,
                // otherwise a stale mapping can keep a pad stuck off-centre.
                Log.d(TAG, "release all action=" + action + " pid=" + pointerId
                        + " pointers=" + pointerPad.size());
                releaseAllPads();
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }

    /** Release a single pointer. Only auto-centre its pad if no other active pointer is on it. */
    private void releasePointer(int pointerId, boolean autoCenterIfUnused) {
        int pad = pointerPad.get(pointerId, -1);
        pointerPad.delete(pointerId);
        if (pad == -1) return;
        boolean stillUsed = false;
        for (int i = 0; i < pointerPad.size(); i++) {
            if (pointerPad.valueAt(i) == pad) {
                stillUsed = true;
                break;
            }
        }
        Log.d(TAG, "pointer up pid=" + pointerId + " pad=" + (pad == LEFT ? "L" : "R")
                + " stillUsed=" + stillUsed + " pointers=" + pointerPad.size());
        if (autoCenterIfUnused && !stillUsed) {
            autoCenterPad(pad);
        }
    }

    /** Auto-centre all axes that are configured to spring back. */
    private void autoCenterPad(int pad) {
        boolean changed = false;
        if (autoCenterX[pad] && padNx[pad] != 0f) {
            padNx[pad] = 0f;
            changed = true;
        }
        if (autoCenterY[pad] && padNy[pad] != 0f) {
            padNy[pad] = 0f;
            changed = true;
        }
        if (changed) {
            report(pad);
            Log.d(TAG, "auto-centre pad=" + (pad == LEFT ? "L" : "R"));
        }
    }

    /** Release every tracked pointer and auto-centre all pads. */
    private void releaseAllPads() {
        pointerPad.clear();
        autoCenterPad(LEFT);
        autoCenterPad(RIGHT);
    }

    private boolean handleAdjustTouch(MotionEvent event, int action, int pointerIndex, int pointerId) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = event.getX(pointerIndex);
                float y = event.getY(pointerIndex);
                int pad = nearestPad(x, y);
                pointerPad.put(pointerId, pad);

                // Check for resize button hit first (top-right of pad)
                int hitBtn = hitResizeButton(pad, x, y);
                if (hitBtn != ADJUST_NONE) {
                    adjustAction[pad] = hitBtn;
                    applyResizeStep(pad, hitBtn);
                    break;
                }

                adjustAction[pad] = ADJUST_MOVE;
                moveStartX[pad] = x;
                moveStartY[pad] = y;
                moveStartCx[pad] = cx[pad];
                moveStartCy[pad] = cy[pad];
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pid = event.getPointerId(i);
                    int pad = pointerPad.get(pid, -1);
                    if (pad == -1) continue;
                    if (adjustAction[pad] != ADJUST_MOVE) continue;
                    float dx = event.getX(i) - moveStartX[pad];
                    float dy = event.getY(i) - moveStartY[pad];
                    cx[pad] = clamp(moveStartCx[pad] + dx, padRadius, getWidth() - padRadius);
                    cy[pad] = clamp(moveStartCy[pad] + dy, padRadius + buttonRadius() * 2f, getHeight() - padRadius);
                    cxPct[pad] = cx[pad] / Math.max(1f, getWidth());
                    cyPct[pad] = cy[pad] / Math.max(1f, getHeight());
                    if (layoutListener != null) {
                        layoutListener.onLayoutChanged(pad, cxPct[pad], cyPct[pad], radiusPct);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int pad = pointerPad.get(pointerId, -1);
                pointerPad.delete(pointerId);
                if (pad != -1) {
                    adjustAction[pad] = ADJUST_NONE;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE: {
                pointerPad.clear();
                adjustAction[LEFT] = ADJUST_NONE;
                adjustAction[RIGHT] = ADJUST_NONE;
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }

    private int nearestPad(float x, float y) {
        float dLeft = (float) Math.hypot(x - cx[LEFT], y - cy[LEFT]);
        float dRight = (float) Math.hypot(x - cx[RIGHT], y - cy[RIGHT]);
        return (dLeft <= dRight) ? LEFT : RIGHT;
    }

    private float buttonRadius() {
        return Math.max(18f, padRadius * 0.18f);
    }

    private int hitResizeButton(int pad, float x, float y) {
        float r = buttonRadius();
        float[] centers = resizeButtonCenters(pad, r);
        float dEnlarge = (float) Math.hypot(x - centers[0], y - centers[1]);
        float dShrink   = (float) Math.hypot(x - centers[2], y - centers[3]);
        if (dEnlarge <= r * 1.3f) return ADJUST_ENLARGE;
        if (dShrink   <= r * 1.3f) return ADJUST_SHRINK;
        return ADJUST_NONE;
    }

    private float[] resizeButtonCenters(int pad, float r) {
        float angle = 45f * (float) Math.PI / 180f;
        float offset = padRadius + r * 1.4f;
        float cxEn = cx[pad] + (float) Math.cos(angle) * offset;
        float cyEn = cy[pad] - (float) Math.sin(angle) * offset;
        float cxSh = cxEn + r * 2.4f;
        float cySh = cyEn;
        return new float[]{cxEn, cyEn, cxSh, cySh};
    }

    private void applyResizeStep(int pad, int action) {
        float step = 0.01f;
        if (action == ADJUST_ENLARGE) {
            radiusPct = clamp(radiusPct + step, 0.08f, 0.45f);
        } else if (action == ADJUST_SHRINK) {
            radiusPct = clamp(radiusPct - step, 0.08f, 0.45f);
        }
        recalcLayout();
        // Keep pad within screen after resize
        cx[pad] = clamp(cx[pad], padRadius, getWidth() - padRadius);
        cy[pad] = clamp(cy[pad], padRadius + buttonRadius() * 2f, getHeight() - padRadius);
        cxPct[pad] = cx[pad] / Math.max(1f, getWidth());
        cyPct[pad] = cy[pad] / Math.max(1f, getHeight());
        if (layoutListener != null) {
            layoutListener.onLayoutChanged(pad, cxPct[pad], cyPct[pad], radiusPct);
        }
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
        textPaint.setAlpha(alpha);

        for (int pad = 0; pad < 2; pad++) {
            canvas.drawCircle(cx[pad], cy[pad], padRadius, basePaint);
            canvas.drawCircle(cx[pad], cy[pad], padRadius, ringPaint);

            // Crosshair
            canvas.drawLine(cx[pad] - padRadius, cy[pad], cx[pad] + padRadius, cy[pad], ringPaint);
            canvas.drawLine(cx[pad], cy[pad] - padRadius, cx[pad], cy[pad] + padRadius, ringPaint);

            float sx = cx[pad] + padNx[pad] * padRadius;
            float sy = cy[pad] + padNy[pad] * padRadius;
            canvas.drawCircle(sx, sy, stickRadius, stickPaint);

            if (!locked) {
                // Adjust mode hint
                String hint = "拖动调整";
                canvas.drawText(hint, cx[pad], cy[pad] + padRadius * 0.55f, textPaint);

                // Resize buttons
                float r = buttonRadius();
                float[] btns = resizeButtonCenters(pad, r);
                drawButton(canvas, btns[0], btns[1], r, "＋", alpha);
                drawButton(canvas, btns[2], btns[3], r, "－", alpha);
            }
        }
    }

    private void drawButton(Canvas canvas, float x, float y, float r, String label, int alpha) {
        btnPaint.setAlpha(alpha);
        btnTextPaint.setAlpha(alpha);
        canvas.drawCircle(x, y, r, btnPaint);
        Paint.FontMetrics fm = btnTextPaint.getFontMetrics();
        float textY = y + (fm.descent - fm.ascent) / 2f - fm.descent;
        canvas.drawText(label, x, textY, btnTextPaint);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
