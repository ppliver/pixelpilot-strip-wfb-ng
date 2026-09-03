package com.openipc.pixelpilot.osd;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.util.AttributeSet;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

public class MovableLayout extends LinearLayout {
    private float dX, dY;
    private SharedPreferences preferences;
    private boolean isMovable = false;
    private String prefName;
    // Default spot as a fraction of the parent (centre-top cluster, like upstream).
    private float defaultFractionX = 0.5f;
    private float defaultFractionY = 0.22f;
    private boolean placed = false;

    public MovableLayout(Context context) {
        super(context);
        init(context);
    }

    public MovableLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MovableLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        preferences = context.getSharedPreferences("movable_layout_prefs", Context.MODE_PRIVATE);
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        Point displaySize = new Point();
        display.getRealSize(displaySize);
        // Keep as fractions so the default survives orientation / density changes.
        defaultFractionX = 0.5f;
        defaultFractionY = (displaySize.y / 2f - displaySize.y / 4f) / displaySize.y;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Intercept touch events and pass them to onTouchEvent
        return isMovable;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isMovable) {
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dX = this.getX() - event.getRawX();
                dY = this.getY() - event.getRawY();
                break;
            case MotionEvent.ACTION_MOVE:
                // Clamp inside the parent so the element can never be dragged off-screen.
                setX(clampX(event.getRawX() + dX));
                setY(clampY(event.getRawY() + dY));
                break;
            case MotionEvent.ACTION_UP:
                savePosition();
                break;
            default:
                return false;
        }
        return true;
    }

    private ViewGroup parent() {
        return (getParent() instanceof ViewGroup) ? (ViewGroup) getParent() : null;
    }

    private float clampX(float x) {
        ViewGroup p = parent();
        if (p == null || p.getWidth() <= 0) return x;
        float max = p.getWidth() - getWidth();
        if (max < 0) max = 0;
        return Math.max(0, Math.min(x, max));
    }

    private float clampY(float y) {
        ViewGroup p = parent();
        if (p == null || p.getHeight() <= 0) return y;
        float max = p.getHeight() - getHeight();
        if (max < 0) max = 0;
        return Math.max(0, Math.min(y, max));
    }

    private void savePosition() {
        SharedPreferences.Editor editor = preferences.edit();
        ViewGroup p = parent();
        if (p != null && p.getWidth() > 0 && p.getHeight() > 0) {
            // Store as fractions of the parent so it stays valid across rotations.
            editor.putFloat(prefName + "_fx", getX() / p.getWidth());
            editor.putFloat(prefName + "_fy", getY() / p.getHeight());
        } else {
            editor.putFloat(prefName + "_x", getX());
            editor.putFloat(prefName + "_y", getY());
        }
        editor.apply();
    }

    public void restorePosition(String prefName_) {
        prefName = prefName_;
        float fx = preferences.getFloat(prefName + "_fx", -1f);
        float fy = preferences.getFloat(prefName + "_fy", -1f);
        ViewGroup p = parent();
        if (fx >= 0 && fy >= 0 && p != null && p.getWidth() > 0 && p.getHeight() > 0) {
            setX(clampX(fx * p.getWidth()));
            setY(clampY(fy * p.getHeight()));
            placed = true;
        } else {
            // No saved fraction yet (first run or pre-layout): leave to onSizeChanged.
            float x = preferences.getFloat(prefName + "_x", 0);
            float y = preferences.getFloat(prefName + "_y", 0);
            setX(clampX(x));
            setY(clampY(y));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ViewGroup p = parent();
        if (p == null || p.getWidth() <= 0 || p.getHeight() <= 0 || prefName == null) return;

        float fx = preferences.getFloat(prefName + "_fx", -1f);
        float fy = preferences.getFloat(prefName + "_fy", -1f);
        if (fx >= 0 && fy >= 0) {
            // Re-apply the saved (orientation-independent) position.
            setX(clampX(fx * p.getWidth()));
            setY(clampY(fy * p.getHeight()));
            placed = true;
        } else if (!placed) {
            // First placement: drop at the default centre-top cluster.
            setX(clampX(defaultFractionX * p.getWidth()));
            setY(clampY(defaultFractionY * p.getHeight()));
            placed = true;
        } else {
            // Keep the current position inside the (possibly resized) bounds.
            setX(clampX(getX()));
            setY(clampY(getY()));
        }
    }

    public void setMovable(boolean movable) {
        isMovable = movable;
    }
}
