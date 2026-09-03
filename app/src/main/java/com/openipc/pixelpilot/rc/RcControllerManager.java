package com.openipc.pixelpilot.rc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.openipc.mavlink.MavlinkNative;

import java.util.Arrays;

/**
 * Owns the RC control model and drives RC_CHANNELS_OVERRIDE emission.
 *
 * <ul>
 *   <li>Sticks (virtual joystick or gamepad) map to channels 1-4 via a Mode 1/2 table.</li>
 *   <li>Gamepad triggers / D-pad extend control to channels 5-8.</li>
 *   <li>Channels 9-18 are sent as "ignore" (not driven in this build).</li>
 *   <li>When enabled, a periodic loop sends the 18-channel override on the same
 *       UDP socket the MAVLink listener uses, to the learned peer (or a manual target).</li>
 * </ul>
 */
public class RcControllerManager implements VirtualJoystickView.StickListener,
        GamepadManager.AxesListener {

    public static final int MAX_CHANNELS = 18;
    private static final float DEADZONE = 0.04f;
    private static final String PREFS = "rc_control";
    private static final String TAG = "RcController";

    // stickToChannel index: 0=LX, 1=LY, 2=RX, 3=RY
    private static final int[] MODE2 = {4, 3, 1, 2}; // L: yaw(X)+throttle(Y)  R: roll(X)+pitch(Y)
    private static final int[] MODE1 = {4, 2, 1, 3}; // L: yaw(X)+pitch(Y)    R: roll(X)+throttle(Y)

    public interface DisplayListener {
        void onChannels(int[] pwm, boolean sentOk);
    }

    public static class ChannelCfg {
        public int min = 1000;
        public int max = 2000;
        public int center = 1500;
        public int trim = 0;
        public boolean inverted = false;
        public boolean enabled = true;
        /** When true the driving input springs back to centre on release;
         *  when false it holds its last value (e.g. throttle). */
        public boolean autoCenter = true;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ChannelCfg[] channels = new ChannelCfg[MAX_CHANNELS];
    private final Handler sendHandler = new Handler(Looper.getMainLooper());

    private int mode = 2;
    private int[] stickToChannel = MODE2.clone();

    private boolean enabled = false;
    private boolean showSticks = false;
    private int rateHz = 20;
    /** How many channels (starting at CH1) are actively driven/overridden. */
    private int channelCount = 8;
    private boolean manualTarget = false;
    private String manualIp = "192.168.1.10";
    private int manualPort = 14550;

    // axis sources (normalized -1..1). Index: 0=LX,1=LY,2=RX,3=RY
    private final float[] joyAxes = new float[4];
    private final float[] gpadAxes = new float[4];
    private final float[] gpadAux = new float[4]; // ch5..ch8 (brake,gas,hatX,hatY)
    private long lastGamepadMs = 0;

    private int[] lastPwm = new int[MAX_CHANNELS];
    private DisplayListener displayListener;

    private final Runnable sendRunnable = new Runnable() {
        @Override
        public void run() {
            if (!enabled) return;
            int[] pwm = computePwm();
            boolean ok = MavlinkNative.nativeSendRcChannels(pwm);
            lastPwm = pwm;
            if (displayListener != null) displayListener.onChannels(pwm, ok);
            sendHandler.postDelayed(this, 1000 / rateHz);
        }
    };

    public RcControllerManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (int i = 0; i < MAX_CHANNELS; i++) channels[i] = new ChannelCfg();
        // Throttle (CH3) defaults to inverted so "stick up = more throttle" on a touch TX,
        // and it must NOT spring back to centre (it holds last value).
        channels[2].inverted = true;
        channels[2].autoCenter = false;
        loadConfig();
    }

    public void setDisplayListener(DisplayListener l) {
        this.displayListener = l;
    }

    // ---- lifecycle ----------------------------------------------------------

    public void start() {
        if (enabled) {
            applyTarget();
            sendHandler.removeCallbacks(sendRunnable);
            sendHandler.postDelayed(sendRunnable, 1000 / rateHz);
        }
    }

    public void stop() {
        sendHandler.removeCallbacks(sendRunnable);
    }

    // ---- configuration -------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        prefs.edit().putBoolean("enabled", enabled).apply();
        if (enabled) {
            applyTarget();
            sendHandler.removeCallbacks(sendRunnable);
            sendHandler.postDelayed(sendRunnable, 1000 / rateHz);
        } else {
            sendHandler.removeCallbacks(sendRunnable);
        }
    }

    public boolean isShowSticks() {
        return showSticks;
    }

    public void setShowSticks(boolean show) {
        this.showSticks = show;
        prefs.edit().putBoolean("show_sticks", show).apply();
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        this.mode = (mode == 1) ? 1 : 2;
        stickToChannel = (this.mode == 1) ? MODE1.clone() : MODE2.clone();
        prefs.edit().putInt("mode", this.mode).apply();
    }

    public int getThrottleSide() {
        return (mode == 2) ? VirtualJoystickView.LEFT : VirtualJoystickView.RIGHT;
    }

    public int getRateHz() {
        return rateHz;
    }

    public void setRateHz(int hz) {
        this.rateHz = Math.max(5, Math.min(50, hz));
        prefs.edit().putInt("rate_hz", this.rateHz).apply();
    }

    public int getChannelCount() {
        return channelCount;
    }

    public void setChannelCount(int count) {
        this.channelCount = Math.max(4, Math.min(MAX_CHANNELS, count));
        prefs.edit().putInt("channel_count", this.channelCount).apply();
    }

    /**
     * Channel number driven by each stick axis.
     * Index: 0=LX, 1=LY, 2=RX, 3=RY — value is a 1-based channel number.
     */
    public int[] getStickChannels() {
        return stickToChannel.clone();
    }

    /**
     * Per stick-axis auto-centre flags, derived from the channel each axis drives.
     * Index: 0=LX, 1=LY, 2=RX, 3=RY (same order as MODE1/MODE2 tables).
     */
    public boolean[] getStickAutoCenter() {
        boolean[] out = new boolean[4];
        for (int i = 0; i < 4; i++) {
            int c = stickToChannel[i];
            out[i] = (c >= 1 && c <= MAX_CHANNELS) && channels[c - 1].autoCenter;
        }
        return out;
    }

    public boolean isManualTarget() {
        return manualTarget;
    }

    public void setManualTarget(boolean manual, String ip, int port) {
        this.manualTarget = manual;
        if (ip != null) this.manualIp = ip;
        if (port > 0) this.manualPort = port;
        prefs.edit().putBoolean("target_manual", manual)
                .putString("manual_ip", this.manualIp)
                .putInt("manual_port", this.manualPort).apply();
        if (enabled) applyTarget();
    }

    public String getManualIp() {
        return manualIp;
    }

    public int getManualPort() {
        return manualPort;
    }

    public ChannelCfg getChannel(int index1Based) {
        return channels[index1Based - 1];
    }

    public void setChannel(int index1Based, ChannelCfg cfg) {
        channels[index1Based - 1] = cfg;
        saveChannels();
    }

    // ---- joystick layout persistence ---------------------------------------

    public static class JoystickLayout {
        public final float cxPct;
        public final float cyPct;
        public final float radiusPct;

        public JoystickLayout(float cxPct, float cyPct, float radiusPct) {
            this.cxPct = cxPct;
            this.cyPct = cyPct;
            this.radiusPct = radiusPct;
        }
    }

    public JoystickLayout getJoystickLayout(int pad) {
        String key = (pad == VirtualJoystickView.LEFT) ? "joy_layout_left" : "joy_layout_right";
        String def = defaultLayout(pad);
        String s = prefs.getString(key, def);
        String[] p = s.split(",");
        try {
            return new JoystickLayout(Float.parseFloat(p[0]),
                    Float.parseFloat(p[1]), Float.parseFloat(p[2]));
        } catch (Exception e) {
            return parseLayout(defaultLayout(pad));
        }
    }

    public void setJoystickLayout(int pad, float cxPct, float cyPct, float radiusPct) {
        String key = (pad == VirtualJoystickView.LEFT) ? "joy_layout_left" : "joy_layout_right";
        prefs.edit().putString(key, cxPct + "," + cyPct + "," + radiusPct).apply();
    }

    private String defaultLayout(int pad) {
        return (pad == VirtualJoystickView.LEFT) ? "0.24,0.62,0.20" : "0.76,0.62,0.20";
    }

    private JoystickLayout parseLayout(String s) {
        String[] p = s.split(",");
        return new JoystickLayout(Float.parseFloat(p[0]),
                Float.parseFloat(p[1]), Float.parseFloat(p[2]));
    }

    // ---- input feeds --------------------------------------------------------

    @Override
    public void onStick(String side, float nx, float ny) {
        int base = "left".equals(side) ? 0 : 2;
        joyAxes[base] = nx;
        joyAxes[base + 1] = ny;
    }

    @Override
    public void onAxes(float lx, float ly, float rx, float ry,
                       float ch5, float ch6, float ch7, float ch8) {
        gpadAxes[0] = lx;
        gpadAxes[1] = ly;
        gpadAxes[2] = rx;
        gpadAxes[3] = ry;
        gpadAux[0] = ch5;
        gpadAux[1] = ch6;
        gpadAux[2] = ch7;
        gpadAux[3] = ch8;
        lastGamepadMs = System.currentTimeMillis();
    }

    private boolean isGamepadActive() {
        return (System.currentTimeMillis() - lastGamepadMs) < 2000;
    }

    public boolean isGamepadConnected() {
        return isGamepadActive();
    }

    // ---- core compute --------------------------------------------------------

    private int[] computePwm() {
        int[] pwm = new int[MAX_CHANNELS];
        for (int i = 0; i < MAX_CHANNELS; i++) {
            // within the configured range: 65535 = ignore; beyond it: 0 = ignore
            pwm[i] = (i < channelCount) ? 65535 : 0;
        }

        boolean gp = isGamepadActive();
        float[] a = gp ? gpadAxes : joyAxes;

        for (int i = 0; i < 4; i++) {
            int c = stickToChannel[i];
            if (c >= 1 && c <= channelCount) {
                pwm[c - 1] = mapAxis(a[i], c);
            }
        }

        if (gp) {
            // GamepadManager normalises every bound axis to -1..1, triggers included.
            if (channelCount >= 5) pwm[4] = auxPwm(5, gpadAux[0]);
            if (channelCount >= 6) pwm[5] = auxPwm(6, gpadAux[1]);
            if (channelCount >= 7) pwm[6] = auxPwm(7, gpadAux[2]);
            if (channelCount >= 8) pwm[7] = auxPwm(8, gpadAux[3]);
        }
        return pwm;
    }

    /** Aux channels are sent as "ignore" when the source axis is at rest. */
    private int auxPwm(int ch1Based, float norm) {
        if (Math.abs(norm) < DEADZONE) return 65535;
        return mapAxis(norm, ch1Based);
    }

    private int mapAxis(float raw, int ch1Based) {
        ChannelCfg cfg = channels[ch1Based - 1];
        if (!cfg.enabled) return (ch1Based <= channelCount) ? 65535 : 0;
        float a = raw;
        if (Math.abs(a) < DEADZONE) a = 0f;
        double span = (cfg.inverted ? -1 : 1) * (cfg.max - cfg.center);
        int v = (int) Math.round(cfg.center + a * span);
        v += cfg.trim;
        return Math.max(cfg.min, Math.min(cfg.max, v));
    }

    private void applyTarget() {
        if (manualTarget) {
            MavlinkNative.nativeSetRcTarget(manualIp, manualPort);
        } else {
            MavlinkNative.nativeSetRcTarget(null, 0);
        }
    }

    public int[] getLastPwm() {
        return Arrays.copyOf(lastPwm, lastPwm.length);
    }

    // ---- persistence ---------------------------------------------------------

    private void loadConfig() {
        enabled = prefs.getBoolean("enabled", false);
        showSticks = prefs.getBoolean("show_sticks", false);
        mode = prefs.getInt("mode", 2);
        stickToChannel = (mode == 1) ? MODE1.clone() : MODE2.clone();
        rateHz = prefs.getInt("rate_hz", 20);
        channelCount = prefs.getInt("channel_count", 8);
        manualTarget = prefs.getBoolean("target_manual", false);
        manualIp = prefs.getString("manual_ip", "192.168.1.10");
        manualPort = prefs.getInt("manual_port", 14550);
        loadChannels();
    }

    private void loadChannels() {
        String raw = prefs.getString("channels_cfg", "");
        if (raw.isEmpty()) return;
        String[] parts = raw.split(";");
        for (int i = 0; i < MAX_CHANNELS && i < parts.length; i++) {
            String[] f = parts[i].split(",");
            if (f.length < 6) continue;
            try {
                ChannelCfg c = channels[i];
                c.min = Integer.parseInt(f[0]);
                c.max = Integer.parseInt(f[1]);
                c.center = Integer.parseInt(f[2]);
                c.trim = Integer.parseInt(f[3]);
                c.inverted = f[4].equals("1");
                c.enabled = f[5].equals("1");
                // autoCenter added later; keep the default when an older config is loaded
                if (f.length >= 7) c.autoCenter = f[6].equals("1");
            } catch (NumberFormatException ignored) {
                Log.w(TAG, "bad channel cfg entry: " + parts[i]);
            }
        }
    }

    private void saveChannels() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_CHANNELS; i++) {
            ChannelCfg c = channels[i];
            if (i > 0) sb.append(';');
            sb.append(c.min).append(',').append(c.max).append(',').append(c.center)
                    .append(',').append(c.trim).append(',')
                    .append(c.inverted ? '1' : '0').append(',')
                    .append(c.enabled ? '1' : '0').append(',')
                    .append(c.autoCenter ? '1' : '0');
        }
        prefs.edit().putString("channels_cfg", sb.toString()).apply();
    }
}
