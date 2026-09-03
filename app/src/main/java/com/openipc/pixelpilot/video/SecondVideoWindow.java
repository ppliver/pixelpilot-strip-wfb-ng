package com.openipc.pixelpilot.video;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
    // Fallback defaults (used only before the host view has been measured, e.g.
    // when first launched in a tiny test harness). Real defaults are computed
    // dynamically from the host size via defaultW/defaultH/defaultX/defaultY.
    private static final int FALLBACK_W_DP = 220;
    private static final int FALLBACK_H_DP = 124;
    private static final int FALLBACK_X_DP = 16;
    private static final int FALLBACK_Y_DP = 72; // below the 48dp settings gear + margin
    private static final int MIN_W_DP = 160;
    private static final int MIN_H_DP = 90;
    // Fraction of the host width used for the default PiP width. Keeps the
    // window visible but leaves room for the OSD and the virtual joysticks.
    private static final float DEFAULT_WIDTH_FRACTION = 0.28f;

    /**
     * Notified whenever the window appears, disappears, moves or is resized, so the
     * activity can keep the RC overlay's touch passthrough region in sync.
     */
    public interface GeometryListener {
        void onGeometryChanged();
    }

    private final WeakReference<AppCompatActivity> activityRef;
    private final FrameLayout host;

    private VideoPlayer player;
    private View container;
    private TextView statusText;
    private boolean active = false;
    private GeometryListener geometryListener;

    public SecondVideoWindow(@NonNull AppCompatActivity activity, @NonNull FrameLayout host) {
        this.activityRef = new WeakReference<>(activity);
        this.host = host;
    }

    private SharedPreferences prefs() {
        AppCompatActivity a = activityRef.get();
        return a.getSharedPreferences(PREF, AppCompatActivity.MODE_PRIVATE);
    }

    private int dp(int v) {
        AppCompatActivity a = activityRef.get();
        if (a == null) return v;
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private int defaultW() {
        int hw = host.getWidth();
        if (hw <= 0) return dp(FALLBACK_W_DP);
        int desired = (int) (hw * DEFAULT_WIDTH_FRACTION);
        return clamp(desired, dp(MIN_W_DP), Math.max(dp(MIN_W_DP), hw / 2));
    }

    private int defaultH() {
        // 16:9 PiP keeps the stream recognisable even before the first frame
        // arrives and matches typical FPV camera output.
        return defaultW() * 9 / 16;
    }

    private int defaultX() {
        int hw = host.getWidth();
        return hw > 0 ? dp(FALLBACK_X_DP) : dp(FALLBACK_X_DP);
    }

    private int defaultY() {
        // Sits just under the 48dp top-left settings gear.
        return dp(FALLBACK_Y_DP);
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

    public void setGeometryListener(@Nullable GeometryListener l) {
        this.geometryListener = l;
    }

    /** Notify listeners that the window moved / resized / appeared / disappeared. */
    private void notifyGeometryChanged() {
        if (geometryListener != null) {
            geometryListener.onGeometryChanged();
        }
    }

    /**
     * Current window rectangle in screen coordinates, or null when the window is
     * not shown. Used to carve a touch passthrough hole in the RC overlay so the
     * window stays draggable even though the overlay sits above it.
     */
    @Nullable
    public Rect getWindowRect() {
        if (!active || container == null || container.getWidth() == 0) return null;
        int[] loc = new int[2];
        container.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1],
                loc[0] + container.getWidth(), loc[1] + container.getHeight());
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

        // Create native player and inflate the window under one try block so any
        // failure (native init or layout inflation) is caught and degrades to a no-op
        // instead of crashing the app. TextureView famously throws if given a
        // background drawable, so we removed that from the XML but stay defensive.
        try {
            player = new VideoPlayer(a);
            container = LayoutInflater.from(a).inflate(R.layout.second_video_window, host, false);
        } catch (Throwable t) {
            Log.e(TAG, "SecondVideo: failed to create player / inflate window", t);
            player = null;
            container = null;
            active = false;
            return;
        }
        statusText = container.findViewById(R.id.tvSecondStatus);
        ImageButton close = container.findViewById(R.id.btnClose);
        View dragHandle = container.findViewById(R.id.dragHandle);
        View resizeHandle = container.findViewById(R.id.resizeHandle);
        android.view.TextureView texture = container.findViewById(R.id.secondVideoTexture);

        // Wire the texture surface to this player's decoder (index 0). The calls
        // into the native decoder are wrapped so a low-level failure cannot crash
        // the UI thread.
        texture.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                if (player == null) return;
                try {
                    player.addAndStartDecoderReceiver(new android.view.Surface(st), 0);
                } catch (Throwable t) {
                    Log.e(TAG, "SecondVideo: setSurface failed", t);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                if (player == null) return true;
                try {
                    player.stopAndRemoveReceiverDecoder(0);
                } catch (Throwable t) {
                    Log.e(TAG, "SecondVideo: clearSurface failed", t);
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {
            }
        });

        setupDrag(dragHandle);
        setupResize(resizeHandle);
        close.setOnClickListener(v -> disable());

        host.addView(container);
        host.setVisibility(View.VISIBLE);

        // Restore geometry once the host has been laid out (it has real size).
        host.post(() -> {
            restoreGeometry();
            notifyGeometryChanged();
        });

        try {
            player.startExternalReceiver(port, ip == null ? "" : ip.trim());
        } catch (Throwable t) {
            Log.e(TAG, "SecondVideo: failed to start external receiver", t);
            updateStatus(a.getString(R.string.second_video_error));
        }
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
        notifyGeometryChanged();
    }

    /** Persist IP/port only (without touching the enabled flag or the running receiver). */
    public void saveConfigOnly(String ip, int port) {
        prefs().edit()
                .putString(KEY_IP, ip == null ? "" : ip.trim())
                .putInt(KEY_PORT, port)
                .apply();
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
            lp.width = defaultW();
            lp.height = defaultH();
            lp.leftMargin = defaultX();
            lp.topMargin = defaultY();
            container.setLayoutParams(lp);
        }
        saveGeometry();
        notifyGeometryChanged();
    }

    // ----------------------------------------------------------------------
    // Geometry persistence
    // ----------------------------------------------------------------------

    private void restoreGeometry() {
        if (container == null || host.getWidth() == 0) return;
        SharedPreferences p = prefs();
        int w = p.getInt(KEY_W, defaultW());
        int h = p.getInt(KEY_H, defaultH());
        int x = p.getInt(KEY_X, defaultX());
        int y = p.getInt(KEY_Y, defaultY());
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
                        notifyGeometryChanged();
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
                        lp.width = clamp(origW + dx, dp(MIN_W_DP), host.getWidth());
                        lp.height = clamp(origH + dy, dp(MIN_H_DP), host.getHeight());
                        container.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        saveGeometry();
                        notifyGeometryChanged();
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
