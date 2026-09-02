package com.openipc.mavlink;

import android.content.Context;

public class MavlinkNative {

    // Used to load the 'mavlink' library on application startup.
    static {
        System.loadLibrary("mavlink");
    }

    public static native void nativeStart(Context context);

    public static native void nativeStop(Context context);

    // TODO: Use message queue from cpp for performance#
    // This initiates a 'call back' for the IVideoParams
    public static native <T extends MavlinkUpdate> void nativeCallBack(T t);

    /**
     * Send an RC_CHANNELS_OVERRIDE message with up to 18 channel PWM values (us).
     * Channels beyond the array length are sent as 0 (ignored by the autopilot).
     * Returns true if the packet was queued for sending.
     */
    public static native boolean nativeSendRcChannels(int[] channels);

    /**
     * Set a manual RC target (IP:port). Pass ip == null to clear and fall back to
     * the automatically learned peer address from incoming MAVLink traffic.
     */
    public static native void nativeSetRcTarget(String ip, int port);
}