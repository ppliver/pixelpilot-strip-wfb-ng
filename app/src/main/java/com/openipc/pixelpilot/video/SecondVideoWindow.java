package com.openipc.pixelpilot.video;

import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.openipc.pixelpilot.R;
import com.openipc.videonative.VideoPlayer;

import java.lang.ref.WeakReference;

/**
 * Manages a second, independent UDP video stream rendered inside a floating,
 * draggable and resizable window that overlays the main app.
 *
 * <p>Each instance owns its own {@link VideoPlayer} (and therefore its own native
 * parser/decoder), listening on a configurable UDP port. Only decoder index 0 is
 * used, which the native side already handles gracefully.</p>
 *
 * <p>The window position and size are persisted so they survive app restarts.</p>
 */
public class SecondVideoWindow {
    private static final String TAG = "pixelpilot";
    private static final String PREF = "second_video";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_IP = "ip";
    private static final String KEY_PORT = "port";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_W = "w";
    private static final String KEY_H = "h";

    private static final int DEFAULT_PORT = 5601;
    private static final int DEFAULT_W = 320;
    private static final int DEFAULT_H = 240;
    private static final int DEFAULT_X = 40;
    private static final int DEFAULT_Y = 40;
    private static final int MIN_W = 120;
    private static final int MIN_H = 90;

    private final WeakReference<AppCompatActivity> activityRef;
    private final FrameLayout host;

    private VideoPlayer player;
    private View container;
    private TextView statusText;
    private boolean active = false;

    public SecondVideoWindow(@NonNull AppCompatActivity activity, @NonNull FrameLayout host) {
        this.activityRef = new WeakReference<>(activity);
        this.host = host;
    }

    private SharedPreferences prefs() {
        AppCompatActivity a = activityRef.get();
        return a.getSharedPreferences(PREF, AppCompatActivity.MODE_PRIVATE);
    }

    /** Restore the enabled stream if the user left it on. Call from onCreate. */
    public void init() {
        SharedPreferences p = prefs();
        if (p.getBoolean(KEY_ENABLED, false)) {
            enable(p.getString(KEY_IP, ""), p.getInt(KEY_PORT, DEFAULT_PORT));
        }
    }

    /** Tear everything down. Call from onDestroy. */
    public void release() {
        if (active) {
            disable();
        }
    }

    /**
     * Stop the receiver and hide the window while keeping the stream "active"
     * (configuration preserved). Mirror of the main video player, which stops on
     * pause. Call from onPause.
     */
    public void pause() {
        if (!active || player == null) return;
        player.stopExternalReceiver();
        host.setVisibility(View.GONE);
    }

    /**
     * Resume a previously paused stream. Call from onResume. Re-attaches the
     * receiver; the SurfaceTexture re-attaches automatically when the host is shown.
     */
    public void resume() {
        if (!active || player == null) return;
        SharedPreferences p = prefs();
        int port = p.getInt(KEY_PORT, DEFAULT_PORT);
        String ip = p.getString(KEY_IP, "");
        host.setVisibility(View.VISIBLE);
        restoreGeometry();
        player.startExternalReceiver(port, ip == null ? "" : ip.trim());
        AppCompatActivity a = activityRef.get();
        if (a != null) {
            updateStatus(a.getString(R.string.second_video_running) + " :" + port);
        }
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Enable the second video window. If already active, just re-apply the
     * (possibly new) configuration.
     */
    public void enable(String ip, int port) {
        if (active) {
            applyConfig(ip, port);
            return;
        }
        AppCompatActivity a = activityRef.get();
        if (a == null) return;

        player = new VideoPlayer(a);

        container = LayoutInflater.from(a).inflate(R.layout.second_video_window, host, false);
        statusText = container.findViewById(R.id.tvSecondStatus);
        ImageButton close = container.findViewById(R.id.btnClose);
        View dragHandle = container.findViewById(R.id.dragHandle);
        View resizeHandle = container.findViewById(R.id.resizeHandle);
        android.view.TextureView texture = container.findViewById(R.id.secondVideoTexture);

        // Wire the texture surface to this player's decoder (index 0).
        texture.setSurfaceTextureListener(player.configureTextureView(0));

        setupDrag(dragHandle);
        setupResize(resizeHandle);
        close.setOnClickListener(v -> disable());

        host.addView(container);
        host.setVisibility(View.VISIBLE);

        // Restore geometry once the host has been laid out (it has real size).
        host.post(this::restoreGeometry);

        player.startExternalReceiver(port, ip == null ? "" : ip.trim());
        active = true;
        saveConfig(ip, port, true);
        updateStatus(a.getString(R.string.second_video_running) + " :" + port);
    }

    /** Stop the second stream and hide the window. */
    public void disable() {
        if (!active) return;
        AppCompatActivity a = activityRef.get();

        if (player != null) {
            player.stopExternalReceiver();
        }
        if (container != null && host != null) {
            host.removeView(container);
            // SurfaceTexture destroyed -> native releases decoder window[0].
        }
        host.setVisibility(View.GONE);
        container = null;
        statusText = null;
        player = null; // GC will nativeFinalize (stops external receiver, deletes).
        active = false;

        SharedPreferences p = prefs();
        p.edit().putBoolean(KEY_ENABLED, false).apply();
    }

    /** Switch the port / source filter of a running stream. */
    public void applyConfig(String ip, int port) {
        SharedPreferences p = prefs();
        p.edit().putString(KEY_IP, ip == null ? "" : ip.trim())
                .putInt(KEY_PORT, port)
                .putBoolean(KEY_ENABLED, true)
                .apply();
        if (player != null) {
            player.stopExternalReceiver();
            player.startExternalReceiver(port, ip == null ? "" : ip.trim());
        }
        AppCompatActivity a = activityRef.get();
        if (a != null) {
            updateStatus(a.getString(R.string.second_video_running) + " :" + port);
        }
    }

    /** Forget the saved geometry and move back to the default position/size. */
    public void resetPosition() {
        prefs().edit().remove(KEY_X).remove(KEY_Y).remove(KEY_W).remove(KEY_H).apply();
        if (container != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
            lp.width = DEFAULT_W;
            lp.height = DEFAULT_H;
            lp.leftMargin = DEFAULT_X;
            lp.topMargin = DEFAULT_Y;
            container.setLayoutParams(lp);
        }
    }

    // ----------------------------------------------------------------------
    // Geometry persistence
    // ----------------------------------------------------------------------

    private void restoreGeometry() {
        if (container == null || host.getWidth() == 0) return;
        SharedPreferences p = prefs();
        int w = p.getInt(KEY_W, DEFAULT_W);
        int h = p.getInt(KEY_H, DEFAULT_H);
        int x = p.getInt(KEY_X, DEFAULT_X);
        int y = p.getInt(KEY_Y, DEFAULT_Y);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.leftMargin = clamp(x, 0, Math.max(0, host.getWidth() - w));
        lp.topMargin = clamp(y, 0, Math.max(0, host.getHeight() - h));
        container.setLayoutParams(lp);
    }

    private void saveGeometry() {
        if (container == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        prefs().edit()
                .putInt(KEY_X, lp.leftMargin)
                .putInt(KEY_Y, lp.topMargin)
                .putInt(KEY_W, lp.width)
                .putInt(KEY_H, lp.height)
                .apply();
    }

    private void saveConfig(String ip, int port, boolean enabled) {
        prefs().edit()
                .putString(KEY_IP, ip == null ? "" : ip.trim())
                .putInt(KEY_PORT, port)
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    // ----------------------------------------------------------------------
    // Dragging / resizing
    // ----------------------------------------------------------------------

    private void setupDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private int origLeft, origTop;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (container == null) return false;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startY = e.getRawY();
                        origLeft = lp.leftMargin;
                        origTop = lp.topMargin;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - startX);
                        int dy = (int) (e.getRawY() - startY);
                        int maxX = Math.max(0, host.getWidth() - container.getWidth());
                        int maxY = Math.max(0, host.getHeight() - container.getHeight());
                        lp.leftMargin = clamp(origLeft + dx, 0, maxX);
                        lp.topMargin = clamp(origTop + dy, 0, maxY);
                        container.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        saveGeometry();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void setupResize(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private int origW, origH;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (container == null) return false;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startY = e.getRawY();
                        origW = lp.width;
                        origH = lp.height;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - startX);
                        int dy = (int) (e.getRawY() - startY);
                        lp.width = clamp(origW + dx, MIN_W, host.getWidth());
                        lp.height = clamp(origH + dy, MIN_H, host.getHeight());
                        container.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        saveGeometry();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void updateStatus(String text) {
        if (statusText != null) {
            statusText.setText(text);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
