package com.openipc.pixelpilot;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import com.openipc.mavlink.MavlinkData;
import com.openipc.mavlink.MavlinkNative;
import com.openipc.mavlink.MavlinkUpdate;
import com.openipc.pixelpilot.databinding.ActivityVideoBinding;
import com.openipc.pixelpilot.osd.OSDElement;
import com.openipc.pixelpilot.osd.OSDManager;
import com.openipc.videonative.DecodingInfo;
import com.openipc.videonative.IVideoParamsChanged;
import com.openipc.videonative.VideoPlayer;
import com.openipc.pixelpilot.rc.RcControllerManager;
import com.openipc.pixelpilot.rc.GamepadManager;
import com.openipc.pixelpilot.rc.VirtualJoystickView;
import com.openipc.pixelpilot.video.SecondVideoWindow;

import androidx.appcompat.app.AlertDialog;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Most basic implementation of an activity that uses VideoNative to stream a video
// Into an Android Surface View
public class VideoActivity extends AppCompatActivity implements IVideoParamsChanged,
        MavlinkUpdate {
    private static final String TAG = "pixelpilot";
    private static final int PICK_DVR_REQUEST_CODE = 2;
    private static final int PICK_MODEL_REQUEST_CODE = 3;
    private static final String MODEL_LITE0_FILE = "efficientdet-lite0.tflite";
    private static final String MODEL_LITE2_FILE = "efficientdet-lite2.tflite";
    private static final String MODEL_LITE0_URL = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite";
    private static final String MODEL_LITE2_URL = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float32/1/efficientdet_lite2.tflite";
    private static final long MODEL_LITE0_BYTES = 13836895L;
    private static final long MODEL_LITE2_BYTES = 23096891L;
    private static final String PREF_OD_CUSTOM_MODEL_URI = "od_custom_model_uri";
    private static final String PREF_OD_CUSTOM_MODEL_NAME = "od_custom_model_name";
    final Handler handler = new Handler(Looper.getMainLooper());
    final Runnable runnable = new Runnable() {
        public void run() {
            MavlinkNative.nativeCallBack(VideoActivity.this);
            handler.postDelayed(this, 100);
        }
    };
    protected DecodingInfo mDecodingInfo;
    int lastVideoW = 0, lastVideoH = 0, lastCodec = 1;
    BroadcastReceiver batteryReceiver;
    VideoPlayer videoPlayer;
    private ActivityVideoBinding binding;
    private OSDManager osdManager;
    private ParcelFileDescriptor dvrFd = null;
    private Timer dvrIconTimer = null;
    private Timer recordTimer = null;
    private int seconds = 0;
    private boolean isVRMode = false;
    private ConstraintLayout constraintLayout;
    private ConstraintSet constraintSet;

    private ObjectDetectorHelper objectDetectorHelper;
    private ExecutorService objectDetectionExecutor;
    private volatile boolean isObjectDetectionEnabled = false;
    private volatile boolean isDetecting = false;
    private final Object detectorLock = new Object();
    private Boolean objectDetectionRuntimeSupported = null;

    private static final String PREF_DVR_FILENAME = "dvr_filename";

    // RC control
    private RcControllerManager rcManager;
    private GamepadManager gamepad;
    private boolean gamepadConnected = false;
    private TextView[] chVal = new TextView[8];

    // Second floating UDP video window (independent stream)
    private SecondVideoWindow secondVideoWindow;

    public boolean getVRSetting() {
        return getSharedPreferences("general", Context.MODE_PRIVATE).getBoolean("vr-mode", false);
    }

    public void setVRSetting(boolean v) {
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("vr-mode", v);
        editor.commit();
    }

    static String paddedDigits(int val, int len) {
        StringBuilder sb = new StringBuilder(String.format("%d", val));
        while (sb.length() < len) {
            sb.append('\t');
        }
        return sb.toString();
    }

    private void resetApp() {
        // Restart the app
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            System.exit(0); // Ensure the app is fully restarted
        }
    }

    private boolean hasUriPermission(Uri uri) {
        for (UriPermission perm : getContentResolver().getPersistedUriPermissions()) {
            if (perm.getUri().equals(uri) && perm.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private void resetFolderPermissions() {
        // Retrieve the stored DVR folder URI
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        String dvrFolderUriString = prefs.getString("dvr_folder_", null);
        if (dvrFolderUriString == null) {
            Toast.makeText(this, "No folder permissions to reset.", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri dvrUri = Uri.parse(dvrFolderUriString);

        // Revoke persisted URI permissions
        for (UriPermission perm : getContentResolver().getPersistedUriPermissions()) {
            if (perm.getUri().equals(dvrUri)) {
                getContentResolver().releasePersistableUriPermission(perm.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Log.d(TAG, "Released URI permission for: " + perm.getUri());
            }
        }

        // Clear the stored URI from SharedPreferences
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove("dvr_folder_");
        editor.apply();

        // Stop any ongoing DVR recording
        if (dvrFd != null) {
            stopDvr();
        }

        // Update the record button icon to default
        binding.imgBtnRecord.setImageResource(R.drawable.record);

        // Reset any related UI elements
        binding.txtRecordLabel.setVisibility(View.GONE);
        binding.imgRecIndicator.setVisibility(View.INVISIBLE);

        // Inform the user
        Toast.makeText(this, "Folder permissions have been reset.", Toast.LENGTH_LONG).show();

        // Optionally, prompt the user to select a new folder immediately
        // Uncomment the following lines if you want to prompt immediately
        /*
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_DVR_REQUEST_CODE);
        */
    }

    // Lifecycle - onCreate

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "lifecycle onCreate");
        super.onCreate(savedInstanceState);

        // UI Setup
        initializeUI();

        // Video Player(s) Setup
        initializeVideoPlayers();

        // VR-specific SeekBars (only if VR mode)
        setupVRSeekBarsIfNeeded();

        // OSD Manager Setup
        setupOSDManager();

        // Button Handlers
        setupButtonHandlers();

        // Mavlink Setup
        setupMavlink();

        // RC control (virtual joystick + gamepad -> RC_CHANNELS_OVERRIDE)
        setupRcController();

        // Second floating UDP video window (independent stream)
        secondVideoWindow = new SecondVideoWindow(this, binding.secondVideoHost);
        secondVideoWindow.init();

        // Battery Receiver
        setupBatteryReceiver();
    }

    // ----------------------------------------------------------------------------
    // UI SETUP
    // ----------------------------------------------------------------------------

    /**
     * Initializes basic UI components, including window flags and layout binding.
     */
    private void initializeUI() {
        binding = ActivityVideoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    // ----------------------------------------------------------------------------
    // VIDEO PLAYER SETUP
    // ----------------------------------------------------------------------------

    /**
     * Initializes VideoPlayer and configures surfaces for VR or standard mode.
     */
    private void initializeVideoPlayers() {
        videoPlayer = new VideoPlayer(this);
        videoPlayer.setIVideoParamsChanged(this);

        isVRMode = getVRSetting();

        if (isVRMode) {
            setupVRVideoPlayers();
        } else {
            setupStandardVideoPlayer();
        }
    }

    /**
     * Configures the UI for VR mode by attaching callbacks to the left and right SurfaceViews.
     */
    private void setupVRVideoPlayers() {
        binding.mainVideo.setVisibility(View.GONE);
        binding.surfaceViewLeft.getHolder().addCallback(videoPlayer.configure1(0));
        binding.surfaceViewRight.getHolder().addCallback(videoPlayer.configure1(1));
    }

    /**
     * Configures the UI for standard, single-surface video playback.
     */
    private void setupStandardVideoPlayer() {
        binding.surfaceViewRight.setVisibility(View.GONE);
        binding.surfaceViewLeft.setVisibility(View.GONE);
        binding.mainVideo.setSurfaceTextureListener(videoPlayer.configureTextureView(0));
    }

    // ----------------------------------------------------------------------------
    // VR SEEK BARS (only in VR mode)
    // ----------------------------------------------------------------------------

    /**
     * Initializes and configures SeekBars for VR mode to adjust the margin and size of surfaces.
     * If not in VR mode, this method does nothing.
     */
    private void setupVRSeekBarsIfNeeded() {
        if (!isVRMode) return;

        constraintLayout = binding.frameLayout;
        constraintSet = new ConstraintSet();
        constraintSet.clone(constraintLayout);

        configureVRSeekBars();
        configureVRSeekBarVisibility();
        configureVRSeekBarListeners();
    }

    /**
     * Configures both the margin (binding.seekBar) and distance (binding.distanceSeekBar) SeekBars.
     */
    private void configureVRSeekBars() {
        // Rotate the first seekBar 180 degrees
        binding.seekBar.setRotation(180);

        // Retrieve saved progress for both seekBars
        SharedPreferences sharedPreferences = getSharedPreferences("SeekBarPrefs", MODE_PRIVATE);
        SharedPreferences sharedPreferencesd = getSharedPreferences("SeekBarPrefsD", MODE_PRIVATE);

        int savedProgress = sharedPreferences.getInt("seekBarProgress", 1);
        int savedDistanceProgress = sharedPreferencesd.getInt("distanceSeekBarProgress", 1);

        // Apply saved progress values
        binding.seekBar.setProgress(savedProgress);
        binding.distanceSeekBar.setProgress(savedDistanceProgress);

        // Make them visible initially
        binding.seekBar.setVisibility(View.VISIBLE);
        binding.distanceSeekBar.setVisibility(View.VISIBLE);

        // Apply initial constraints
        applyVRMargins(savedProgress);
        applyVRDistance(savedDistanceProgress);
    }

    /**
     * Manages hiding and showing the SeekBars after some delay or upon user touch.
     */
    private void configureVRSeekBarVisibility() {
        // Hide SeekBars after 3 seconds
        handler.postDelayed(() -> {
            binding.seekBar.setVisibility(View.GONE);
            binding.distanceSeekBar.setVisibility(View.GONE);
            updateViewRatio(R.id.surfaceViewLeft, lastVideoW, lastVideoH);
            updateViewRatio(R.id.surfaceViewRight, lastVideoW, lastVideoH);
        }, 3000);

        // Show SeekBars when the layout is touched
        binding.frameLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                binding.seekBar.setVisibility(View.VISIBLE);
                binding.distanceSeekBar.setVisibility(View.VISIBLE);
                handler.postDelayed(() -> {
                    binding.seekBar.setVisibility(View.GONE);
                    binding.distanceSeekBar.setVisibility(View.GONE);
                    updateViewRatio(R.id.surfaceViewLeft, lastVideoW, lastVideoH);
                    updateViewRatio(R.id.surfaceViewRight, lastVideoW, lastVideoH);
                }, 3000);
            }
            return false;
        });
    }

    /**
     * Sets listeners on the SeekBars to adjust margins and distances in real time.
     */
    private void configureVRSeekBarListeners() {
        binding.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyVRMargins(progress);
                saveSeekBarValue("SeekBarPrefs", "seekBarProgress", progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        binding.distanceSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar distanceSeekBar, int progress, boolean fromUser) {
                applyVRDistance(progress);
                saveSeekBarValue("SeekBarPrefsD", "distanceSeekBarProgress", progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    /**
     * Adjusts margins for left/right SurfaceViews based on progress.
     */
    private void applyVRMargins(int progress) {
        int margin = progress * 20; // Adjust multiplier as needed
        constraintSet.setMargin(R.id.surfaceViewLeft, ConstraintSet.START, margin);
        constraintSet.setMargin(R.id.surfaceViewRight, ConstraintSet.END, margin);
        constraintSet.applyTo(constraintLayout);
    }

    /**
     * Adjusts size for left/right SurfaceViews based on progress.
     */
    private void applyVRDistance(int progress) {
        int size = progress * 20; // Adjust multiplier as needed
        constraintSet.setMargin(R.id.surfaceViewLeft, ConstraintSet.END, size);
        constraintSet.setMargin(R.id.surfaceViewRight, ConstraintSet.START, size);
        constraintSet.applyTo(constraintLayout);
    }

    /**
     * Saves the SeekBar progress value to SharedPreferences.
     */
    private void saveSeekBarValue(String prefsName, String key, int progress) {
        SharedPreferences sp = getSharedPreferences(prefsName, MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(key, progress);
        editor.apply();
    }

    // ----------------------------------------------------------------------------
    // OSD MANAGER
    // ----------------------------------------------------------------------------

    /**
     * Sets up the On-Screen Display (OSD) manager for telemetry or other overlays.
     */
    private void setupOSDManager() {
        osdManager = new OSDManager(this, binding);
        osdManager.setUp();
    }

    // ----------------------------------------------------------------------------
    // BUTTON HANDLERS
    // ----------------------------------------------------------------------------

    /**
     * Sets up the main button click listeners: Record and Settings.
     */
    private void setupButtonHandlers() {
        binding.imgBtnRecord.setOnClickListener(item -> startStopDvr());
        binding.btnSettings.setOnClickListener(this::showSettingsMenu);
    }

    /**
     * Shows the main settings popup menu and configures its items.
     */
    private void showSettingsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);

        // VR submenu
        setupVRSubMenu(popup);


        // Recording submenu
        setupRecordingSubMenu(popup);

        // UDP Forwarding submenu
        setupUdpForwardingSubMenu(popup);

        // Help submenu
        setupHelpSubMenu(popup);

        // Object Detection submenu
        setupObjectDetectionSubMenu(popup);

        // OSD submenu (toggles + lock). Restored: it is driven purely by
        // MAVLink telemetry, not by wfb-ng, so it survived the strip.
        setupOSDSubMenu(popup);

        // RC control submenu
        setupRcSubMenu(popup);

        // Second UDP video window submenu
        setupSecondVideoSubMenu(popup);

        popup.show();
    }

    /**
     * Submenu that toggles VR mode.
     */
    private void setupVRSubMenu(PopupMenu popup) {
        SubMenu vrMenu = popup.getMenu().addSubMenu("VR mode");
        MenuItem vrItem = vrMenu.add(getVRSetting() ? "On" : "Off");
        vrItem.setOnMenuItemClickListener(item -> {
            isVRMode = !getVRSetting();
            setVRSetting(isVRMode);
            vrItem.setTitle(isVRMode ? "Off" : "On");
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            resetApp();
            return false;
        });
    }

    /**
     * Submenu handling OSD toggles and locks.
     */
    private void setupOSDSubMenu(PopupMenu popup) {
        SubMenu osd = popup.getMenu().addSubMenu("OSD");
        MenuItem lock = osd.add(osdManager.getTitle());
        lock.setOnMenuItemClickListener(item -> {
            osdManager.lockOSD(!osdManager.isOSDLocked());
            lock.setTitle(osdManager.getTitle());
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        for (OSDElement element : osdManager.listOSDItems) {
            MenuItem itm = osd.add(element.name);
            itm.setCheckable(true);
            itm.setChecked(osdManager.isElementEnabled(element));
            itm.setOnMenuItemClickListener(menuItem -> {
                menuItem.setChecked(!menuItem.isChecked());
                osdManager.onOSDItemCheckChanged(element, menuItem.isChecked());
                menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
                menuItem.setActionView(new View(this));
                return false;
            });
        }
    }

    /**
     * Submenu for recording options, including start/stop DVR and toggling fMP4.
     */
    private void setupRecordingSubMenu(PopupMenu popup) {
        SubMenu recording = popup.getMenu().addSubMenu("Recording");

        MenuItem dvrBtn = recording.add(dvrFd == null ? "Start" : "Stop");
        dvrBtn.setOnMenuItemClickListener(item -> {
            startStopDvr();
            return true;
        });

        MenuItem fmp4 = recording.add("fMP4");
        fmp4.setCheckable(true);
        fmp4.setChecked(getDvrMP4());
        fmp4.setOnMenuItemClickListener(item -> {
            boolean enabled = getDvrMP4();
            item.setChecked(!enabled);
            setDvrMP4(!enabled);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        MenuItem resetPermissions = recording.add("Reset DVR folder");
        resetPermissions.setOnMenuItemClickListener(item -> {
            resetFolderPermissions();
            return true;
        });

        MenuItem editFileNameTemplate = recording.add("File Name Template");
        editFileNameTemplate.setOnMenuItemClickListener(item -> {
            showEditFileNameTemplateDialog();
            return true;
        });
    }

    /**
     * Submenu for drone settings.
     */
    private void setupUdpForwardingSubMenu(PopupMenu popup) {
        SubMenu forwardMenu = popup.getMenu().addSubMenu("UDP Forwarding");

        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("forward_udp_enabled", false);
        String ip = prefs.getString("forward_udp_ip", "192.168.1.100");
        int port = prefs.getInt("forward_udp_port", 5600);

        MenuItem enableItem = forwardMenu.add("Enable");
        enableItem.setCheckable(true);
        enableItem.setChecked(enabled);
        enableItem.setOnMenuItemClickListener(item -> {
            boolean newState = !item.isChecked();
            item.setChecked(newState);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("forward_udp_enabled", newState);
            editor.apply();
            updateUdpForwardingState();
            return true;
        });

        MenuItem configItem = forwardMenu.add("Config Target (" + ip + ":" + port + ")");
        configItem.setOnMenuItemClickListener(item -> {
            showUdpForwardingDialog();
            return true;
        });
    }

    private void showUdpForwardingDialog() {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        String ip = prefs.getString("forward_udp_ip", "192.168.1.100");
        int port = prefs.getInt("forward_udp_port", 5600);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        final android.widget.EditText ipEditText = new android.widget.EditText(this);
        ipEditText.setHint("Destination IP Address");
        ipEditText.setText(ip);
        layout.addView(ipEditText);

        final android.widget.EditText portEditText = new android.widget.EditText(this);
        portEditText.setHint("Destination Port");
        portEditText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portEditText.setText(String.valueOf(port));
        layout.addView(portEditText);

        new android.app.AlertDialog.Builder(this)
                .setTitle("UDP Forwarding Config")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newIp = ipEditText.getText().toString().trim();
                    String newPortStr = portEditText.getText().toString().trim();
                    int newPort = 5600;
                    try {
                        newPort = Integer.parseInt(newPortStr);
                    } catch (NumberFormatException ignored) {}

                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString("forward_udp_ip", newIp);
                    editor.putInt("forward_udp_port", newPort);
                    editor.apply();

                    Toast.makeText(this, "Forwarding settings saved: " + newIp + ":" + newPort, Toast.LENGTH_SHORT).show();
                    updateUdpForwardingState();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateUdpForwardingState() {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("forward_udp_enabled", false);
        String ip = prefs.getString("forward_udp_ip", "192.168.1.100");
        int port = prefs.getInt("forward_udp_port", 5600);

        if (videoPlayer != null) {
            videoPlayer.setUdpForwarding(ip, port, enabled);
        }
    }

    /**
     * Submenu for help items, such as sending logs.
     */
    private void setupHelpSubMenu(PopupMenu popup) {
        SubMenu help = popup.getMenu().addSubMenu("Help");
        MenuItem logs = help.add("Send Logs");

        // Increase logcat buffer to 10MB if possible
        try {
            Runtime.getRuntime().exec("logcat -G 10M");
        } catch (IOException e) {
            Log.e(TAG, "ShareLog: ", e);
        }

        logs.setOnMenuItemClickListener(item -> {
            shareLogs();
            return true;
        });
    }

    // ----------------------------------------------------------------------------
    // MAVLINK SETUP
    // ----------------------------------------------------------------------------

    /**
     * Starts the native Mavlink service and posts an initial Runnable to the Handler.
     */
    private void setupMavlink() {
        MavlinkNative.nativeStart(this);
        handler.post(runnable);
    }

    // ----------------------------------------------------------------------------
    // RC CONTROL SETUP (virtual joystick + gamepad -> RC_CHANNELS_OVERRIDE)
    // ----------------------------------------------------------------------------

    private void setupRcController() {
        rcManager = new RcControllerManager(this);
        rcManager.setDisplayListener((pwm, ok) -> updateRcHud(pwm, ok));

        VirtualJoystickView joystick = binding.rcOverlay.joystickView;
        joystick.setStickListener(rcManager);
        rcManager.setJoystickMirror(joystick);
        joystick.setLayoutChangeListener((pad, cxPct, cyPct, radiusPct) ->
                rcManager.setJoystickLayout(pad, cxPct, cyPct, radiusPct));
        joystick.setThrottleSide(rcManager.getThrottleSide());
        joystick.setOpacity(0.5f);
        applyStickAutoCenter();
        loadJoystickLayout(joystick);

        gamepad = new GamepadManager(this);
        gamepad.setListener(rcManager);
        setupGamepadConnectionWatcher();

        chVal[0] = binding.rcOverlay.chVal1;
        chVal[1] = binding.rcOverlay.chVal2;
        chVal[2] = binding.rcOverlay.chVal3;
        chVal[3] = binding.rcOverlay.chVal4;
        chVal[4] = binding.rcOverlay.chVal5;
        chVal[5] = binding.rcOverlay.chVal6;
        chVal[6] = binding.rcOverlay.chVal7;
        chVal[7] = binding.rcOverlay.chVal8;

        binding.rcOverlay.rcOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                joystick.setOpacity(progress / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        binding.rcOverlay.rcLock.setOnClickListener(v -> {
            boolean locked = !joystick.isLocked(); // locked=true=fly, false=adjust
            joystick.setLocked(locked);
            binding.rcOverlay.rcLock.setText(locked ? R.string.rc_unlock : R.string.rc_lock);
        });
        binding.rcOverlay.rcLock.setText(joystick.isLocked() ? R.string.rc_unlock : R.string.rc_lock);

        binding.rcOverlay.rcConfig.setOnClickListener(v -> showRcChannelConfigDialog());
        binding.rcOverlay.rcHide.setOnClickListener(v -> {
            rcManager.setShowSticks(false);
            setRcOverlayVisible(false);
        });

        setRcOverlayVisible(rcManager.isEnabled() && rcManager.isShowSticks());
        if (rcManager.isEnabled()) {
            rcManager.start();
        }
    }

    /**
     * Push the per-channel "auto centre" setting down to the virtual sticks
     * (index order: 0=LX, 1=LY, 2=RX, 3=RY).
     */
    private void applyStickAutoCenter() {
        if (rcManager == null) return;
        boolean[] ac = rcManager.getStickAutoCenter();
        VirtualJoystickView joystick = binding.rcOverlay.joystickView;
        joystick.setAxisAutoCenter(VirtualJoystickView.LEFT, ac[0], ac[1]);
        joystick.setAxisAutoCenter(VirtualJoystickView.RIGHT, ac[2], ac[3]);
    }

    private void loadJoystickLayout(VirtualJoystickView joystick) {
        if (rcManager == null) return;
        RcControllerManager.JoystickLayout left = rcManager.getJoystickLayout(VirtualJoystickView.LEFT);
        RcControllerManager.JoystickLayout right = rcManager.getJoystickLayout(VirtualJoystickView.RIGHT);
        joystick.setLayout(VirtualJoystickView.LEFT, left.cxPct, left.cyPct, left.radiusPct);
        joystick.setLayout(VirtualJoystickView.RIGHT, right.cxPct, right.cyPct, right.radiusPct);
    }

    private void setRcOverlayVisible(boolean visible) {
        binding.rcOverlay.getRoot().setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Track physical gamepad connect/disconnect via the system InputDevice listener and
     * reflect it on the RC pipeline + on-screen sticks:
     *  - when a gamepad is present the virtual sticks stop accepting touch "打杆" and only
     *    mirror the gamepad as visual feedback (gamepad takes priority / is more controllable);
     *  - when it is removed, touch control is re-enabled.
     */
    private void setupGamepadConnectionWatcher() {
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im == null) return;
        InputManager.InputDeviceListener listener = new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int id) {
                refreshGamepadState(im);
            }

            @Override
            public void onInputDeviceRemoved(int id) {
                refreshGamepadState(im);
            }

            @Override
            public void onInputDeviceChanged(int id) {
                refreshGamepadState(im);
            }
        };
        refreshGamepadState(im);
        im.registerInputDeviceListener(listener, null);
    }

    private void refreshGamepadState(InputManager im) {
        boolean connected = false;
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d != null && isGamepadDevice(d)) {
                connected = true;
                break;
            }
        }
        if (connected == gamepadConnected) return;
        gamepadConnected = connected;
        if (gamepad != null) gamepad.setDeviceConnected(connected);
        if (rcManager != null) rcManager.setGamepadConnected(connected);
        binding.rcOverlay.joystickView.setGamepadMirror(connected);
        Log.d(TAG, "gamepad " + (connected ? "connected" : "disconnected")
                + " -> mirror=" + connected);
    }

    private static boolean isGamepadDevice(InputDevice d) {
        int src = d.getSources();
        return (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    private void updateRcHud(int[] pwm, boolean ok) {
        if (chVal[0] == null) return;
        // The HUD has eight slots; anything beyond the configured count is blanked.
        int shown = Math.min(8, rcManager.getChannelCount());
        for (int i = 0; i < 8; i++) {
            if (i >= shown) {
                chVal[i].setText("—");
                continue;
            }
            int v = pwm[i];
            chVal[i].setText(v == 65535 ? "—" : String.valueOf(v));
        }
        if (rcManager == null) return;
        String status;
        if (rcManager.isGamepadConnected()) {
            status = getString(R.string.rc_status_gamepad);
        } else if (!ok) {
            status = getString(R.string.rc_status_nolink);
        } else {
            status = getString(R.string.rc_status_sending);
        }
        binding.rcOverlay.rcStatus.setText(status);
    }

    /** Editable widgets for one channel row in the RC settings dialog. */
    private static class RcRow {
        int ch;
        CheckBox enabled;
        CheckBox invert;
        CheckBox autoCenter;
        EditText min;
        EditText max;
        EditText center;
        EditText trim;
    }

    private void showRcChannelConfigDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_rc_config, null);
        LinearLayout list = dialogView.findViewById(R.id.rcChannelList);

        // ---------- global settings ----------
        list.addView(sectionTitle(getString(R.string.rc_global_section)));

        list.addView(fieldLabel(getString(R.string.rc_rate_hz)));
        final int[] rateOpts = {5, 10, 20, 30, 50};
        RadioGroup rateGroup = new RadioGroup(this);
        rateGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (int hz : rateOpts) {
            RadioButton rb = new RadioButton(this);
            rb.setId(2000 + hz);
            rb.setText(hz + "Hz");
            rateGroup.addView(rb);
        }
        rateGroup.check(2000 + rcManager.getRateHz());
        if (rateGroup.getCheckedRadioButtonId() < 0) {
            rateGroup.check(2020); // stored rate not in the list -> fall back to 20Hz
        }
        list.addView(rateGroup);

        list.addView(fieldLabel(getString(R.string.rc_channel_count)));
        final int[] countOpts = {8, 12, 16, 18};
        RadioGroup countGroup = new RadioGroup(this);
        countGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (int c : countOpts) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + c);
            rb.setText(String.valueOf(c));
            countGroup.addView(rb);
        }
        countGroup.check(1000 + rcManager.getChannelCount());
        if (countGroup.getCheckedRadioButtonId() < 0) {
            countGroup.check(1008); // stored count not in the list -> fall back to 8
        }
        list.addView(countGroup);

        Button calibBtn = new Button(this);
        calibBtn.setText(R.string.rc_gamepad_calib);
        list.addView(calibBtn);

        // ---------- per-channel rows ----------
        list.addView(sectionTitle(getString(R.string.rc_channel_config)));
        final java.util.List<RcRow> rows = new java.util.ArrayList<>();
        final LinearLayout channelHost = new LinearLayout(this);
        channelHost.setOrientation(LinearLayout.VERTICAL);
        list.addView(channelHost);

        buildChannelRows(channelHost, rows, rcManager.getChannelCount());

        countGroup.setOnCheckedChangeListener((group, checkedId) ->
                buildChannelRows(channelHost, rows, checkedId - 1000));
        calibBtn.setOnClickListener(v -> showGamepadCalibDialog());

        builder.setView(dialogView);
        builder.setTitle(R.string.rc_settings);
        builder.setPositiveButton(R.string.rc_done, (dialog, which) -> {
            int rateId = rateGroup.getCheckedRadioButtonId();
            if (rateId >= 2000) {
                rcManager.setRateHz(rateId - 2000);
            }
            int countId = countGroup.getCheckedRadioButtonId();
            if (countId >= 1000) {
                rcManager.setChannelCount(countId - 1000);
            }
            for (RcRow r : rows) {
                RcControllerManager.ChannelCfg cfg = new RcControllerManager.ChannelCfg();
                cfg.enabled = r.enabled.isChecked();
                cfg.inverted = r.invert.isChecked();
                cfg.autoCenter = r.autoCenter.isChecked();
                cfg.min = parseOr(r.min, 1000);
                cfg.max = parseOr(r.max, 2000);
                cfg.center = parseOr(r.center, 1500);
                cfg.trim = parseOr(r.trim, 0);
                rcManager.setChannel(r.ch, cfg);
            }
            applyStickAutoCenter();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    /** (Re)builds one labelled card per driven channel. */
    private void buildChannelRows(LinearLayout host, java.util.List<RcRow> rows, int count) {
        host.removeAllViews();
        rows.clear();
        for (int i = 1; i <= count; i++) {
            final int ch = i;
            RcControllerManager.ChannelCfg cfg = rcManager.getChannel(ch);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(0, 10, 0, 10);

            TextView title = new TextView(this);
            title.setText("CH" + ch + "  " + channelRole(ch));
            title.setTextSize(15f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(getResources().getColor(android.R.color.black));
            card.addView(title);

            LinearLayout toggles = new LinearLayout(this);
            toggles.setOrientation(LinearLayout.HORIZONTAL);

            RcRow r = new RcRow();
            r.ch = ch;
            r.enabled = new CheckBox(this);
            r.enabled.setText(R.string.rc_enabled);
            r.enabled.setChecked(cfg.enabled);
            r.invert = new CheckBox(this);
            r.invert.setText(R.string.rc_invert);
            r.invert.setChecked(cfg.inverted);
            r.autoCenter = new CheckBox(this);
            r.autoCenter.setText(R.string.rc_auto_center);
            r.autoCenter.setChecked(cfg.autoCenter);
            toggles.addView(r.enabled);
            toggles.addView(r.invert);
            toggles.addView(r.autoCenter);
            card.addView(toggles);

            r.min = new EditText(this);
            r.max = new EditText(this);
            r.center = new EditText(this);
            r.trim = new EditText(this);
            for (EditText e : new EditText[]{r.min, r.max, r.center, r.trim}) {
                e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                e.setSingleLine(true);
            }

            LinearLayout nums1 = new LinearLayout(this);
            nums1.setOrientation(LinearLayout.HORIZONTAL);
            nums1.addView(labeledField(getString(R.string.rc_min), r.min, String.valueOf(cfg.min)));
            nums1.addView(labeledField(getString(R.string.rc_max), r.max, String.valueOf(cfg.max)));

            LinearLayout nums2 = new LinearLayout(this);
            nums2.setOrientation(LinearLayout.HORIZONTAL);
            nums2.addView(labeledField(getString(R.string.rc_center), r.center, String.valueOf(cfg.center)));
            nums2.addView(labeledField(getString(R.string.rc_trim), r.trim, String.valueOf(cfg.trim)));

            card.addView(nums1);
            card.addView(nums2);

            View sep = new View(this);
            sep.setBackgroundColor(0xFFCCCCCC);
            sep.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            card.addView(sep);

            host.addView(card);
            rows.add(r);
        }
    }

    private LinearLayout labeledField(String label, EditText field, String value) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setPadding(0, 0, 20, 0);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(getResources().getColor(android.R.color.black));
        tv.setMinWidth(90);
        field.setText(value);
        field.setMinWidth(160);
        ll.addView(tv);
        ll.addView(field);
        return ll;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(getResources().getColor(android.R.color.black));
        tv.setPadding(0, 14, 0, 4);
        return tv;
    }

    private TextView fieldLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(android.R.color.black));
        tv.setPadding(0, 8, 0, 2);
        return tv;
    }

    /** Human readable role of a channel, derived from the active stick mapping. */
    private String channelRole(int ch) {
        int[] map = rcManager.getStickChannels(); // index 0=LX,1=LY,2=RX,3=RY
        for (int i = 0; i < map.length; i++) {
            if (map[i] == ch) {
                switch (i) {
                    case 0:
                        return getString(R.string.rc_slot_yaw);
                    case 2:
                        return getString(R.string.rc_slot_roll);
                    case 1:
                        return getString(rcManager.getMode() == 2
                                ? R.string.rc_slot_throttle : R.string.rc_slot_pitch);
                    case 3:
                        return getString(rcManager.getMode() == 2
                                ? R.string.rc_slot_pitch : R.string.rc_slot_throttle);
                    default:
                        break;
                }
            }
        }
        if (ch >= 5) {
            return String.format(java.util.Locale.US, getString(R.string.rc_aux_fmt), ch);
        }
        return "";
    }

    /**
     * Gamepad calibration: shows every input slot with its live value and lets the
     * user re-bind it by tapping "bind" and then moving the desired axis.
     */
    private void showGamepadCalibDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 18, 28, 18);

        TextView help = new TextView(this);
        help.setText(R.string.rc_gamepad_calib_help);
        help.setTextColor(getResources().getColor(android.R.color.black));
        root.addView(help);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv);

        final int[] slots = {
                GamepadManager.SLOT_LX, GamepadManager.SLOT_LY,
                GamepadManager.SLOT_RX, GamepadManager.SLOT_RY,
                GamepadManager.SLOT_AUX1, GamepadManager.SLOT_AUX2,
                GamepadManager.SLOT_AUX3, GamepadManager.SLOT_AUX4,
        };
        final TextView[] bindLabels = new TextView[slots.length];
        final ProgressBar[] bars = new ProgressBar[slots.length];
        final TextView[] valLabels = new TextView[slots.length];

        for (int i = 0; i < slots.length; i++) {
            final int slot = slots[i];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(0, 10, 0, 10);

            TextView title = new TextView(this);
            title.setText(slotTitle(slot));
            title.setTextSize(15f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(getResources().getColor(android.R.color.black));
            card.addView(title);

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            Button bindBtn = new Button(this);
            bindBtn.setText(R.string.rc_bind);
            TextView bindLabel = new TextView(this);
            bindLabel.setText(GamepadManager.axisName(gamepad.getBinding(slot)));
            bindLabel.setTextColor(getResources().getColor(android.R.color.black));
            bindLabel.setPadding(16, 0, 0, 0);
            bindLabels[i] = bindLabel;
            line.addView(bindBtn);
            line.addView(bindLabel);
            card.addView(line);

            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(200);
            bar.setProgress(100);
            bars[i] = bar;
            card.addView(bar);

            TextView val = new TextView(this);
            val.setText("0.00");
            val.setTextColor(getResources().getColor(android.R.color.black));
            valLabels[i] = val;
            card.addView(val);

            bindBtn.setOnClickListener(v -> {
                gamepad.startLearning(slot);
                bindLabel.setText(R.string.rc_binding);
            });

            list.addView(card);
        }

        Button resetBtn = new Button(this);
        resetBtn.setText(R.string.rc_reset_bindings);
        resetBtn.setOnClickListener(v -> {
            gamepad.resetToDefaultsAndSave();
            for (int i = 0; i < slots.length; i++) {
                bindLabels[i].setText(GamepadManager.axisName(gamepad.getBinding(slots[i])));
            }
        });
        root.addView(resetBtn);

        gamepad.setCalibListener(new GamepadManager.CalibListener() {
            @Override
            public void onLiveValues(float[] values) {
                for (int i = 0; i < slots.length; i++) {
                    int idx = indexOfAxis(GamepadManager.CANDIDATE_AXES, gamepad.getBinding(slots[i]));
                    if (idx < 0) continue;
                    float v = values[idx];
                    bars[i].setProgress((int) ((v + 1f) * 100f));
                    valLabels[i].setText(String.format(java.util.Locale.US, "%.2f", v));
                }
            }

            @Override
            public void onBindingLearned(int slot, int axis) {
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i] == slot) {
                        bindLabels[i].setText(GamepadManager.axisName(axis));
                    }
                }
            }
        });

        builder.setView(root);
        builder.setTitle(R.string.rc_gamepad_calib);
        builder.setNegativeButton(R.string.rc_done, null);
        builder.setOnDismissListener(d -> {
            gamepad.cancelLearning();
            gamepad.setCalibListener(null);
        });
        AlertDialog dlg = builder.show();

        // Gamepad motion is delivered to the focused window; mirror it from the
        // dialog's decor view so learning also works while this dialog is open.
        if (dlg.getWindow() != null) {
            View decor = dlg.getWindow().getDecorView();
            decor.setOnGenericMotionListener((v, ev) -> gamepad.onGenericMotionEvent(ev));
        }
        root.setOnGenericMotionListener((v, ev) -> gamepad.onGenericMotionEvent(ev));
    }

    private String slotTitle(int slot) {
        switch (slot) {
            case GamepadManager.SLOT_LX:
            case GamepadManager.SLOT_LY:
            case GamepadManager.SLOT_RX:
            case GamepadManager.SLOT_RY: {
                int[] map = rcManager.getStickChannels();
                int idx = slot - GamepadManager.SLOT_LX;
                int ch = map[idx];
                return "CH" + ch + "  " + channelRole(ch);
            }
            case GamepadManager.SLOT_AUX1:
            case GamepadManager.SLOT_AUX2:
            case GamepadManager.SLOT_AUX3:
            case GamepadManager.SLOT_AUX4: {
                int ch = 5 + (slot - GamepadManager.SLOT_AUX1);
                return "CH" + ch + "  " + String.format(java.util.Locale.US,
                        getString(R.string.rc_aux_fmt), ch);
            }
            default:
                return "";
        }
    }

    private static int indexOfAxis(int[] axes, int axis) {
        for (int i = 0; i < axes.length; i++) {
            if (axes[i] == axis) return i;
        }
        return -1;
    }

    private int parseOr(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private void showRcTargetDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 20, 40, 20);

        final EditText ipE = new EditText(this);
        ipE.setHint(R.string.rc_manual_ip_hint);
        ipE.setText(rcManager.getManualIp());
        final EditText portE = new EditText(this);
        portE.setHint(R.string.rc_manual_port_hint);
        portE.setInputType(InputType.TYPE_CLASS_NUMBER);
        portE.setText(String.valueOf(rcManager.getManualPort()));

        ll.addView(ipE);
        ll.addView(portE);
        builder.setView(ll);
        builder.setTitle(R.string.rc_target_manual);
        builder.setPositiveButton(R.string.rc_apply, (d, w) -> {
            int port = 14550;
            try {
                port = Integer.parseInt(portE.getText().toString().trim());
            } catch (Exception ignored) {
            }
            rcManager.setManualTarget(true, ipE.getText().toString().trim(), port);
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void setupRcSubMenu(PopupMenu popup) {
        SubMenu rc = popup.getMenu().addSubMenu(R.string.rc_title);

        MenuItem en = rc.add(rcManager.isEnabled() ? R.string.rc_enable_off : R.string.rc_enable_on);
        en.setOnMenuItemClickListener(item -> {
            boolean e = !rcManager.isEnabled();
            rcManager.setEnabled(e);
            if (e) setRcOverlayVisible(rcManager.isShowSticks());
            en.setTitle(e ? R.string.rc_enable_off : R.string.rc_enable_on);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        MenuItem sticks = rc.add(R.string.rc_show_sticks);
        sticks.setOnMenuItemClickListener(item -> {
            boolean show = !rcManager.isShowSticks();
            rcManager.setShowSticks(show);
            setRcOverlayVisible(show && rcManager.isEnabled());
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        MenuItem mode = rc.add(getString(R.string.rc_mode) + ": " + rcManager.getMode());
        mode.setOnMenuItemClickListener(item -> {
            int m = (rcManager.getMode() == 1) ? 2 : 1;
            rcManager.setMode(m);
            binding.rcOverlay.joystickView.setThrottleSide(rcManager.getThrottleSide());
            applyStickAutoCenter();
            mode.setTitle(getString(R.string.rc_mode) + ": " + m);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        MenuItem tgt = rc.add(rcManager.isManualTarget() ? R.string.rc_target_manual : R.string.rc_target_auto);
        tgt.setOnMenuItemClickListener(item -> {
            if (rcManager.isManualTarget()) {
                rcManager.setManualTarget(false, null, 0);
                tgt.setTitle(R.string.rc_target_auto);
            } else {
                showRcTargetDialog();
                tgt.setTitle(R.string.rc_target_manual);
            }
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        // Rate and channel count now live in the RC settings dialog.
        MenuItem cfg = rc.add(R.string.rc_settings);
        cfg.setOnMenuItemClickListener(item -> {
            showRcChannelConfigDialog();
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        MenuItem lock = rc.add(binding.rcOverlay.joystickView.isLocked() ? R.string.rc_unlock : R.string.rc_lock);
        lock.setOnMenuItemClickListener(item -> {
            boolean l = !binding.rcOverlay.joystickView.isLocked();
            binding.rcOverlay.joystickView.setLocked(l);
            binding.rcOverlay.rcLock.setText(l ? R.string.rc_unlock : R.string.rc_lock);
            lock.setTitle(l ? R.string.rc_unlock : R.string.rc_lock);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });
    }

    /**
     * Submenu for the second floating UDP video window.
     */
    private void setupSecondVideoSubMenu(PopupMenu popup) {
        SubMenu sub = popup.getMenu().addSubMenu(R.string.second_video_title);
        SharedPreferences p = getSharedPreferences("second_video", MODE_PRIVATE);
        boolean enabled = p.getBoolean("enabled", false);

        MenuItem en = sub.add(R.string.second_video_enable);
        en.setCheckable(true);
        en.setChecked(enabled);
        en.setOnMenuItemClickListener(item -> {
            boolean e = !item.isChecked();
            item.setChecked(e);
            if (e) {
                secondVideoWindow.enable(p.getString("ip", ""), p.getInt("port", 5601));
            } else {
                secondVideoWindow.disable();
            }
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        MenuItem cfg = sub.add(R.string.second_video_config);
        cfg.setOnMenuItemClickListener(item -> {
            showSecondVideoConfigDialog();
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        MenuItem reset = sub.add(R.string.second_video_reset_pos);
        reset.setOnMenuItemClickListener(item -> {
            secondVideoWindow.resetPosition();
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });
    }

    /**
     * Configuration dialog for the second video window: source IP, port, enable.
     */
    private void showSecondVideoConfigDialog() {
        SharedPreferences p = getSharedPreferences("second_video", MODE_PRIVATE);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.second_video_dialog_title);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 20, 40, 20);

        CheckBox cb = new CheckBox(this);
        cb.setText(R.string.second_video_enable);
        cb.setChecked(p.getBoolean("enabled", false));
        root.addView(cb);

        TextView ipLbl = new TextView(this);
        ipLbl.setText(R.string.second_video_ip);
        EditText ipEd = new EditText(this);
        ipEd.setInputType(InputType.TYPE_CLASS_TEXT);
        ipEd.setText(p.getString("ip", ""));
        root.addView(ipLbl);
        root.addView(ipEd);

        TextView portLbl = new TextView(this);
        portLbl.setText(R.string.second_video_port);
        EditText portEd = new EditText(this);
        portEd.setInputType(InputType.TYPE_CLASS_NUMBER);
        portEd.setText(String.valueOf(p.getInt("port", 5601)));
        root.addView(portLbl);
        root.addView(portEd);

        sv.addView(root);
        b.setView(sv);
        b.setPositiveButton(R.string.rc_apply, (d, w) -> {
            String ip = ipEd.getText().toString().trim();
            int port = 5601;
            try {
                port = Integer.parseInt(portEd.getText().toString().trim());
            } catch (NumberFormatException ignore) {
            }
            boolean en = cb.isChecked();
            if (en) {
                secondVideoWindow.enable(ip, port);
            } else {
                secondVideoWindow.disable();
            }
        });
        b.setNegativeButton(R.string.rc_done, (d, w) -> d.dismiss());
        b.show();
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (gamepad != null && gamepad.onGenericMotionEvent(event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    // ----------------------------------------------------------------------------
    // BATTERY RECEIVER
    // ----------------------------------------------------------------------------

    /**
     * Registers a receiver that listens for battery status changes and updates the UI accordingly.
     */
    private void setupBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent batteryStatus) {
                updateBatteryStatus(batteryStatus);
            }
        };
    }

    /**
     * Updates the battery icon and percentage based on the current battery state.
     */
    private void updateBatteryStatus(Intent batteryStatus) {
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = level * 100 / (float) scale;
        binding.tvGSBattery.setText((int) batteryPct + "%");

        int icon;
        if (isCharging) {
            icon = R.drawable.baseline_battery_charging_full_24;
        } else {
            // Adjust these thresholds as needed
            if (batteryPct <= 0) {
                icon = R.drawable.baseline_battery_0_bar_24;
            } else if (batteryPct <= (1f / 7f) * 100) {
                icon = R.drawable.baseline_battery_1_bar_24;
            } else if (batteryPct <= (2f / 7f) * 100) {
                icon = R.drawable.baseline_battery_2_bar_24;
            } else if (batteryPct <= (3f / 7f) * 100) {
                icon = R.drawable.baseline_battery_3_bar_24;
            } else if (batteryPct <= (4f / 7f) * 100) {
                icon = R.drawable.baseline_battery_4_bar_24;
            } else if (batteryPct <= (5f / 7f) * 100) {
                icon = R.drawable.baseline_battery_5_bar_24;
            } else if (batteryPct <= (6f / 7f) * 100) {
                icon = R.drawable.baseline_battery_6_bar_24;
            } else {
                icon = R.drawable.baseline_battery_full_24;
            }
        }
        binding.imgGSBattery.setImageResource(icon);
    }

    // ----------------------------------------------------------------------------
    // LOG SHARING
    // ----------------------------------------------------------------------------

    /**
     * Shares the device logs by writing them to a file and prompting the user to choose a share target.
     */
    private void shareLogs() {
        try {
            Process process = Runtime.getRuntime().exec("logcat -d");
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File logFile = new File(getExternalFilesDir(null), "pixelpilot_log_" + timeStamp + ".txt");
            FileWriter fileWriter = new FileWriter(logFile);

            // Fetch app version info
            String versionName = "";
            long versionCode = 0;
            try {
                PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                versionName = packageInfo.versionName;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    versionCode = packageInfo.getLongVersionCode();
                } else {
                    versionCode = packageInfo.versionCode;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }

            // Write device/app info
            fileWriter.append("Device Model: ").append(Build.MODEL).append("\n")
                    .append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
                    .append("OS Version: ").append(Build.VERSION.RELEASE).append("\n")
                    .append("SDK Version: ").append(String.valueOf(Build.VERSION.SDK_INT)).append("\n")
                    .append("App Version Name: ").append(versionName).append("\n")
                    .append("App Version Code: ").append(String.valueOf(versionCode)).append("\n");

            // Write actual logs
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                fileWriter.append(line).append("\n");
            }
            fileWriter.flush();
            fileWriter.close();

            // Share the log file
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            Uri fileUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", logFile);
            sendIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            sendIntent.setType("text/plain");
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent shareIntent = Intent.createChooser(sendIntent, null);
            startActivity(shareIntent);

        } catch (IOException e) {
            Log.e(TAG, "ShareLog: ", e);
        }
    }

    private Uri openDvrFile() {
        String dvrFolder = getSharedPreferences("general",
                Context.MODE_PRIVATE).getString("dvr_folder_", "");
        if (dvrFolder.isEmpty()) {
            Log.e(TAG, "dvrFolder is empty");
            return null;
        }
        Uri uri = Uri.parse(dvrFolder);
        DocumentFile pickedDir = DocumentFile.fromTreeUri(this, uri);
        if (pickedDir != null && pickedDir.canWrite()) {
            LocalDateTime now = LocalDateTime.now();
            String filename = getDvrFileName(getDvrFileNameTemplate(), now) + ".mp4";
            DocumentFile newFile = pickedDir.createFile("video/mp4", filename);
            Toast.makeText(this, "Recording to " + filename, Toast.LENGTH_SHORT).show();
            if (newFile == null)
                Log.e(TAG, "dvr newFile null");
            return newFile != null ? newFile.getUri() : null;
        }
        return null;
    }

    private void startStopDvr() {
        if (dvrFd == null) {
            Uri dvrUri = openDvrFile();
            if (dvrUri != null) {
                startDvr(dvrUri);
            } else {
                videoPlayer.stop();
                videoPlayer.stopAudio();

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, PICK_DVR_REQUEST_CODE);
            }
        } else {
            stopDvr();
        }
    }

    private void startDvr(Uri dvrUri) {
        if (dvrFd != null) {
            stopDvr();
        }
        try {
            dvrFd = getContentResolver().openFileDescriptor(dvrUri, "rw");
            videoPlayer.startDvr(dvrFd.getFd(), getDvrMP4());
            binding.imgBtnRecord.setImageResource(R.drawable.recording);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open dvr file ", e);
            dvrFd = null;
        }

        binding.txtRecordLabel.setVisibility(View.VISIBLE);
        recordTimer = new Timer();
        recordTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                int minutes = seconds / 60;
                int secs = seconds % 60;

                String timeFormatted = String.format("%02d:%02d", minutes, secs);
                runOnUiThread(() -> binding.txtRecordLabel.setText(timeFormatted));
                seconds++;
            }
        }, 0, 1000);

        dvrIconTimer = new Timer();
        dvrIconTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> binding.imgRecIndicator.setVisibility(binding.imgRecIndicator
                        .getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE));
            }
        }, 0, 1000);
    }

    private void stopDvr() {
        if (dvrFd == null) {
            return;
        }
        binding.imgRecIndicator.setVisibility(View.INVISIBLE);
        binding.imgBtnRecord.setImageResource(R.drawable.record);
        videoPlayer.stopDvr();
        if (recordTimer != null) {
            recordTimer.cancel();
            recordTimer.purge();
            recordTimer = null;
            seconds = 0;
            binding.txtRecordLabel.setVisibility(View.GONE);
        }
        if (dvrIconTimer != null) {
            dvrIconTimer.cancel();
            dvrIconTimer.purge();
            dvrIconTimer = null;
        }
        try {
            dvrFd.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        dvrFd = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_DVR_REQUEST_CODE && resultCode == RESULT_OK) {
            // The result data contains a URI for the document or directory that
            // the user selected.
            Uri uri;
            if (data != null && data.getData() != null) {
                uri = data.getData();
                final int takeFlags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, takeFlags);

                // Perform operations on the document using its URI.
                SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("dvr_folder_", uri.toString());
                editor.apply();
                Uri dvrUri = openDvrFile();
                if (dvrUri != null) {
                    startDvr(dvrUri);
                }
            }
        } else if (requestCode == PICK_MODEL_REQUEST_CODE && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                handleSelectedModelUri(data);
            }
        } else {
            Log.w(TAG, "onActivityResult: unknown request code " + requestCode);
        }
    }

    public boolean getDvrMP4() {
        return getSharedPreferences("general", Context.MODE_PRIVATE).getBoolean("dvr_fmp4", true);
    }

    public void setDvrMP4(boolean enabled) {
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("dvr_fmp4", enabled);
        editor.apply();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void registerReceivers() {
        IntentFilter batFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, batFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, batFilter);
        }
    }

    public void unregisterReceivers() {
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (rcManager != null) rcManager.stop();

        stopObjectDetectionLoop();

        unregisterReceivers();

        if (secondVideoWindow != null) secondVideoWindow.pause();

        videoPlayer.stop();
        videoPlayer.stopAudio();
    }

    @Override
    protected void onStop() {
        MavlinkNative.nativeStop(this);
        handler.removeCallbacks(runnable);
        unregisterReceivers();
        videoPlayer.stop();
        videoPlayer.stopAudio();
        super.onStop();
    }

    @Override
    protected void onResume() {
        registerReceivers();

        videoPlayer.start();
        updateUdpForwardingState();
        videoPlayer.startAudio();

        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        boolean odEnabled = prefs.getBoolean("od_enabled", false);
        setObjectDetectionEnabled(odEnabled);

        osdManager.restoreOSDConfig();

        if (rcManager != null && rcManager.isEnabled()) rcManager.start();

        if (secondVideoWindow != null) secondVideoWindow.resume();

        super.onResume();
    }

    @Override
    protected void onDestroy() {
        if (secondVideoWindow != null) secondVideoWindow.release();
        super.onDestroy();
    }


    @Override
    public void onVideoRatioChanged(final int videoW, final int videoH) {
        lastVideoW = videoW;
        lastVideoH = videoH;

        Log.d(TAG, "Set resolution: " + videoW + "x" + videoH);

        updateViewRatio(R.id.mainVideo, lastVideoW, lastVideoH);
        updateViewRatio(R.id.surfaceViewLeft, lastVideoW, lastVideoH);
        updateViewRatio(R.id.surfaceViewRight, lastVideoW, lastVideoH);
    }

    private void updateViewRatio(int viewId, int videoW, int videoH) {
        if (videoW == 0 || videoH == 0) {
            return;
        }

        View view = findViewById(viewId);
        if (view != null) {
            ConstraintLayout.LayoutParams params =
                    (ConstraintLayout.LayoutParams) view.getLayoutParams();
            params.dimensionRatio = videoW + ":" + videoH;
            runOnUiThread(() -> view.setLayoutParams(params));
        } else {
            Log.w(TAG, "View with ID " + viewId + " not found.");
        }
    }

    @Override
    public void onDecodingInfoChanged(final DecodingInfo decodingInfo) {
        mDecodingInfo = decodingInfo;
        runOnUiThread(() -> {
            if (lastCodec != decodingInfo.nCodec) {
                lastCodec = decodingInfo.nCodec;
            }
            if (decodingInfo.currentFPS > 0) {
                binding.tvMessage.setVisibility(View.GONE);
            }
            String info = "%dx%d@%.0f " + (decodingInfo.nCodec == 1 ? " H265 " : " H264 ")
                    + (decodingInfo.currentKiloBitsPerSecond > 1000 ? " %.1fMbps " : " %.1fKpbs ")
                    + " %.1fms";
            binding.tvVideoStats.setText(String.format(Locale.US, info,
                    lastVideoW, lastVideoH, decodingInfo.currentFPS,
                    decodingInfo.currentKiloBitsPerSecond / 1000,
                    decodingInfo.avgTotalDecodingTime_ms));
        });
    }

    @Override
    public void onNewMavlinkData(MavlinkData data) {
        runOnUiThread(() -> osdManager.render(data));
    }

    private void showEditFileNameTemplateDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30); // Add some padding around the content

        // EditText for filename
        final android.widget.EditText fileNameEditText = new android.widget.EditText(this);
        fileNameEditText.setHint("pixelpilot_[yyyyMMdd-HHmmss]");
        fileNameEditText.setText(getDvrFileNameTemplate()); // Pre-fill with current saved filename template
        layout.addView(fileNameEditText);

        // TextView for preview filename
        final android.widget.TextView previewTextView = new android.widget.TextView(this);
        previewTextView.setText(getDvrFileName(getDvrFileNameTemplate(), LocalDateTime.now()) + ".mp4");
        layout.addView(previewTextView);

        // Set a listener for EditText
        fileNameEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                previewTextView.setText(getDvrFileName(fileNameEditText.getText().toString(), LocalDateTime.now()) + ".mp4");
            }

            @Override
            public void afterTextChanged(Editable editable) {}
        });

        // Build and show the AlertDialog
        new android.app.AlertDialog.Builder(this)
                .setTitle("DVR File Name Template")
                .setView(layout) // Set our custom layout
                .setPositiveButton("Save", (dialog, which) -> {
                    // Save the new values to SharedPreferences
                    setDvrFileName(fileNameEditText.getText().toString());
                    Toast.makeText(this, "DVR file name template saved.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    dialog.cancel(); // Dismiss the dialog
                })
                .show();
    }

    // pixelpilot_[yyyyMMdd-HHmmss]
    // ".mp4" will append later
    private String getDvrFileName(String template, LocalDateTime time) {
        Matcher matcher = Pattern.compile("\\[([^\\]]*)\\]").matcher(template);
        String fallbackTime = time.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        if (matcher.find()) {
            String prefix = template.substring(0, matcher.start());
            String pattern = matcher.group(1);
            String suffix = template.substring(matcher.end());

            try {
                String timePart = time.format(DateTimeFormatter.ofPattern(pattern));
                return prefix + timePart + suffix;
            } catch (IllegalArgumentException e) {
                return prefix + fallbackTime + suffix;
            }
        }
        return "pixelpilot_" + fallbackTime;
    }

    private String getDvrFileNameTemplate()
    {
        return getSharedPreferences("general", Context.MODE_PRIVATE).getString(PREF_DVR_FILENAME, "pixelpilot_[yyyyMMdd-HHmmss]");
    }

    private void setDvrFileName(String fileName) {
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_DVR_FILENAME, fileName);
        editor.apply();
    }

    private void setupObjectDetectionSubMenu(PopupMenu popup) {
        SubMenu odMenu = popup.getMenu().addSubMenu("Object Detection");

        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        boolean odEnabled = prefs.getBoolean("od_enabled", false);
        boolean runtimeSupported = isObjectDetectionRuntimeSupported();
        boolean selectedModelAvailable = isSelectedObjectDetectionModelAvailable();
        String status = getObjectDetectionStatus(runtimeSupported, selectedModelAvailable);

        MenuItem statusItem = odMenu.add(status);
        statusItem.setEnabled(false);

        MenuItem enableItem = odMenu.add("Enable");
        enableItem.setCheckable(true);
        enableItem.setEnabled(runtimeSupported && selectedModelAvailable);
        enableItem.setChecked(odEnabled && runtimeSupported && selectedModelAvailable);
        enableItem.setOnMenuItemClickListener(item -> {
            boolean newState = !item.isChecked();
            item.setChecked(newState);
            setObjectDetectionEnabled(newState);
            return true;
        });

        SubMenu delegateMenu = odMenu.addSubMenu("Processing Unit");
        int savedDelegate = prefs.getInt("od_delegate", ObjectDetectorHelper.DELEGATE_CPU);

        MenuItem cpuItem = delegateMenu.add("CPU");
        cpuItem.setEnabled(runtimeSupported);
        cpuItem.setCheckable(true);
        cpuItem.setChecked(savedDelegate == ObjectDetectorHelper.DELEGATE_CPU);
        cpuItem.setOnMenuItemClickListener(item -> {
            prefs.edit().putInt("od_delegate", ObjectDetectorHelper.DELEGATE_CPU).apply();
            restartObjectDetector();
            return true;
        });

        MenuItem gpuItem = delegateMenu.add("GPU");
        gpuItem.setEnabled(runtimeSupported && isGpuDelegateSupported());
        gpuItem.setCheckable(true);
        gpuItem.setChecked(savedDelegate == ObjectDetectorHelper.DELEGATE_GPU);
        gpuItem.setOnMenuItemClickListener(item -> {
            prefs.edit().putInt("od_delegate", ObjectDetectorHelper.DELEGATE_GPU).apply();
            restartObjectDetector();
            return true;
        });

        SubMenu modelMenu = odMenu.addSubMenu("Model");
        int savedModel = prefs.getInt("od_model", ObjectDetectorHelper.MODEL_EFFICIENTDETV0);

        MenuItem downloadItem = modelMenu.add("Download Default models");
        downloadItem.setEnabled(runtimeSupported);
        downloadItem.setOnMenuItemClickListener(item -> {
            downloadObjectDetectionModels();
            return true;
        });

        MenuItem v0Item = modelMenu.add("EfficientDet-Lite0");
        v0Item.setEnabled(runtimeSupported && isDownloadedModelAvailable(ObjectDetectorHelper.MODEL_EFFICIENTDETV0));
        v0Item.setCheckable(true);
        v0Item.setChecked(savedModel == ObjectDetectorHelper.MODEL_EFFICIENTDETV0);
        v0Item.setOnMenuItemClickListener(item -> {
            prefs.edit().putInt("od_model", ObjectDetectorHelper.MODEL_EFFICIENTDETV0).apply();
            restartObjectDetector();
            return true;
        });

        MenuItem v2Item = modelMenu.add("EfficientDet-Lite2");
        v2Item.setEnabled(runtimeSupported && isDownloadedModelAvailable(ObjectDetectorHelper.MODEL_EFFICIENTDETV2));
        v2Item.setCheckable(true);
        v2Item.setChecked(savedModel == ObjectDetectorHelper.MODEL_EFFICIENTDETV2);
        v2Item.setOnMenuItemClickListener(item -> {
            prefs.edit().putInt("od_model", ObjectDetectorHelper.MODEL_EFFICIENTDETV2).apply();
            restartObjectDetector();
            return true;
        });

        MenuItem selectLocalItem = modelMenu.add("Select local model");
        selectLocalItem.setEnabled(runtimeSupported);
        selectLocalItem.setOnMenuItemClickListener(item -> {
            selectLocalObjectDetectionModel();
            return true;
        });

        if (isCustomModelAvailable()) {
            String customModelName = prefs.getString(PREF_OD_CUSTOM_MODEL_NAME, "Local model");
            MenuItem customItem = modelMenu.add(customModelName);
            customItem.setEnabled(runtimeSupported);
            customItem.setCheckable(true);
            customItem.setChecked(savedModel == ObjectDetectorHelper.MODEL_CUSTOM);
            customItem.setOnMenuItemClickListener(item -> {
                prefs.edit().putInt("od_model", ObjectDetectorHelper.MODEL_CUSTOM).apply();
                restartObjectDetector();
                return true;
            });
        }

 
    }

    private void setObjectDetectionEnabled(boolean enabled) {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        if (enabled) {
            boolean runtimeSupported = isObjectDetectionRuntimeSupported();
            boolean modelAvailable = isSelectedObjectDetectionModelAvailable();
            if (!runtimeSupported || !modelAvailable) {
                isObjectDetectionEnabled = false;
                prefs.edit().putBoolean("od_enabled", false).apply();
                binding.detectionOverlay.setVisibility(View.GONE);
                binding.detectionOverlay.clear();
                stopObjectDetectionLoop();
                Toast.makeText(this, getObjectDetectionStatus(runtimeSupported, modelAvailable), Toast.LENGTH_LONG).show();
                return;
            }
        }

        isObjectDetectionEnabled = enabled;
        prefs.edit().putBoolean("od_enabled", enabled).apply();

        if (enabled) {
            binding.detectionOverlay.setVisibility(View.VISIBLE);
            startObjectDetectionLoop();
        } else {
            binding.detectionOverlay.setVisibility(View.GONE);
            binding.detectionOverlay.clear();
            stopObjectDetectionLoop();
        }
    }

    private void restartObjectDetector() {
        if (isObjectDetectionEnabled) {
            stopObjectDetectionLoop();
            startObjectDetectionLoop();
        }
    }

    private void startObjectDetectionLoop() {
        if (isVRMode) return; // Standard mode only
        if (objectDetectionExecutor == null) {
            objectDetectionExecutor = Executors.newSingleThreadExecutor();
        }
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        int delegate = prefs.getInt("od_delegate", ObjectDetectorHelper.DELEGATE_CPU);
        if (delegate == ObjectDetectorHelper.DELEGATE_GPU && !isGpuDelegateSupported()) {
            delegate = ObjectDetectorHelper.DELEGATE_CPU;
            prefs.edit().putInt("od_delegate", ObjectDetectorHelper.DELEGATE_CPU).apply();
        }
        final int detectorDelegate = delegate;

        objectDetectionExecutor.execute(() -> {
            ByteBuffer modelBuffer = readSelectedObjectDetectionModel();
            if (modelBuffer == null) {
                runOnUiThread(() -> setObjectDetectionEnabled(false));
                return;
            }

            synchronized (detectorLock) {
                if (objectDetectorHelper != null) {
                    objectDetectorHelper.clear();
                }
                objectDetectorHelper = new ObjectDetectorHelper(
                        VideoActivity.this,
                        0.5f,
                        3,
                        detectorDelegate,
                        modelBuffer
                );
            }

            isDetecting = true;
            while (isDetecting && isObjectDetectionEnabled) {
                Bitmap bitmap = null;
                try {
                    long start = SystemClock.uptimeMillis();
                    if (binding.mainVideo != null && binding.mainVideo.isAvailable()) {
                        bitmap = binding.mainVideo.getBitmap();
                    }

                    if (bitmap != null) {
                        ObjectDetectorHelper.ResultBundle result = null;
                        synchronized (detectorLock) {
                            if (objectDetectorHelper != null) {
                                result = objectDetectorHelper.detectImage(bitmap);
                            }
                        }
                        
                        if (result != null && !result.results.isEmpty() && isObjectDetectionEnabled) {
                            final ObjectDetectorHelper.ResultBundle finalResult = result;
                            runOnUiThread(() -> {
                                if (isObjectDetectionEnabled) {
                                    binding.detectionOverlay.setResults(finalResult.results.get(0), finalResult.inputImageHeight, finalResult.inputImageWidth);
                                }
                            });
                        } else {
                            runOnUiThread(() -> {
                                if (isObjectDetectionEnabled) {
                                    binding.detectionOverlay.clear();
                                }
                            });
                        }
                    }

                    long sleepTime = 100 - (SystemClock.uptimeMillis() - start); // ~10 FPS
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in detection loop", e);
                } finally {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                }
            }
        });
    }

    private void stopObjectDetectionLoop() {
        isDetecting = false;
        if (objectDetectionExecutor != null) {
            objectDetectionExecutor.shutdownNow();
            objectDetectionExecutor = null;
        }
        synchronized (detectorLock) {
            if (objectDetectorHelper != null) {
                objectDetectorHelper.clear();
                objectDetectorHelper = null;
            }
        }
    }

    private File getObjectDetectionModelsDir() {
        File modelsDir = new File(getFilesDir(), "models");
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            Log.e(TAG, "Failed to create object detection models directory " + modelsDir);
        }
        return modelsDir;
    }

    private File getDownloadedModelFile(int model) {
        String fileName = model == ObjectDetectorHelper.MODEL_EFFICIENTDETV2 ? MODEL_LITE2_FILE : MODEL_LITE0_FILE;
        return new File(getObjectDetectionModelsDir(), fileName);
    }

    private boolean isDownloadedModelAvailable(int model) {
        File file = getDownloadedModelFile(model);
        long expectedBytes = model == ObjectDetectorHelper.MODEL_EFFICIENTDETV2 ? MODEL_LITE2_BYTES : MODEL_LITE0_BYTES;
        return file.isFile() && file.length() == expectedBytes;
    }

    private boolean isCustomModelAvailable() {
        String uriString = getSharedPreferences("general", MODE_PRIVATE).getString(PREF_OD_CUSTOM_MODEL_URI, null);
        if (uriString == null) {
            return false;
        }
        try (InputStream inputStream = getContentResolver().openInputStream(Uri.parse(uriString))) {
            return inputStream != null;
        } catch (Exception e) {
            Log.e(TAG, "Custom object detection model is not available", e);
            return false;
        }
    }

    private boolean isSelectedObjectDetectionModelAvailable() {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        int model = prefs.getInt("od_model", ObjectDetectorHelper.MODEL_EFFICIENTDETV0);
        if (model == ObjectDetectorHelper.MODEL_CUSTOM) {
            return isCustomModelAvailable();
        }
        return isDownloadedModelAvailable(model);
    }

    private String getObjectDetectionStatus(boolean runtimeSupported, boolean selectedModelAvailable) {
        if (!runtimeSupported) {
            return "Unavailable: device is not supported";
        }
        if (!selectedModelAvailable) {
            return "Unavailable: model not installed";
        }
        return "Ready";
    }

    private boolean isObjectDetectionRuntimeSupported() {
        if (objectDetectionRuntimeSupported != null) {
            return objectDetectionRuntimeSupported;
        }
        if (!isObjectDetectionAbiSupported()) {
            objectDetectionRuntimeSupported = false;
            return false;
        }
        try {
            Class.forName("com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector");
            System.loadLibrary("mediapipe_tasks_vision_jni");
            objectDetectionRuntimeSupported = true;
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Object detection runtime is not available", e);
            objectDetectionRuntimeSupported = false;
            return false;
        }
    }

    private boolean isObjectDetectionAbiSupported() {
        return Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
    }

    private boolean isGpuDelegateSupported() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null || activityManager.getDeviceConfigurationInfo() == null) {
            return false;
        }
        return activityManager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x00030001;
    }

    private void downloadObjectDetectionModels() {
        Toast.makeText(this, "Downloading object detection models...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                downloadObjectDetectionModel(ObjectDetectorHelper.MODEL_EFFICIENTDETV0);
                downloadObjectDetectionModel(ObjectDetectorHelper.MODEL_EFFICIENTDETV2);
                if (!isSelectedObjectDetectionModelAvailable()) {
                    getSharedPreferences("general", MODE_PRIVATE)
                            .edit()
                            .putInt("od_model", ObjectDetectorHelper.MODEL_EFFICIENTDETV0)
                            .apply();
                }
                runOnUiThread(() -> Toast.makeText(this, "Object detection models downloaded", Toast.LENGTH_LONG).show());
            } catch (IOException e) {
                Log.e(TAG, "Failed to download object detection models", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed to download models: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void downloadObjectDetectionModel(int model) throws IOException {
        File target = getDownloadedModelFile(model);
        long expectedBytes = model == ObjectDetectorHelper.MODEL_EFFICIENTDETV2 ? MODEL_LITE2_BYTES : MODEL_LITE0_BYTES;
        if (target.isFile() && target.length() == expectedBytes) {
            return;
        }

        String modelUrl = model == ObjectDetectorHelper.MODEL_EFFICIENTDETV2 ? MODEL_LITE2_URL : MODEL_LITE0_URL;
        File tempFile = new File(target.getParentFile(), target.getName() + ".download");
        HttpURLConnection connection = (HttpURLConnection) new URL(modelUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.connect();
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("HTTP " + responseCode);
        }

        long bytesCopied = 0;
        try (InputStream inputStream = connection.getInputStream();
             OutputStream outputStream = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
                bytesCopied += read;
            }
        } finally {
            connection.disconnect();
        }

        if (bytesCopied != expectedBytes) {
            if (!tempFile.delete()) {
                Log.w(TAG, "Failed to delete incomplete model " + tempFile);
            }
            throw new IOException("Incomplete model download");
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Failed to replace existing model");
        }
        if (!tempFile.renameTo(target)) {
            throw new IOException("Failed to save model");
        }
    }

    private void selectLocalObjectDetectionModel() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/x-tflite"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_MODEL_REQUEST_CODE);
    }

    private void handleSelectedModelUri(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        String displayName = "Local model";
        DocumentFile documentFile = DocumentFile.fromSingleUri(this, uri);
        if (documentFile != null && documentFile.getName() != null) {
            displayName = documentFile.getName();
        }
        if (!displayName.toLowerCase(Locale.US).endsWith(".tflite")) {
            Toast.makeText(this, "Please select a .tflite model file", Toast.LENGTH_LONG).show();
            return;
        }

        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("Unable to open model file");
            }
            final int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
            SharedPreferences.Editor editor = getSharedPreferences("general", MODE_PRIVATE).edit();
            editor.putString(PREF_OD_CUSTOM_MODEL_URI, uri.toString());
            editor.putString(PREF_OD_CUSTOM_MODEL_NAME, displayName);
            editor.putInt("od_model", ObjectDetectorHelper.MODEL_CUSTOM);
            editor.apply();
            Toast.makeText(this, "Selected " + displayName, Toast.LENGTH_LONG).show();
            restartObjectDetector();
        } catch (Exception e) {
            Log.e(TAG, "Failed to select local object detection model", e);
            Toast.makeText(this, "Failed to select model: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private ByteBuffer readSelectedObjectDetectionModel() {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        int model = prefs.getInt("od_model", ObjectDetectorHelper.MODEL_EFFICIENTDETV0);
        try {
            if (model == ObjectDetectorHelper.MODEL_CUSTOM) {
                String uriString = prefs.getString(PREF_OD_CUSTOM_MODEL_URI, null);
                if (uriString == null) {
                    return null;
                }
                try (InputStream inputStream = getContentResolver().openInputStream(Uri.parse(uriString))) {
                    return readModelBuffer(inputStream);
                }
            }
            try (InputStream inputStream = new FileInputStream(getDownloadedModelFile(model))) {
                return readModelBuffer(inputStream);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read object detection model", e);
            return null;
        }
    }

    private ByteBuffer readModelBuffer(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IOException("Model input stream is null");
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        byte[] bytes = outputStream.toByteArray();
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(bytes.length);
        directBuffer.put(bytes);
        directBuffer.rewind();
        return directBuffer;
    }
}
