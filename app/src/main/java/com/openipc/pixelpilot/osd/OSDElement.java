package com.openipc.pixelpilot.osd;

public class OSDElement {
    public String name;
    public MovableLayout layout;

    public OSDElement(String n, MovableLayout l) {
        name = n;
        layout = l;
    }

    public String prefName() {
        // Unique, collision-free key (names are unique in the list). Sanitise so it
        // is a safe SharedPreferences key. Used for position prefs (_fx/_fy).
        return "osd_" + name.replace(' ', '_').replace('.', '_').replace('-', '_');
    }
}
