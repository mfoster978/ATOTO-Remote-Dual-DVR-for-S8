package com.codex.hn7camerastream;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceResponse;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.MediaController;
import android.widget.VideoView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Date;

public class MainActivity extends Activity {
    private static final String APP_VERSION_NAME = "2.0.1";
    private static final int APP_VERSION_CODE = 20000;
    private static final String GITHUB_REPOSITORY = "mfoster978/ATOTO-Remote-Dual-DVR-for-S8";
    private static final String GITHUB_REPOSITORY_URL = "https://github.com/" + GITHUB_REPOSITORY;
    private static final String GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/" + GITHUB_REPOSITORY + "/releases/latest";
    private static final long MAX_UPDATE_BYTES = 300L * 1024L * 1024L;
    private static final int CAMERA_PERMISSION_REQUEST = 40;
    private static final int AUDIO_PERMISSION_REQUEST = 42;
    private static final int QR_PERMISSION_REQUEST = 43;
    private static final int LOCATION_PERMISSION_REQUEST = 44;
    private static final int QR_SCAN_REQUEST = 60;
    private static final String PREFS = "settings";
    private static final String MODE_KEY = "device_mode";
    private static final String MODE_RADIO = "radio";
    private static final String MODE_PHONE = "phone";
    private static final String PHONE_HOST_KEY = "phone_host";
    private static final String PHONE_PORT_KEY = "phone_port";
    private static final String PHONE_PIN_KEY = "phone_pin";
    private static final String PHONE_VEHICLE_NAME_KEY = "phone_vehicle_name";
    private static final String PHONE_PROFILES_KEY = "phone_vehicle_profiles";
    private static final String VEHICLE_NAME_KEY = "vehicle_name";
    private static final String DEFAULT_VEHICLE_NAME = "ATOTO radio";
    private static final String PHONE_DISCONNECT_KEY = "phone_disconnect_vpn";
    private static final String OWNER_PIN_SALT_KEY = "owner_pin_salt";
    private static final String OWNER_PIN_HASH_KEY = "owner_pin_hash";
    private static final String TAILSCALE_PACKAGE = "com.tailscale.ipn";

    private static final String RADIO_HOME = "radio_home";
    private static final String RADIO_DVR = "radio_dvr";
    private static final String RADIO_NETWORK = "radio_network";
    private static final String RADIO_RECORDING = "radio_recording";
    private static final String RADIO_SECURITY = "radio_security";
    private static final String RADIO_GUIDE = "radio_guide";
    private static final String RADIO_VIDEOS = "radio_videos";
    private static final String RADIO_PHOTOS = "radio_photos";
    private static final String PHONE_LIVE = "phone_live";
    private static final String PHONE_RECORDINGS = "phone_recordings";
    private static final String PHONE_CONNECTION = "phone_connection";
    private static final String PHONE_VPN = "phone_vpn";
    private static final String PHONE_SETTINGS = "phone_settings";

    private static final int BG = Color.rgb(7, 17, 31);
    private static final int APP_BAR = Color.rgb(10, 24, 42);
    private static final int SURFACE = Color.rgb(16, 36, 59);
    private static final int SURFACE_2 = Color.rgb(25, 49, 75);
    private static final int PRIMARY = Color.rgb(43, 125, 255);
    private static final int GREEN = Color.rgb(54, 211, 153);
    private static final int AMBER = Color.rgb(251, 191, 36);
    private static final int RED = Color.rgb(248, 113, 113);
    private static final int TEXT = Color.rgb(240, 247, 255);
    private static final int MUTED = Color.rgb(166, 189, 214);

    private SharedPreferences preferences;
    private FrameLayout drawerOverlay;
    private TextView status;
    private EditText hostInput;
    private EditText portInput;
    private EditText pinInput;
    private EditText vehicleNameInput;
    private WebView webView;
    private String currentPage = "";
    private boolean ownerUnlocked;
    private boolean activityResumed;
    private String ownerUnlockDestination = RADIO_RECORDING;
    private boolean launchingTailscale;
    private boolean vpnPromptShown;
    private boolean vehicleSyncRunning;
    private long lastVehicleSyncAt;
    private boolean clearPhoneConnectionForm;
    private int radioPreviewCamera;
    private int radioDvrCamera;
    private final ArrayList<MjpegView> phoneLiveViews = new ArrayList<MjpegView>();
    private final PcmAudioPlayer liveAudioPlayer = new PcmAudioPlayer();
    private final Handler ownerSessionHandler = new Handler(Looper.getMainLooper());
    private final Runnable ownerSessionTimeout = new Runnable() {
        @Override public void run() {
            ownerUnlocked = false;
            if (!activityResumed || !isFactoryDvrHost() || !isOwnerLockEnabled()) return;
            if (!StockDvrAccessGuard.isSessionUnlocked(MainActivity.this)
                    && isProtectedRadioPage(currentPage) && !"unlock".equals(currentPage)) {
                showRadioDvr();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (isFactoryDvrHost()) {
            // The radio build now lives inside ATOTO's stock DVR package. It
            // is always the radio endpoint; the standalone package is the
            // phone/tablet client.
            preferences.edit().putString(MODE_KEY, MODE_RADIO).apply();
        } else {
            // The standalone package is now the phone/tablet viewer only.
            preferences.edit().putString(MODE_KEY, MODE_PHONE).apply();
        }
        radioPreviewCamera = preferences.getInt("dvr_preview_camera", 0);
        radioDvrCamera = preferences.getInt("dvr_primary_camera", radioPreviewCamera == 2 ? 2 : 0);
        if (radioDvrCamera != 2) radioDvrCamera = 0;
        if (importSetupUri(getIntent() == null ? null : getIntent().getData())) return;
        if (!showRequestedFactoryPage(getIntent())) showCurrentMode();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!importSetupUri(intent == null ? null : intent.getData())) {
            showRequestedFactoryPage(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (preferences.getBoolean("pending_app_update_install", false)
                && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            preferences.edit().putBoolean("pending_app_update_install", false).apply();
            installDownloadedUpdate();
            return;
        }
        if (preferences.getBoolean("pending_tailscale_install", false)
                && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            preferences.edit().putBoolean("pending_tailscale_install", false).apply();
            installBundledTailscale();
            return;
        }
        if (preferences.getBoolean("pending_factory_dvr_install", false)
                && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            preferences.edit().putBoolean("pending_factory_dvr_install", false).apply();
            installBundledFactoryDvrIntegration();
            return;
        }
        if (preferences.getBoolean("factory_dvr_installer_open", false)) {
            preferences.edit().putBoolean("factory_dvr_installer_open", false).apply();
            startFactoryDvrAfterIntegrationInstall();
        }
        String mode = preferences.getString(MODE_KEY, "");
        if (MODE_RADIO.equals(mode)) syncParkingSystemSetting();
        boolean sessionUnlocked = StockDvrAccessGuard.isSessionUnlocked(this);
        if (MODE_RADIO.equals(mode) && isOwnerLockEnabled() && !ownerUnlocked && !sessionUnlocked
                && isProtectedRadioPage(currentPage) && !"unlock".equals(currentPage)) {
            showRadioDvr();
        } else if (MODE_RADIO.equals(mode) && RADIO_RECORDING.equals(currentPage)
                && (!isOwnerLockEnabled() || ownerUnlocked || sessionUnlocked)) {
            showRadioRecording();
        } else if (MODE_RADIO.equals(mode) && RADIO_DVR.equals(currentPage)) {
            showRadioDvr();
        } else if (MODE_PHONE.equals(mode) && PHONE_LIVE.equals(currentPage)) {
            showPhoneLive();
        } else if (MODE_PHONE.equals(mode) && PHONE_RECORDINGS.equals(currentPage)) {
            requestPhoneTailscaleConnect();
        }
        scheduleOwnerSessionTimeout();
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityResumed = false;
        ownerSessionHandler.removeCallbacks(ownerSessionTimeout);
        if (MODE_RADIO.equals(preferences.getString(MODE_KEY, "")) && isOwnerLockEnabled()) {
            ownerUnlocked = false;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        liveAudioPlayer.stop();
        stopPhoneLiveViews();
        if (MODE_RADIO.equals(preferences.getString(MODE_KEY, "")) && isOwnerLockEnabled()) {
            ownerUnlocked = false;
        }
        if (MODE_PHONE.equals(preferences.getString(MODE_KEY, ""))
                && preferences.getBoolean(PHONE_DISCONNECT_KEY, true)
                && !launchingTailscale) {
            sendTailscaleAction("com.tailscale.ipn.DISCONNECT_VPN");
        }
        launchingTailscale = false;
    }

    private void showCurrentMode() {
        if (isFactoryDvrHost()) {
            showRadioMenu();
            return;
        }
        showPhoneLive();
    }

    private boolean showRequestedFactoryPage(Intent intent) {
        if (!isFactoryDvrHost() || intent == null) return false;
        String page = intent.getStringExtra(StockDvrSettingsBridge.EXTRA_PAGE);
        if (page == null || page.length() == 0) return false;
        intent.removeExtra(StockDvrSettingsBridge.EXTRA_PAGE);
        if (StockDvrSettingsBridge.PAGE_MENU.equals(page)) showRadioMenu();
        else if (StockDvrSettingsBridge.PAGE_NETWORK.equals(page)) showRadioNetwork();
        else if (StockDvrSettingsBridge.PAGE_RECORDING.equals(page)) showRadioRecording();
        else if (StockDvrSettingsBridge.PAGE_SECURITY.equals(page)) showRadioSecurity();
        else if (StockDvrSettingsBridge.PAGE_UPDATE.equals(page)) {
            if (!requireOwnerAccess(StockDvrSettingsBridge.PAGE_UPDATE)) {
                showRadioSecurity();
                checkForAppUpdate();
            }
        } else showRadioMenu();
        return true;
    }

    private void showRadioMenu() {
        if (requireOwnerAccess(StockDvrSettingsBridge.PAGE_MENU)) return;
        showRadioNetwork();
        if (drawerOverlay != null) {
            drawerOverlay.post(new Runnable() {
                @Override public void run() {
                    if (drawerOverlay != null) drawerOverlay.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private boolean isFactoryDvrHost() {
        return "com.hcn.dvr".equals(getPackageName());
    }

    private void openStockDvrCamera() {
        try {
            Intent camera = new Intent().setClassName("com.hcn.dvr", "com.hcn.dvr.HDvrActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(camera);
            finish();
        } catch (RuntimeException error) {
            toast("The stock DVR camera screen could not be opened.");
        }
    }

    private void showRoleChooser() {
        currentPage = "onboarding";
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(34), dp(24), dp(34));
        page.setBackgroundColor(BG);

        ImageView icon = new ImageView(this);
        icon.setImageResource(getApplicationInfo().icon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(92), dp(92));
        iconParams.bottomMargin = dp(18);
        page.addView(icon, iconParams);

        TextView title = text("ATOTO Camera Link", 30, TEXT, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, matchWrap());
        TextView subtitle = text("One app. Two devices. Private camera access.", 17, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(26));
        page.addView(subtitle, matchWrap());

        LinearLayout radio = card();
        addCardTitle(radio, "Install on the radio");
        addBody(radio, "Replaces the factory DVR, records front and reverse cameras to USB, starts after reboot, and securely streams live video to the phone.");
        addAction(radio, "Set up this ATOTO radio", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { setMode(MODE_RADIO); }
        });
        page.addView(radio, cardParams());

        LinearLayout phone = card();
        addCardTitle(phone, "Install on your phone");
        addBody(phone, "Connects through Tailscale, opens live Front, DVR Camera 2, or Reverse video, and plays USB recordings.");
        addAction(phone, "Set up this phone", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { setMode(MODE_PHONE); }
        });
        page.addView(phone, cardParams());

        TextView note = text("The official Tailscale installer is included, so this is the only APK you need to download.", 14, MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(12), dp(12), dp(12), 0);
        page.addView(note, matchWrap());
        showStandalone(page);
    }

    private void setMode(String mode) {
        preferences.edit().putString(MODE_KEY, mode).apply();
        if (MODE_PHONE.equals(mode)) stopService(new Intent(this, CameraStreamService.class));
        showCurrentMode();
    }

    private void showRadioHome() {
        showRadioMenu();
    }

    private void showRadioDvr() {
        if (isFactoryDvrHost()) {
            openStockDvrCamera();
            return;
        }
        currentPage = RADIO_DVR;
        ensurePermissionAndStart();
        getWindow().setStatusBarColor(Color.BLACK);
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        View preview = buildDirectRadioPreview(radioPreviewCamera);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        previewParams.bottomMargin = dp(72);
        root.addView(preview, previewParams);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(8), dp(14), dp(8));
        top.setBackgroundColor(Color.argb(115, 0, 0, 0));
        TextView menu = text("☰", 30, TEXT, false);
        menu.setGravity(Gravity.CENTER);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { drawerOverlay.setVisibility(View.VISIBLE); }
        });
        top.addView(menu, new LinearLayout.LayoutParams(dp(58), dp(50)));
        TextView cameraName = text(dvrCameraName(radioPreviewCamera), 18, TEXT, true);
        cameraName.setGravity(Gravity.CENTER);
        top.addView(cameraName, new LinearLayout.LayoutParams(0, dp(50), 1));
        boolean showingActiveRecording = radioPreviewCamera == 3 ?
                (preferences.getBoolean("record_camera_0", false) || preferences.getBoolean("record_camera_1", false)) :
                isSelectedCameraRecording();
        TextView recording = text(showingActiveRecording ? "● REC" : "STANDBY", 17,
                showingActiveRecording ? Color.rgb(255, 70, 70) : TEXT, true);
        recording.setGravity(Gravity.CENTER);
        top.addView(recording, new LinearLayout.LayoutParams(dp(120), dp(50)));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(66), Gravity.TOP);
        root.addView(top, topParams);


        LinearLayout cameras = new LinearLayout(this);
        cameras.setOrientation(LinearLayout.HORIZONTAL);
        cameras.setPadding(dp(6), dp(6), dp(6), dp(6));
        cameras.setBackground(rounded(Color.argb(205, 7, 17, 31), dp(16)));
        addCameraChoice(cameras, "Front", 0);
        addCameraChoice(cameras, "DVR 2", 2);
        addCameraChoice(cameras, "Reverse", 1);
        addCameraChoice(cameras, "Split", 3);
        FrameLayout.LayoutParams choiceParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(54), Gravity.TOP | Gravity.END);
        choiceParams.topMargin = dp(74);
        choiceParams.rightMargin = dp(14);
        root.addView(cameras, choiceParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.END);
        if (!isOwnerLockEnabled() || ownerUnlocked ||
                StockDvrAccessGuard.isSessionUnlocked(this)) {
            addCameraOverlayAction(actions, isSelectedCameraRecording() ? "■  STOP" : "●  REC",
                    isSelectedCameraRecording() ? RED : TEXT, new View.OnClickListener() {
                        @Override public void onClick(View view) { toggleSelectedCameraRecording(); }
                    });
            addCameraOverlayAction(actions, "◎  PHOTO", TEXT, new View.OnClickListener() {
                @Override public void onClick(View view) { captureSelectedPhoto(); }
            });
            addCameraOverlayAction(actions, "◆  LOCK", TEXT, new View.OnClickListener() {
                @Override public void onClick(View view) { protectSelectedClip(); }
            });
        } else {
            addCameraOverlayAction(actions, "🔒  OWNER", AMBER, new View.OnClickListener() {
                @Override public void onClick(View view) { showOwnerUnlock(RADIO_DVR); }
            });
        }
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                dp(142), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
        actionParams.rightMargin = dp(14);
        actionParams.bottomMargin = dp(86);
        root.addView(actions, actionParams);

        root.addView(buildRadioBottomTabs(RADIO_DVR), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM));

        drawerOverlay = buildDrawer(true);
        root.addView(drawerOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private View buildDirectRadioPreview(int selection) {
        if (selection == 3) {
            LinearLayout split = new LinearLayout(this);
            split.setOrientation(LinearLayout.HORIZONTAL);
            split.setBackgroundColor(Color.BLACK);
            split.addView(directPreviewPanel(radioDvrCamera, dvrCameraName(radioDvrCamera)), new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            split.addView(directPreviewPanel(1, "REVERSE"), new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            return split;
        }
        return directPreviewPanel(selection, dvrCameraName(selection));
    }

    private View directPreviewPanel(final int cameraId, String label) {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.BLACK);
        final SurfaceView surface = new SurfaceView(this);
        panel.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView name = text(label, 13, TEXT, true);
        name.setPadding(dp(10), dp(5), dp(10), dp(5));
        name.setBackground(rounded(Color.argb(165, 0, 0, 0), dp(9)));
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.TOP);
        nameParams.leftMargin = dp(12);
        nameParams.topMargin = dp(78);
        panel.addView(name, nameParams);

        final TextView watermark = text("", 14, Color.WHITE, true);
        watermark.setPadding(dp(10), dp(7), dp(10), dp(7));
        watermark.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        watermark.setBackground(rounded(Color.argb(175, 0, 0, 0), dp(10)));
        FrameLayout.LayoutParams watermarkParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.BOTTOM);
        watermarkParams.leftMargin = dp(12);
        watermarkParams.bottomMargin = dp(14);
        panel.addView(watermark, watermarkParams);
        final Runnable updateWatermark = new Runnable() {
            @Override public void run() {
                CameraStreamService service = CameraStreamService.getRunningInstance();
                watermark.setText(service == null ? "" : service.currentWatermarkText());
                watermark.setVisibility(watermark.getText().length() == 0 ? View.GONE : View.VISIBLE);
                if (panel.isAttachedToWindow() && RADIO_DVR.equals(currentPage)) panel.postDelayed(this, 1000L);
            }
        };
        panel.post(updateWatermark);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { attachPreviewWhenReady(surface, holder, cameraId, 0); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                attachPreviewWhenReady(surface, holder, cameraId, 0);
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                CameraStreamService service = CameraStreamService.getRunningInstance();
                if (service != null) service.detachRadioPreview(holder, cameraId);
            }
        });
        return panel;
    }

    private void attachPreviewWhenReady(final View anchor, final SurfaceHolder holder,
                                        final int cameraId, final int attempt) {
        if (!holder.getSurface().isValid()) return;
        CameraStreamService service = CameraStreamService.getRunningInstance();
        if (service != null) {
            service.attachRadioPreview(holder, cameraId);
        } else if (attempt < 30) {
            anchor.postDelayed(new Runnable() {
                @Override public void run() { attachPreviewWhenReady(anchor, holder, cameraId, attempt + 1); }
            }, 100);
        }
    }

    private void addCameraChoice(LinearLayout row, String label, final int cameraId) {
        boolean selected = radioPreviewCamera == cameraId ||
                (radioPreviewCamera == 3 && (cameraId == 3 || cameraId == radioDvrCamera));
        TextView button = text(label, 13, TEXT, selected);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(selected ? PRIMARY : Color.TRANSPARENT, dp(11)));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (cameraId == 0 || cameraId == 2) {
                    radioDvrCamera = cameraId;
                    if (radioPreviewCamera != 3) radioPreviewCamera = cameraId;
                } else if (cameraId == 3) {
                    radioPreviewCamera = radioPreviewCamera == 3 ? radioDvrCamera : 3;
                } else {
                    radioPreviewCamera = cameraId;
                }
                preferences.edit()
                        .putInt("dvr_primary_camera", radioDvrCamera)
                        .putInt("dvr_preview_camera", radioPreviewCamera).apply();
                showRadioDvr();
            }
        });
        row.addView(button, new LinearLayout.LayoutParams(dp(82), LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private void addCameraOverlayAction(LinearLayout column, String label, int color, View.OnClickListener action) {
        TextView button = text(label, 13, color, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(Color.argb(210, 10, 24, 42), dp(13)));
        button.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        params.topMargin = dp(7);
        column.addView(button, params);
    }

    private void captureSelectedPhoto() {
        Intent intent = new Intent(this, CameraStreamService.class);
        intent.setAction(CameraStreamService.ACTION_CAPTURE);
        intent.putExtra("camera_id", radioPreviewCamera);
        intent.putExtra("primary_camera_id", radioDvrCamera);
        startService(intent);
        toast(radioPreviewCamera == 3 ? "Saving " + dvrCameraName(radioDvrCamera) + " and reverse photos to USB." :
                "Saving a " + dvrCameraName(radioPreviewCamera) + " photo to USB.");
    }

    private void protectSelectedClip() {
        Intent intent = new Intent(this, CameraStreamService.class);
        intent.setAction(CameraStreamService.ACTION_PROTECT);
        intent.putExtra("camera_id", radioPreviewCamera);
        startService(intent);
        toast(radioPreviewCamera == 3 ? "Protecting both current clips." : "Protecting the current clip.");
    }

    private String localRadioUrl(String path) {
        return "http://127.0.0.1:" + CameraStreamService.PORT + path;
    }

    private LinearLayout buildRadioBottomTabs(String active) {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(8), dp(5), dp(8), dp(5));
        tabs.setBackgroundColor(APP_BAR);
        addRadioTab(tabs, "◉\nCamera", RADIO_DVR, active, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioDvr(); }
        });
        addRadioTab(tabs, "▶\nVideos", RADIO_VIDEOS, active, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioVideos(); }
        });
        addRadioTab(tabs, "▣\nPhotos", RADIO_PHOTOS, active, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioPhotos(); }
        });
        addRadioTab(tabs, "⚙\nSettings", RADIO_RECORDING, active, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioRecording(); }
        });
        return tabs;
    }

    private void addRadioTab(LinearLayout tabs, String label, String id, String active,
                             View.OnClickListener listener) {
        boolean selected = id.equals(active) || (RADIO_RECORDING.equals(id) &&
                !RADIO_DVR.equals(active) && !RADIO_VIDEOS.equals(active) && !RADIO_PHOTOS.equals(active));
        TextView item = text(label, 13, selected ? PRIMARY : MUTED, selected);
        item.setGravity(Gravity.CENTER);
        item.setBackground(rounded(selected ? Color.argb(70, 43, 125, 255) : Color.TRANSPARENT, dp(12)));
        item.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        tabs.addView(item, params);
    }

    private void showRadioVideos() {
        if (requireOwnerAccess(RADIO_VIDEOS)) return;
        currentPage = RADIO_VIDEOS;
        LinearLayout page = newPage();
        LinearLayout intro = card();
        addCardHeader(intro, "Saved videos", "NATIVE PLAYER", GREEN);
        addBody(intro, "Front, Reverse, DVR Camera 2, and protected clips on the USB drive. Tap a clip to play it with native seek, pause, and volume controls.");
        addAction(intro, "Refresh library", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioVideos(); }
        });
        page.addView(intro, cardParams());
        List<File> files = findLocalMedia(false);
        if (files.isEmpty()) {
            LinearLayout empty = card();
            addCardTitle(empty, "No completed videos yet");
            addBody(empty, "The clip currently being recorded appears here after its loop segment finishes.");
            page.addView(empty, cardParams());
        } else {
            for (final File file : files) {
                LinearLayout item = card();
                addCardTitle(item, file.getName());
                addKeyValue(item, "Recorded", DateFormat.getDateTimeInstance().format(new Date(file.lastModified())));
                addKeyValue(item, "Size", readableSize(file.length()));
                final boolean protectedVideo = isProtectedVideo(file);
                addKeyValue(item, "Type", protectedVideo ? "Emergency / protected" : "Daily recording");
                addAction(item, "Play video", PRIMARY, new View.OnClickListener() {
                    @Override public void onClick(View view) { showNativeVideoPlayer(file, null); }
                });
                addAction(item, protectedVideo ? "Unlock video" : "Protect video",
                        protectedVideo ? SURFACE_2 : RED, new View.OnClickListener() {
                    @Override public void onClick(View view) { setLibraryFileProtected(file, !protectedVideo); }
                });
                addAction(item, "Delete video", SURFACE_2, new View.OnClickListener() {
                    @Override public void onClick(View view) { confirmDeleteMedia(file, false); }
                });
                page.addView(item, cardParams());
            }
        }
        showShell("Videos", true, page);
    }

    private void showRadioPhotos() {
        if (requireOwnerAccess(RADIO_PHOTOS)) return;
        currentPage = RADIO_PHOTOS;
        LinearLayout page = newPage();
        LinearLayout intro = card();
        addCardHeader(intro, "Saved photos", "USB GALLERY", GREEN);
        addBody(intro, "Photos captured from the Camera tab are displayed directly from the USB drive.");
        addAction(intro, "Take photo now", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { captureSelectedPhoto(); }
        });
        addAction(intro, "Refresh gallery", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioPhotos(); }
        });
        page.addView(intro, cardParams());
        List<File> files = findLocalMedia(true);
        if (files.isEmpty()) {
            LinearLayout empty = card();
            addCardTitle(empty, "No photos yet");
            addBody(empty, "Use Photo on the Camera tab, then refresh this gallery.");
            page.addView(empty, cardParams());
        } else {
            for (final File file : files) {
                LinearLayout item = card();
                ImageView preview = new ImageView(this);
                preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                preview.setImageURI(Uri.fromFile(file));
                item.addView(preview, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(210)));
                addCardTitle(item, file.getName());
                addAction(item, "Open photo", PRIMARY, new View.OnClickListener() {
                    @Override public void onClick(View view) { showNativePhoto(file); }
                });
                addAction(item, "Delete photo", SURFACE_2, new View.OnClickListener() {
                    @Override public void onClick(View view) { confirmDeleteMedia(file, true); }
                });
                page.addView(item, cardParams());
            }
        }
        showShell("Photos", true, page);
    }

    private List<File> findLocalMedia(boolean photos) {
        ArrayList<File> result = new ArrayList<File>();
        String type = photos ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES;
        File[] roots = getExternalFilesDirs(type);
        if (roots != null) for (File root : roots) collectMedia(root, photos, result, 0);
        if (!photos) collectMedia(new File("/storage/udisk1/dvr"), false, result, 0);
        Collections.sort(result, new Comparator<File>() {
            @Override public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        if (result.size() > 100) return new ArrayList<File>(result.subList(0, 100));
        return result;
    }

    private void collectMedia(File directory, boolean photos, List<File> output, int depth) {
        if (directory == null || depth > 5 || !directory.canRead()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collectMedia(file, photos, output, depth + 1);
            else {
                String name = file.getName().toLowerCase(java.util.Locale.US);
                if (photos ? (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))
                        : (name.endsWith(".mp4") && !name.contains("_recording"))) output.add(file);
            }
        }
    }

    private String readableSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024f * 1024f * 1024f));
        if (bytes >= 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        return Math.max(1, bytes / 1024) + " KB";
    }

    private boolean isProtectedVideo(File file) {
        String path = file.getAbsolutePath().replace('\\', '/').toLowerCase(java.util.Locale.US);
        String name = file.getName().toLowerCase(java.util.Locale.US);
        return path.contains("/protected/") || path.contains("/emergency/") ||
                name.startsWith("event-") || name.contains("_locked");
    }

    private void setLibraryFileProtected(File file, boolean protect) {
        File parent = file.getParentFile();
        if (parent == null) return;
        File destinationDirectory;
        String name = file.getName();
        if (protect) {
            if (file.getAbsolutePath().replace('\\', '/').contains("/dvr/daily/")) {
                destinationDirectory = new File("/storage/udisk1/dvr/emergency");
            } else {
                destinationDirectory = new File(parent, "Protected");
                if (!name.startsWith("EVENT-")) name = "EVENT-" + name;
            }
        } else {
            if ("emergency".equalsIgnoreCase(parent.getName())) destinationDirectory = new File(parent.getParentFile(), "daily");
            else destinationDirectory = parent.getParentFile();
            if (name.startsWith("EVENT-")) name = name.substring(6);
            name = name.replace("_locked.mp4", ".mp4");
        }
        if (destinationDirectory == null || (!destinationDirectory.exists() && !destinationDirectory.mkdirs())) {
            toast("The destination folder could not be created.");
            return;
        }
        if (file.renameTo(new File(destinationDirectory, name))) {
            toast(protect ? "Video protected from loop cleanup." : "Video returned to daily recordings.");
            showRadioVideos();
        } else {
            toast("The USB drive did not allow that change.");
        }
    }

    private void confirmDeleteMedia(final File file, final boolean photo) {
        new AlertDialog.Builder(this)
                .setTitle(photo ? "Delete photo?" : "Delete video?")
                .setMessage(file.getName() + " will be permanently removed from the USB drive.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (file.delete()) {
                            toast(photo ? "Photo deleted." : "Video deleted.");
                            if (photo) showRadioPhotos(); else showRadioVideos();
                        } else toast("The USB drive did not allow deletion.");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showNativePhoto(final File file) {
        currentPage = RADIO_PHOTOS;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageURI(Uri.fromFile(file));
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        imageParams.bottomMargin = dp(72);
        root.addView(image, imageParams);
        TextView close = text("‹  BACK TO PHOTOS", 14, TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(Color.argb(210, 10, 24, 42), dp(13)));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioPhotos(); }
        });
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(190), dp(48), Gravity.TOP | Gravity.START);
        closeParams.leftMargin = dp(14);
        closeParams.topMargin = dp(14);
        root.addView(close, closeParams);
        TextView delete = text("DELETE PHOTO", 14, RED, true);
        delete.setGravity(Gravity.CENTER);
        delete.setBackground(rounded(Color.argb(210, 10, 24, 42), dp(13)));
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { confirmDeleteMedia(file, true); }
        });
        FrameLayout.LayoutParams deleteParams = new FrameLayout.LayoutParams(dp(170), dp(48), Gravity.TOP | Gravity.END);
        deleteParams.rightMargin = dp(14);
        deleteParams.topMargin = dp(14);
        root.addView(delete, deleteParams);
        root.addView(buildRadioBottomTabs(RADIO_PHOTOS), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM));
        setContentView(root);
    }

    private String dvrCameraName(int cameraId) {
        if (cameraId == 3) return (radioDvrCamera == 2 ? "DVR CAMERA 2" : "FRONT DVR") + " + REVERSE";
        if (cameraId == 2) return "DVR CAMERA 2";
        if (cameraId == 1) return "REVERSE CAMERA";
        return "FRONT DVR";
    }

    private boolean isSelectedCameraRecording() {
        if (radioPreviewCamera == 3) {
            return preferences.getBoolean("record_camera_0", false) &&
                    preferences.getBoolean("record_camera_1", false);
        }
        return preferences.getBoolean(radioPreviewCamera == 1 ? "record_camera_1" : "record_camera_0", false);
    }

    private void toggleSelectedCameraRecording() {
        boolean enable = !isSelectedCameraRecording();
        if (radioPreviewCamera == 3) {
            preferences.edit().putBoolean("record_camera_0", enable)
                    .putBoolean("record_camera_1", enable)
                    .putBoolean("primary_dvr_mode", true).apply();
            Intent front = new Intent(this, CameraStreamService.class);
            front.setAction(enable ? CameraStreamService.ACTION_FRONT_RECORD_START : CameraStreamService.ACTION_FRONT_RECORD_STOP);
            front.putExtra("camera_id", radioDvrCamera);
            Intent reverse = new Intent(this, CameraStreamService.class);
            reverse.setAction(enable ? CameraStreamService.ACTION_RECORD_START : CameraStreamService.ACTION_RECORD_STOP);
            if (enable) {
                startForegroundService(front);
                startForegroundService(reverse);
            } else {
                startService(front);
                startService(reverse);
            }
            toast(dvrCameraName(radioDvrCamera) + " and reverse recording " + (enable ? "started." : "stopped."));
            showRadioDvr();
            return;
        }
        String key = radioPreviewCamera == 1 ? "record_camera_1" : "record_camera_0";
        preferences.edit().putBoolean(key, enable)
                .putBoolean("primary_dvr_mode", true).apply();
        Intent intent = new Intent(this, CameraStreamService.class);
        intent.putExtra("camera_id", radioPreviewCamera);
        intent.setAction(radioPreviewCamera == 1 ?
                (enable ? CameraStreamService.ACTION_RECORD_START : CameraStreamService.ACTION_RECORD_STOP) :
                (enable ? CameraStreamService.ACTION_FRONT_RECORD_START : CameraStreamService.ACTION_FRONT_RECORD_STOP));
        if (enable) startForegroundService(intent); else startService(intent);
        toast(dvrCameraName(radioPreviewCamera) + (enable ? " recording started." : " recording stopped."));
        showRadioDvr();
    }

    private void addDvrDockButton(LinearLayout dock, String label, int color, View.OnClickListener listener) {
        TextView button = text(label, 14, color, true);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        dock.addView(button, params);
    }

    private void showRadioNetwork() {
        if (requireOwnerAccess(RADIO_NETWORK)) return;
        currentPage = RADIO_NETWORK;
        LinearLayout page = newPage();
        String tailIp = findIpv4Address(true);

        LinearLayout card = card();
        addCardHeader(card, "Private Internet connection",
                tailIp.length() > 0 ? "CONNECTED" : "ACTION NEEDED",
                tailIp.length() > 0 ? GREEN : AMBER);
        addBody(card, "The radio's SIM uses carrier NAT. Tailscale creates the private route that lets the phone reach Camera Link from anywhere.");
        addKeyValue(card, "Tailscale", isPackageInstalled(TAILSCALE_PACKAGE) ? "Installed" : "Included and ready to install");
        addKeyValue(card, "Phone address", tailIp.length() > 0 ? tailIp + ":" + CameraStreamService.PORT : "Available after Tailscale sign-in");
        String connectionPin = CameraStreamService.getConnectionPassword(this);
        addKeyValue(card, "App connection PIN", connectionPin);
        addKeyValue(card, "Website username", CameraStreamService.USERNAME);
        addKeyValue(card, "Website password", connectionPin);
        addAction(card, "Install or open Tailscale", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { openTailscale(); }
        });
        addAction(card, "Open VPN settings", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { openVpnSettings(); }
        });
        addAction(card, "Copy phone connection details", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { copyConnectionDetails(); }
        });
        status = addStatus(card, tailIp.length() > 0 ?
                "Ready for phone setup. Keep Tailscale Always-on on the radio." :
                "Open Tailscale, sign in, then return here. The phone address will appear automatically.");
        page.addView(card, cardParams());

        if (tailIp.length() > 0) {
            LinearLayout qr = card();
            addCardHeader(qr, "Phone auto setup", "SCAN QR", GREEN);
            addBody(qr, "After Tailscale is ready on the phone, open Camera Link > Connection and scan this code. The private radio address and app PIN are filled in automatically.");
            String setupUri = setupUri(tailIp, connectionPin);
            Bitmap code = createQrCode(setupUri, 640);
            if (code != null) {
                ImageView image = new ImageView(this);
                image.setImageBitmap(code);
                image.setBackgroundColor(Color.WHITE);
                image.setPadding(dp(10), dp(10), dp(10), dp(10));
                image.setContentDescription("Phone setup QR code");
                LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(300), dp(300));
                imageParams.gravity = Gravity.CENTER_HORIZONTAL;
                imageParams.topMargin = dp(10);
                qr.addView(image, imageParams);
            }
            page.addView(qr, cardParams());
        }

        LinearLayout keepAlive = card();
        addCardTitle(keepAlive, "Stay connected after restart");
        addBody(keepAlive, "In Android VPN settings, choose Tailscale as Always-on. If available, enable Block connections without VPN. Camera Link's server already starts at boot.");
        long lastBoot = preferences.getLong("last_boot_start", 0);
        addKeyValue(keepAlive, "Last automatic start", lastBoot == 0 ? "Waiting for next restart" : DateFormat.getDateTimeInstance().format(new Date(lastBoot)));
        page.addView(keepAlive, cardParams());

        showShell("Internet & Tailscale", true, page);
    }

    private void showRadioRecording() {
        if (requireOwnerAccess(RADIO_RECORDING)) return;
        currentPage = RADIO_RECORDING;
        LinearLayout page = newPage();

        LinearLayout front = card();
        boolean libraryAccess = hasDvrLibraryAccess();
        boolean primary = preferences.getBoolean("primary_dvr_mode", false);
        boolean frontEnabled = preferences.getBoolean("record_camera_0", false);
        addCardHeader(front, "Primary front DVR", primary && frontEnabled ? "RECORDING" : "SETUP", primary && frontEnabled ? GREEN : AMBER);
        addBody(front, "Camera Link owns camera 0, records one-minute H.264 clips, and keeps the same live DVR screen available on the radio and phone.");
        addKeyValue(front, "USB folder", "/storage/udisk1/dvr/daily");
        addAction(front, libraryAccess ? "DVR library access granted" : "Allow DVR library access", libraryAccess ? SURFACE_2 : PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { openDvrLibraryAccess(); }
        });
        addAction(front, "Enable primary DVR and start front recording", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) {
                preferences.edit().putBoolean("primary_dvr_mode", true)
                        .putBoolean("record_camera_0", true).apply();
                Intent intent = new Intent(MainActivity.this, CameraStreamService.class);
                intent.setAction(CameraStreamService.ACTION_FRONT_RECORD_START);
                intent.putExtra("camera_id", 0);
                startForegroundService(intent);
                radioPreviewCamera = 0;
                showRadioDvr();
            }
        });
        addAction(front, "Stop front recording", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this, CameraStreamService.class);
                intent.setAction(CameraStreamService.ACTION_FRONT_RECORD_STOP);
                startService(intent);
                preferences.edit().putBoolean("record_camera_0", false).apply();
                showRadioRecording();
            }
        });
        page.addView(front, cardParams());

        LinearLayout loop = card();
        final int loopMinutes = preferences.getInt("loop_minutes", 1);
        addCardHeader(loop, "Loop recording", loopMinutes + " MIN", GREEN);
        addBody(loop, "Choose the length of each front and reverse MP4 segment. Protected clips are moved into a Protected folder and are excluded from automatic cleanup.");
        addAction(loop, "1 minute", loopMinutes == 1 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setLoopMinutes(1); }
        });
        addAction(loop, "2 minutes", loopMinutes == 2 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setLoopMinutes(2); }
        });
        addAction(loop, "3 minutes", loopMinutes == 3 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setLoopMinutes(3); }
        });
        addBody(loop, "Photo and emergency-lock controls save to the USB drive.");
        page.addView(loop, cardParams());

        addUsbStorageSettings(page);

        LinearLayout audioCard = card();
        final boolean audioEnabled = preferences.getBoolean("record_audio", true);
        addCardHeader(audioCard, "Cabin microphone", audioEnabled ? "ON" : "OFF", audioEnabled ? GREEN : MUTED);
        addBody(audioCard, "Records cabin sound into new Front and Reverse clips. This is also the master privacy switch for live vehicle sound on the phone.");
        addAction(audioCard, audioEnabled ? "Turn microphone recording off" : "Turn microphone recording on",
                audioEnabled ? SURFACE_2 : PRIMARY, new View.OnClickListener() {
                    @Override public void onClick(View view) { setRecordedAudioEnabled(!audioEnabled); }
                });
        page.addView(audioCard, cardParams());

        addWatermarkSettings(page);

        addEmergencySettings(page);

        LinearLayout display = card();
        int defaultCamera = preferences.getInt("dvr_preview_camera", 0);
        addCardHeader(display, "Default camera display", dvrCameraName(defaultCamera), GREEN);
        addBody(display, "Chooses the native camera view shown when Camera Link opens.");
        addAction(display, "Front DVR", defaultCamera == 0 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setDefaultRadioCamera(0); }
        });
        addAction(display, "DVR Camera 2", defaultCamera == 2 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setDefaultRadioCamera(2); }
        });
        addAction(display, "Reverse camera", defaultCamera == 1 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setDefaultRadioCamera(1); }
        });
        addAction(display, dvrCameraName(radioDvrCamera) + " + Reverse split", defaultCamera == 3 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setDefaultRadioCamera(3); }
        });
        page.addView(display, cardParams());

        LinearLayout dvr2 = card();
        addCardHeader(dvr2, "DVR Camera 2", "SHARED INPUT", MUTED);
        addBody(dvr2, "The factory app confirms that Front and DVR Camera 2 share camera 0 through ATOTO's video mux. Selecting DVR Camera 2 switches that decoder, so it cannot record at the same instant as Front. Reverse camera 1 remains independent.");
        addAction(dvr2, "Open DVR Camera 2", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) {
                radioPreviewCamera = 2;
                radioDvrCamera = 2;
                preferences.edit().putInt("dvr_preview_camera", 2)
                        .putInt("dvr_primary_camera", 2).apply();
                showRadioDvr();
            }
        });
        page.addView(dvr2, cardParams());

        LinearLayout reverse = card();
        boolean enabled = preferences.getBoolean("record_camera_1", false);
        addCardHeader(reverse, "Reverse camera recording", enabled ? "ENABLED" : "DISABLED", enabled ? GREEN : MUTED);
        addBody(reverse, "Camera 1 records independently beside the front DVR. Clips are 1280x720 H.264 MP4 and use the removable USB drive.");
        addKeyValue(reverse, "Storage", findRemovableStorage());
        addAction(reverse, "Enable automatic recording", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this, CameraStreamService.class);
                intent.setAction(CameraStreamService.ACTION_RECORD_START);
                startForegroundService(intent);
                toast("Reverse camera recording enabled.");
                showRadioRecording();
            }
        });
        addAction(reverse, "Disable automatic recording", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this, CameraStreamService.class);
                intent.setAction(CameraStreamService.ACTION_RECORD_STOP);
                startService(intent);
                toast("Reverse camera recording disabled.");
                showRadioRecording();
            }
        });
        page.addView(reverse, cardParams());

        LinearLayout integration = card();
        boolean factoryReady = CameraStreamService.getRunningInstance() != null &&
                CameraStreamService.getRunningInstance().isFactoryDvrIntegrationReady();
        addCardHeader(integration, "ATOTO factory DVR integration",
                isFactoryDvrHost() ? "BUILT IN" : factoryReady ? "CONNECTED" : "CHECKING",
                isFactoryDvrHost() || factoryReady ? GREEN : AMBER);
        addBody(integration, isFactoryDvrHost() ?
                "Remote streaming, reverse recording, pairing, storage tools, and the enhanced settings menu are installed inside ATOTO's stock DVR package. The stock camera pipeline remains the owner of Front and DVR Camera 2." :
                "The factory DVR keeps ownership of the shared Front/DVR Camera 2 input. Camera Link adds the independent reverse recording and private remote streams without stopping the factory DVR.");
        addKeyValue(integration, "Front / DVR Camera 2", "Factory DVR shared input");
        addKeyValue(integration, "Reverse camera", "Independent Camera Link input");
        addKeyValue(integration, "Factory parking settings", "Unchanged and controlled only by ATOTO DVR");
        if (!isFactoryDvrHost()) {
            addAction(integration, factoryReady ? "Reinstall/update factory DVR integration" :
                    "Install factory DVR integration", factoryReady ? SURFACE_2 : PRIMARY,
                    new View.OnClickListener() {
                @Override public void onClick(View view) { installBundledFactoryDvrIntegration(); }
            });
        }
        page.addView(integration, cardParams());
        showShell("DVR Settings", true, page);
    }

    private void setLoopMinutes(int minutes) {
        preferences.edit().putInt("loop_minutes", minutes).apply();
        toast("New clips will use " + minutes + " minute segments.");
        showRadioRecording();
    }

    private void syncParkingSystemSetting() {
        // The platform-signed factory DVR owns sentry configuration and the
        // MCU heartbeat. Camera Link only observes that vendor-managed state.
    }

    private void addUsbStorageSettings(LinearLayout page) {
        final File usb = findDvrUsbRoot();
        final boolean ready = usb != null && usb.exists() && usb.canRead();
        final String policy = preferences.getString("storage_policy", "loop");
        final int allocation = preferences.getInt("storage_limit_percent", 90);
        LinearLayout storage = card();
        addCardHeader(storage, "DVR USB storage", ready ? "CONNECTED" : "NOT FOUND", ready ? GREEN : AMBER);
        if (ready) {
            long total = usb.getTotalSpace();
            long free = usb.getUsableSpace();
            long used = Math.max(0, total - free);
            long daily = folderSize(new File(usb, "dvr/daily"), 0) + cameraLinkFolderSize(Environment.DIRECTORY_MOVIES);
            long emergency = folderSize(new File(usb, "dvr/emergency"), 0);
            long photos = folderSize(new File(usb, "dvr/photo"), 0) + cameraLinkFolderSize(Environment.DIRECTORY_PICTURES);
            addKeyValue(storage, "USB drive", usb.getAbsolutePath());
            addKeyValue(storage, "Capacity", readableSize(total));
            addKeyValue(storage, "Used / available", readableSize(used) + " / " + readableSize(free));
            addKeyValue(storage, "Daily video", readableSize(daily));
            addKeyValue(storage, "Emergency video", readableSize(emergency));
            addKeyValue(storage, "Photos", readableSize(photos));
        } else {
            addBody(storage, "Insert the DVR flash drive into the special ATOTO USB port, then refresh this page.");
        }
        addCardHeader(storage, "Recording storage policy",
                "until_full".equals(policy) ? "UNTIL FULL" : "LOOP", GREEN);
        addBody(storage, "Loop mode removes the oldest unprotected daily clips when the selected DVR allocation or the 1 GB safety reserve is reached. Until Full never deletes clips and stops recording at the safety reserve.");
        addAction(storage, "Loop recording", "loop".equals(policy) ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setStoragePolicy("loop"); }
        });
        addAction(storage, "Record until drive is full", "until_full".equals(policy) ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setStoragePolicy("until_full"); }
        });
        if ("loop".equals(policy)) {
            addKeyValue(storage, "Maximum DVR allocation", allocation + "% of USB capacity");
            addAction(storage, "Use up to 50%", allocation == 50 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { setStorageAllocation(50); }
            });
            addAction(storage, "Use up to 75%", allocation == 75 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { setStorageAllocation(75); }
            });
            addAction(storage, "Use up to 90%", allocation == 90 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { setStorageAllocation(90); }
            });
            addAction(storage, "Use all available space", allocation == 100 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { setStorageAllocation(100); }
            });
        }
        addAction(storage, "Refresh USB information", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioRecording(); }
        });
        addAction(storage, "Safely eject DVR USB", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { confirmEjectDvrUsb(); }
        });
        addAction(storage, "Format DVR USB", RED, new View.OnClickListener() {
            @Override public void onClick(View view) { confirmFormatDvrUsb(); }
        });
        page.addView(storage, cardParams());
    }

    private void setStoragePolicy(String policy) {
        preferences.edit().putString("storage_policy", policy).apply();
        toast("until_full".equals(policy) ?
                "Recordings will stop at the 1 GB safety reserve." :
                "Loop cleanup will remove the oldest unprotected clips.");
        showRadioRecording();
    }

    private void setStorageAllocation(int percent) {
        preferences.edit().putInt("storage_limit_percent", percent).apply();
        toast("DVR recordings may use up to " + percent + "% of the USB drive.");
        showRadioRecording();
    }

    private void addWatermarkSettings(LinearLayout page) {
        final boolean enabled = preferences.getBoolean("watermark_enabled", true);
        final boolean date = preferences.getBoolean("watermark_datetime", true);
        final boolean speed = preferences.getBoolean("watermark_speed", true);
        final boolean gps = preferences.getBoolean("watermark_gps", true);
        LinearLayout watermark = card();
        addCardHeader(watermark, "Video watermark", enabled ? "ON" : "OFF", enabled ? GREEN : MUTED);
        addBody(watermark, "Burns the selected details into Front, DVR Camera 2, and Reverse recordings and remote live video. The radio live screen shows the same overlay.");
        addAction(watermark, enabled ? "Turn watermark off" : "Turn watermark on",
                enabled ? SURFACE_2 : PRIMARY, new View.OnClickListener() {
                    @Override public void onClick(View view) { setWatermarkOption("watermark_enabled", !enabled); }
                });
        addAction(watermark, "Date & time: " + (date ? "ON" : "OFF"), date ? PRIMARY : SURFACE_2,
                new View.OnClickListener() {
                    @Override public void onClick(View view) { setWatermarkOption("watermark_datetime", !date); }
                });
        addAction(watermark, "Vehicle speed: " + (speed ? "ON" : "OFF"), speed ? PRIMARY : SURFACE_2,
                new View.OnClickListener() {
                    @Override public void onClick(View view) { setWatermarkOption("watermark_speed", !speed); }
                });
        addAction(watermark, "GPS coordinates: " + (gps ? "ON" : "OFF"), gps ? PRIMARY : SURFACE_2,
                new View.OnClickListener() {
                    @Override public void onClick(View view) { setWatermarkOption("watermark_gps", !gps); }
                });
        boolean locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        addKeyValue(watermark, "Location access", locationGranted ? "Allowed" : "Needed for speed and GPS");
        if (!locationGranted && enabled && (speed || gps)) {
            addAction(watermark, "Allow GPS access", PRIMARY, new View.OnClickListener() {
                @Override public void onClick(View view) { requestWatermarkLocationPermission(); }
            });
        }
        page.addView(watermark, cardParams());
    }

    private void setWatermarkOption(String key, boolean enabled) {
        preferences.edit().putBoolean(key, enabled).apply();
        if (enabled && ("watermark_enabled".equals(key) || "watermark_speed".equals(key) || "watermark_gps".equals(key)) &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestWatermarkLocationPermission();
            return;
        }
        CameraStreamService service = CameraStreamService.getRunningInstance();
        if (service != null) service.refreshWatermarkSettings();
        toast("Watermark settings updated.");
        showRadioRecording();
    }

    private void requestWatermarkLocationPermission() {
        preferences.edit().putBoolean("watermark_location_asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
    }

    private void addEmergencySettings(LinearLayout page) {
        final int sensitivity = preferences.getInt("emergency_sensitivity", 3);
        final int duration = preferences.getInt("emergency_duration_seconds", 30);
        String level = sensitivity == 0 ? "OFF" : sensitivity == 1 ? "LOW" :
                sensitivity == 2 ? "MEDIUM" : "HIGH";
        LinearLayout emergency = card();
        addCardHeader(emergency, "Emergency record lock", level, sensitivity == 0 ? MUTED : RED);
        addBody(emergency, "Uses the ATOTO MCU G-sensor and Android impact sensor. A detected impact protects the active clip and the selected post-impact duration for Front and Reverse recording.");
        addKeyValue(emergency, "Lock duration", duration == 30 ? "30 seconds" : duration == 60 ? "1 minute" : "3 minutes");
        addAction(emergency, "Off", sensitivity == 0 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setEmergencySensitivity(0); }
        });
        addAction(emergency, "Low sensitivity", sensitivity == 1 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setEmergencySensitivity(1); }
        });
        addAction(emergency, "Medium sensitivity", sensitivity == 2 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setEmergencySensitivity(2); }
        });
        addAction(emergency, "High sensitivity", sensitivity == 3 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setEmergencySensitivity(3); }
        });
        addAction(emergency, "30 second emergency clip", duration == 30 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setEmergencyDuration(30); }
        });
        addAction(emergency, "1 minute emergency clip", duration == 60 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setEmergencyDuration(60); }
        });
        addAction(emergency, "3 minute emergency clip", duration == 180 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { setEmergencyDuration(180); }
        });
        if (sensitivity > 0) addAction(emergency, "Test emergency lock now", RED, new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this, CameraStreamService.class);
                intent.setAction(CameraStreamService.ACTION_EMERGENCY_LOCK);
                startForegroundService(intent);
                toast("Emergency recording triggered. Active clips are being protected.");
            }
        });
        page.addView(emergency, cardParams());
    }

    private void setEmergencySensitivity(int sensitivity) {
        preferences.edit().putInt("emergency_sensitivity", sensitivity).apply();
        toast(sensitivity == 0 ? "Automatic emergency locking is off." : "Emergency sensitivity updated.");
        showRadioRecording();
    }

    private void setEmergencyDuration(int seconds) {
        preferences.edit().putInt("emergency_duration_seconds", seconds).apply();
        toast("Emergency lock duration updated.");
        showRadioRecording();
    }

    private void setDefaultRadioCamera(int cameraId) {
        radioPreviewCamera = cameraId;
        if (cameraId == 0 || cameraId == 2) radioDvrCamera = cameraId;
        preferences.edit().putInt("dvr_preview_camera", cameraId)
                .putInt("dvr_primary_camera", radioDvrCamera).apply();
        toast(dvrCameraName(cameraId) + " is the default display.");
        showRadioRecording();
    }

    private void setRecordedAudioEnabled(boolean enabled) {
        if (enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
            return;
        }
        preferences.edit().putBoolean("record_audio", enabled).apply();
        Intent intent = new Intent(this, CameraStreamService.class);
        intent.setAction(CameraStreamService.ACTION_AUDIO_CHANGED);
        startService(intent);
        toast(enabled ? "Cabin microphone will be recorded in front and reverse clips." :
                "Cabin microphone recording is off.");
        if (RADIO_DVR.equals(currentPage)) showRadioDvr();
        else showRadioRecording();
    }

    private void showRadioSecurity() {
        if (requireOwnerAccess(RADIO_SECURITY)) return;
        currentPage = RADIO_SECURITY;
        LinearLayout page = newPage();

        LinearLayout identity = card();
        addCardHeader(identity, "Vehicle identity", "SYNCED", GREEN);
        addBody(identity, "This name identifies the radio in Camera Link, its setup QR code, and every phone connected to it.");
        addKeyValue(identity, "Vehicle name", radioVehicleName());
        addAction(identity, "Rename vehicle", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { showVehicleNameEditor(); }
        });
        page.addView(identity, cardParams());

        LinearLayout lock = card();
        addCardHeader(lock, "Owner lock", isOwnerLockEnabled() ? "ON" : "OFF", isOwnerLockEnabled() ? GREEN : AMBER);
        addBody(lock, "Protects recordings, photos, recording controls, settings, Tailscale setup, and connection details. Live camera viewing stays available. One correct PIN opens every protected DVR screen for 10 minutes, even if you briefly leave the app. It then relocks automatically; a radio restart relocks it immediately.");
        addAction(lock, isOwnerLockEnabled() ? "Change owner PIN" : "Set owner PIN", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { showOwnerPinSetup(); }
        });
        if (isOwnerLockEnabled()) {
            addAction(lock, "Turn owner lock off", SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { showOwnerPinRemoval(); }
            });
            addAction(lock, "Lock controls now", SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) {
                    ownerUnlocked = false;
                    StockDvrAccessGuard.clearSession(MainActivity.this);
                    ownerSessionHandler.removeCallbacks(ownerSessionTimeout);
                    toast("Owner controls locked. Live cameras remain available.");
                    showRadioDvr();
                }
            });
        }
        page.addView(lock, cardParams());

        addUpdateCard(page);

        LinearLayout limits = card();
        addCardTitle(limits, "System-level protection");
        addBody(limits, "The owner PIN protects this app. Android system Settings can still force-stop or uninstall apps and disable VPN. Preventing those actions requires managed-device/device-owner provisioning, which a normal APK cannot enable on an already configured radio.");
        page.addView(limits, cardParams());
        showShell("Settings & Security", true, page);
    }

    private void showRadioGuide() {
        if (requireOwnerAccess(RADIO_GUIDE)) return;
        currentPage = RADIO_GUIDE;
        LinearLayout page = newPage();
        LinearLayout setup = card();
        addCardTitle(setup, "Radio setup checklist");
        addStep(setup, "1", "Install the enhanced stock DVR update built for the tested HN7 S8 firmware. It replaces com.hcn.dvr as a normal signed update and keeps ATOTO's native camera screen.");
        addStep(setup, "2", "Open DVR and use the LINK button for remote access, reverse recording, storage, security, and setup controls.");
        addStep(setup, "3", "Keep the DVR USB drive inserted. Enable automatic recording only for physical cameras installed in this vehicle and verify each live picture.");
        addStep(setup, "4", "Install/open Tailscale, sign in, and set it as Android's Always-on VPN on the radio.");
        addStep(setup, "5", "Use ATOTO's original DVR Settings screen for parking monitoring, duration, and battery cutoff. The enhanced features do not write those settings.");
        addStep(setup, "6", "Set an owner PIN, install the separate Camera Link Viewer APK on the phone, and scan the radio setup QR.");
        page.addView(setup, cardParams());

        LinearLayout integration = card();
        boolean factoryReady = CameraStreamService.getRunningInstance() != null &&
                CameraStreamService.getRunningInstance().isFactoryDvrIntegrationReady();
        addCardHeader(integration, "Enhanced stock DVR",
                isFactoryDvrHost() ? "INSTALLED" : factoryReady ? "CONNECTED" : "RADIO UPDATE REQUIRED",
                isFactoryDvrHost() || factoryReady ? GREEN : AMBER);
        addBody(integration, "ATOTO's native Front/DVR2 pipeline now hosts the continuous remote stream. Reverse recording, pairing, the owner lock, USB tools, and remote settings run in the same stock DVR package.");
        if (!isFactoryDvrHost()) {
            addAction(integration, factoryReady ? "Reinstall/update enhanced stock DVR" :
                    "Install enhanced stock DVR", factoryReady ? SURFACE_2 : PRIMARY,
                    new View.OnClickListener() {
                @Override public void onClick(View view) { installBundledFactoryDvrIntegration(); }
            });
        }
        page.addView(integration, cardParams());

        LinearLayout limits = card();
        addCardTitle(limits, "Confirmed ATOTO camera map");
        addBody(limits, "Front and DVR Camera 2 share camera 0 through /dev/videomux13; only the selected DVR lens can use that decoder. Reverse uses camera 1 and can record beside the selected camera 0 feed. Camera availability depends on what is installed in each vehicle. A fully powered-off radio cannot be awakened by an APK.");
        page.addView(limits, cardParams());
        showShell("Setup Guide", true, page);
    }

    private void showPhoneLive() {
        currentPage = PHONE_LIVE;
        liveAudioPlayer.stop();
        stopPhoneLiveViews();
        LinearLayout page = newPage();
        final String host = preferences.getString(PHONE_HOST_KEY, "");
        final String pin = preferences.getString(PHONE_PIN_KEY, "");
        final int selectedCamera = preferences.getInt("phone_live_camera", 0);
        final int selectedDvrCamera = preferences.getInt("phone_live_primary_camera",
                selectedCamera == 2 ? 2 : 0) == 2 ? 2 : 0;

        addPhoneVehicleSelector(page);

        LinearLayout live = card();
        addCardHeader(live, activePhoneVehicleName(), host.length() > 0 && pin.length() > 0 ? "LIVE" : "SETUP", host.length() > 0 && pin.length() > 0 ? GREEN : AMBER);
        if (host.length() == 0 || pin.length() == 0) {
            addBody(live, "Connect this phone to the radio once. Camera Link will remember the address and reconnect automatically later.");
            addAction(live, "Set up radio connection", PRIMARY, new View.OnClickListener() {
                @Override public void onClick(View view) { showPhoneConnection(); }
            });
            status = addStatus(live, "Connection details have not been saved.");
        } else {
            addKeyValue(live, "Vehicle", activePhoneVehicleName());
            addKeyValue(live, "Address", host + ":" + preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT)));
            status = addStatus(live, "Connecting through Tailscale...");
            addAction(live, "Front DVR", selectedCamera == 0 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { selectPhoneCamera(0); }
            });
            addAction(live, "DVR Camera 2", selectedCamera == 2 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { selectPhoneCamera(2); }
            });
            addAction(live, "Reverse camera", selectedCamera == 1 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { selectPhoneCamera(1); }
            });
            addAction(live, (selectedDvrCamera == 2 ? "DVR Camera 2" : "Front DVR") + " + Reverse",
                    selectedCamera == 3 ? PRIMARY : SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { selectPhoneCamera(3); }
            });
            final boolean sound = preferences.getBoolean("phone_live_sound", false);
            addAction(live, sound ? "Vehicle sound: ON" : "Vehicle sound: OFF", sound ? GREEN : SURFACE_2,
                    new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            preferences.edit().putBoolean("phone_live_sound", !sound).apply();
                            showPhoneLive();
                        }
                    });
        }
        page.addView(live, cardParams());

        if (host.length() > 0 && pin.length() > 0) {
            LinearLayout viewer = card();
            addCardTitle(viewer, selectedCamera == 3 ?
                    (selectedDvrCamera == 2 ? "DVR CAMERA 2" : "FRONT DVR") + " + REVERSE" :
                    dvrCameraName(selectedCamera));
            if (selectedCamera == 3) {
                LinearLayout split = new LinearLayout(this);
                split.setOrientation(LinearLayout.HORIZONTAL);
                MjpegView front = new MjpegView(this);
                MjpegView reverse = new MjpegView(this);
                split.addView(front, new LinearLayout.LayoutParams(0, dp(360), 1));
                split.addView(reverse, new LinearLayout.LayoutParams(0, dp(360), 1));
                viewer.addView(split, matchWrap());
                startPhoneMjpeg(front, selectedDvrCamera);
                startPhoneMjpeg(reverse, 1);
            } else {
                MjpegView stream = new MjpegView(this);
                viewer.addView(stream, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(440)));
                startPhoneMjpeg(stream, selectedCamera);
            }
            page.addView(viewer, cardParams());
        }

        LinearLayout recordings = card();
        addCardHeader(recordings, "Camera recordings", host.length() > 0 && pin.length() > 0 ? "NATIVE PLAYER" : "SETUP", host.length() > 0 && pin.length() > 0 ? GREEN : AMBER);
        addBody(recordings, "Browse Front DVR, DVR Camera 2, Reverse, and protected clips. Playback opens in Camera Link's native video player.");
        addAction(recordings, "Open saved videos", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { showPhoneRecordings(); }
        });
        page.addView(recordings, cardParams());

        LinearLayout vpn = card();
        String tailIp = findIpv4Address(true);
        addCardHeader(vpn, "Private connection", tailIp.length() > 0 ? "CONNECTED" : "CONNECTING", tailIp.length() > 0 ? GREEN : AMBER);
        addBody(vpn, "Camera Link asks Tailscale to connect when this page opens. Normal Internet traffic stays on the phone's regular route unless you select an exit node.");
        addAction(vpn, "Tailscale controls", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { showPhoneVpn(); }
        });
        page.addView(vpn, cardParams());

        showShell("Live Cameras", false, page);
        requestPhoneTailscaleConnect();
        if (host.length() > 0 && pin.length() > 0 && preferences.getBoolean("phone_live_sound", false)) {
            String port = preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT));
            liveAudioPlayer.start("http://" + host + ":" + port + "/audio.wav",
                    CameraStreamService.USERNAME, pin, new PcmAudioPlayer.Listener() {
                        @Override public void onStopped(final String reason) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    preferences.edit().putBoolean("phone_live_sound", false).apply();
                                    toast(reason);
                                }
                            });
                        }
                    });
        }
    }

    private void selectPhoneCamera(int cameraId) {
        int selected = preferences.getInt("phone_live_camera", 0);
        int primary = preferences.getInt("phone_live_primary_camera", selected == 2 ? 2 : 0) == 2 ? 2 : 0;
        if (cameraId == 0 || cameraId == 2) {
            primary = cameraId;
            if (selected != 3) selected = cameraId;
        } else if (cameraId == 3) {
            selected = selected == 3 ? primary : 3;
        } else {
            selected = cameraId;
        }
        preferences.edit().putInt("phone_live_camera", selected)
                .putInt("phone_live_primary_camera", primary).apply();
        showPhoneLive();
    }

    private void startPhoneMjpeg(MjpegView view, int cameraId) {
        String host = preferences.getString(PHONE_HOST_KEY, "");
        String port = preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT));
        String pin = preferences.getString(PHONE_PIN_KEY, "");
        view.start("http://" + host + ":" + port + "/stream?camera=" + cameraId,
                CameraStreamService.USERNAME, pin);
        phoneLiveViews.add(view);
    }

    private void stopPhoneLiveViews() {
        for (MjpegView view : new ArrayList<MjpegView>(phoneLiveViews)) view.stop();
        phoneLiveViews.clear();
    }

    private void showPhoneRecordings() {
        currentPage = PHONE_RECORDINGS;
        LinearLayout page = newPage();
        String host = preferences.getString(PHONE_HOST_KEY, "");
        String pin = preferences.getString(PHONE_PIN_KEY, "");

        addPhoneVehicleSelector(page);

        LinearLayout intro = card();
        addCardHeader(intro, activePhoneVehicleName() + " recordings", host.length() > 0 && pin.length() > 0 ? "SECURE" : "SETUP", host.length() > 0 && pin.length() > 0 ? GREEN : AMBER);
        addBody(intro, "Play completed Front DVR, DVR Camera 2, and Reverse clips from USB or download a copy to this phone. Active clips appear after they are finalized.");
        if (host.length() == 0 || pin.length() == 0) {
            addAction(intro, "Set up radio connection", PRIMARY, new View.OnClickListener() {
                @Override public void onClick(View view) { showPhoneConnection(); }
            });
            status = addStatus(intro, "Connection details have not been saved.");
        } else {
            addKeyValue(intro, "Vehicle", activePhoneVehicleName());
            addKeyValue(intro, "Address", host + ":" + preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT)));
            addAction(intro, "Refresh saved videos", SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { loadPhoneUrl("/recordings"); }
            });
            status = addStatus(intro, "Connecting securely to the radio...");
        }
        page.addView(intro, cardParams());

        if (host.length() > 0 && pin.length() > 0) {
            LinearLayout browser = card();
            webView = new WebView(this);
            configureWebView();
            browser.addView(webView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(560)));
            page.addView(browser, cardParams());
        }
        showShell("Camera Recordings", false, page);
        requestPhoneTailscaleConnect();
        if (host.length() > 0 && pin.length() > 0) loadPhoneUrl("/recordings");
    }

    private void showPhoneConnection() {
        currentPage = PHONE_CONNECTION;
        final boolean blankForm = clearPhoneConnectionForm;
        clearPhoneConnectionForm = false;
        LinearLayout page = newPage();

        addPhoneConnectionProfiles(page);

        LinearLayout card = card();
        addCardTitle(card, "Add or update a vehicle");
        addBody(card, "On the radio, open Camera Link > Internet & Tailscale. Scan its QR code for automatic setup, or enter the details shown there.");
        addAction(card, "Scan radio setup QR", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { launchQrScanner(); }
        });

        addFieldLabel(card, "Vehicle name");
        vehicleNameInput = input("Example: NV2500", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        vehicleNameInput.setText(blankForm ? "" : preferences.getString(PHONE_VEHICLE_NAME_KEY, ""));
        card.addView(vehicleNameInput, matchWrap());

        addFieldLabel(card, "Radio Tailscale IP");
        hostInput = input("Example: 100.80.20.10", InputType.TYPE_CLASS_TEXT);
        hostInput.setText(blankForm ? "" : preferences.getString(PHONE_HOST_KEY, ""));
        card.addView(hostInput, matchWrap());

        addFieldLabel(card, "Port");
        portInput = input("8765", InputType.TYPE_CLASS_NUMBER);
        portInput.setText(blankForm ? Integer.toString(CameraStreamService.PORT) :
                preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT)));
        card.addView(portInput, matchWrap());

        addFieldLabel(card, "Connection PIN");
        pinInput = input("Shown on the radio", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pinInput.setText(blankForm ? "" : preferences.getString(PHONE_PIN_KEY, ""));
        card.addView(pinInput, matchWrap());

        status = addStatus(card, "These details stay on this phone.");
        addAction(card, "Save vehicle and open camera", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { savePhoneConnection(); }
        });
        if (preferences.getString(PHONE_HOST_KEY, "").length() > 0) {
            addAction(card, "Clear form for another vehicle", SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) {
                    vehicleNameInput.setText("");
                    hostInput.setText("");
                    portInput.setText(Integer.toString(CameraStreamService.PORT));
                    pinInput.setText("");
                    status.setText("Enter the second vehicle's details or scan its QR code.");
                }
            });
        }
        page.addView(card, cardParams());

        LinearLayout help = card();
        addCardTitle(help, "Can't connect?");
        addBody(help, "Make sure both devices are signed into the same Tailscale account, the radio is awake, and the radio shows a 100.x Tailscale address.");
        addAction(help, "Open Tailscale", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { openTailscale(); }
        });
        page.addView(help, cardParams());
        showShell("Connection", false, page);
    }

    private static final class VehicleProfile {
        final String name;
        final String host;
        final String port;
        final String pin;

        VehicleProfile(String name, String host, String port, String pin) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.pin = pin;
        }
    }

    private String radioVehicleName() {
        return normalizedVehicleName(preferences.getString(VEHICLE_NAME_KEY, DEFAULT_VEHICLE_NAME), DEFAULT_VEHICLE_NAME);
    }

    private String activePhoneVehicleName() {
        String host = preferences.getString(PHONE_HOST_KEY, "");
        String fallback = host.length() == 0 ? "No vehicle selected" : "Radio " + host;
        return normalizedVehicleName(preferences.getString(PHONE_VEHICLE_NAME_KEY, ""), fallback);
    }

    private String normalizedVehicleName(String value, String fallback) {
        String clean = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (clean.length() == 0) clean = fallback;
        if (clean.length() > 24) clean = clean.substring(0, 24).trim();
        return clean;
    }

    private List<VehicleProfile> phoneVehicleProfiles() {
        ArrayList<VehicleProfile> result = new ArrayList<VehicleProfile>();
        String encoded = preferences.getString(PHONE_PROFILES_KEY, "");
        try {
            JSONArray values = encoded.length() == 0 ? new JSONArray() : new JSONArray(encoded);
            for (int index = 0; index < values.length(); index++) {
                JSONObject item = values.optJSONObject(index);
                if (item == null) continue;
                String host = item.optString("host", "").trim();
                String port = item.optString("port", Integer.toString(CameraStreamService.PORT)).trim();
                String pin = item.optString("pin", "");
                if (host.length() == 0 || pin.length() == 0) continue;
                String name = normalizedVehicleName(item.optString("name", ""), "Radio " + host);
                result.add(new VehicleProfile(name, host, port, pin));
            }
        } catch (Throwable ignored) { }

        if (result.isEmpty()) {
            String host = preferences.getString(PHONE_HOST_KEY, "").trim();
            String pin = preferences.getString(PHONE_PIN_KEY, "");
            if (host.length() > 0 && pin.length() > 0) {
                VehicleProfile migrated = new VehicleProfile(activePhoneVehicleName(), host,
                        preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT)), pin);
                result.add(migrated);
                savePhoneVehicleProfiles(result);
            }
        }
        return result;
    }

    private void savePhoneVehicleProfiles(List<VehicleProfile> profiles) {
        JSONArray values = new JSONArray();
        try {
            for (VehicleProfile profile : profiles) {
                JSONObject item = new JSONObject();
                item.put("name", profile.name);
                item.put("host", profile.host);
                item.put("port", profile.port);
                item.put("pin", profile.pin);
                values.put(item);
            }
            preferences.edit().putString(PHONE_PROFILES_KEY, values.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private void upsertPhoneVehicle(String name, String host, String port, String pin) {
        name = normalizedVehicleName(name, "Radio " + host);
        List<VehicleProfile> profiles = phoneVehicleProfiles();
        ArrayList<VehicleProfile> updated = new ArrayList<VehicleProfile>();
        boolean replaced = false;
        for (VehicleProfile profile : profiles) {
            if (profile.host.equalsIgnoreCase(host) && profile.port.equals(port)) {
                if (!replaced) updated.add(new VehicleProfile(name, host, port, pin));
                replaced = true;
            } else {
                updated.add(profile);
            }
        }
        if (!replaced) updated.add(new VehicleProfile(name, host, port, pin));
        savePhoneVehicleProfiles(updated);
        preferences.edit().putString(PHONE_VEHICLE_NAME_KEY, name)
                .putString(PHONE_HOST_KEY, host).putString(PHONE_PORT_KEY, port)
                .putString(PHONE_PIN_KEY, pin).apply();
    }

    private boolean isActivePhoneVehicle(VehicleProfile profile) {
        return profile.host.equalsIgnoreCase(preferences.getString(PHONE_HOST_KEY, "")) &&
                profile.port.equals(preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT)));
    }

    private void selectPhoneVehicle(VehicleProfile profile) {
        preferences.edit().putString(PHONE_VEHICLE_NAME_KEY, profile.name)
                .putString(PHONE_HOST_KEY, profile.host).putString(PHONE_PORT_KEY, profile.port)
                .putString(PHONE_PIN_KEY, profile.pin).apply();
        lastVehicleSyncAt = 0;
        showPhoneLive();
    }

    private void addPhoneVehicleSelector(LinearLayout page) {
        final List<VehicleProfile> profiles = phoneVehicleProfiles();
        if (profiles.isEmpty()) return;
        LinearLayout vehicles = card();
        addCardHeader(vehicles, "Your vehicles", profiles.size() + (profiles.size() == 1 ? " SAVED" : " SAVED"), GREEN);
        addBody(vehicles, "Choose a vehicle to switch its live cameras, recordings, and saved secure connection.");
        for (final VehicleProfile profile : profiles) {
            boolean active = isActivePhoneVehicle(profile);
            addAction(vehicles, (active ? "●  " : "") + profile.name, active ? PRIMARY : SURFACE_2,
                    new View.OnClickListener() {
                        @Override public void onClick(View view) { selectPhoneVehicle(profile); }
                    });
        }
        addAction(vehicles, "+ Add another vehicle", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) {
                clearPhoneConnectionForm = true;
                showPhoneConnection();
            }
        });
        page.addView(vehicles, cardParams());
    }

    private void addPhoneConnectionProfiles(LinearLayout page) {
        final List<VehicleProfile> profiles = phoneVehicleProfiles();
        if (profiles.isEmpty()) return;
        LinearLayout saved = card();
        addCardHeader(saved, "Saved vehicles", Integer.toString(profiles.size()), GREEN);
        addBody(saved, "Tap a vehicle to open it. Remove only deletes the saved connection from this phone.");
        for (final VehicleProfile profile : profiles) {
            boolean active = isActivePhoneVehicle(profile);
            addAction(saved, (active ? "●  " : "") + profile.name + "  •  " + profile.host,
                    active ? PRIMARY : SURFACE_2, new View.OnClickListener() {
                        @Override public void onClick(View view) { selectPhoneVehicle(profile); }
                    });
            addAction(saved, "Remove " + profile.name, SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) { confirmRemovePhoneVehicle(profile); }
            });
        }
        page.addView(saved, cardParams());
    }

    private void confirmRemovePhoneVehicle(final VehicleProfile removing) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + removing.name + "?")
                .setMessage("This removes the saved address and PIN from this phone. It does not change the radio.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        List<VehicleProfile> profiles = phoneVehicleProfiles();
                        ArrayList<VehicleProfile> kept = new ArrayList<VehicleProfile>();
                        for (VehicleProfile profile : profiles) {
                            if (!(profile.host.equalsIgnoreCase(removing.host) && profile.port.equals(removing.port))) kept.add(profile);
                        }
                        savePhoneVehicleProfiles(kept);
                        if (isActivePhoneVehicle(removing)) {
                            if (kept.isEmpty()) {
                                preferences.edit().remove(PHONE_VEHICLE_NAME_KEY).remove(PHONE_HOST_KEY)
                                        .remove(PHONE_PORT_KEY).remove(PHONE_PIN_KEY).apply();
                            } else {
                                VehicleProfile next = kept.get(0);
                                preferences.edit().putString(PHONE_VEHICLE_NAME_KEY, next.name)
                                        .putString(PHONE_HOST_KEY, next.host).putString(PHONE_PORT_KEY, next.port)
                                        .putString(PHONE_PIN_KEY, next.pin).apply();
                            }
                        }
                        showPhoneConnection();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPhoneVpn() {
        currentPage = PHONE_VPN;
        LinearLayout page = newPage();
        LinearLayout card = card();
        String tailIp = findIpv4Address(true);
        addCardHeader(card, "Tailscale", tailIp.length() > 0 ? "CONNECTED" : "DISCONNECTED", tailIp.length() > 0 ? GREEN : AMBER);
        addBody(card, "Tailscale supplies the encrypted private path to the radio. Android controls the first VPN approval and account sign-in.");
        addKeyValue(card, "Phone tailnet IP", tailIp.length() > 0 ? tailIp : "Not connected");
        addAction(card, "Install or open Tailscale", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { openTailscale(); }
        });
        addAction(card, "Connect now", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) {
                sendTailscaleAction("com.tailscale.ipn.CONNECT_VPN");
                toast("Asked Tailscale to connect.");
            }
        });
        addAction(card, "Disconnect now", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) {
                sendTailscaleAction("com.tailscale.ipn.DISCONNECT_VPN");
                toast("Asked Tailscale to disconnect.");
            }
        });
        page.addView(card, cardParams());

        LinearLayout behavior = card();
        addCardTitle(behavior, "Phone behavior");
        CheckBox disconnect = new CheckBox(this);
        disconnect.setText("Disconnect Tailscale when Camera Link closes");
        disconnect.setTextColor(TEXT);
        disconnect.setTextSize(16);
        disconnect.setButtonTintList(ColorStateList.valueOf(GREEN));
        disconnect.setPadding(0, dp(10), 0, dp(10));
        disconnect.setChecked(preferences.getBoolean(PHONE_DISCONNECT_KEY, true));
        disconnect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                preferences.edit().putBoolean(PHONE_DISCONNECT_KEY, checked).apply();
            }
        });
        behavior.addView(disconnect, matchWrap());
        addBody(behavior, "You can leave Tailscale connected without slowing normal browsing: it is split tunnel unless an exit node is selected.");
        page.addView(behavior, cardParams());
        showShell("Tailscale", false, page);
    }

    private void showPhoneSettings() {
        currentPage = PHONE_SETTINGS;
        LinearLayout page = newPage();
        LinearLayout setup = card();
        addCardTitle(setup, "Quick setup");
        addStep(setup, "1", "Install/open the included Tailscale app and sign in with the same account as the radio.");
        addStep(setup, "2", "Enter the radio's Tailscale IP and connection PIN once on the Connection page.");
        addStep(setup, "3", "Open Live Camera. Tailscale connects automatically and can disconnect when you leave.");
        page.addView(setup, cardParams());

        LinearLayout privacy = card();
        addCardTitle(privacy, "Security");
        addBody(privacy, "The connection PIN is generated separately on each radio and saved locally on this phone. Camera traffic stays inside your Tailscale network. Do not expose port 8765 directly to the public Internet.");
        addAction(privacy, "Edit saved connection", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { showPhoneConnection(); }
        });
        page.addView(privacy, cardParams());

        addUpdateCard(page);

        LinearLayout about = card();
        addCardTitle(about, "About");
        addKeyValue(about, "Camera Link", APP_VERSION_NAME);
        addKeyValue(about, "Included Tailscale", "1.104.1 universal APK");
        addBody(about, "Tailscale is separate BSD-licensed software and is not affiliated with Camera Link.");
        page.addView(about, cardParams());
        showShell("Settings & Help", false, page);
    }

    private void showShell(String pageTitle, boolean radio, LinearLayout page) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), 0, dp(16), 0);
        bar.setBackgroundColor(APP_BAR);
        TextView menu = text("☰", 30, TEXT, false);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Open navigation menu");
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { drawerOverlay.setVisibility(View.VISIBLE); }
        });
        bar.addView(menu, new LinearLayout.LayoutParams(dp(58), dp(64)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(getApplicationInfo().icon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconParams.rightMargin = dp(12);
        bar.addView(icon, iconParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView app = text("Camera Link", 19, TEXT, true);
        TextView section = text(pageTitle, 13, MUTED, false);
        labels.addView(app, matchWrap());
        labels.addView(section, matchWrap());
        bar.addView(labels, new LinearLayout.LayoutParams(0, dp(64), 1));
        column.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(page);
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        frame.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        drawerOverlay = buildDrawer(radio);
        frame.addView(drawerOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
    }

    private FrameLayout buildDrawer(final boolean radio) {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(185, 0, 0, 0));
        overlay.setVisibility(View.GONE);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { drawerOverlay.setVisibility(View.GONE); }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(28), dp(18), dp(24));
        panel.setBackgroundColor(APP_BAR);
        panel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { }
        });

        TextView brand = text("CAMERA LINK", 14, PRIMARY, true);
        brand.setPadding(dp(12), 0, 0, dp(4));
        panel.addView(brand, matchWrap());
        TextView role = text(radio ? radioVehicleName() : activePhoneVehicleName(), 25, TEXT, true);
        role.setPadding(dp(12), 0, 0, dp(22));
        panel.addView(role, matchWrap());

        if (radio) {
            addDrawerItem(panel, "DVR Camera", RADIO_DVR, new View.OnClickListener() {
                @Override public void onClick(View view) { showRadioDvr(); }
            });
            addDrawerItem(panel, "Phone connection & QR", RADIO_NETWORK, new View.OnClickListener() {
                @Override public void onClick(View view) { showRadioNetwork(); }
            });
            addDrawerItem(panel, "Reverse camera & remote", RADIO_RECORDING, new View.OnClickListener() {
                @Override public void onClick(View view) { showRadioRecording(); }
            });
            addDrawerItem(panel, "Vehicle & owner lock", RADIO_SECURITY, new View.OnClickListener() {
                @Override public void onClick(View view) { showRadioSecurity(); }
            });
            addDrawerItem(panel, "Setup Guide", RADIO_GUIDE, new View.OnClickListener() {
                @Override public void onClick(View view) { showRadioGuide(); }
            });
        } else {
            addDrawerItem(panel, "Live cameras", PHONE_LIVE, new View.OnClickListener() {
                @Override public void onClick(View view) { showPhoneLive(); }
            });
            addDrawerItem(panel, "Camera recordings", PHONE_RECORDINGS, new View.OnClickListener() {
                @Override public void onClick(View view) { showPhoneRecordings(); }
            });
            addDrawerItem(panel, "Connection", PHONE_CONNECTION, new View.OnClickListener() {
                @Override public void onClick(View view) { showPhoneConnection(); }
            });
            addDrawerItem(panel, "Tailscale", PHONE_VPN, new View.OnClickListener() {
                @Override public void onClick(View view) { showPhoneVpn(); }
            });
            addDrawerItem(panel, "Settings & Help", PHONE_SETTINGS, new View.OnClickListener() {
                @Override public void onClick(View view) { showPhoneSettings(); }
            });
        }

        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        addDrawerItem(panel, "Check for updates", "app_update", new View.OnClickListener() {
            @Override public void onClick(View view) {
                drawerOverlay.setVisibility(View.GONE);
                if (radio && requireOwnerAccess(RADIO_SECURITY)) return;
                checkForAppUpdate();
            }
        });
        TextView version = text("Version " + APP_VERSION_NAME, 13, MUTED, false);
        version.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.addView(version, matchWrap());
        // Package roles are fixed: com.hcn.dvr is the radio endpoint and the
        // standalone Camera Link package is the phone/tablet viewer.

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                Math.min(dp(330), getResources().getDisplayMetrics().widthPixels * 4 / 5),
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        overlay.addView(panel, panelParams);
        return overlay;
    }

    private void addDrawerItem(LinearLayout panel, String label, String id, View.OnClickListener listener) {
        TextView item = text(label, 17, TEXT, currentPage.equals(id));
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), 0, dp(12), 0);
        item.setBackground(rounded(currentPage.equals(id) ? SURFACE_2 : Color.TRANSPARENT, dp(12)));
        item.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        params.bottomMargin = dp(4);
        panel.addView(item, params);
    }

    private void confirmRoleChange() {
        new AlertDialog.Builder(this)
                .setTitle("Switch device role?")
                .setMessage("Choose this only if the APK was installed on the wrong device.")
                .setPositiveButton("Switch role", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (MODE_RADIO.equals(preferences.getString(MODE_KEY, ""))) {
                            stopService(new Intent(MainActivity.this, CameraStreamService.class));
                        }
                        preferences.edit().remove(MODE_KEY).apply();
                        ownerUnlocked = false;
                        showRoleChooser();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout newPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(30));
        page.setBackgroundColor(BG);
        return page;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(SURFACE, dp(18)));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(14);
        return params;
    }

    private void addCardTitle(LinearLayout card, String value) {
        TextView title = text(value, 21, TEXT, true);
        title.setPadding(0, 0, 0, dp(8));
        card.addView(title, matchWrap());
    }

    private void addCardHeader(LinearLayout card, String value, String badge, int badgeColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(value, 21, TEXT, true);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView chip = text(badge, 11, badgeColor, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(rounded(Color.argb(42, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)), dp(20)));
        row.addView(chip, wrapWrap());
        row.setPadding(0, 0, 0, dp(8));
        card.addView(row, matchWrap());
    }

    private TextView addBody(LinearLayout parent, String value) {
        TextView body = text(value, 15, MUTED, false);
        body.setLineSpacing(0, 1.12f);
        body.setPadding(0, 0, 0, dp(10));
        parent.addView(body, matchWrap());
        return body;
    }

    private void addKeyValue(LinearLayout parent, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView left = text(key, 14, MUTED, false);
        TextView right = text(value, 14, TEXT, true);
        right.setGravity(Gravity.END);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.38f));
        row.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.62f));
        parent.addView(row, matchWrap());
    }

    private TextView addStatus(LinearLayout parent, String value) {
        TextView result = text(value, 14, MUTED, false);
        result.setPadding(dp(12), dp(10), dp(12), dp(10));
        result.setBackground(rounded(SURFACE_2, dp(10)));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        parent.addView(result, params);
        return result;
    }

    private void addStep(LinearLayout parent, String number, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView circle = text(number, 14, TEXT, true);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(rounded(PRIMARY, dp(22)));
        row.addView(circle, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView body = text(value, 15, MUTED, false);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        bodyParams.leftMargin = dp(12);
        row.addView(body, bodyParams);
        parent.addView(row, matchWrap());
    }

    private Button addAction(LinearLayout parent, String label, int color, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundTintList(ColorStateList.valueOf(color));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        params.topMargin = dp(7);
        parent.addView(button, params);
        return button;
    }

    private void addFieldLabel(LinearLayout parent, String value) {
        TextView label = text(value, 13, MUTED, true);
        label.setPadding(dp(2), dp(10), 0, dp(4));
        parent.addView(label, matchWrap());
    }

    private EditText input(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(112, 142, 172));
        input.setTextSize(16);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackgroundTintList(ColorStateList.valueOf(PRIMARY));
        return input;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return text;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showStandalone(LinearLayout page) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(page);
        setContentView(scroll);
    }

    private void ensurePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        if (preferences.getBoolean("record_audio", true)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
            return;
        }
        if (preferences.getBoolean("watermark_enabled", true) &&
                (preferences.getBoolean("watermark_speed", true) || preferences.getBoolean("watermark_gps", true)) &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean("watermark_location_asked", false)) {
            requestWatermarkLocationPermission();
            return;
        }
        startForegroundService(new Intent(this, CameraStreamService.class));
        preferences.edit().putLong("last_manual_start", System.currentTimeMillis()).apply();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            ensurePermissionAndStart();
            toast("Camera service is ready.");
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            toast("Camera permission is required on the radio.");
        } else if (requestCode == AUDIO_PERMISSION_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            setRecordedAudioEnabled(true);
            ensurePermissionAndStart();
        } else if (requestCode == AUDIO_PERMISSION_REQUEST) {
            preferences.edit().putBoolean("record_audio", false).apply();
            startForegroundService(new Intent(this, CameraStreamService.class));
            toast("Microphone permission was declined. Video recording will continue without sound.");
        } else if (requestCode == LOCATION_PERMISSION_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(new Intent(this, CameraStreamService.class));
            CameraStreamService service = CameraStreamService.getRunningInstance();
            if (service != null) service.refreshWatermarkSettings();
            toast("GPS speed and location watermark are ready.");
            if (RADIO_RECORDING.equals(currentPage)) showRadioRecording();
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            startForegroundService(new Intent(this, CameraStreamService.class));
            toast("Date and time will still appear. Speed and GPS need location permission.");
            if (RADIO_RECORDING.equals(currentPage)) showRadioRecording();
        } else if (requestCode == QR_PERMISSION_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startActivityForResult(new Intent(this, QrScannerActivity.class), QR_SCAN_REQUEST);
        } else if (requestCode == QR_PERMISSION_REQUEST) {
            toast("Camera permission is needed to scan the radio setup code.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == QR_SCAN_REQUEST && resultCode == RESULT_OK && data != null) {
            String raw = data.getStringExtra(QrScannerActivity.EXTRA_SETUP_URI);
            if (!importSetupUri(raw == null ? null : Uri.parse(raw))) {
                toast("That QR code is not a Camera Link setup code.");
            }
        }
    }

    private void launchQrScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, QR_PERMISSION_REQUEST);
            return;
        }
        startActivityForResult(new Intent(this, QrScannerActivity.class), QR_SCAN_REQUEST);
    }

    private boolean importSetupUri(Uri uri) {
        if (uri == null || !"atotocam".equalsIgnoreCase(uri.getScheme()) ||
                !"setup".equalsIgnoreCase(uri.getHost())) return false;
        String host = uri.getQueryParameter("host");
        String port = uri.getQueryParameter("port");
        String pin = uri.getQueryParameter("pin");
        String vehicle = uri.getQueryParameter("vehicle");
        if (host == null || !host.matches("[0-9A-Za-z._:-]+") || pin == null || pin.length() < 4) return false;
        if (port == null || !port.matches("[0-9]{1,5}")) port = Integer.toString(CameraStreamService.PORT);
        preferences.edit().putString(MODE_KEY, MODE_PHONE).apply();
        upsertPhoneVehicle(vehicle, host, port, pin);
        lastVehicleSyncAt = 0;
        toast(activePhoneVehicleName() + " added.");
        showPhoneLive();
        return true;
    }

    private String setupUri(String host, String pin) {
        return new Uri.Builder().scheme("atotocam").authority("setup")
                .appendQueryParameter("host", host)
                .appendQueryParameter("port", Integer.toString(CameraStreamService.PORT))
                .appendQueryParameter("pin", pin)
                .appendQueryParameter("vehicle", radioVehicleName()).build().toString();
    }

    private Bitmap createQrCode(String value, int size) {
        try {
            Map<EncodeHintType, Object> hints = new java.util.HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                int row = y * size;
                for (int x = 0; x < size; x++) pixels[row + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565);
        } catch (Throwable error) {
            return null;
        }
    }

    private void configureWebView() {
        webView.setBackgroundColor(Color.BLACK);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient() {
            private boolean openNativePlayer(String url) {
                try {
                    Uri uri = Uri.parse(url);
                    if (!"/play".equals(uri.getPath())) return false;
                    String direct = uri.buildUpon().path("/recording").build().toString();
                    showNativeVideoPlayer(null, direct);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openNativePlayer(url);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openNativePlayer(request.getUrl().toString());
            }

            @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                            String host, String realm) {
                String pin = MODE_RADIO.equals(preferences.getString(MODE_KEY, "")) ?
                        CameraStreamService.getConnectionPassword(MainActivity.this) :
                        preferences.getString(PHONE_PIN_KEY, "");
                if (pin.length() > 0) handler.proceed(CameraStreamService.USERNAME, pin);
                else handler.cancel();
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (status != null) status.setText("Connected securely to " + preferences.getString(PHONE_HOST_KEY, "radio"));
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                   WebResourceError error) {
                if (request.isForMainFrame() && status != null) {
                    status.setText("Radio not reached. Check Tailscale on both devices and confirm the radio is awake.");
                }
            }
        });
    }

    private void showNativeVideoPlayer(final File localFile, final String remoteUrl) {
        final boolean phone = localFile == null;
        currentPage = phone ? PHONE_RECORDINGS : RADIO_VIDEOS;
        getWindow().setStatusBarColor(Color.BLACK);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        final VideoView player = new VideoView(this);
        MediaController controls = new MediaController(this);
        controls.setAnchorView(player);
        player.setMediaController(controls);
        FrameLayout.LayoutParams playerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        if (!phone) playerParams.bottomMargin = dp(72);
        root.addView(player, playerParams);

        TextView back = text(phone ? "‹  RECORDINGS" : "‹  VIDEOS", 14, TEXT, true);
        back.setGravity(Gravity.CENTER);
        back.setBackground(rounded(Color.argb(220, 10, 24, 42), dp(13)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                player.stopPlayback();
                if (phone) showPhoneRecordings(); else showRadioVideos();
            }
        });
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(170), dp(48), Gravity.TOP | Gravity.START);
        backParams.leftMargin = dp(14);
        backParams.topMargin = dp(14);
        root.addView(back, backParams);

        TextView title = text(localFile == null ? "Remote recording" : localFile.getName(), 14, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(12), 0, dp(12), 0);
        title.setBackground(rounded(Color.argb(200, 0, 0, 0), dp(12)));
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        titleParams.topMargin = dp(16);
        root.addView(title, titleParams);
        if (!phone) {
            TextView delete = text("DELETE", 14, RED, true);
            delete.setGravity(Gravity.CENTER);
            delete.setBackground(rounded(Color.argb(220, 10, 24, 42), dp(13)));
            delete.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    player.stopPlayback();
                    confirmDeleteMedia(localFile, false);
                }
            });
            FrameLayout.LayoutParams deleteParams = new FrameLayout.LayoutParams(dp(120), dp(48), Gravity.TOP | Gravity.END);
            deleteParams.rightMargin = dp(14);
            deleteParams.topMargin = dp(14);
            root.addView(delete, deleteParams);
        }
        if (!phone) root.addView(buildRadioBottomTabs(RADIO_VIDEOS), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM));
        setContentView(root);

        player.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(android.media.MediaPlayer mediaPlayer) { player.start(); }
        });
        player.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            @Override public boolean onError(android.media.MediaPlayer mediaPlayer, int what, int extra) {
                toast("This video is still being written or could not be played.");
                return true;
            }
        });
        if (localFile != null) {
            player.setVideoPath(localFile.getAbsolutePath());
        } else {
            String pin = preferences.getString(PHONE_PIN_KEY, "");
            String auth = "Basic " + Base64.encodeToString((CameraStreamService.USERNAME + ":" + pin)
                    .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            java.util.HashMap<String, String> headers = new java.util.HashMap<String, String>();
            headers.put("Authorization", auth);
            player.setVideoURI(Uri.parse(remoteUrl), headers);
        }
        player.requestFocus();
    }

    private void savePhoneConnection() {
        String name = vehicleNameInput == null ? "" : vehicleNameInput.getText().toString().trim();
        String host = hostInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        String pin = pinInput.getText().toString().trim();
        if (host.length() == 0) {
            status.setText("Enter the radio's Tailscale IP.");
            return;
        }
        if (pin.length() == 0) {
            status.setText("Enter the connection PIN shown on the radio.");
            return;
        }
        if (port.length() == 0) port = Integer.toString(CameraStreamService.PORT);
        host = host.replace("http://", "").replace("https://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            String enteredPort = host.substring(colon + 1);
            if (enteredPort.matches("[0-9]+")) {
                port = enteredPort;
                host = host.substring(0, colon);
            }
        }
        upsertPhoneVehicle(name, host, port, pin);
        toast(activePhoneVehicleName() + " saved.");
        lastVehicleSyncAt = 0;
        showPhoneLive();
    }

    private void loadPhoneUrl(String path) {
        if (webView == null) return;
        String host = preferences.getString(PHONE_HOST_KEY, "");
        String port = preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT));
        if (host.length() == 0) {
            showPhoneConnection();
            return;
        }
        if (status != null) status.setText("Connecting to the radio...");
        webView.loadUrl("http://" + host + ":" + port + path);
    }

    private void requestPhoneTailscaleConnect() {
        if (!MODE_PHONE.equals(preferences.getString(MODE_KEY, "")) ||
                (!PHONE_LIVE.equals(currentPage) && !PHONE_RECORDINGS.equals(currentPage))) return;
        if (!isPackageInstalled(TAILSCALE_PACKAGE)) {
            if (!vpnPromptShown) showTailscalePrompt(true);
            return;
        }
        sendTailscaleAction("com.tailscale.ipn.CONNECT_VPN");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (!PHONE_LIVE.equals(currentPage) && !PHONE_RECORDINGS.equals(currentPage)) return;
                if (findIpv4Address(true).length() == 0) {
                    if (!vpnPromptShown) showTailscalePrompt(false);
                } else if (PHONE_RECORDINGS.equals(currentPage) && webView != null) {
                    loadPhoneUrl("/recordings");
                } else if (PHONE_LIVE.equals(currentPage) && status != null) {
                    status.setText("Connected securely to the radio.");
                }
                syncActiveVehicleName();
            }
        }, 2500);
    }

    private void syncActiveVehicleName() {
        final String host = preferences.getString(PHONE_HOST_KEY, "");
        final String port = preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT));
        final String pin = preferences.getString(PHONE_PIN_KEY, "");
        long now = System.currentTimeMillis();
        if (host.length() == 0 || pin.length() == 0 || vehicleSyncRunning || now - lastVehicleSyncAt < 15000) return;
        lastVehicleSyncAt = now;
        vehicleSyncRunning = true;
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL("http://" + host + ":" + port + "/status").openConnection();
                    connection.setConnectTimeout(4000);
                    connection.setReadTimeout(4000);
                    connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(
                            (CameraStreamService.USERNAME + ":" + pin).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && body.length() < 32768) body.append(line);
                    reader.close();
                    final String received = normalizedVehicleName(new JSONObject(body.toString()).optString("vehicleName", ""), "");
                    if (received.length() > 0) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                vehicleSyncRunning = false;
                                if (!host.equalsIgnoreCase(preferences.getString(PHONE_HOST_KEY, "")) ||
                                        !port.equals(preferences.getString(PHONE_PORT_KEY, Integer.toString(CameraStreamService.PORT)))) return;
                                String existing = preferences.getString(PHONE_VEHICLE_NAME_KEY, "");
                                if (!received.equals(existing)) {
                                    upsertPhoneVehicle(received, host, port, pin);
                                    if (PHONE_LIVE.equals(currentPage)) showPhoneLive();
                                    else if (PHONE_RECORDINGS.equals(currentPage)) showPhoneRecordings();
                                }
                            }
                        });
                        return;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (connection != null) connection.disconnect();
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { vehicleSyncRunning = false; }
                });
            }
        }, "vehicle-name-sync").start();
    }

    private void showTailscalePrompt(boolean needsInstall) {
        vpnPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle(needsInstall ? "Install private connection" : "Connect private network")
                .setMessage(needsInstall ?
                        "Camera Link includes Tailscale. Install it, sign in, and return to finish setup." :
                        "Tailscale needs attention. Open it, connect, and return to Camera Link.")
                .setPositiveButton(needsInstall ? "Install Tailscale" : "Open Tailscale",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { openTailscale(); }
                        })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void addUpdateCard(LinearLayout page) {
        LinearLayout update = card();
        addCardHeader(update, "App updates", "v" + APP_VERSION_NAME, GREEN);
        addBody(update, "Checks the official GitHub release, downloads a newer signed APK, verifies that it belongs to Camera Link and uses the same signing certificate, then opens Android's update screen.");
        addAction(update, "Check for updates", PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View view) { checkForAppUpdate(); }
        });
        addAction(update, "Open GitHub project", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { openWebAddress(GITHUB_REPOSITORY_URL); }
        });
        page.addView(update, cardParams());
    }

    private void checkForAppUpdate() {
        final AlertDialog checking = new AlertDialog.Builder(this)
                .setTitle("Checking for updates")
                .setMessage("Contacting the official GitHub release page...")
                .setCancelable(false)
                .create();
        checking.show();
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(GITHUB_LATEST_RELEASE_API).openConnection();
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(15000);
                    connection.setRequestProperty("Accept", "application/vnd.github+json");
                    connection.setRequestProperty("User-Agent", "ATOTO-Camera-Link/" + APP_VERSION_NAME);
                    int statusCode = connection.getResponseCode();
                    if (statusCode != HttpURLConnection.HTTP_OK) {
                        throw new Exception(statusCode == 404 ?
                                "The release page is not available yet." : "GitHub returned HTTP " + statusCode + ".");
                    }
                    JSONObject release = new JSONObject(readText(connection.getInputStream()));
                    final String remoteVersion = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                    final String releaseUrl = release.optString("html_url", GITHUB_REPOSITORY_URL + "/releases/latest");
                    String selectedUrl = "";
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets != null) {
                        for (int index = 0; index < assets.length(); index++) {
                            JSONObject asset = assets.optJSONObject(index);
                            if (asset == null) continue;
                            String name = asset.optString("name", "");
                            String url = asset.optString("browser_download_url", "");
                            String lowerName = name.toLowerCase(java.util.Locale.US);
                            if (lowerName.endsWith(".apk") && url.startsWith("https://") &&
                                    lowerName.contains(expectedUpdateAssetMarker())) {
                                selectedUrl = url;
                                break;
                            }
                        }
                    }
                    final String apkUrl = selectedUrl;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            checking.dismiss();
                            if (!isNewerVersion(remoteVersion, APP_VERSION_NAME)) {
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("Camera Link is up to date")
                                        .setMessage("Version " + APP_VERSION_NAME + " is the newest published version.")
                                        .setPositiveButton("OK", null)
                                        .setNeutralButton("View GitHub", new DialogInterface.OnClickListener() {
                                            @Override public void onClick(DialogInterface dialog, int which) { openWebAddress(releaseUrl); }
                                        })
                                        .show();
                                return;
                            }
                            if (apkUrl.length() == 0) {
                                showUpdateError("Version " + remoteVersion + " is available, but its APK could not be found. Open GitHub to download it manually.", releaseUrl);
                                return;
                            }
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Update available")
                                    .setMessage("Camera Link " + remoteVersion + " is available. The APK will be downloaded and checked before Android asks you to install it.")
                                    .setPositiveButton("Download & install", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface dialog, int which) { downloadAndInstallUpdate(apkUrl); }
                                    })
                                    .setNeutralButton("Release notes", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface dialog, int which) { openWebAddress(releaseUrl); }
                                    })
                                    .setNegativeButton("Later", null)
                                    .show();
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            checking.dismiss();
                            showUpdateError("Could not check for updates: " + safeMessage(error), GITHUB_REPOSITORY_URL + "/releases/latest");
                        }
                    });
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }, "CameraLinkUpdateCheck").start();
    }

    private String expectedUpdateAssetMarker() {
        return isFactoryDvrHost() ? "enhanced-stock-dvr" : "camera-link-viewer";
    }

    private void downloadAndInstallUpdate(final String downloadUrl) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Downloading update")
                .setMessage("Downloading the signed Camera Link APK...")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                File partial = new File(getCacheDir(), BundledApkProvider.UPDATE_FILE_NAME + ".part");
                File complete = new File(getCacheDir(), BundledApkProvider.UPDATE_FILE_NAME);
                try {
                    URL url = new URL(downloadUrl);
                    if (!"https".equalsIgnoreCase(url.getProtocol())) throw new Exception("The download did not use HTTPS.");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(45000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
                    connection.setRequestProperty("User-Agent", "ATOTO-Camera-Link/" + APP_VERSION_NAME);
                    int statusCode = connection.getResponseCode();
                    if (statusCode != HttpURLConnection.HTTP_OK) throw new Exception("The APK download returned HTTP " + statusCode + ".");
                    long declaredSize = connection.getContentLengthLong();
                    if (declaredSize > MAX_UPDATE_BYTES) throw new Exception("The APK is larger than the allowed update size.");
                    InputStream input = connection.getInputStream();
                    OutputStream output = new FileOutputStream(partial, false);
                    byte[] buffer = new byte[131072];
                    long total = 0;
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        total += count;
                        if (total > MAX_UPDATE_BYTES) throw new Exception("The APK is larger than the allowed update size.");
                        output.write(buffer, 0, count);
                    }
                    output.close();
                    input.close();
                    String validationError = validateDownloadedUpdate(partial);
                    if (validationError != null) throw new Exception(validationError);
                    if (complete.exists() && !complete.delete()) throw new Exception("The previous update file could not be replaced.");
                    if (!partial.renameTo(complete)) throw new Exception("The verified update could not be staged.");
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.dismiss();
                            installDownloadedUpdate();
                        }
                    });
                } catch (final Exception error) {
                    partial.delete();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.dismiss();
                            showUpdateError("The update was not installed: " + safeMessage(error), GITHUB_REPOSITORY_URL + "/releases/latest");
                        }
                    });
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }, "CameraLinkUpdateDownload").start();
    }

    private void installDownloadedUpdate() {
        File update = new File(getCacheDir(), BundledApkProvider.UPDATE_FILE_NAME);
        String validationError = validateDownloadedUpdate(update);
        if (validationError != null) {
            showUpdateError("The saved update cannot be installed: " + validationError, GITHUB_REPOSITORY_URL + "/releases/latest");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            launchingTailscale = true;
            preferences.edit().putBoolean("pending_app_update_install", true).apply();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri apk = Uri.parse("content://" + getPackageName() + ".apks/update.apk");
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apk, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        grantInstallerReadAccess(install, apk);
        launchingTailscale = true;
        startActivity(install);
    }

    private String validateDownloadedUpdate(File apk) {
        if (apk == null || !apk.isFile() || apk.length() == 0) return "The APK file is missing.";
        try {
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            PackageInfo installed = getPackageManager().getPackageInfo(getPackageName(), flags);
            if (archive == null || !getPackageName().equals(archive.packageName)) return "The downloaded APK is for a different app.";
            long archiveCode = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
            long installedCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
            if (archiveCode <= installedCode) return "The downloaded APK is not newer than the installed version.";
            Signature[] archiveSignatures = packageSignatures(archive);
            Signature[] installedSignatures = packageSignatures(installed);
            if (!sameSignatures(archiveSignatures, installedSignatures)) return "The APK signing certificate does not match this installation.";
            return null;
        } catch (Exception error) {
            return "Android could not verify the APK: " + safeMessage(error);
        }
    }

    private Signature[] packageSignatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            return info.signingInfo.hasMultipleSigners() ?
                    info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures;
    }

    private boolean sameSignatures(Signature[] first, Signature[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return false;
        boolean[] matched = new boolean[second.length];
        for (Signature candidate : first) {
            boolean found = false;
            for (int index = 0; index < second.length; index++) {
                if (!matched[index] && candidate.equals(second[index])) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean isNewerVersion(String remote, String current) {
        if (remote == null || remote.length() == 0) return false;
        String[] remoteParts = remote.split("\\.");
        String[] currentParts = current.split("\\.");
        int length = Math.max(remoteParts.length, currentParts.length);
        for (int index = 0; index < length; index++) {
            int remotePart = versionNumber(remoteParts, index);
            int currentPart = versionNumber(currentParts, index);
            if (remotePart != currentPart) return remotePart > currentPart;
        }
        return false;
    }

    private int versionNumber(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String digits = parts[index].replaceAll("[^0-9].*$", "");
        if (digits.length() == 0) return 0;
        try { return Integer.parseInt(digits); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String readText(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) text.append(line);
        reader.close();
        return text.toString();
    }

    private void showUpdateError(String message, final String releaseUrl) {
        new AlertDialog.Builder(this)
                .setTitle("Update check")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Open GitHub", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { openWebAddress(releaseUrl); }
                })
                .show();
    }

    private void openWebAddress(String address) {
        try {
            launchingTailscale = true;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (Exception error) {
            toast("No browser is available.");
        }
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().length() == 0 ? "Unknown error." : message;
    }

    private void sendTailscaleAction(String action) {
        if (!isPackageInstalled(TAILSCALE_PACKAGE)) return;
        Intent intent = new Intent(action);
        intent.setPackage(TAILSCALE_PACKAGE);
        sendBroadcast(intent);
    }

    private void openTailscale() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TAILSCALE_PACKAGE);
        if (launch != null) {
            launchingTailscale = true;
            startActivity(launch);
        } else {
            installBundledTailscale();
        }
    }

    private void installBundledTailscale() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            launchingTailscale = true;
            preferences.edit().putBoolean("pending_tailscale_install", true).apply();
            if (status != null) status.setText("Allow Camera Link to install Tailscale, then return.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (status != null) status.setText("Preparing the included Tailscale installer...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File staged = new File(getCacheDir(), BundledApkProvider.FILE_NAME);
                    InputStream input = getAssets().open(BundledApkProvider.FILE_NAME);
                    OutputStream output = new FileOutputStream(staged, false);
                    byte[] buffer = new byte[131072];
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                    output.close();
                    input.close();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { launchBundledTailscaleInstaller(); }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (status != null) status.setText("Could not prepare Tailscale: " + error.getMessage());
                            else toast("Could not prepare Tailscale.");
                        }
                    });
                }
            }
        }, "TailscaleInstaller").start();
    }

    private void launchBundledTailscaleInstaller() {
        Uri apk = Uri.parse("content://" + getPackageName() + ".apks/tailscale.apk");
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apk, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        grantInstallerReadAccess(install, apk);
        launchingTailscale = true;
        startActivity(install);
    }

    private void grantInstallerReadAccess(Intent install, Uri apk) {
        install.setClipData(ClipData.newRawUri("Camera Link APK", apk));
        try {
            List<android.content.pm.ResolveInfo> handlers = getPackageManager()
                    .queryIntentActivities(install, PackageManager.MATCH_DEFAULT_ONLY);
            for (android.content.pm.ResolveInfo handler : handlers) {
                if (handler.activityInfo != null) {
                    grantUriPermission(handler.activityInfo.packageName, apk,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
        } catch (RuntimeException ignored) { }
    }

    private void installBundledFactoryDvrIntegration() {
        String compatibilityError = factoryDvrCompatibilityError();
        if (compatibilityError != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Factory DVR build is not supported")
                    .setMessage(compatibilityError)
                    .setPositiveButton("Close", null)
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            launchingTailscale = true;
            preferences.edit().putBoolean("pending_factory_dvr_install", true).apply();
            toast("Allow Camera Link to install the factory DVR update, then return.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        toast("Preparing the tested HN7 factory DVR integration...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File staged = new File(getCacheDir(), BundledApkProvider.FACTORY_DVR_FILE_NAME);
                    InputStream input = getAssets().open(BundledApkProvider.FACTORY_DVR_FILE_NAME);
                    OutputStream output = new FileOutputStream(staged, false);
                    byte[] buffer = new byte[131072];
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                    output.close();
                    input.close();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { launchFactoryDvrIntegrationInstaller(); }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast("Could not prepare the factory DVR update: " + error.getMessage());
                        }
                    });
                }
            }
        }, "FactoryDvrIntegrationInstaller").start();
    }

    private String factoryDvrCompatibilityError() {
        try {
            PackageInfo installed = getPackageManager().getPackageInfo("com.hcn.dvr",
                    Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES :
                            PackageManager.GET_SIGNATURES);
            String version = installed.versionName == null ? "" : installed.versionName;
            if (!version.startsWith("1.4.20260309-uis8581")) {
                return "Installed com.hcn.dvr version " + version +
                        " does not match the tested HN7 factory DVR build. The update was not opened.";
            }
            Signature[] signatures = packageSignatures(installed);
            String certificate = signatures == null || signatures.length == 0 ? "" :
                    sha256Hex(signatures[0].toByteArray());
            if (!"c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
                    .equals(certificate)) {
                return "The installed factory DVR has a different signing certificate. " +
                        "The HN7 integration update was not opened.";
            }
            return null;
        } catch (Exception error) {
            return "ATOTO's com.hcn.dvr package is missing. Restore the factory DVR before installing this integration.";
        }
    }

    private String sha256Hex(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) hex.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
        return hex.toString();
    }

    private void launchFactoryDvrIntegrationInstaller() {
        Uri apk = Uri.parse("content://" + getPackageName() +
                ".apks/factory-dvr-integration.apk");
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apk, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        grantInstallerReadAccess(install, apk);
        preferences.edit().putBoolean("factory_dvr_installer_open", true).apply();
        launchingTailscale = true;
        startActivity(install);
    }

    private void startFactoryDvrAfterIntegrationInstall() {
        try {
            Intent factory = new Intent("android.intent.action.VIDEOIN_SERVICE")
                    .setClassName("com.hcn.dvr", "com.hcn.dvr.service.VideoService");
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(factory);
            else startService(factory);
            toast("Factory DVR update finished. Checking the integration...");
        } catch (RuntimeException error) {
            toast("Factory DVR updated. Restart the radio once to activate it.");
        }
        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                CameraStreamService service = CameraStreamService.getRunningInstance();
                if (service != null && service.isFactoryDvrIntegrationReady()) {
                    toast("ATOTO factory DVR integration connected.");
                }
                if (RADIO_GUIDE.equals(currentPage)) showRadioGuide();
                else if (RADIO_RECORDING.equals(currentPage)) showRadioRecording();
            }
        }, 4_000L);
    }

    private void openVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (Exception error) {
            toast("Open Android Settings > Network > VPN.");
        }
    }

    private boolean hasDvrLibraryAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return false;
        File directory = new File("/storage/udisk1/dvr");
        return directory.canRead();
    }

    private void openDvrLibraryAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 41);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception unavailable) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void copyConnectionDetails() {
        String tailIp = findIpv4Address(true);
        if (tailIp.length() == 0) {
            toast("Sign into Tailscale first so the phone address is available.");
            return;
        }
        String pin = CameraStreamService.getConnectionPassword(this);
        String details = "Vehicle: " + radioVehicleName() + "\nPhone app: " + tailIp + ":" + CameraStreamService.PORT +
                "  PIN " + pin + "\nWebsite: http://" + tailIp + ":" + CameraStreamService.PORT +
                "/  Username " + CameraStreamService.USERNAME + "  Password " + pin +
                "\nQR setup: " + setupUri(tailIp, pin);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("ATOTO Camera Link", details));
        toast("Phone connection details copied.");
    }

    private void showVehicleNameEditor() {
        currentPage = "vehicle_name";
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(22), dp(24), dp(20));
        page.setBackgroundColor(BG);
        TextView title = text("Name this vehicle", 27, TEXT, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, matchWrap());
        TextView body = text("The name appears on this radio, in its QR setup code, and in the phone vehicle picker.", 16, MUTED, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(6), 0, dp(8));
        page.addView(body, matchWrap());
        page.addView(new VehicleNamePad(radioVehicleName()), matchWrap());
        addAction(page, "Cancel", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioSecurity(); }
        });
        showStandalone(page);
    }

    private final class VehicleNamePad extends LinearLayout {
        private final StringBuilder value = new StringBuilder();
        private final TextView display;

        VehicleNamePad(String initial) {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(0, dp(4), 0, dp(3));
            value.append(normalizedVehicleName(initial, DEFAULT_VEHICLE_NAME));
            display = text(value.toString(), 21, TEXT, true);
            display.setGravity(Gravity.CENTER);
            display.setBackground(rounded(SURFACE_2, dp(12)));
            addView(display, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
            addNameKeyRow("1234567890");
            addNameKeyRow("QWERTYUIOP");
            addNameKeyRow("ASDFGHJKL");
            addNameKeyRow("ZXCVBNM-");
            LinearLayout controls = new LinearLayout(MainActivity.this);
            controls.setOrientation(HORIZONTAL);
            controls.addView(nameKey("CLEAR", new View.OnClickListener() {
                @Override public void onClick(View view) { value.setLength(0); refresh(); }
            }), new LinearLayout.LayoutParams(0, dp(52), 0.9f));
            controls.addView(nameKey("SPACE", new View.OnClickListener() {
                @Override public void onClick(View view) { append(" "); }
            }), new LinearLayout.LayoutParams(0, dp(52), 1.4f));
            controls.addView(nameKey("⌫", new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (value.length() > 0) value.deleteCharAt(value.length() - 1);
                    refresh();
                }
            }), new LinearLayout.LayoutParams(0, dp(52), 0.8f));
            addView(controls, matchWrap());
            addAction(this, "Save vehicle name", PRIMARY, new View.OnClickListener() {
                @Override public void onClick(View view) {
                    String saved = normalizedVehicleName(value.toString(), DEFAULT_VEHICLE_NAME);
                    preferences.edit().putString(VEHICLE_NAME_KEY, saved).apply();
                    toast(saved + " saved. Connected phones will sync this name.");
                    showRadioSecurity();
                }
            });
        }

        private void addNameKeyRow(String keys) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(HORIZONTAL);
            for (int index = 0; index < keys.length(); index++) {
                final String character = keys.substring(index, index + 1);
                row.addView(nameKey(character, new View.OnClickListener() {
                    @Override public void onClick(View view) { append(character); }
                }), new LinearLayout.LayoutParams(0, dp(52), 1));
            }
            addView(row, matchWrap());
        }

        private TextView nameKey(String label, View.OnClickListener listener) {
            TextView key = text(label, label.length() == 1 ? 18 : 13, TEXT, true);
            key.setGravity(Gravity.CENTER);
            key.setBackground(rounded(SURFACE_2, dp(9)));
            key.setOnClickListener(listener);
            key.setPadding(dp(2), dp(2), dp(2), dp(2));
            return key;
        }

        private void append(String character) {
            if (value.length() >= 24) {
                toast("Vehicle names can use up to 24 characters.");
                return;
            }
            if (" ".equals(character) && (value.length() == 0 || value.charAt(value.length() - 1) == ' ')) return;
            value.append(character);
            refresh();
        }

        private void refresh() {
            display.setText(value.length() == 0 ? "Enter a vehicle name" : value.toString());
        }
    }

    private boolean isOwnerLockEnabled() {
        return preferences.getString(OWNER_PIN_HASH_KEY, "").length() > 0;
    }

    private boolean isProtectedRadioPage(String page) {
        return RADIO_HOME.equals(page) || StockDvrSettingsBridge.PAGE_MENU.equals(page) ||
                RADIO_NETWORK.equals(page) ||
                RADIO_RECORDING.equals(page) || RADIO_SECURITY.equals(page) ||
                RADIO_GUIDE.equals(page) || RADIO_VIDEOS.equals(page) ||
                RADIO_PHOTOS.equals(page) || StockDvrSettingsBridge.PAGE_UPDATE.equals(page) ||
                "pin_setup".equals(page) || "vehicle_name".equals(page);
    }

    private boolean requireOwnerAccess(String destination) {
        if (!isOwnerLockEnabled() || ownerUnlocked ||
                StockDvrAccessGuard.isSessionUnlocked(this)) return false;
        showOwnerUnlock(destination);
        return true;
    }

    private void openProtectedRadioPage(String destination) {
        if (StockDvrSettingsBridge.PAGE_MENU.equals(destination)) showRadioMenu();
        else if (StockDvrSettingsBridge.PAGE_UPDATE.equals(destination)) {
            showRadioSecurity();
            checkForAppUpdate();
        }
        else if (RADIO_HOME.equals(destination)) showRadioHome();
        else if (RADIO_NETWORK.equals(destination)) showRadioNetwork();
        else if (RADIO_SECURITY.equals(destination)) showRadioSecurity();
        else if (RADIO_GUIDE.equals(destination)) showRadioGuide();
        else if (RADIO_VIDEOS.equals(destination)) showRadioVideos();
        else if (RADIO_PHOTOS.equals(destination)) showRadioPhotos();
        else if (RADIO_DVR.equals(destination)) showRadioDvr();
        else showRadioRecording();
    }

    private void showOwnerUnlock() {
        showOwnerUnlock(RADIO_RECORDING);
    }

    private void showOwnerUnlock(String destination) {
        ownerUnlockDestination = destination == null ? RADIO_RECORDING : destination;
        currentPage = "unlock";
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(22), dp(28), dp(24));
        page.setBackgroundColor(BG);
        TextView title = text("Owner controls locked", 27, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(4), 0, dp(8));
        page.addView(title, matchWrap());
        TextView body = text("Live cameras stay available. Enter the numeric owner PIN to open recordings or settings.", 16, MUTED, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 0, 0, dp(10));
        page.addView(body, matchWrap());
        status = addStatus(page, "");
        page.addView(new NumericPinPad("UNLOCK", new PinPadAction() {
            @Override public void onSubmit(String pin) {
                if (verifyOwnerPin(pin)) {
                    ownerUnlocked = true;
                    StockDvrAccessGuard.unlockSession(MainActivity.this);
                    scheduleOwnerSessionTimeout();
                    openProtectedRadioPage(ownerUnlockDestination);
                } else {
                    status.setText("Incorrect owner PIN.");
                }
            }
        }), matchWrap());
        addAction(page, "Back to live cameras", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioDvr(); }
        });
        showStandalone(page);
    }

    private void showOwnerPinSetup() {
        currentPage = "pin_setup";
        final boolean changing = isOwnerLockEnabled();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(34), dp(24), dp(34));
        page.setBackgroundColor(BG);
        TextView title = text(changing ? "Change owner PIN" : "Create owner PIN", 27, TEXT, true);
        page.addView(title, matchWrap());
        TextView body = text("The PIN locks recordings, photos, settings, and controls. Live cameras remain visible.", 16, MUTED, false);
        body.setPadding(0, dp(8), 0, dp(18));
        page.addView(body, matchWrap());
        final TextView prompt = text(changing ? "Enter current PIN" : "Create a new PIN (4–12 digits)", 18, TEXT, true);
        prompt.setGravity(Gravity.CENTER);
        prompt.setPadding(0, 0, 0, dp(5));
        page.addView(prompt, matchWrap());
        status = addStatus(page, "Only digits are accepted. Store the PIN somewhere safe.");
        final int[] stage = new int[]{changing ? 0 : 1};
        final String[] newPin = new String[]{""};
        final NumericPinPad[] pad = new NumericPinPad[1];
        pad[0] = new NumericPinPad("CONTINUE", new PinPadAction() {
            @Override public void onSubmit(String value) {
                if (!value.matches("[0-9]{4,12}")) {
                    status.setText("Use 4 to 12 digits.");
                    return;
                }
                if (stage[0] == -1) {
                    if (!verifyOwnerPin(value)) {
                        status.setText("The current PIN is incorrect.");
                        return;
                    }
                    preferences.edit().remove(OWNER_PIN_SALT_KEY).remove(OWNER_PIN_HASH_KEY).apply();
                    ownerUnlocked = false;
                    StockDvrAccessGuard.clearSession(MainActivity.this);
                    toast("Owner lock removed.");
                    showRadioDvr();
                } else if (stage[0] == 0) {
                    if (!verifyOwnerPin(value)) {
                        status.setText("The current PIN is incorrect.");
                        return;
                    }
                    stage[0] = 1;
                    pad[0].clear();
                    prompt.setText("Create a new PIN (4–12 digits)");
                    status.setText("Current PIN accepted.");
                } else if (stage[0] == 1) {
                    newPin[0] = value;
                    stage[0] = 2;
                    pad[0].clear();
                    pad[0].setSubmitLabel("SAVE PIN");
                    prompt.setText("Enter the new PIN again");
                    status.setText("Confirm the same digits.");
                } else if (newPin[0].equals(value)) {
                    saveOwnerPin(value);
                    ownerUnlocked = false;
                    toast("Owner PIN saved. Live cameras remain available while locked.");
                    showRadioDvr();
                } else {
                    stage[0] = 1;
                    newPin[0] = "";
                    pad[0].clear();
                    pad[0].setSubmitLabel("CONTINUE");
                    prompt.setText("Create a new PIN (4–12 digits)");
                    status.setText("PINs did not match. Enter a new PIN again.");
                }
            }
        });
        page.addView(pad[0], matchWrap());
        if (changing) {
            addAction(page, "Remove owner lock", SURFACE_2, new View.OnClickListener() {
                @Override public void onClick(View view) {
                    stage[0] = -1;
                    pad[0].clear();
                    pad[0].setSubmitLabel("REMOVE LOCK");
                    prompt.setText("Enter current PIN to remove the lock");
                    status.setText("Live cameras and all controls will become available without a PIN.");
                }
            });
        }
        addAction(page, "Cancel", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioSecurity(); }
        });
        showStandalone(page);
    }

    private void showOwnerPinRemoval() {
        currentPage = "pin_setup";
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(28), dp(28), dp(24));
        page.setBackgroundColor(BG);
        TextView title = text("Turn owner lock off", 27, TEXT, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, matchWrap());
        TextView body = text("Enter the current numeric PIN. Live cameras, recordings, settings, and controls will then open without a PIN.", 16, MUTED, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(8), 0, dp(10));
        page.addView(body, matchWrap());
        status = addStatus(page, "A successful unlock lasts 10 minutes across every DVR screen.");
        page.addView(new NumericPinPad("TURN LOCK OFF", new PinPadAction() {
            @Override public void onSubmit(String value) {
                if (!verifyOwnerPin(value)) {
                    status.setText("The current PIN is incorrect.");
                    return;
                }
                preferences.edit().remove(OWNER_PIN_SALT_KEY).remove(OWNER_PIN_HASH_KEY).apply();
                ownerUnlocked = false;
                StockDvrAccessGuard.clearSession(MainActivity.this);
                toast("Owner lock turned off.");
                showRadioSecurity();
            }
        }), matchWrap());
        addAction(page, "Cancel", SURFACE_2, new View.OnClickListener() {
            @Override public void onClick(View view) { showRadioSecurity(); }
        });
        showStandalone(page);
    }

    private interface PinPadAction {
        void onSubmit(String value);
    }

    private final class NumericPinPad extends LinearLayout {
        private final StringBuilder value = new StringBuilder();
        private final TextView display;
        private final TextView submit;
        private final PinPadAction action;

        NumericPinPad(String submitLabel, PinPadAction action) {
            super(MainActivity.this);
            this.action = action;
            setOrientation(VERTICAL);
            setPadding(0, dp(6), 0, dp(2));
            display = text("Enter PIN  •  4–12 digits", 18, MUTED, true);
            display.setGravity(Gravity.CENTER);
            display.setBackground(rounded(SURFACE_2, dp(12)));
            addView(display, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
            for (int row = 0; row < 3; row++) {
                LinearLayout keys = new LinearLayout(MainActivity.this);
                keys.setOrientation(HORIZONTAL);
                for (int column = 1; column <= 3; column++) {
                    final String digit = Integer.toString(row * 3 + column);
                    keys.addView(pinKey(digit, false, new View.OnClickListener() {
                        @Override public void onClick(View view) { append(digit); }
                    }), new LinearLayout.LayoutParams(0, dp(62), 1));
                }
                addView(keys, matchWrap());
            }
            LinearLayout last = new LinearLayout(MainActivity.this);
            last.setOrientation(HORIZONTAL);
            last.addView(pinKey("⌫", false, new View.OnClickListener() {
                @Override public void onClick(View view) { backspace(); }
            }), new LinearLayout.LayoutParams(0, dp(62), 1));
            last.addView(pinKey("0", false, new View.OnClickListener() {
                @Override public void onClick(View view) { append("0"); }
            }), new LinearLayout.LayoutParams(0, dp(62), 1));
            FrameLayout submitCell = pinKey("✓", true, new View.OnClickListener() {
                @Override public void onClick(View view) {
                    String entered = value.toString();
                    NumericPinPad.this.action.onSubmit(entered);
                    if (value.toString().equals(entered)) clear();
                }
            });
            submit = (TextView) submitCell.getChildAt(0);
            submit.setContentDescription(submitLabel);
            last.addView(submitCell, new LinearLayout.LayoutParams(0, dp(62), 1));
            addView(last, matchWrap());
        }

        private FrameLayout pinKey(String label, boolean primary, View.OnClickListener listener) {
            FrameLayout cell = new FrameLayout(MainActivity.this);
            TextView key = text(label, 20, TEXT, true);
            key.setGravity(Gravity.CENTER);
            key.setText(label);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(primary ? PRIMARY : SURFACE_2);
            circle.setStroke(dp(1), primary ? Color.rgb(105, 165, 255) : Color.rgb(48, 79, 108));
            key.setBackground(circle);
            key.setElevation(dp(3));
            key.setOnClickListener(listener);
            FrameLayout.LayoutParams keyParams = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER);
            cell.addView(key, keyParams);
            return cell;
        }

        private void append(String digit) {
            if (value.length() >= 12) return;
            value.append(digit);
            updateDisplay();
        }

        private void backspace() {
            if (value.length() > 0) value.deleteCharAt(value.length() - 1);
            updateDisplay();
        }

        void clear() {
            value.setLength(0);
            updateDisplay();
        }

        void setSubmitLabel(String label) {
            submit.setContentDescription(label);
        }

        private void updateDisplay() {
            if (value.length() == 0) {
                display.setText("Enter PIN  •  4–12 digits");
                display.setTextColor(MUTED);
                return;
            }
            StringBuilder masked = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                if (i > 0) masked.append(' ');
                masked.append('●');
            }
            display.setText(masked.toString());
            display.setTextColor(TEXT);
        }
    }

    private void saveOwnerPin(String pin) {
        byte[] saltBytes = new byte[18];
        new SecureRandom().nextBytes(saltBytes);
        String salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP | Base64.URL_SAFE);
        preferences.edit().putString(OWNER_PIN_SALT_KEY, salt)
                .putString(OWNER_PIN_HASH_KEY, hashOwnerPin(pin, salt)).commit();
        StockDvrAccessGuard.clearSession(this);
        ownerSessionHandler.removeCallbacks(ownerSessionTimeout);
    }

    private void scheduleOwnerSessionTimeout() {
        ownerSessionHandler.removeCallbacks(ownerSessionTimeout);
        long remaining = StockDvrAccessGuard.sessionRemainingMs(this);
        if (remaining > 0L) ownerSessionHandler.postDelayed(ownerSessionTimeout, remaining + 100L);
    }

    private boolean verifyOwnerPin(String pin) {
        String salt = preferences.getString(OWNER_PIN_SALT_KEY, "");
        String expected = preferences.getString(OWNER_PIN_HASH_KEY, "");
        if (salt.length() == 0 || expected.length() == 0) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                hashOwnerPin(pin, salt).getBytes(StandardCharsets.UTF_8));
    }

    private String hashOwnerPin(String pin, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.encodeToString(digest.digest((salt + ":" + pin)
                    .getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String findIpv4Address(boolean tailscaleOnly) {
        String fallback = "radio-ip";
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String value = address.getHostAddress();
                    boolean tail = isTailscaleAddress(value);
                    if (tailscaleOnly && tail) return value;
                    if (!tailscaleOnly && !tail && network.getName().startsWith("wlan")) return value;
                    if (!tailscaleOnly && !tail && "radio-ip".equals(fallback)) fallback = value;
                }
            }
        } catch (Exception ignored) { }
        return tailscaleOnly ? "" : fallback;
    }

    private boolean isTailscaleAddress(String address) {
        String[] parts = address.split("\\.");
        if (parts.length != 4) return false;
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 100 && second >= 64 && second <= 127;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String findRemovableStorage() {
        File[] roots = getExternalFilesDirs(null);
        if (roots != null) {
            for (int i = 1; i < roots.length; i++) {
                if (roots[i] != null) return roots[i].getAbsolutePath();
            }
        }
        return "Removable USB drive not found";
    }

    private File findDvrUsbRoot() {
        File atoto = new File("/storage/udisk1");
        if (atoto.exists()) return atoto;
        File[] roots = getExternalFilesDirs(null);
        if (roots != null) for (File root : roots) {
            if (root == null || !Environment.isExternalStorageRemovable(root)) continue;
            String path = root.getAbsolutePath().replace('\\', '/');
            int android = path.indexOf("/Android/");
            return new File(android > 0 ? path.substring(0, android) : path);
        }
        return null;
    }

    private long cameraLinkFolderSize(String type) {
        long size = 0;
        File[] roots = getExternalFilesDirs(type);
        if (roots != null) for (File root : roots) {
            if (root != null && root.getAbsolutePath().contains("udisk1")) size += folderSize(root, 0);
        }
        return size;
    }

    private long folderSize(File file, int depth) {
        if (file == null || !file.exists() || depth > 12) return 0;
        if (file.isFile()) return file.length();
        long size = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) size += folderSize(child, depth + 1);
        return size;
    }

    private String findDvrStorageId(boolean diskId) {
        try {
            Object manager = getSystemService("storage");
            Method getVolumes = manager.getClass().getMethod("getVolumes");
            List<?> volumes = (List<?>) getVolumes.invoke(manager);
            for (Object volume : volumes) {
                Method getPath = volume.getClass().getMethod("getPath");
                File path = (File) getPath.invoke(volume);
                if (path == null || !path.getAbsolutePath().contains("udisk1")) continue;
                if (!diskId) return String.valueOf(volume.getClass().getMethod("getId").invoke(volume));
                Object disk = volume.getClass().getMethod("getDisk").invoke(volume);
                if (disk != null) return String.valueOf(disk.getClass().getMethod("getId").invoke(disk));
            }
        } catch (Throwable ignored) { }
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/self/mountinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains(" /mnt/media_rw/udisk1 ") && !line.contains(" /storage/udisk1 ")) continue;
                String[] fields = line.split(" ");
                if (fields.length < 3 || !fields[2].matches("[0-9]+:[0-9]+")) continue;
                String[] device = fields[2].split(":");
                reader.close();
                return diskId ? "disk:" + device[0] + ",0" :
                        "public:" + device[0] + "," + device[1];
            }
            reader.close();
        } catch (Throwable ignored) { }
        return "";
    }

    private void confirmFormatDvrUsb() {
        new AlertDialog.Builder(this)
                .setTitle("Format DVR USB?")
                .setMessage("This permanently erases every recording, photo, and other file on the special DVR USB drive. This is the final confirmation before Android starts formatting.")
                .setPositiveButton("Format drive", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { openDvrFormatScreen(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openDvrFormatScreen() {
        String diskId = findDvrStorageId(true);
        if (diskId.length() == 0) {
            toast("The DVR USB disk was not found. Opening Android Storage settings.");
            try { startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); }
            catch (Throwable ignored) { }
            return;
        }
        stopService(new Intent(this, CameraStreamService.class));
        try {
            Intent format = new Intent();
            format.setClassName("com.android.settings", "com.android.settings.deviceinfo.StorageWizardFormatProgress");
            format.putExtra("android.os.storage.extra.DISK_ID", diskId);
            startActivity(format);
        } catch (Throwable error) {
            toast("This ATOTO firmware did not allow the format screen. Open Android Storage settings.");
            try { startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); }
            catch (Throwable ignored) { }
        }
    }

    private void confirmEjectDvrUsb() {
        new AlertDialog.Builder(this)
                .setTitle("Safely eject DVR USB?")
                .setMessage("Recording will pause before Android unmounts the special DVR drive.")
                .setPositiveButton("Eject", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        stopService(new Intent(MainActivity.this, CameraStreamService.class));
                        String volumeId = findDvrStorageId(false);
                        try {
                            Object manager = getSystemService("storage");
                            manager.getClass().getMethod("unmount", String.class).invoke(manager, volumeId);
                            toast("DVR USB safely ejected.");
                        } catch (Throwable error) {
                            toast("Android denied safe eject. Use the radio's Storage settings before unplugging.");
                            ensurePermissionAndStart();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        if (drawerOverlay != null && drawerOverlay.getVisibility() == View.VISIBLE) {
            drawerOverlay.setVisibility(View.GONE);
        } else if (RADIO_VIDEOS.equals(currentPage) || RADIO_PHOTOS.equals(currentPage) ||
                RADIO_RECORDING.equals(currentPage) || RADIO_NETWORK.equals(currentPage) ||
                 RADIO_HOME.equals(currentPage) || RADIO_SECURITY.equals(currentPage) ||
                 RADIO_GUIDE.equals(currentPage) || "vehicle_name".equals(currentPage)) {
            showRadioDvr();
        } else if (PHONE_RECORDINGS.equals(currentPage) || PHONE_CONNECTION.equals(currentPage) ||
                PHONE_VPN.equals(currentPage) || PHONE_SETTINGS.equals(currentPage)) {
            showPhoneLive();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
