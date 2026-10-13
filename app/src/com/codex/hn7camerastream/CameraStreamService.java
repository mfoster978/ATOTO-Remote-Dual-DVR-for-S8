package com.codex.hn7camerastream;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public class CameraStreamService extends Service implements SensorEventListener, LocationListener {
    public static final String ACTION_STOP = "com.codex.hn7camerastream.STOP";
    public static final String ACTION_RECORD_START = "com.codex.hn7camerastream.RECORD_START";
    public static final String ACTION_RECORD_STOP = "com.codex.hn7camerastream.RECORD_STOP";
    public static final String ACTION_FRONT_RECORD_START = "com.codex.hn7camerastream.FRONT_RECORD_START";
    public static final String ACTION_FRONT_RECORD_STOP = "com.codex.hn7camerastream.FRONT_RECORD_STOP";
    public static final String ACTION_CAPTURE = "com.codex.hn7camerastream.CAPTURE";
    public static final String ACTION_PROTECT = "com.codex.hn7camerastream.PROTECT";
    public static final String ACTION_AUDIO_CHANGED = "com.codex.hn7camerastream.AUDIO_CHANGED";
    public static final String ACTION_EMERGENCY_LOCK = "com.codex.hn7camerastream.EMERGENCY_LOCK";
    public static final String ACTION_PARKING_SETTINGS_CHANGED = "com.codex.hn7camerastream.PARKING_SETTINGS_CHANGED";
    public static final int PORT = 8765;
    public static final String USERNAME = "camera";

    private static final String TAG = "HN7CameraStream";
    private static final String CHANNEL_ID = "camera_stream";
    private static final int NOTIFICATION_ID = 8765;
    private static final long FRAME_INTERVAL_MS = 200;
    private static final long MIN_FREE_BYTES = 1024L * 1024L * 1024L;
    private static final long SEGMENT_HEADROOM_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_LIBRARY_FILES = 500;
    private static final String FACTORY_DVR_DIRECTORY = "/storage/udisk1/dvr";
    private static final String ACTION_ATOTO_ACC = "android.mcu.device.action.ACC";
    private static final String ACTION_ATOTO_DVR_RECORD = "com.hcn.dvr.action.record";
    private static final String EXTRA_ATOTO_DVR_RECORD_STATE = "dvr_state";
    private static final long SYSTEM_UI_RECORDING_RECONCILE_MS = 5_000L;
    private static final long PARKING_HEARTBEAT_MS = 5_000L;
    private static volatile CameraStreamService runningInstance;

    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final ExecutorService encoder = Executors.newSingleThreadExecutor();
    private final ExecutorService recorderExecutor = Executors.newSingleThreadExecutor();
    private final Object frameLock = new Object();
    private final Object cameraLock = new Object();
    private final Object recorderLock = new Object();
    private final AtomicBoolean encoding = new AtomicBoolean(false);
    private final AtomicBoolean recordFrameBusy = new AtomicBoolean(false);
    private final AtomicBoolean recordAudioBusy = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile byte[] latestJpeg;
    private volatile String cameraError = "Camera has not been requested";
    private volatile int activeCamera = -1;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile long lastFrameEncoded;
    private volatile boolean recording;
    private volatile boolean recordingRequested;
    private volatile String recordingError = "Recording has not been enabled";
    private volatile String currentRecordingPath = "";
    private volatile String recordingDirectory = "";

    private ServerSocket serverSocket;
    private Thread serverThread;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Camera camera;
    private SurfaceTexture surfaceTexture;
    private volatile SurfaceHolder reversePreviewHolder;
    private MediaCodec videoCodec;
    private MediaCodec audioCodec;
    private MediaMuxer mediaMuxer;
    private int mediaMuxerTrack = -1;
    private int audioMuxerTrack = -1;
    private boolean mediaMuxerStarted;
    private boolean audioEnabledForSegment;
    private long recordingStartNs;
    private byte[] i420Buffer;
    private PowerManager.WakeLock wakeLock;
    private FrontPipeline frontPipeline;
    private MicrophoneHub microphoneHub;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private final Handler emergencyHandler = new Handler(Looper.getMainLooper());
    private volatile long lastEmergencyAt;
    private float gravityX;
    private float gravityY;
    private float gravityZ;
    private boolean impactSensorPrimed;
    private long impactSensorReadyAt;
    private LocationManager locationManager;
    private volatile Location latestLocation;
    private final Object watermarkLock = new Object();
    private WatermarkFrame watermarkFrame;
    private final Handler parkingHandler = new Handler(Looper.getMainLooper());
    private AtotoParkingBridge parkingBridge;
    private BroadcastReceiver accReceiver;
    private volatile boolean ignitionOn = true;
    private volatile boolean parkingActive;
    private volatile float vehicleBatteryVoltage = -1f;
    private volatile long parkingSessionStartedAt;
    private volatile String parkingStatus = "Parking controller is starting";
    private volatile boolean parkingSessionRan;
    private Boolean lastPublishedSystemUiRecordingState;

    private final Runnable systemUiRecordingReconciler = new Runnable() {
        @Override public void run() {
            publishSystemUiRecordingState(isActivelyRecording(), true);
            parkingHandler.postDelayed(this, SYSTEM_UI_RECORDING_RECONCILE_MS);
        }
    };

    private final Runnable parkingHeartbeat = new Runnable() {
        @Override public void run() {
            runParkingHeartbeat();
        }
    };

    private static final class WatermarkFrame {
        final String key;
        final int width;
        final int height;
        final int x;
        final int y;
        final int[] pixels;

        WatermarkFrame(String key, int width, int height, int x, int y, int[] pixels) {
            this.key = key;
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.pixels = pixels;
        }
    }

    private final Runnable rollRecordingSegment = new Runnable() {
        @Override public void run() {
            if (!recordingRequested || activeCamera != 1 || camera == null) return;
            stopRecorderOnCameraThread(false);
            if (recordingRequested) startRecorderOnCameraThread();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        runningInstance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for a viewer"));

        cameraThread = new HandlerThread("HN7CameraWorker");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        frontPipeline = new FrontPipeline();
        microphoneHub = new MicrophoneHub();
        publishSystemUiRecordingState(false, true);
        parkingHandler.removeCallbacks(systemUiRecordingReconciler);
        parkingHandler.postDelayed(systemUiRecordingReconciler, SYSTEM_UI_RECORDING_RECONCILE_MS);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                impactSensorReadyAt = System.currentTimeMillis() + 5_000L;
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
        startLocationTracking();

        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HN7CameraStream:server");
        wakeLock.acquire();
        initializeParkingController();
        startServer();
        requestTailscaleConnect();

        recordingRequested = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("record_camera_1", false);
        final boolean frontRequested = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("primary_dvr_mode", false) && getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("record_camera_0", false);
        boolean mayRecord = ignitionOn || parkingActive;
        if (frontRequested && mayRecord) {
            new Thread(new Runnable() {
                @Override public void run() { frontPipeline.startRecordingBlocking(false); }
            }, "HN7FrontAutoRecord").start();
        }
        if (recordingRequested && mayRecord) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { startRecordingBlocking(1, false); }
                    catch (Exception e) { recordingError = e.toString(); }
                }
            }, "HN7AutoRecord").start();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RECORD_START.equals(intent.getAction())) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { startRecordingBlocking(1, true); }
                    catch (Exception e) { recordingError = e.toString(); }
                }
            }, "HN7StartRecord").start();
        } else if (intent != null && ACTION_RECORD_STOP.equals(intent.getAction())) {
            stopRecordingBlocking(true);
        } else if (intent != null && ACTION_FRONT_RECORD_START.equals(intent.getAction())) {
            final int requestedFrontCamera = intent.getIntExtra("camera_id", 0);
            new Thread(new Runnable() {
                @Override public void run() {
                    frontPipeline.selectInputBlocking(requestedFrontCamera);
                    frontPipeline.startRecordingBlocking(true);
                }
            }, "HN7StartFrontRecord").start();
        } else if (intent != null && ACTION_FRONT_RECORD_STOP.equals(intent.getAction())) {
            frontPipeline.stopRecordingBlocking(true);
        } else if (intent != null && ACTION_CAPTURE.equals(intent.getAction())) {
            final int cameraId = intent.getIntExtra("camera_id", 0);
            new Thread(new Runnable() {
                @Override public void run() {
                    if (cameraId == 3) {
                        saveSnapshotBlocking(0);
                        saveSnapshotBlocking(1);
                    } else {
                        saveSnapshotBlocking(cameraId);
                    }
                }
            }, "HN7SavePhoto").start();
        } else if (intent != null && ACTION_PROTECT.equals(intent.getAction())) {
            final int cameraId = intent.getIntExtra("camera_id", 0);
            new Thread(new Runnable() {
                @Override public void run() {
                    if (cameraId == 3) {
                        frontPipeline.protectCurrentBlocking();
                        protectReverseBlocking();
                    } else if (cameraId == 0 || cameraId == 2) {
                        frontPipeline.protectCurrentBlocking();
                    } else {
                        protectReverseBlocking();
                    }
                }
            }, "HN7ProtectClip").start();
        } else if (intent != null && ACTION_AUDIO_CHANGED.equals(intent.getAction())) {
            new Thread(new Runnable() {
                @Override public void run() {
                    frontPipeline.restartForAudioSettingBlocking();
                    restartReverseForAudioSettingBlocking();
                }
            }, "HN7AudioSetting").start();
        } else if (intent != null && ACTION_EMERGENCY_LOCK.equals(intent.getAction())) {
            triggerEmergencyLock("vehicle G-sensor");
        } else if (intent != null && ACTION_PARKING_SETTINGS_CHANGED.equals(intent.getAction())) {
            applyParkingSettings();
        }
        if (!running) startServer();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (runningInstance == this) runningInstance = null;
        parkingHandler.removeCallbacks(parkingHeartbeat);
        parkingHandler.removeCallbacks(systemUiRecordingReconciler);
        parkingActive = false;
        if (accReceiver != null) {
            try { unregisterReceiver(accReceiver); } catch (Throwable ignored) { }
            accReceiver = null;
        }
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Throwable ignored) { }
        }
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        recordingRequested = false;
        releaseCameraBlocking();
        if (microphoneHub != null) microphoneHub.shutdown();
        if (frontPipeline != null) frontPipeline.shutdown();
        publishSystemUiRecordingState(false, true);
        clients.shutdownNow();
        encoder.shutdownNow();
        recorderExecutor.shutdownNow();
        if (cameraThread != null) cameraThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        super.onDestroy();
    }

    public static CameraStreamService getRunningInstance() {
        return runningInstance;
    }

    public void handleMcuEmergencyEvent() {
        triggerEmergencyLock("vehicle G-sensor");
    }

    private void initializeParkingController() {
        parkingBridge = new AtotoParkingBridge();
        int acc = parkingBridge.readAccStatus();
        if (acc >= 0) ignitionOn = acc != 0;
        vehicleBatteryVoltage = parkingBridge.readBatteryVoltage();
        accReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!ACTION_ATOTO_ACC.equals(intent.getAction())) return;
                handleAccState(intent.getBooleanExtra("AccStatus", true));
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_ATOTO_ACC);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(accReceiver, filter, Context.RECEIVER_EXPORTED);
            else registerReceiver(accReceiver, filter);
        } catch (Throwable error) {
            parkingStatus = "ACC receiver unavailable: " + error.getClass().getSimpleName();
            Log.w(TAG, parkingStatus, error);
        }
        applyParkingSettings();
    }

    private void handleAccState(boolean isOn) {
        ignitionOn = isOn;
        if (isOn) {
            parkingHandler.removeCallbacks(parkingHeartbeat);
            parkingActive = false;
            parkingSessionStartedAt = 0L;
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putLong("parking_session_started_at", 0L).apply();
            if (parkingSessionRan && "once".equals(parkingActivationMode())) {
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putBoolean("parking_mode_enabled", false)
                        .putBoolean("parking_once_armed", false).apply();
                AtotoParkingBridge.setSystemSentryEnabled(this, false);
                parkingStatus = "One-time parking session completed";
            } else {
                configureSystemSentry();
                parkingStatus = "Ignition on";
            }
            parkingSessionRan = false;
            ensureWakeLock();
            resumeConfiguredRecordings();
            requestTailscaleConnect();
            updateNotification("Camera server running");
        } else {
            beginParkingSessionIfConfigured();
        }
    }

    public void applyParkingSettings() {
        configureSystemSentry();
        if (ignitionOn) {
            parkingStatus = parkingEnabled() ? "Armed for ignition off" : "Parking mode off";
            vehicleBatteryVoltage = parkingBridge == null ? -1f : parkingBridge.readBatteryVoltage();
            return;
        }
        if (parkingEnabled()) beginParkingSessionIfConfigured();
        else finishParkingSession("Parking mode turned off");
    }

    private void configureSystemSentry() {
        boolean enabled = parkingEnabled();
        if (AtotoParkingBridge.canControlSystemSentry(this)) {
            if (!AtotoParkingBridge.setSystemSentryEnabled(this, enabled)) {
                parkingStatus = "Could not update ATOTO parking setting";
            }
        } else if (enabled && !AtotoParkingBridge.isSystemSentryEnabled(this)) {
            parkingStatus = "Allow system setting access to activate parking mode";
        }
    }

    private void beginParkingSessionIfConfigured() {
        if (!parkingEnabled()) {
            finishParkingSession("Parking mode is off");
            return;
        }
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        long savedStart = settings.getLong("parking_session_started_at", 0L);
        boolean continuous = "continuous".equals(parkingActivationMode());
        boolean oneTimeArmed = settings.getBoolean("parking_once_armed", true);
        if (!continuous && !oneTimeArmed && savedStart == 0L) {
            finishParkingSession("One-time parking session has already been used");
            return;
        }
        if (parkingBridge == null || !parkingBridge.isAvailable()) {
            finishParkingSession("ATOTO vehicle power service is unavailable");
            return;
        }
        boolean sentryReady = AtotoParkingBridge.isSystemSentryEnabled(this);
        if (!sentryReady && AtotoParkingBridge.canControlSystemSentry(this)) {
            sentryReady = AtotoParkingBridge.setSystemSentryEnabled(this, true);
        }
        if (!sentryReady) {
            finishParkingSession("Allow system setting access to activate parking mode");
            return;
        }
        if (savedStart == 0L) {
            savedStart = System.currentTimeMillis();
            settings.edit().putLong("parking_session_started_at", savedStart)
                    .putBoolean("parking_once_armed", false).apply();
        }
        parkingSessionStartedAt = savedStart;
        parkingSessionRan = true;
        parkingActive = true;
        ensureWakeLock();
        resumeConfiguredRecordings();
        parkingHandler.removeCallbacks(parkingHeartbeat);
        runParkingHeartbeat();
    }

    private void runParkingHeartbeat() {
        if (!parkingActive || ignitionOn) return;
        long elapsed = Math.max(0L, System.currentTimeMillis() - parkingSessionStartedAt);
        long limit = parkingDurationMinutes() * 60_000L;
        if (elapsed >= limit) {
            finishParkingSession("Parking duration reached");
            return;
        }
        vehicleBatteryVoltage = parkingBridge.readBatteryVoltage();
        if (vehicleBatteryVoltage <= 0f) {
            finishParkingSession("Vehicle battery voltage is unavailable");
            return;
        }
        if (vehicleBatteryVoltage <= parkingCutoffVoltage()) {
            finishParkingSession(String.format(Locale.US,
                    "Battery cutoff reached at %.1f V", vehicleBatteryVoltage));
            return;
        }
        if (!parkingBridge.sendSentryHeartbeat()) {
            finishParkingSession("ATOTO parking heartbeat failed");
            return;
        }
        long remainingMinutes = Math.max(1L, (limit - elapsed + 59_999L) / 60_000L);
        parkingStatus = String.format(Locale.US, "Recording parked • %.1f V • %s remaining",
                vehicleBatteryVoltage, formatParkingRemaining(remainingMinutes));
        updateNotification(parkingStatus);
        parkingHandler.postDelayed(parkingHeartbeat, PARKING_HEARTBEAT_MS);
    }

    private void finishParkingSession(String reason) {
        boolean wasActive = parkingActive;
        parkingActive = false;
        parkingHandler.removeCallbacks(parkingHeartbeat);
        parkingSessionStartedAt = 0L;
        parkingStatus = reason;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putLong("parking_session_started_at", 0L)
                .putString("parking_last_stop_reason", reason).apply();
        if (!ignitionOn && (wasActive || (frontPipeline != null && frontPipeline.recording) || recording)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    pauseRecordingsForParkingSleep();
                    releaseWakeLock();
                }
            }, "HN7ParkingStop").start();
        } else if (!ignitionOn) {
            releaseWakeLock();
        }
        updateNotification(reason);
    }

    private void resumeConfiguredRecordings() {
        final SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        final boolean frontWanted = settings.getBoolean("primary_dvr_mode", false) &&
                settings.getBoolean("record_camera_0", false);
        final boolean reverseWanted = settings.getBoolean("record_camera_1", false);
        new Thread(new Runnable() {
            @Override public void run() {
                if (frontWanted && frontPipeline != null && !frontPipeline.recordingRequested) {
                    frontPipeline.startRecordingBlocking(false);
                }
                if (reverseWanted && !recordingRequested) {
                    try { startRecordingBlocking(1, false); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            }
        }, "HN7ResumeConfiguredRecording").start();
    }

    private void pauseRecordingsForParkingSleep() {
        if (frontPipeline != null) {
            frontPipeline.stopRecordingBlocking(false);
            frontPipeline.releaseBlocking();
        }
        stopRecordingBlocking(false);
        releaseCameraBlocking();
    }

    private boolean parkingEnabled() {
        return getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("parking_mode_enabled", false);
    }

    private String parkingActivationMode() {
        return getSharedPreferences("settings", MODE_PRIVATE)
                .getString("parking_activation_mode", "continuous");
    }

    private int parkingDurationMinutes() {
        int value = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("parking_duration_minutes", 960);
        return value == 30 || value == 960 || value == 1440 ? value : 960;
    }

    private float parkingCutoffVoltage() {
        int tenths = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("parking_voltage_tenths", 118);
        return tenths / 10f;
    }

    private String formatParkingRemaining(long minutes) {
        if (minutes < 60L) return minutes + " min";
        long hours = minutes / 60L;
        long remainder = minutes % 60L;
        return remainder == 0 ? hours + " h" : hours + " h " + remainder + " min";
    }

    private void ensureWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(); } catch (Throwable ignored) { }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) { }
        }
    }

    public boolean isIgnitionOn() { return ignitionOn; }
    public boolean isParkingActive() { return parkingActive; }
    public float getVehicleBatteryVoltage() { return vehicleBatteryVoltage; }
    public String getParkingStatus() { return parkingStatus; }
    public boolean isParkingVendorAvailable() {
        return parkingBridge != null && parkingBridge.isAvailable();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER || event.values.length < 3) return;
        if (System.currentTimeMillis() < impactSensorReadyAt) return;
        int sensitivity = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("emergency_sensitivity", 3);
        if (sensitivity <= 0) return;
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        if (!impactSensorPrimed) {
            gravityX = x;
            gravityY = y;
            gravityZ = z;
            impactSensorPrimed = true;
            return;
        }
        final float alpha = 0.82f;
        gravityX = alpha * gravityX + (1.0f - alpha) * x;
        gravityY = alpha * gravityY + (1.0f - alpha) * y;
        gravityZ = alpha * gravityZ + (1.0f - alpha) * z;
        float linearX = x - gravityX;
        float linearY = y - gravityY;
        float linearZ = z - gravityZ;
        float impact = (float) Math.sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ);
        float threshold = sensitivity == 1 ? 10.0f : sensitivity == 2 ? 5.7f : 3.7f;
        if (impact >= threshold) triggerEmergencyLock("impact sensor");
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void startLocationTracking() {
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("watermark_enabled", true)) return;
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("watermark_speed", true) &&
                !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("watermark_gps", true)) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
                latestLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
        } catch (Throwable error) {
            Log.w(TAG, "GPS watermark provider unavailable", error);
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this);
                Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (network != null && (latestLocation == null || network.getTime() > latestLocation.getTime())) latestLocation = network;
            }
        } catch (Throwable error) {
            Log.w(TAG, "Network location watermark provider unavailable", error);
        }
    }

    @Override public void onLocationChanged(Location location) {
        if (location != null) latestLocation = location;
    }

    @Override public void onProviderEnabled(String provider) { }

    @Override public void onProviderDisabled(String provider) { }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    public String currentWatermarkText() {
        android.content.SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        if (!settings.getBoolean("watermark_enabled", true)) return "";
        StringBuilder first = new StringBuilder();
        if (settings.getBoolean("watermark_datetime", true)) {
            first.append(new SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US).format(new Date()));
        }
        Location location = latestLocation;
        if (settings.getBoolean("watermark_speed", true)) {
            if (first.length() > 0) first.append("   •   ");
            if (location == null) first.append("SPEED -- MPH");
            else first.append(String.format(Locale.US, "%.0f MPH", Math.max(0f, location.getSpeed() * 2.2369363f)));
        }
        String second = "";
        if (settings.getBoolean("watermark_gps", true)) {
            second = location == null ? "GPS acquiring…" :
                    String.format(Locale.US, "GPS %.6f, %.6f", location.getLatitude(), location.getLongitude());
        }
        if (first.length() == 0) return second;
        return second.length() == 0 ? first.toString() : first + "\n" + second;
    }

    public void refreshWatermarkSettings() {
        synchronized (watermarkLock) { watermarkFrame = null; }
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Throwable ignored) { }
            locationManager = null;
        }
        startLocationTracking();
    }

    private void applyWatermark(byte[] nv21, int frameWidth, int frameHeight) {
        if (nv21 == null || nv21.length < frameWidth * frameHeight) return;
        String text = currentWatermarkText();
        if (text.length() == 0) return;
        String key = frameWidth + "x" + frameHeight + "|" + text;
        WatermarkFrame overlay;
        synchronized (watermarkLock) {
            overlay = watermarkFrame;
            if (overlay == null || !key.equals(overlay.key)) {
                overlay = renderWatermark(key, text, frameWidth, frameHeight);
                watermarkFrame = overlay;
            }
        }
        if (overlay == null) return;
        int yPlaneSize = frameWidth * frameHeight;
        for (int row = 0; row < overlay.height; row++) {
            int targetRow = (overlay.y + row) * frameWidth + overlay.x;
            int sourceRow = row * overlay.width;
            for (int column = 0; column < overlay.width; column++) {
                int target = targetRow + column;
                if (target < 0 || target >= yPlaneSize) continue;
                int pixel = overlay.pixels[sourceRow + column];
                int alpha = Color.alpha(pixel);
                if (alpha == 0) continue;
                int luminance = (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) >> 8;
                int original = nv21[target] & 0xff;
                nv21[target] = (byte) ((luminance * alpha + original * (255 - alpha)) / 255);
            }
        }
    }

    private WatermarkFrame renderWatermark(String key, String text, int frameWidth, int frameHeight) {
        try {
            String[] lines = text.split("\\n", -1);
            float textSize = Math.max(18f, Math.min(34f, frameHeight / 26f));
            int padding = Math.max(9, Math.round(textSize * 0.48f));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(textSize);
            paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,
                    android.graphics.Typeface.BOLD));
            float widest = 0;
            for (String line : lines) widest = Math.max(widest, paint.measureText(line));
            Paint.FontMetrics metrics = paint.getFontMetrics();
            int lineHeight = Math.max(1, Math.round(metrics.descent - metrics.ascent + textSize * 0.15f));
            int overlayWidth = Math.min(frameWidth - 16, Math.max(1, Math.round(widest) + padding * 2));
            int overlayHeight = Math.min(frameHeight - 16, Math.max(1, lineHeight * lines.length + padding * 2));
            Bitmap bitmap = Bitmap.createBitmap(overlayWidth, overlayHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
            background.setColor(Color.argb(175, 0, 0, 0));
            canvas.drawRoundRect(new RectF(0, 0, overlayWidth, overlayHeight), padding, padding, background);
            paint.setColor(Color.WHITE);
            paint.setShadowLayer(2.5f, 1f, 1f, Color.BLACK);
            float baseline = padding - metrics.ascent;
            for (String line : lines) {
                canvas.drawText(line, padding, baseline, paint);
                baseline += lineHeight;
            }
            int[] pixels = new int[overlayWidth * overlayHeight];
            bitmap.getPixels(pixels, 0, overlayWidth, 0, 0, overlayWidth, overlayHeight);
            bitmap.recycle();
            int margin = Math.max(8, frameWidth / 80);
            return new WatermarkFrame(key, overlayWidth, overlayHeight, margin,
                    Math.max(0, frameHeight - overlayHeight - margin), pixels);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to render watermark", error);
            return null;
        }
    }

    private synchronized void triggerEmergencyLock(String source) {
        final long now = System.currentTimeMillis();
        if (now - lastEmergencyAt < 10_000L) return;
        final int sensitivity = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("emergency_sensitivity", 3);
        if (sensitivity <= 0) return;
        lastEmergencyAt = now;
        final int durationSeconds = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("emergency_duration_seconds", 30);
        Log.w(TAG, "Emergency recording triggered by " + source + " for " + durationSeconds + " seconds");
        updateNotification("Emergency recording locked");
        new Thread(new Runnable() {
            @Override public void run() {
                if (frontPipeline.recordingRequested || frontPipeline.recording) {
                    frontPipeline.protectCurrentBlocking();
                } else {
                    frontPipeline.startRecordingBlocking(false);
                }
                if (recordingRequested || recording) protectReverseBlocking();
            }
        }, "HN7EmergencyBefore").start();
        emergencyHandler.postDelayed(new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        if (frontPipeline.recordingRequested || frontPipeline.recording) {
                            frontPipeline.protectCurrentBlocking();
                        }
                        if (recordingRequested || recording) protectReverseBlocking();
                        updateNotification("Emergency recording saved");
                    }
                }, "HN7EmergencyAfter").start();
            }
        }, Math.max(30, durationSeconds) * 1000L);
    }

    public void attachRadioPreview(final SurfaceHolder holder, final int cameraId) {
        if (holder == null) return;
        if (cameraId == 0 || cameraId == 2) {
            frontPipeline.attachPreview(holder, cameraId);
            return;
        }
        if (cameraId != 1) return;
        reversePreviewHolder = holder;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!ensureCamera(1)) return;
                    cameraHandler.post(new Runnable() {
                        @Override public void run() { applyReversePreviewTarget(); }
                    });
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "HN7AttachReversePreview").start();
    }

    public void detachRadioPreview(final SurfaceHolder holder, int cameraId) {
        if (cameraId == 0 || cameraId == 2) {
            frontPipeline.detachPreview(holder);
            return;
        }
        if (cameraId != 1 || reversePreviewHolder != holder) return;
        reversePreviewHolder = null;
        if (cameraHandler != null) cameraHandler.post(new Runnable() {
            @Override public void run() { applyReversePreviewTarget(); }
        });
    }

    private void applyReversePreviewTarget() {
        Camera target = camera;
        if (target == null) return;
        try {
            target.stopPreview();
            SurfaceHolder holder = reversePreviewHolder;
            if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                target.setPreviewDisplay(holder);
                if (surfaceTexture != null) {
                    try { surfaceTexture.release(); } catch (Exception ignored) { }
                    surfaceTexture = null;
                }
            } else {
                if (surfaceTexture != null) try { surfaceTexture.release(); } catch (Exception ignored) { }
                surfaceTexture = new SurfaceTexture(8765);
                surfaceTexture.setDefaultBufferSize(Math.max(1, frameWidth), Math.max(1, frameHeight));
                target.setPreviewTexture(surfaceTexture);
            }
            target.startPreview();
        } catch (Throwable error) {
            cameraError = "Preview display error: " + error.getMessage();
            Log.e(TAG, "Unable to change reverse preview display", error);
        }
    }

    private void startServer() {
        if (running) return;
        running = true;
        serverThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    while (running) {
                        final Socket socket = serverSocket.accept();
                        socket.setSoTimeout(15000);
                        clients.execute(new Runnable() {
                            @Override public void run() { handleClient(socket); }
                        });
                    }
                } catch (IOException e) {
                    if (running) Log.e(TAG, "HTTP server stopped", e);
                }
            }
        }, "HN7CameraHttp");
        serverThread.start();
    }

    private void requestTailscaleConnect() {
        Intent tailscale = new Intent("com.tailscale.ipn.CONNECT_VPN");
        tailscale.setPackage("com.tailscale.ipn");
        try { sendBroadcast(tailscale); }
        catch (RuntimeException error) { Log.w(TAG, "Unable to request Tailscale connection", error); }
    }

    private void handleClient(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String first = reader.readLine();
            if (first == null || first.length() == 0) return;
            String authorization = null;
            String range = null;
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0 && "authorization".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    authorization = line.substring(colon + 1).trim();
                } else if (colon > 0 && "range".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    range = line.substring(colon + 1).trim();
                }
            }

            OutputStream out = socket.getOutputStream();
            if (!isAuthorized(authorization)) {
                writeUnauthorized(out);
                return;
            }

            String[] request = first.split(" ");
            String method = request.length > 0 ? request[0] : "GET";
            String target = request.length > 1 ? request[1] : "/";
            int requestedCamera = parseCamera(target);

            if (target.startsWith("/recordings")) {
                writeRecordingsPage(out);
            } else if (target.startsWith("/play")) {
                writeRecordingPlayer(out, target);
            } else if (target.startsWith("/recording")) {
                serveRecording(out, target, range, "HEAD".equalsIgnoreCase(method));
            } else if (target.startsWith("/view")) {
                writeLiveViewPage(out, target);
            } else if (target.startsWith("/input")) {
                boolean selected = (requestedCamera == 0 || requestedCamera == 2) &&
                        frontPipeline.selectInputBlocking(requestedCamera);
                writeText(out, selected ? 200 : 503, selected ?
                        "Selected " + (requestedCamera == 2 ? "DVR Camera 2" : "Front DVR") + "\n" :
                        frontPipeline.cameraError + "\n");
            } else if (target.startsWith("/stream")) {
                streamMjpeg(socket, out, requestedCamera);
            } else if (target.startsWith("/audio.wav")) {
                streamLiveAudio(socket, out);
            } else if (target.startsWith("/snapshot")) {
                writeSnapshot(out, requestedCamera);
            } else if (target.startsWith("/record/start")) {
                boolean frontCamera = requestedCamera == 0 || requestedCamera == 2;
                if (frontCamera) frontPipeline.selectInputBlocking(requestedCamera);
                boolean started = frontCamera ? frontPipeline.startRecordingBlocking(true) : startRecordingBlocking(1, true);
                writeText(out, started ? 200 : 503,
                        started ? "Camera " + requestedCamera + " recording enabled\n" :
                                (frontCamera ? frontPipeline.recordingError : recordingError) + "\n");
            } else if (target.startsWith("/record/stop")) {
                if (requestedCamera == 0 || requestedCamera == 2) frontPipeline.stopRecordingBlocking(true);
                else stopRecordingBlocking(true);
                writeText(out, 200, "Camera " + requestedCamera + " recording disabled\n");
            } else if (target.startsWith("/release")) {
                releaseCameraBlocking();
                frontPipeline.releaseBlocking();
                writeText(out, 200, "Cameras released\n");
            } else if (target.startsWith("/status")) {
                String json = String.format(Locale.US,
                        "{\"vehicleName\":\"%s\",\"running\":true,\"cameraCount\":%d,\"activeCamera\":%d,\"width\":%d,\"height\":%d," +
                                "\"recording\":%s,\"recordingRequested\":%s,\"recordingPath\":\"%s\"," +
                                "\"recordingDirectory\":\"%s\",\"recordingError\":\"%s\",\"cameraError\":\"%s\"," +
                                "\"frontOpen\":%s,\"frontWidth\":%d,\"frontHeight\":%d,\"frontRecording\":%s," +
                                "\"frontRecordingRequested\":%s,\"frontInput\":%d,\"frontRecordingPath\":\"%s\"," +
                                "\"frontRecordingError\":\"%s\",\"frontError\":\"%s\"," +
                                "\"frontPreviewFrames\":%d,\"frontQueuedFrames\":%d,\"frontEncodedFrames\":%d,\"frontSamples\":%d,\"parkingEnabled\":%s," +
                                "\"parkingActive\":%s,\"ignitionOn\":%s,\"vehicleBatteryVoltage\":%.1f,\"parkingCutoffVoltage\":%.1f," +
                                "\"parkingDurationMinutes\":%d,\"parkingSystemReady\":%s,\"parkingVendorAvailable\":%s,\"parkingStatus\":\"%s\"}\n",
                        jsonEscape(getSharedPreferences("settings", MODE_PRIVATE).getString("vehicle_name", "ATOTO radio")),
                        Camera.getNumberOfCameras(), activeCamera, frameWidth, frameHeight,
                        Boolean.toString(recording), Boolean.toString(recordingRequested),
                        jsonEscape(currentRecordingPath), jsonEscape(recordingDirectory),
                        jsonEscape(recordingError),
                        jsonEscape(cameraError), Boolean.toString(frontPipeline.camera != null),
                        frontPipeline.frameWidth, frontPipeline.frameHeight,
                        Boolean.toString(frontPipeline.recording), Boolean.toString(frontPipeline.recordingRequested), frontPipeline.inputChannel,
                        jsonEscape(frontPipeline.currentRecordingPath), jsonEscape(frontPipeline.recordingError),
                        jsonEscape(frontPipeline.cameraError), frontPipeline.previewFrameCount,
                        frontPipeline.queuedFrameCount, frontPipeline.encodedFrameCount, frontPipeline.sampleCount,
                        Boolean.toString(parkingEnabled()), Boolean.toString(parkingActive), Boolean.toString(ignitionOn),
                        vehicleBatteryVoltage, parkingCutoffVoltage(), parkingDurationMinutes(),
                        Boolean.toString(AtotoParkingBridge.canControlSystemSentry(this) ||
                                AtotoParkingBridge.isSystemSentryEnabled(this)),
                        Boolean.toString(isParkingVendorAvailable()), jsonEscape(parkingStatus));
                writeJson(out, json);
            } else {
                writeHome(out);
            }
        } catch (Exception e) {
            Log.w(TAG, "Client disconnected", e);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private boolean isAuthorized(String header) {
        String credentials = USERNAME + ":" + getConnectionPassword(this);
        String expected = "Basic " + Base64.encodeToString(credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return expected.equals(header);
    }

    public static synchronized String getConnectionPassword(android.content.Context context) {
        android.content.SharedPreferences preferences =
                context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE);
        String existing = preferences.getString("connection_pin", "");
        if (existing.length() > 0) return existing;
        SecureRandom random = new SecureRandom();
        String created = String.format(Locale.US, "%08d-%04d",
                random.nextInt(100_000_000), random.nextInt(10_000));
        preferences.edit().putString("connection_pin", created).commit();
        return created;
    }

    private int parseCamera(String target) {
        int marker = target.indexOf("camera=");
        if (marker < 0) return 1;
        int start = marker + 7;
        int end = target.indexOf('&', start);
        if (end < 0) end = target.length();
        try { return Integer.parseInt(target.substring(start, end)); }
        catch (Exception ignored) { return 1; }
    }

    private void streamMjpeg(Socket socket, OutputStream out, int cameraId) throws Exception {
        if (cameraId == 0 || cameraId == 2) {
            frontPipeline.streamMjpeg(socket, out, cameraId);
            return;
        }
        if (!ensureCamera(cameraId)) {
            writeText(out, 503, cameraError + "\n");
            return;
        }
        updateNotification("Streaming camera " + cameraId);
        socket.setSoTimeout(0);
        out.write(("HTTP/1.1 200 OK\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n").getBytes(StandardCharsets.US_ASCII));

        byte[] previouslySent = null;
        while (running && !socket.isClosed()) {
            byte[] frame;
            synchronized (frameLock) {
                if (latestJpeg == null || latestJpeg == previouslySent) frameLock.wait(2000);
                frame = latestJpeg;
            }
            if (frame == null) continue;
            out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(frame);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            previouslySent = frame;
        }
    }

    private void writeSnapshot(OutputStream out, int cameraId) throws Exception {
        if (cameraId == 0 || cameraId == 2) {
            frontPipeline.writeSnapshot(out, cameraId);
            return;
        }
        if (!ensureCamera(cameraId)) {
            writeText(out, 503, cameraError + "\n");
            return;
        }
        byte[] frame = waitForFrame(6000);
        if (frame == null) {
            writeText(out, 504, "Camera opened but no video frame arrived\n");
            return;
        }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length +
                "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(frame);
        out.flush();
    }

    private byte[] waitForFrame(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (latestJpeg == null && System.currentTimeMillis() < end) {
                frameLock.wait(Math.max(1, end - System.currentTimeMillis()));
            }
            return latestJpeg;
        }
    }

    private long segmentDurationMs() {
        int minutes = getSharedPreferences("settings", MODE_PRIVATE).getInt("loop_minutes", 1);
        if (minutes < 1 || minutes > 3) minutes = 1;
        return minutes * 60_000L;
    }

    private void saveSnapshotBlocking(int cameraId) {
        try {
            byte[] jpeg;
            if (cameraId == 0 || cameraId == 2) {
                jpeg = frontPipeline.snapshotBytesBlocking(cameraId);
            } else {
                if (!ensureCamera(1)) throw new IOException(cameraError);
                jpeg = waitForFrame(6000);
            }
            if (jpeg == null || jpeg.length == 0) throw new IOException("No camera frame arrived");
            File base = null;
            File[] candidates = getExternalFilesDirs(Environment.DIRECTORY_PICTURES);
            if (candidates != null) {
                for (File candidate : candidates) {
                    if (candidate != null && Environment.isExternalStorageRemovable(candidate)) {
                        base = candidate;
                        break;
                    }
                }
                if (base == null) {
                    for (File candidate : candidates) {
                        if (candidate != null) { base = candidate; break; }
                    }
                }
            }
            if (base == null) throw new IOException("No writable photo storage is available");
            String channel = cameraId == 1 ? "Reverse" : cameraId == 2 ? "DVR2" : "Front";
            File directory = new File(base, "ATOTO-Camera-Link/Photos/" + channel);
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create photo folder");
            File photo = new File(directory, "P-" + (cameraId == 1 ? "R" : cameraId == 2 ? "D2" : "F") + "-" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            FileOutputStream output = new FileOutputStream(photo);
            try { output.write(jpeg); output.flush(); }
            finally { output.close(); }
            Log.i(TAG, "Snapshot saved: " + photo.getAbsolutePath());
        } catch (Throwable error) {
            Log.e(TAG, "Unable to save snapshot for camera " + cameraId, error);
        }
    }

    private File moveToProtected(File source) {
        if (source == null || !source.isFile() || source.length() == 0) return null;
        File directory = new File(source.getParentFile(), "Protected");
        if (!directory.exists() && !directory.mkdirs()) return null;
        File target = new File(directory, source.getName().startsWith("EVENT-") ?
                source.getName() : "EVENT-" + source.getName());
        if (source.renameTo(target)) return target;
        return null;
    }

    private void protectReverseBlocking() {
        if (cameraHandler == null) return;
        final CountDownLatch done = new CountDownLatch(1);
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                boolean restart = recordingRequested;
                if (recording) stopRecorderOnCameraThread(false);
                File protectedFile = moveToProtected(new File(currentRecordingPath));
                if (protectedFile != null) currentRecordingPath = protectedFile.getAbsolutePath();
                if (restart) startRecorderOnCameraThread();
                done.countDown();
            }
        });
        try { done.await(12, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private boolean wantsRecordedAudio() {
        return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("record_audio", true)
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void restartReverseForAudioSettingBlocking() {
        if (cameraHandler == null) return;
        final CountDownLatch done = new CountDownLatch(1);
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                boolean restart = recordingRequested;
                if (recording) stopRecorderOnCameraThread(false);
                if (restart) startRecorderOnCameraThread();
                done.countDown();
            }
        });
        try { done.await(12, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void maybeStartReverseMuxer() {
        if (!mediaMuxerStarted && mediaMuxerTrack >= 0 && (!audioEnabledForSegment || audioMuxerTrack >= 0)) {
            mediaMuxer.start();
            mediaMuxerStarted = true;
        }
    }

    private void queueReverseAudio(byte[] pcm, int length, final long capturedNs) {
        if (!recording || !audioEnabledForSegment || audioCodec == null ||
                !recordAudioBusy.compareAndSet(false, true)) return;
        final byte[] copy = Arrays.copyOf(pcm, length);
        try {
            recorderExecutor.execute(new Runnable() {
                @Override public void run() {
                    try { encodeReverseAudio(copy, capturedNs); }
                    catch (Throwable error) { Log.e(TAG, "Reverse audio encoder error", error); }
                    finally { recordAudioBusy.set(false); }
                }
            });
        } catch (Throwable error) {
            recordAudioBusy.set(false);
        }
    }

    private void encodeReverseAudio(byte[] pcm, long capturedNs) {
        synchronized (recorderLock) {
            if (!recording || audioCodec == null) return;
            int inputIndex = audioCodec.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                ByteBuffer input = audioCodec.getInputBuffer(inputIndex);
                if (input != null) {
                    input.clear();
                    int size = Math.min(input.remaining(), pcm.length);
                    input.put(pcm, 0, size);
                    audioCodec.queueInputBuffer(inputIndex, 0, size,
                            Math.max(0, capturedNs - recordingStartNs) / 1000L, 0);
                }
            }
            drainReverseAudio(false);
        }
    }

    private void signalReverseAudioEnd() {
        if (audioCodec == null) return;
        int inputIndex = audioCodec.dequeueInputBuffer(100_000);
        if (inputIndex >= 0) {
            audioCodec.queueInputBuffer(inputIndex, 0, 0,
                    Math.max(0, System.nanoTime() - recordingStartNs) / 1000L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drainReverseAudio(true);
        }
    }

    private void drainReverseAudio(boolean waitForEnd) {
        if (audioCodec == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.currentTimeMillis() + (waitForEnd ? 3000 : 0);
        while (true) {
            int outputIndex = audioCodec.dequeueOutputBuffer(info, waitForEnd ? 10_000 : 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEnd || System.currentTimeMillis() >= deadline) return;
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mediaMuxerStarted) throw new IllegalStateException("Reverse audio format changed after muxer start");
                audioMuxerTrack = mediaMuxer.addTrack(audioCodec.getOutputFormat());
                maybeStartReverseMuxer();
                continue;
            }
            if (outputIndex < 0) continue;
            ByteBuffer output = audioCodec.getOutputBuffer(outputIndex);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0 && mediaMuxerStarted && output != null) {
                output.position(info.offset);
                output.limit(info.offset + info.size);
                mediaMuxer.writeSampleData(audioMuxerTrack, output, info);
            }
            boolean ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            audioCodec.releaseOutputBuffer(outputIndex, false);
            if (ended || (!waitForEnd && info.size == 0)) return;
        }
    }

    private boolean ensureCamera(final int cameraId) throws InterruptedException {
        if (cameraId == 0) return frontPipeline.openBlocking();
        if (cameraId != 1) {
            cameraError = "Camera " + cameraId + " is not available";
            return false;
        }
        synchronized (cameraLock) {
            if (camera != null && activeCamera == cameraId) return true;
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean opened = new AtomicBoolean(false);
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    releaseCameraOnCameraThread();
                    latestJpeg = null;
                    cameraError = "Opening camera " + cameraId;
                    Camera candidate = Camera.open(cameraId);
                    Camera.Parameters parameters = candidate.getParameters();
                    Camera.Size selected = chooseSize(parameters.getSupportedPreviewSizes());
                    parameters.setPreviewSize(selected.width, selected.height);
                    parameters.setPreviewFormat(ImageFormat.NV21);
                    candidate.setParameters(parameters);
                    frameWidth = selected.width;
                    frameHeight = selected.height;
                    SurfaceHolder holder = reversePreviewHolder;
                    if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                        candidate.setPreviewDisplay(holder);
                    } else {
                        surfaceTexture = new SurfaceTexture(8765);
                        surfaceTexture.setDefaultBufferSize(frameWidth, frameHeight);
                        candidate.setPreviewTexture(surfaceTexture);
                    }
                    candidate.setPreviewCallback(new Camera.PreviewCallback() {
                        @Override public void onPreviewFrame(byte[] data, Camera source) {
                            if (recording) queueFrameForRecording(data, frameWidth, frameHeight);
                            long now = System.currentTimeMillis();
                            if (now - lastFrameEncoded < FRAME_INTERVAL_MS || !encoding.compareAndSet(false, true)) return;
                            lastFrameEncoded = now;
                            final byte[] copy = data.clone();
                            final int width = frameWidth;
                            final int height = frameHeight;
                            encoder.execute(new Runnable() {
                                @Override public void run() {
                                    try {
                                        applyWatermark(copy, width, height);
                                        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(width * height / 2);
                                        YuvImage image = new YuvImage(copy, ImageFormat.NV21, width, height, null);
                                        image.compressToJpeg(new Rect(0, 0, width, height), 65, jpeg);
                                        synchronized (frameLock) {
                                            latestJpeg = jpeg.toByteArray();
                                            cameraError = "";
                                            frameLock.notifyAll();
                                        }
                                    } catch (Exception e) {
                                        cameraError = e.toString();
                                    } finally {
                                        encoding.set(false);
                                    }
                                }
                            });
                        }
                    });
                    candidate.startPreview();
                    synchronized (cameraLock) {
                        camera = candidate;
                        activeCamera = cameraId;
                    }
                    cameraError = "";
                    opened.set(true);
                } catch (Throwable error) {
                    cameraError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                    Log.e(TAG, "Unable to open camera " + cameraId, error);
                    releaseCameraOnCameraThread();
                } finally {
                    done.countDown();
                }
            }
        });
        if (!done.await(7, TimeUnit.SECONDS)) {
            cameraError = "Timed out opening camera " + cameraId;
            return false;
        }
        return opened.get();
    }

    private Camera.Size chooseSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        long bestScore = Long.MAX_VALUE;
        for (Camera.Size size : sizes) {
            long pixels = (long) size.width * size.height;
            long target = 1280L * 720L;
            long score = Math.abs(pixels - target);
            if (size.width > 1920 || size.height > 1080) score += 10000000L;
            if (score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean startRecordingBlocking(final int cameraId, boolean persist) throws InterruptedException {
        if (cameraId != 1) {
            recordingError = "This pipeline records reverse camera 1";
            return false;
        }
        recordingRequested = true;
        if (persist) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("record_camera_1", true).apply();
        }
        if (!ensureCamera(cameraId)) return false;
        if (recording) return true;

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean started = new AtomicBoolean(false);
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                started.set(startRecorderOnCameraThread());
                done.countDown();
            }
        });
        if (!done.await(12, TimeUnit.SECONDS)) {
            recordingError = "Timed out starting reverse-camera recording";
            return false;
        }
        return started.get();
    }

    private void stopRecordingBlocking(boolean persist) {
        recordingRequested = false;
        if (persist) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("record_camera_1", false).apply();
        }
        if (cameraHandler == null) return;
        final CountDownLatch done = new CountDownLatch(1);
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                stopRecorderOnCameraThread(true);
                done.countDown();
            }
        });
        try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    private boolean startRecorderOnCameraThread() {
        if (!recordingRequested || camera == null || activeCamera != 1) return false;
        if (recording) return true;
        File directory = selectRecordingDirectory();
        if (directory == null) return false;
        if (!prepareRecordingStorage(directory)) {
            recordingError = "DVR USB storage limit reached";
            return false;
        }

        File output = new File(directory, "R-" + new SimpleDateFormat(
                "yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_recording.mp4");
        try {
            synchronized (recorderLock) {
                MediaFormat format = MediaFormat.createVideoFormat("video/avc", frameWidth, frameHeight);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                videoCodec = MediaCodec.createEncoderByType("video/avc");
                videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoCodec.start();
                audioEnabledForSegment = wantsRecordedAudio();
                audioMuxerTrack = -1;
                if (audioEnabledForSegment) {
                    MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 1);
                    audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
                    audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8_192);
                    audioCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
                    audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    audioCodec.start();
                }
                mediaMuxer = new MediaMuxer(output.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                mediaMuxerTrack = -1;
                mediaMuxerStarted = false;
                recordingStartNs = System.nanoTime();
                i420Buffer = new byte[frameWidth * frameHeight * 3 / 2];
                currentRecordingPath = output.getAbsolutePath();
                recording = true;
                publishSystemUiRecordingState();
                if (audioEnabledForSegment) microphoneHub.setReverseRecording(true);
                recordingError = "";
                cameraHandler.removeCallbacks(rollRecordingSegment);
                cameraHandler.postDelayed(rollRecordingSegment, segmentDurationMs());
                updateNotification("Reverse camera recording to USB");
                Log.i(TAG, "Recording started: " + currentRecordingPath);
                return true;
            }
        } catch (Throwable error) {
            recordingError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            Log.e(TAG, "Unable to start recording", error);
            releaseCodecAndMuxer(false);
            if (output.exists() && output.length() == 0) output.delete();
            recording = false;
            publishSystemUiRecordingState();
            updateNotification(currentRecordingStatusText());
            return false;
        }
    }

    private void stopRecorderOnCameraThread(boolean userRequested) {
        cameraHandler.removeCallbacks(rollRecordingSegment);
        if (microphoneHub != null) microphoneHub.setReverseRecording(false);
        recording = false;
        publishSystemUiRecordingState();
        final CountDownLatch finished = new CountDownLatch(1);
        recorderExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    synchronized (recorderLock) {
                        signalReverseAudioEnd();
                        signalEndOfStream();
                        releaseCodecAndMuxer(true);
                    }
                } finally {
                    finished.countDown();
                }
            }
        });
        try { finished.await(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        finishCurrentRecordingName();
        if (userRequested) recordingError = "Recording disabled";
        updateNotification(recordingRequested ? "Restarting reverse camera recording" : currentRecordingStatusText());
    }

    private void queueFrameForRecording(byte[] nv21, final int width, final int height) {
        if (!recording || !recordFrameBusy.compareAndSet(false, true)) return;
        final byte[] copy = nv21.clone();
        try {
            recorderExecutor.execute(new Runnable() {
                @Override public void run() {
                    try { encodeRecordingFrame(copy, width, height); }
                    catch (Throwable error) {
                        recordingError = "Encoder error: " + error.getMessage();
                        Log.e(TAG, "Encoder error", error);
                    } finally {
                        recordFrameBusy.set(false);
                    }
                }
            });
        } catch (Throwable error) {
            recordFrameBusy.set(false);
        }
    }

    private void encodeRecordingFrame(byte[] nv21, int width, int height) {
        synchronized (recorderLock) {
            if (!recording || videoCodec == null) return;
            int inputIndex = videoCodec.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                applyWatermark(nv21, width, height);
                nv21ToI420(nv21, i420Buffer, width, height);
                ByteBuffer input = videoCodec.getInputBuffer(inputIndex);
                if (input != null) {
                    input.clear();
                    input.put(i420Buffer);
                    long ptsUs = (System.nanoTime() - recordingStartNs) / 1000L;
                    videoCodec.queueInputBuffer(inputIndex, 0, i420Buffer.length, ptsUs, 0);
                }
            }
            drainEncoder(false);
        }
    }

    private void nv21ToI420(byte[] nv21, byte[] i420, int width, int height) {
        int ySize = width * height;
        int chromaSize = ySize / 4;
        System.arraycopy(nv21, 0, i420, 0, ySize);
        for (int i = 0; i < chromaSize; i++) {
            int source = ySize + i * 2;
            i420[ySize + i] = nv21[source + 1];
            i420[ySize + chromaSize + i] = nv21[source];
        }
    }

    private void signalEndOfStream() {
        if (videoCodec == null) return;
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            int inputIndex = videoCodec.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                long ptsUs = (System.nanoTime() - recordingStartNs) / 1000L;
                videoCodec.queueInputBuffer(inputIndex, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drainEncoder(true);
                return;
            }
        }
    }

    private void drainEncoder(boolean waitForEnd) {
        if (videoCodec == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.currentTimeMillis() + (waitForEnd ? 3000 : 0);
        while (true) {
            int outputIndex = videoCodec.dequeueOutputBuffer(info, waitForEnd ? 10_000 : 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEnd || System.currentTimeMillis() >= deadline) return;
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mediaMuxerStarted) throw new IllegalStateException("Reverse video format changed after muxer start");
                mediaMuxerTrack = mediaMuxer.addTrack(videoCodec.getOutputFormat());
                maybeStartReverseMuxer();
                continue;
            }
            if (outputIndex < 0) continue;
            ByteBuffer output = videoCodec.getOutputBuffer(outputIndex);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0 && mediaMuxerStarted && output != null) {
                output.position(info.offset);
                output.limit(info.offset + info.size);
                mediaMuxer.writeSampleData(mediaMuxerTrack, output, info);
            }
            boolean ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            videoCodec.releaseOutputBuffer(outputIndex, false);
            if (ended || (!waitForEnd && info.size == 0)) return;
        }
    }

    private void releaseCodecAndMuxer(boolean completed) {
        synchronized (recorderLock) {
            if (videoCodec != null) {
                try { videoCodec.stop(); } catch (Exception ignored) {}
                try { videoCodec.release(); } catch (Exception ignored) {}
                videoCodec = null;
            }
            if (audioCodec != null) {
                try { audioCodec.stop(); } catch (Exception ignored) {}
                try { audioCodec.release(); } catch (Exception ignored) {}
                audioCodec = null;
            }
            if (mediaMuxer != null) {
                if (mediaMuxerStarted) {
                    try { mediaMuxer.stop(); } catch (Exception error) {
                        completed = false;
                        recordingError = "Unable to finish MP4: " + error.getMessage();
                    }
                }
                try { mediaMuxer.release(); } catch (Exception ignored) {}
                mediaMuxer = null;
            }
            mediaMuxerStarted = false;
            mediaMuxerTrack = -1;
            audioMuxerTrack = -1;
            audioEnabledForSegment = false;
            i420Buffer = null;
        }
        if (!completed && currentRecordingPath.length() > 0) {
            File failed = new File(currentRecordingPath);
            if (failed.length() == 0) failed.delete();
        }
    }

    private void finishCurrentRecordingName() {
        if (!currentRecordingPath.endsWith("_recording.mp4")) return;
        File pending = new File(currentRecordingPath);
        if (!pending.exists() || pending.length() == 0) return;
        File finished = new File(pending.getParentFile(),
                pending.getName().replace("_recording.mp4", ".mp4"));
        if (pending.renameTo(finished)) currentRecordingPath = finished.getAbsolutePath();
    }

    private File selectRecordingDirectory() {
        File selected = null;
        try {
            File[] candidates = getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
            if (candidates != null) {
                for (File candidate : candidates) {
                    if (candidate != null && Environment.isExternalStorageRemovable(candidate)) {
                        selected = candidate;
                        break;
                    }
                }
                if (selected == null) {
                    for (File candidate : candidates) {
                        if (candidate != null) { selected = candidate; break; }
                    }
                }
            }
            if (selected == null) {
                recordingError = "No writable storage is available";
                return null;
            }
            File directory = new File(selected, "ATOTO-Camera-Link/Reverse");
            if (!directory.exists() && !directory.mkdirs()) {
                recordingError = "Cannot create recording folder on " + selected;
                return null;
            }
            recordingDirectory = directory.getAbsolutePath();
            return directory;
        } catch (Throwable error) {
            recordingError = "Storage error: " + error.getMessage();
            return null;
        }
    }

    private void cleanupOldRecordings(File directory) {
        try {
            StatFs stats = new StatFs(directory.getAbsolutePath());
            if (stats.getAvailableBytes() >= MIN_FREE_BYTES) return;
            File[] files = directory.listFiles();
            if (files == null) return;
            Arrays.sort(files, new Comparator<File>() {
                @Override public int compare(File left, File right) {
                    return Long.compare(left.lastModified(), right.lastModified());
                }
            });
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".mp4")) continue;
                if (file.getAbsolutePath().equals(currentRecordingPath)) continue;
                if (!file.delete()) continue;
                stats.restat(directory.getAbsolutePath());
                if (stats.getAvailableBytes() >= MIN_FREE_BYTES) break;
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to clean old recordings", error);
        }
    }

    private void cleanupCameraLinkFrontRecordings(File directory) {
        try {
            StatFs stats = new StatFs(directory.getAbsolutePath());
            if (stats.getAvailableBytes() >= MIN_FREE_BYTES) return;
            File[] files = directory.listFiles();
            if (files == null) return;
            Arrays.sort(files, new Comparator<File>() {
                @Override public int compare(File left, File right) {
                    return Long.compare(left.lastModified(), right.lastModified());
                }
            });
            for (File file : files) {
                String name = file.getName();
                if (!file.isFile() || !name.startsWith("CL-F-") || !name.endsWith(".mp4")) continue;
                if (file.getAbsolutePath().equals(frontPipeline.currentRecordingPath)) continue;
                if (!file.delete()) continue;
                stats.restat(directory.getAbsolutePath());
                if (stats.getAvailableBytes() >= MIN_FREE_BYTES) break;
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to clean old front recordings", error);
        }
    }

    private synchronized boolean prepareRecordingStorage(File directory) {
        try {
            StatFs stats = new StatFs(directory.getAbsolutePath());
            long available = stats.getAvailableBytes();
            String policy = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("storage_policy", "loop");
            if ("until_full".equals(policy)) return available >= MIN_FREE_BYTES;

            int percent = getSharedPreferences("settings", MODE_PRIVATE)
                    .getInt("storage_limit_percent", 90);
            percent = Math.max(10, Math.min(100, percent));
            long quota = stats.getTotalBytes() * percent / 100L;
            ArrayList<File> allVideos = new ArrayList<File>();
            collectRecordingVideos(new File(FACTORY_DVR_DIRECTORY), allVideos, 0);
            File[] movieRoots = getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
            if (movieRoots != null) for (File root : movieRoots) {
                if (root != null) collectRecordingVideos(root, allVideos, 0);
            }
            long videoBytes = 0;
            ArrayList<File> deletable = new ArrayList<File>();
            for (File file : allVideos) {
                videoBytes += file.length();
                if (isLoopCleanupCandidate(file)) deletable.add(file);
            }
            deletable.sort(new Comparator<File>() {
                @Override public int compare(File left, File right) {
                    return Long.compare(left.lastModified(), right.lastModified());
                }
            });
            int index = 0;
            while ((available < MIN_FREE_BYTES || videoBytes + SEGMENT_HEADROOM_BYTES > quota)
                    && index < deletable.size()) {
                File oldest = deletable.get(index++);
                long length = oldest.length();
                if (!oldest.delete()) continue;
                videoBytes = Math.max(0, videoBytes - length);
                stats.restat(directory.getAbsolutePath());
                available = stats.getAvailableBytes();
                Log.i(TAG, "Loop cleanup removed " + oldest.getAbsolutePath());
            }
            return available >= MIN_FREE_BYTES && videoBytes + SEGMENT_HEADROOM_BYTES <= quota;
        } catch (Throwable error) {
            Log.w(TAG, "Unable to enforce DVR storage policy", error);
            return false;
        }
    }

    private void collectRecordingVideos(File directory, List<File> output, int depth) {
        if (directory == null || depth > 10 || !directory.canRead()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collectRecordingVideos(file, output, depth + 1);
            else if (file.getName().toLowerCase(Locale.US).endsWith(".mp4")) output.add(file);
        }
    }

    private boolean isLoopCleanupCandidate(File file) {
        String path = file.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.US);
        String name = file.getName().toLowerCase(Locale.US);
        if (path.contains("/protected/") || path.contains("/emergency/") ||
                name.startsWith("event-") || name.contains("_locked") ||
                name.contains("_recording")) return false;
        if (path.equals(currentRecordingPath.replace('\\', '/').toLowerCase(Locale.US))) return false;
        return frontPipeline == null || !path.equals(frontPipeline.currentRecordingPath
                .replace('\\', '/').toLowerCase(Locale.US));
    }

    private void releaseCameraBlocking() {
        if (cameraHandler == null) return;
        final CountDownLatch done = new CountDownLatch(1);
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                releaseCameraOnCameraThread();
                done.countDown();
            }
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    private void releaseCameraOnCameraThread() {
        synchronized (cameraLock) {
            stopRecorderOnCameraThread(false);
            if (camera != null) {
                try { camera.setPreviewCallback(null); } catch (Exception ignored) {}
                try { camera.stopPreview(); } catch (Exception ignored) {}
                try { camera.release(); } catch (Exception ignored) {}
                camera = null;
            }
            if (surfaceTexture != null) {
                try { surfaceTexture.release(); } catch (Exception ignored) {}
                surfaceTexture = null;
            }
            activeCamera = -1;
            frameWidth = 0;
            frameHeight = 0;
            latestJpeg = null;
        }
        updateNotification("Waiting for a viewer");
    }

    private void streamLiveAudio(Socket socket, OutputStream out) throws Exception {
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("record_audio", true)) {
            writeText(out, 403, "The radio microphone is turned off\n");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            writeText(out, 503, "Microphone permission is not granted on the radio\n");
            return;
        }
        PcmStreamClient listener = new PcmStreamClient();
        microphoneHub.addLiveClient(listener);
        try {
            socket.setSoTimeout(0);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nCache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(wavHeader());
            out.flush();
            while (running && !socket.isClosed()) {
                byte[] pcm = listener.frames.poll(3, TimeUnit.SECONDS);
                if (pcm == null) continue;
                out.write(pcm);
                out.flush();
            }
        } finally {
            microphoneHub.removeLiveClient(listener);
        }
    }

    private byte[] wavHeader() {
        byte[] header = new byte[44];
        byte[] riff = new byte[]{'R','I','F','F'};
        byte[] wave = new byte[]{'W','A','V','E','f','m','t',' '};
        System.arraycopy(riff, 0, header, 0, riff.length);
        putLittleEndian(header, 4, 0x7ffffff0, 4);
        System.arraycopy(wave, 0, header, 8, wave.length);
        putLittleEndian(header, 16, 16, 4);
        putLittleEndian(header, 20, 1, 2);
        putLittleEndian(header, 22, 1, 2);
        putLittleEndian(header, 24, 44_100, 4);
        putLittleEndian(header, 28, 88_200, 4);
        putLittleEndian(header, 32, 2, 2);
        putLittleEndian(header, 34, 16, 2);
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        putLittleEndian(header, 40, 0x7fffffcc, 4);
        return header;
    }

    private void putLittleEndian(byte[] target, int offset, int value, int bytes) {
        for (int i = 0; i < bytes; i++) target[offset + i] = (byte) ((value >>> (8 * i)) & 0xff);
    }

    private static final class PcmStreamClient {
        final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<byte[]>(12);
        void offer(byte[] pcm) {
            if (!frames.offer(pcm)) {
                frames.poll();
                frames.offer(pcm);
            }
        }
    }

    private final class MicrophoneHub {
        private final CopyOnWriteArrayList<PcmStreamClient> liveClients = new CopyOnWriteArrayList<PcmStreamClient>();
        private boolean reverseRecording;
        private boolean frontRecording;
        private boolean stopped;
        private boolean failed;
        private Thread captureThread;

        synchronized void setReverseRecording(boolean enabled) {
            reverseRecording = enabled;
            if (enabled) ensureStartedLocked();
        }

        synchronized void setFrontRecording(boolean enabled) {
            frontRecording = enabled;
            if (enabled) ensureStartedLocked();
        }

        void addLiveClient(PcmStreamClient client) {
            liveClients.add(client);
            synchronized (this) { ensureStartedLocked(); }
        }

        void removeLiveClient(PcmStreamClient client) {
            liveClients.remove(client);
        }

        private synchronized boolean hasDemandLocked() {
            return reverseRecording || frontRecording || !liveClients.isEmpty();
        }

        private void ensureStartedLocked() {
            if (stopped || failed || captureThread != null || !hasDemandLocked()) return;
            captureThread = new Thread(new Runnable() {
                @Override public void run() { captureLoop(); }
            }, "HN7CabinMicrophone");
            captureThread.start();
        }

        private void captureLoop() {
            AudioRecord recorder = null;
            try {
                int minimum = AudioRecord.getMinBufferSize(44_100, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize = Math.max(8_192, minimum > 0 ? minimum * 2 : 8_192);
                recorder = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, 44_100,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IOException("The cabin microphone could not be initialized");
                }
                recorder.startRecording();
                byte[] buffer = new byte[4_096];
                while (running) {
                    boolean reverse;
                    boolean front;
                    synchronized (this) {
                        if (stopped || !hasDemandLocked()) break;
                        reverse = reverseRecording;
                        front = frontRecording;
                    }
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read <= 0) continue;
                    long capturedNs = System.nanoTime();
                    if (reverse) queueReverseAudio(buffer, read, capturedNs);
                    if (front) frontPipeline.queueAudio(buffer, read, capturedNs);
                    if (!liveClients.isEmpty()) {
                        byte[] live = Arrays.copyOf(buffer, read);
                        for (PcmStreamClient client : liveClients) client.offer(live);
                    }
                }
            } catch (Throwable error) {
                synchronized (this) { failed = true; }
                Log.e(TAG, "Cabin microphone stopped", error);
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); } catch (Exception ignored) { }
                    try { recorder.release(); } catch (Exception ignored) { }
                }
                synchronized (this) {
                    captureThread = null;
                    if (!stopped && !failed && hasDemandLocked()) ensureStartedLocked();
                }
            }
        }

        synchronized void shutdown() {
            stopped = true;
            reverseRecording = false;
            frontRecording = false;
            liveClients.clear();
        }
    }

    private final class FrontPipeline {
        private final HandlerThread thread;
        private final Handler handler;
        private final ExecutorService jpegExecutor = Executors.newSingleThreadExecutor();
        private final ExecutorService recordingExecutor = Executors.newSingleThreadExecutor();
        private final Object frameMonitor = new Object();
        private final Object recordingMonitor = new Object();
        private final AtomicBoolean jpegBusy = new AtomicBoolean(false);
        private final AtomicBoolean recordingFrameBusy = new AtomicBoolean(false);
        private final AtomicBoolean recordingAudioBusy = new AtomicBoolean(false);

        private volatile Camera camera;
        private SurfaceTexture texture;
        private volatile SurfaceHolder previewHolder;
        private volatile byte[] latestJpeg;
        private volatile String cameraError = "Front camera has not been opened";
        private volatile int frameWidth;
        private volatile int frameHeight;
        private volatile long lastJpegTime;
        private volatile boolean recording;
        private volatile boolean recordingRequested;
        private volatile String recordingError = "Front recording has not been enabled";
        private volatile String currentRecordingPath = "";
        private volatile String recordingDirectory = "";
        private volatile int inputChannel;
        private volatile long previewFrameCount;
        private volatile long queuedFrameCount;
        private volatile long encodedFrameCount;
        private volatile long sampleCount;

        private MediaCodec codec;
        private MediaCodec audioCodec;
        private MediaMuxer muxer;
        private int muxerTrack = -1;
        private int audioMuxerTrack = -1;
        private boolean muxerStarted;
        private boolean audioEnabledForSegment;
        private long recordingStartNs;
        private byte[] planarBuffer;

        private final Runnable rollSegment = new Runnable() {
            @Override public void run() {
                if (!recordingRequested || camera == null) return;
                stopRecorderOnThread(false);
                if (recordingRequested) startRecorderOnThread();
            }
        };

        FrontPipeline() {
            thread = new HandlerThread("HN7FrontCamera");
            thread.start();
            handler = new Handler(thread.getLooper());
            recordingRequested = getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("record_camera_0", false);
        }

        void attachPreview(final SurfaceHolder holder, final int requestedCamera) {
            previewHolder = holder;
            new Thread(new Runnable() {
                @Override public void run() {
                    if (!selectInputBlocking(requestedCamera) && requestedCamera == 2) return;
                    if (!openBlocking()) return;
                    handler.post(new Runnable() {
                        @Override public void run() { applyPreviewTarget(); }
                    });
                }
            }, "HN7AttachFrontPreview").start();
        }

        void detachPreview(final SurfaceHolder holder) {
            if (previewHolder != holder) return;
            previewHolder = null;
            handler.post(new Runnable() {
                @Override public void run() { applyPreviewTarget(); }
            });
        }

        private void applyPreviewTarget() {
            Camera target = camera;
            if (target == null) return;
            try {
                target.stopPreview();
                SurfaceHolder holder = previewHolder;
                if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                    target.setPreviewDisplay(holder);
                    if (texture != null) {
                        try { texture.release(); } catch (Exception ignored) { }
                        texture = null;
                    }
                } else {
                    if (texture != null) try { texture.release(); } catch (Exception ignored) { }
                    texture = new SurfaceTexture(8766);
                    texture.setDefaultBufferSize(Math.max(1, frameWidth), Math.max(1, frameHeight));
                    target.setPreviewTexture(texture);
                }
                target.startPreview();
            } catch (Throwable error) {
                cameraError = "Preview display error: " + error.getMessage();
                Log.e(TAG, "Unable to change front preview display", error);
            }
        }

        boolean openBlocking() {
            if (camera != null) return true;
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicBoolean opened = new AtomicBoolean(false);
            handler.post(new Runnable() {
                @Override public void run() {
                    Camera candidate = null;
                    try {
                        if (camera != null) {
                            opened.set(true);
                            return;
                        }
                        latestJpeg = null;
                        cameraError = "Opening front camera";
                        candidate = Camera.open(0);
                        Camera.Parameters parameters = candidate.getParameters();
                        Camera.Size selected = chooseSize(parameters.getSupportedPreviewSizes());
                        parameters.setPreviewSize(selected.width, selected.height);
                        parameters.setPreviewFormat(ImageFormat.NV21);
                        candidate.setParameters(parameters);
                        frameWidth = selected.width;
                        frameHeight = selected.height;
                        SurfaceHolder holder = previewHolder;
                        if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                            candidate.setPreviewDisplay(holder);
                        } else {
                            texture = new SurfaceTexture(8766);
                            texture.setDefaultBufferSize(frameWidth, frameHeight);
                            candidate.setPreviewTexture(texture);
                        }
                        candidate.setPreviewCallback(new Camera.PreviewCallback() {
                            @Override public void onPreviewFrame(byte[] data, Camera source) {
                                previewFrameCount++;
                                if (recording) queueRecordingFrame(data, frameWidth, frameHeight);
                                long now = System.currentTimeMillis();
                                if (now - lastJpegTime < FRAME_INTERVAL_MS || !jpegBusy.compareAndSet(false, true)) return;
                                lastJpegTime = now;
                                final byte[] copy = data.clone();
                                final int width = frameWidth;
                                final int height = frameHeight;
                                jpegExecutor.execute(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            applyWatermark(copy, width, height);
                                            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(width * height / 2);
                                            new YuvImage(copy, ImageFormat.NV21, width, height, null)
                                                    .compressToJpeg(new Rect(0, 0, width, height), 68, jpeg);
                                            synchronized (frameMonitor) {
                                                latestJpeg = jpeg.toByteArray();
                                                cameraError = "";
                                                frameMonitor.notifyAll();
                                            }
                                        } catch (Throwable error) {
                                            cameraError = error.toString();
                                        } finally {
                                            jpegBusy.set(false);
                                        }
                                    }
                                });
                            }
                        });
                        candidate.startPreview();
                        camera = candidate;
                        cameraError = "";
                        opened.set(true);
                        candidate = null;
                    } catch (Throwable error) {
                        cameraError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                        Log.e(TAG, "Unable to open front camera", error);
                        if (candidate != null) {
                            try { candidate.release(); } catch (Exception ignored) { }
                        }
                        releaseOnThread();
                    } finally {
                        done.countDown();
                    }
                }
            });
            try {
                if (!done.await(8, TimeUnit.SECONDS)) {
                    cameraError = "Timed out opening front camera";
                    return false;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cameraError = "Front-camera open was interrupted";
                return false;
            }
            return opened.get();
        }

        boolean selectInputBlocking(int requestedCamera) {
            final int desired = requestedCamera == 2 ? 1 : 0;
            if (desired == inputChannel) return true;
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicBoolean selected = new AtomicBoolean(false);
            handler.post(new Runnable() {
                @Override public void run() {
                    boolean restart = recordingRequested && recording;
                    if (restart) stopRecorderOnThread(false);
                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(new File("/dev/videomux13"));
                        output.write(("camera_mode=0,camera_ch_num=" + desired).getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                        inputChannel = desired;
                        latestJpeg = null;
                        cameraError = "";
                        selected.set(true);
                    } catch (Throwable error) {
                        cameraError = "DVR Camera 2 needs ATOTO video-mux access: " + error.getMessage();
                        Log.e(TAG, "Unable to switch DVR input", error);
                    } finally {
                        if (output != null) try { output.close(); } catch (IOException ignored) { }
                        if (restart) startRecorderOnThread();
                        done.countDown();
                    }
                }
            });
            try { done.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return selected.get();
        }

        void streamMjpeg(Socket socket, OutputStream out, int requestedCamera) throws Exception {
            if (!selectInputBlocking(requestedCamera)) {
                writeText(out, 503, cameraError + "\n");
                return;
            }
            if (!openBlocking()) {
                writeText(out, 503, cameraError + "\n");
                return;
            }
            updateNotification(recording ? "Front DVR recording and streaming" : "Streaming front DVR");
            socket.setSoTimeout(0);
            out.write(("HTTP/1.1 200 OK\r\nCache-Control: no-store, no-cache, must-revalidate\r\n" +
                    "Pragma: no-cache\r\nConnection: close\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] previouslySent = null;
            while (running && !socket.isClosed()) {
                byte[] frame;
                synchronized (frameMonitor) {
                    if (latestJpeg == null || latestJpeg == previouslySent) frameMonitor.wait(2000);
                    frame = latestJpeg;
                }
                if (frame == null) continue;
                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(frame);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                previouslySent = frame;
            }
        }

        void writeSnapshot(OutputStream out, int requestedCamera) throws Exception {
            byte[] frame = snapshotBytesBlocking(requestedCamera);
            if (frame == null) {
                writeText(out, 504, cameraError + "\n");
                return;
            }
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length +
                    "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(frame);
            out.flush();
        }

        byte[] snapshotBytesBlocking(int requestedCamera) throws Exception {
            if (!selectInputBlocking(requestedCamera)) return null;
            if (!openBlocking()) return null;
            long deadline = System.currentTimeMillis() + 6000;
            synchronized (frameMonitor) {
                while (latestJpeg == null && System.currentTimeMillis() < deadline) {
                    frameMonitor.wait(Math.max(1, deadline - System.currentTimeMillis()));
                }
                if (latestJpeg == null) cameraError = "Front camera opened but no video frame arrived";
                return latestJpeg;
            }
        }

        boolean startRecordingBlocking(boolean persist) {
            recordingRequested = true;
            if (persist) {
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putBoolean("primary_dvr_mode", true)
                        .putBoolean("record_camera_0", true).apply();
            }
            if (!openBlocking()) {
                recordingError = cameraError;
                return false;
            }
            if (recording) return true;
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicBoolean started = new AtomicBoolean(false);
            handler.post(new Runnable() {
                @Override public void run() {
                    started.set(startRecorderOnThread());
                    done.countDown();
                }
            });
            try {
                if (!done.await(12, TimeUnit.SECONDS)) {
                    recordingError = "Timed out starting front recording";
                    return false;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            return started.get();
        }

        void stopRecordingBlocking(boolean persist) {
            recordingRequested = false;
            if (persist) getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("record_camera_0", false).apply();
            final CountDownLatch done = new CountDownLatch(1);
            handler.post(new Runnable() {
                @Override public void run() {
                    stopRecorderOnThread(true);
                    done.countDown();
                }
            });
            try { done.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        void protectCurrentBlocking() {
            final CountDownLatch done = new CountDownLatch(1);
            handler.post(new Runnable() {
                @Override public void run() {
                    boolean restart = recordingRequested;
                    if (recording) stopRecorderOnThread(false);
                    File protectedFile = moveToProtected(new File(currentRecordingPath));
                    if (protectedFile != null) currentRecordingPath = protectedFile.getAbsolutePath();
                    if (restart) startRecorderOnThread();
                    done.countDown();
                }
            });
            try { done.await(12, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        void restartForAudioSettingBlocking() {
            final CountDownLatch done = new CountDownLatch(1);
            handler.post(new Runnable() {
                @Override public void run() {
                    boolean restart = recordingRequested;
                    if (recording) stopRecorderOnThread(false);
                    if (restart) startRecorderOnThread();
                    done.countDown();
                }
            });
            try { done.await(12, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        private boolean startRecorderOnThread() {
            if (!recordingRequested || camera == null || frameWidth <= 0 || frameHeight <= 0) return false;
            if (recording) return true;
            File directory = selectDirectory();
            if (directory == null) return false;
            if (!prepareRecordingStorage(directory)) {
                recordingError = "Front DVR USB storage limit reached";
                return false;
            }
            File output = new File(directory, (inputChannel == 1 ? "CL-D2-" : "CL-F-") + new SimpleDateFormat(
                    "yyyy-MMdd-HHmmss", Locale.US).format(new Date()) + "_recording.mp4");
            try {
                synchronized (recordingMonitor) {
                    MediaFormat format = MediaFormat.createVideoFormat("video/avc", frameWidth, frameHeight);
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
                    format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                    codec = MediaCodec.createEncoderByType("video/avc");
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    codec.start();
                    audioEnabledForSegment = wantsRecordedAudio();
                    audioMuxerTrack = -1;
                    if (audioEnabledForSegment) {
                        MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 1);
                        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
                        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8_192);
                        audioCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
                        audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        audioCodec.start();
                    }
                    muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    muxerTrack = -1;
                    muxerStarted = false;
                    recordingStartNs = System.nanoTime();
                    planarBuffer = new byte[frameWidth * frameHeight * 3 / 2];
                    currentRecordingPath = output.getAbsolutePath();
                    recording = true;
                    publishSystemUiRecordingState();
                    if (audioEnabledForSegment) microphoneHub.setFrontRecording(true);
                    recordingError = "";
                    handler.removeCallbacks(rollSegment);
                    handler.postDelayed(rollSegment, segmentDurationMs());
                    updateNotification(inputChannel == 1 ? "DVR Camera 2 recording to USB" : "Front DVR recording to USB");
                    Log.i(TAG, "Front recording started: " + currentRecordingPath);
                    return true;
                }
            } catch (Throwable error) {
                recordingError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                Log.e(TAG, "Unable to start front recording", error);
                releaseEncoder(false);
                if (output.exists() && output.length() == 0) output.delete();
                recording = false;
                publishSystemUiRecordingState();
                updateNotification(currentRecordingStatusText());
                return false;
            }
        }

        private void stopRecorderOnThread(boolean userRequested) {
            handler.removeCallbacks(rollSegment);
            if (!recording && codec == null) {
                if (userRequested) recordingError = "Front recording disabled";
                publishSystemUiRecordingState();
                updateNotification(currentRecordingStatusText());
                return;
            }
            if (microphoneHub != null) microphoneHub.setFrontRecording(false);
            recording = false;
            publishSystemUiRecordingState();
            final CountDownLatch finished = new CountDownLatch(1);
            recordingExecutor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        synchronized (recordingMonitor) {
                            signalAudioEnd();
                            signalEndOfStream();
                            releaseEncoder(true);
                        }
                    } finally {
                        finished.countDown();
                    }
                }
            });
            try { finished.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finishRecordingName();
            if (userRequested) recordingError = "Front recording disabled";
            updateNotification(currentRecordingStatusText());
        }

        private void queueRecordingFrame(byte[] nv21, final int width, final int height) {
            if (!recording || !recordingFrameBusy.compareAndSet(false, true)) return;
            queuedFrameCount++;
            final byte[] copy = nv21.clone();
            try {
                recordingExecutor.execute(new Runnable() {
                    @Override public void run() {
                        try { encodeFrame(copy, width, height); }
                        catch (Throwable error) {
                            recordingError = "Front encoder error: " + error.getMessage();
                            Log.e(TAG, "Front encoder error", error);
                        } finally {
                            recordingFrameBusy.set(false);
                        }
                    }
                });
            } catch (Throwable error) {
                recordingFrameBusy.set(false);
            }
        }

        private void encodeFrame(byte[] nv21, int width, int height) {
            synchronized (recordingMonitor) {
                if (!recording || codec == null) return;
                int inputIndex = codec.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    applyWatermark(nv21, width, height);
                    nv21ToI420(nv21, planarBuffer, width, height);
                    ByteBuffer input = codec.getInputBuffer(inputIndex);
                    if (input != null) {
                        input.clear();
                        input.put(planarBuffer);
                        codec.queueInputBuffer(inputIndex, 0, planarBuffer.length,
                                (System.nanoTime() - recordingStartNs) / 1000L, 0);
                        encodedFrameCount++;
                    }
                }
                drain(false);
            }
        }

        void queueAudio(byte[] pcm, int length, final long capturedNs) {
            if (!recording || !audioEnabledForSegment || audioCodec == null ||
                    !recordingAudioBusy.compareAndSet(false, true)) return;
            final byte[] copy = Arrays.copyOf(pcm, length);
            try {
                recordingExecutor.execute(new Runnable() {
                    @Override public void run() {
                        try { encodeAudio(copy, capturedNs); }
                        catch (Throwable error) { Log.e(TAG, "Front audio encoder error", error); }
                        finally { recordingAudioBusy.set(false); }
                    }
                });
            } catch (Throwable error) {
                recordingAudioBusy.set(false);
            }
        }

        private void encodeAudio(byte[] pcm, long capturedNs) {
            synchronized (recordingMonitor) {
                if (!recording || audioCodec == null) return;
                int inputIndex = audioCodec.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    ByteBuffer input = audioCodec.getInputBuffer(inputIndex);
                    if (input != null) {
                        input.clear();
                        int size = Math.min(input.remaining(), pcm.length);
                        input.put(pcm, 0, size);
                        audioCodec.queueInputBuffer(inputIndex, 0, size,
                                Math.max(0, capturedNs - recordingStartNs) / 1000L, 0);
                    }
                }
                drainAudio(false);
            }
        }

        private void signalAudioEnd() {
            if (audioCodec == null) return;
            int inputIndex = audioCodec.dequeueInputBuffer(100_000);
            if (inputIndex >= 0) {
                audioCodec.queueInputBuffer(inputIndex, 0, 0,
                        Math.max(0, System.nanoTime() - recordingStartNs) / 1000L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drainAudio(true);
            }
        }

        private void maybeStartMuxer() {
            if (!muxerStarted && muxerTrack >= 0 && (!audioEnabledForSegment || audioMuxerTrack >= 0)) {
                muxer.start();
                muxerStarted = true;
            }
        }

        private void drainAudio(boolean waitForEnd) {
            if (audioCodec == null) return;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + (waitForEnd ? 3000 : 0);
            while (true) {
                int outputIndex = audioCodec.dequeueOutputBuffer(info, waitForEnd ? 10_000 : 0);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!waitForEnd || System.currentTimeMillis() >= deadline) return;
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("Front audio format changed after muxer start");
                    audioMuxerTrack = muxer.addTrack(audioCodec.getOutputFormat());
                    maybeStartMuxer();
                    continue;
                }
                if (outputIndex < 0) continue;
                ByteBuffer output = audioCodec.getOutputBuffer(outputIndex);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && muxerStarted && output != null) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    muxer.writeSampleData(audioMuxerTrack, output, info);
                }
                boolean ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                audioCodec.releaseOutputBuffer(outputIndex, false);
                if (ended || (!waitForEnd && info.size == 0)) return;
            }
        }

        private void signalEndOfStream() {
            if (codec == null) return;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                int inputIndex = codec.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0,
                            (System.nanoTime() - recordingStartNs) / 1000L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    drain(true);
                    return;
                }
            }
        }

        private void drain(boolean waitForEnd) {
            if (codec == null) return;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + (waitForEnd ? 3000 : 0);
            while (true) {
                int outputIndex = codec.dequeueOutputBuffer(info, waitForEnd ? 10_000 : 0);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!waitForEnd || System.currentTimeMillis() >= deadline) return;
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("Front video format changed after muxer start");
                    muxerTrack = muxer.addTrack(codec.getOutputFormat());
                    maybeStartMuxer();
                    continue;
                }
                if (outputIndex < 0) continue;
                ByteBuffer output = codec.getOutputBuffer(outputIndex);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && muxerStarted && output != null) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    muxer.writeSampleData(muxerTrack, output, info);
                    sampleCount++;
                }
                boolean ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outputIndex, false);
                if (ended || (!waitForEnd && info.size == 0)) return;
            }
        }

        private void releaseEncoder(boolean completed) {
            synchronized (recordingMonitor) {
                if (codec != null) {
                    try { codec.stop(); } catch (Exception ignored) { }
                    try { codec.release(); } catch (Exception ignored) { }
                    codec = null;
                }
                if (audioCodec != null) {
                    try { audioCodec.stop(); } catch (Exception ignored) { }
                    try { audioCodec.release(); } catch (Exception ignored) { }
                    audioCodec = null;
                }
                if (muxer != null) {
                    if (muxerStarted) {
                        try { muxer.stop(); }
                        catch (Exception error) {
                            completed = false;
                            recordingError = "Unable to finish front MP4: " + error.getMessage();
                        }
                    }
                    try { muxer.release(); } catch (Exception ignored) { }
                    muxer = null;
                }
                muxerStarted = false;
                muxerTrack = -1;
                audioMuxerTrack = -1;
                audioEnabledForSegment = false;
                planarBuffer = null;
            }
            if (!completed && currentRecordingPath.length() > 0) {
                File failed = new File(currentRecordingPath);
                if (failed.length() == 0) failed.delete();
            }
        }

        private void finishRecordingName() {
            if (!currentRecordingPath.endsWith("_recording.mp4")) return;
            File pending = new File(currentRecordingPath);
            if (!pending.exists() || pending.length() == 0) return;
            File finished = new File(pending.getParentFile(),
                    pending.getName().replace("_recording.mp4", ".mp4"));
            if (pending.renameTo(finished)) currentRecordingPath = finished.getAbsolutePath();
        }

        private File selectDirectory() {
            try {
                File factoryDaily = new File(FACTORY_DVR_DIRECTORY, "daily");
                boolean broadAccess = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
                if (broadAccess && (factoryDaily.exists() || factoryDaily.mkdirs()) && factoryDaily.canWrite()) {
                    recordingDirectory = factoryDaily.getAbsolutePath();
                    return factoryDaily;
                }
                File selected = null;
                File[] candidates = getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
                if (candidates != null) {
                    for (File candidate : candidates) {
                        if (candidate != null && Environment.isExternalStorageRemovable(candidate)) {
                            selected = candidate;
                            break;
                        }
                    }
                }
                if (selected == null) {
                    recordingError = "Allow DVR library access so front clips can be written to USB";
                    return null;
                }
                File directory = new File(selected, inputChannel == 1 ? "ATOTO-Camera-Link/DVR2" : "ATOTO-Camera-Link/Front");
                if (!directory.exists() && !directory.mkdirs()) {
                    recordingError = "Cannot create the front recording folder";
                    return null;
                }
                recordingDirectory = directory.getAbsolutePath();
                return directory;
            } catch (Throwable error) {
                recordingError = "Front storage error: " + error.getMessage();
                return null;
            }
        }

        void releaseBlocking() {
            final CountDownLatch done = new CountDownLatch(1);
            handler.post(new Runnable() {
                @Override public void run() {
                    releaseOnThread();
                    done.countDown();
                }
            });
            try { done.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        private void releaseOnThread() {
            stopRecorderOnThread(false);
            Camera existing = camera;
            camera = null;
            if (existing != null) {
                try { existing.setPreviewCallback(null); } catch (Exception ignored) { }
                try { existing.stopPreview(); } catch (Exception ignored) { }
                try { existing.release(); } catch (Exception ignored) { }
            }
            if (texture != null) {
                try { texture.release(); } catch (Exception ignored) { }
                texture = null;
            }
            frameWidth = 0;
            frameHeight = 0;
            latestJpeg = null;
        }

        void shutdown() {
            recordingRequested = false;
            releaseBlocking();
            jpegExecutor.shutdownNow();
            recordingExecutor.shutdownNow();
            thread.quitSafely();
        }
    }

    private void writeLiveViewPage(OutputStream out, String target) throws IOException {
        String selected = queryValue(target, "camera");
        boolean both = "both".equalsIgnoreCase(selected);
        boolean radio = "1".equals(queryValue(target, "radio"));
        int cameraId = parseCamera(target);
        boolean radioMicEnabled = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("record_audio", true)
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        String title = both ? "Front + Reverse" : cameraId == 0 ? "Front DVR" : cameraId == 2 ? "DVR Camera 2" : "Reverse Camera";
        StringBuilder html = new StringBuilder(8_000);
        if (radio) {
            html.append("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>")
                    .append("<style>*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}")
                    .append(".split{display:grid;grid-template-columns:").append(both ? "1fr 1fr" : "1fr")
                    .append(";width:100%;height:100%;gap:2px}img{display:block;width:100%;height:100%;min-width:0;object-fit:cover}</style><div class=split>");
            if (both) {
                html.append("<img src='/stream?camera=0' alt='Front DVR'><img src='/stream?camera=1' alt='Reverse Camera'>");
            } else {
                html.append("<img src='/stream?camera=").append(cameraId).append("' alt='").append(title).append("'>");
            }
            html.append("</div>");
            writeHtml(out, html.toString());
            return;
        }
        html.append("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>").append(title).append("</title><style>")
                .append("*{box-sizing:border-box}body{font:16px system-ui,sans-serif;background:#05080d;color:#fff;margin:0;padding:12px}")
                .append("main{max-width:1100px;margin:auto}.top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px}")
                .append("h1{font-size:21px;margin:0}.tabs{display:flex;gap:7px;flex-wrap:wrap}.tabs a,.tabs button{color:#fff;text-decoration:none;background:#263d55;border:0;padding:9px 12px;border-radius:9px;font:inherit}.tabs button.on{background:#1677ff}.tabs button:disabled{opacity:.55}")
                .append(".feeds{display:grid;grid-template-columns:").append(both ? "1fr 1fr" : "1fr").append(";gap:10px}")
                .append(".feed{position:relative;background:#000;border-radius:12px;overflow:hidden;min-height:220px}.feed img{display:block;width:100%;height:100%;min-height:220px;object-fit:contain}")
                .append(".label{position:absolute;left:10px;top:10px;background:#000b;padding:6px 10px;border-radius:8px;font-weight:700}")
                .append("@media(max-width:720px){.feeds{grid-template-columns:1fr}.top{align-items:flex-start;flex-direction:column}}</style>")
                .append("<main><div class=top><h1>").append(title).append("</h1><div class=tabs>")
                .append("<a href='/view?camera=0'>Front</a><a href='/view?camera=2'>DVR 2</a><a href='/view?camera=1'>Reverse</a><a href='/view?camera=both'>Both</a><a href='/recordings'>Videos</a>");
        if (radioMicEnabled) {
            html.append("<button id=sound onclick='toggleSound()'>Vehicle sound: Off</button>");
        } else {
            html.append("<button id=sound disabled>Vehicle sound: Radio mic off</button>");
        }
        html.append("</div></div><div class=feeds>");
        if (both || cameraId == 0) {
            html.append("<div class=feed><span class=label>FRONT DVR</span><img src='/stream?camera=0' alt='Front camera'></div>");
        }
        if (!both && cameraId == 2) {
            html.append("<div class=feed><span class=label>DVR CAMERA 2</span><img src='/stream?camera=2' alt='DVR Camera 2'></div>");
        } else if (both || cameraId != 0) {
            html.append("<div class=feed><span class=label>REVERSE</span><img src='/stream?camera=1' alt='Reverse camera'></div>");
        }
        html.append("</div></main>");
        if (radioMicEnabled) {
            html.append("<script>let vehicleAudio=null;function toggleSound(){let b=document.getElementById('sound');if(vehicleAudio){vehicleAudio.pause();vehicleAudio.src='';vehicleAudio=null;b.textContent='Vehicle sound: Off';b.className='';return;}vehicleAudio=new Audio('/audio.wav?t='+Date.now());vehicleAudio.preload='none';vehicleAudio.onerror=function(){vehicleAudio=null;b.textContent='Sound unavailable';b.className='';};let p=vehicleAudio.play();b.textContent='Vehicle sound: On';b.className='on';if(p)p.catch(function(){vehicleAudio=null;b.textContent='Tap again for sound';b.className='';});}</script>");
        }
        writeHtml(out, html.toString());
    }

    private void writeHome(OutputStream out) throws IOException {
        String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
                "<title>ATOTO Camera Link</title><style>" +
                "*{box-sizing:border-box}body{font:17px system-ui,sans-serif;background:#07111f;color:#eef6ff;margin:0;padding:18px}" +
                ".card{max-width:900px;margin:auto;background:#10243b;border:1px solid #244766;border-radius:18px;padding:18px}" +
                "h1{margin:0 0 6px;color:#fff}p{color:#b9cce0}img{width:100%;min-height:220px;background:#000;border-radius:12px;object-fit:contain}" +
                ".grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:14px}" +
                "a{display:block;text-align:center;text-decoration:none;color:white;background:#1677ff;border-radius:10px;padding:14px 8px}" +
                "a.secondary{background:#263d55}small{display:block;color:#8faac5;margin-top:14px}</style>" +
                "<div class=card><h1>ATOTO Camera Link</h1>" +
                "<p>Live and recorded access to the radio's DVR cameras.</p>" +
                "<img src='/stream?camera=0' alt='Waiting for front camera'>" +
                "<div class=grid><a href='/view?camera=0'>Live front</a>" +
                "<a href='/view?camera=1'>Live reverse</a>" +
                "<a href='/view?camera=both'>View both</a>" +
                "<a href='/recordings'>All recordings</a>" +
                "<a class=secondary href='/status'>Camera status</a></div>" +
                "<small>The factory DVR must be disabled once before Camera Link can own camera 0.</small></div>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length +
                "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    private void writeRecordingsPage(OutputStream out) throws IOException {
        List<File> recordings = findRecordings();
        StringBuilder html = new StringBuilder(32_000);
        html.append("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>Camera recordings</title><style>")
                .append("*{box-sizing:border-box}body{font:16px system-ui,sans-serif;background:#07111f;color:#eef6ff;margin:0;padding:16px}")
                .append("main{max-width:880px;margin:auto}.top{display:flex;align-items:center;gap:12px;margin-bottom:16px}")
                .append("h1{font-size:25px;margin:0}p{color:#a9bfd7;line-height:1.45}.back{color:#8fc0ff;text-decoration:none;font-size:24px}")
                .append(".notice,.clip{background:#10243b;border:1px solid #244766;border-radius:16px;padding:15px;margin:11px 0}")
                .append(".row{display:flex;align-items:center;gap:12px}.info{min-width:0;flex:1}.name{font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
                .append(".meta{color:#96adc6;font-size:13px;margin-top:5px}.tag{color:#6ee7b7;font-size:12px;font-weight:700}")
                .append("a.play{display:inline-block;background:#2b7dff;color:white;text-decoration:none;border-radius:10px;padding:11px 16px;font-weight:700}")
                .append("</style><main><div class=top><a class=back href='/'>&#8249;</a><div><h1>Camera recordings</h1>")
                .append("<p style='margin:4px 0 0'>Front DVR, DVR Camera 2, and reverse clips on the radio's USB drive</p></div></div>");

        File factory = new File(FACTORY_DVR_DIRECTORY);
        if (!factory.canRead()) {
            html.append("<div class=notice><b>USB library access is off</b><p>On the radio, open Camera Link &gt; USB Recording and allow DVR library access. Then refresh this page.</p></div>");
        } else if (recordings.isEmpty()) {
            html.append("<div class=notice><b>No completed recordings found</b><p>Keep the USB drive inserted and let Camera Link finish a clip, then refresh.</p></div>");
        } else {
            html.append("<p>").append(recordings.size()).append(" newest clips. The clip currently being written may not play until the DVR finishes it.</p>");
            SimpleDateFormat date = new SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.US);
            for (File file : recordings) {
                String id = encodeRecordingId(file);
                html.append("<div class=clip><div class=row><div class=info><div class=tag>")
                        .append(escapeHtml(recordingLabel(file))).append("</div><div class=name>")
                        .append(escapeHtml(file.getName())).append("</div><div class=meta>")
                        .append(escapeHtml(date.format(new Date(file.lastModified())))).append(" &middot; ")
                        .append(formatBytes(file.length())).append("</div></div><a class=play href='/play?id=")
                        .append(id).append("'>Play</a></div></div>");
            }
        }
        html.append("</main>");
        writeHtml(out, html.toString());
    }

    private void writeRecordingPlayer(OutputStream out, String target) throws IOException {
        File file = recordingFromTarget(target);
        if (file == null) {
            writeText(out, 404, "Recording not found\n");
            return;
        }
        String id = encodeRecordingId(file);
        String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
                "<title>Play recording</title><style>*{box-sizing:border-box}body{font:16px system-ui,sans-serif;background:#07111f;color:#eef6ff;margin:0;padding:16px}" +
                "main{max-width:900px;margin:auto}a{color:#8fc0ff;text-decoration:none}h1{font-size:21px;word-break:break-word}" +
                "video{display:block;width:100%;max-height:72vh;background:#000;border-radius:16px;margin:16px 0}.download{display:inline-block;background:#2b7dff;color:#fff;padding:12px 16px;border-radius:10px;font-weight:700}</style>" +
                "<main><a href='/recordings'>&#8249; All recordings</a><h1>" + escapeHtml(file.getName()) + "</h1>" +
                "<video controls autoplay playsinline preload=metadata src='/recording?id=" + id + "'></video>" +
                "<a class=download href='/recording?id=" + id + "&download=1'>Download video</a></main>";
        writeHtml(out, html);
    }

    private void serveRecording(OutputStream out, String target, String range, boolean headOnly) throws IOException {
        File file = recordingFromTarget(target);
        if (file == null) {
            writeText(out, 404, "Recording not found\n");
            return;
        }
        long length = file.length();
        long start = 0;
        long end = Math.max(0, length - 1);
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && length > 0) {
            try {
                String value = range.substring(6).split(",", 2)[0].trim();
                int dash = value.indexOf('-');
                String first = dash >= 0 ? value.substring(0, dash).trim() : value;
                String last = dash >= 0 ? value.substring(dash + 1).trim() : "";
                if (first.length() == 0 && last.length() > 0) {
                    long suffix = Long.parseLong(last);
                    start = Math.max(0, length - suffix);
                } else {
                    start = Long.parseLong(first);
                    if (last.length() > 0) end = Math.min(length - 1, Long.parseLong(last));
                }
                if (start < 0 || start >= length || end < start) {
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + length +
                            "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                partial = true;
            } catch (Exception ignored) {
                start = 0;
                end = Math.max(0, length - 1);
            }
        }
        long contentLength = length == 0 ? 0 : end - start + 1;
        String disposition = target.contains("download=1") ? "attachment" : "inline";
        String safeName = file.getName().replace("\"", "").replace("\r", "").replace("\n", "");
        StringBuilder headers = new StringBuilder();
        headers.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ").append(videoMimeType(file)).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(contentLength).append("\r\n")
                .append("Content-Disposition: ").append(disposition).append("; filename=\"").append(safeName).append("\"\r\n")
                .append("Cache-Control: private, max-age=60\r\n");
        if (partial) headers.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(length).append("\r\n");
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        if (headOnly || contentLength == 0) return;

        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            input.seek(start);
            byte[] buffer = new byte[128 * 1024];
            long remaining = contentLength;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) break;
                out.write(buffer, 0, count);
                remaining -= count;
            }
            out.flush();
        } finally {
            input.close();
        }
    }

    private List<File> findRecordings() {
        List<File> files = new ArrayList<>();
        for (File root : recordingRoots()) collectRecordings(root, files, 0);
        files.sort(new Comparator<File>() {
            @Override public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        if (files.size() > 200) return new ArrayList<>(files.subList(0, 200));
        return files;
    }

    private List<File> recordingRoots() {
        List<File> roots = new ArrayList<>();
        addUniqueRoot(roots, new File(FACTORY_DVR_DIRECTORY));
        addUniqueRoot(roots, new File("/mnt/USB1/dvr"));
        File[] external = getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
        if (external != null) {
            for (File path : external) {
                if (path == null) continue;
                addUniqueRoot(roots, new File(path, "ATOTO-Camera-Link/Reverse"));
                addUniqueRoot(roots, new File(path, "ATOTO-Camera-Link/Front"));
                addUniqueRoot(roots, new File(path, "ATOTO-Camera-Link/DVR2"));
                String value = path.getAbsolutePath();
                int marker = value.indexOf(File.separator + "Android" + File.separator + "data" + File.separator);
                if (marker > 0) addUniqueRoot(roots, new File(value.substring(0, marker), "dvr"));
            }
        }
        return roots;
    }

    private void addUniqueRoot(List<File> roots, File candidate) {
        try {
            String wanted = candidate.getCanonicalPath();
            for (File root : roots) if (root.getCanonicalPath().equals(wanted)) return;
            roots.add(candidate);
        } catch (IOException ignored) { }
    }

    private void collectRecordings(File file, List<File> output, int depth) {
        if (file == null || depth > 12 || output.size() >= MAX_LIBRARY_FILES || !file.exists()) return;
        if (file.isFile()) {
            String name = file.getName().toLowerCase(Locale.US);
            if (name.endsWith(".mp4") || name.endsWith(".ts") || name.endsWith(".mov") ||
                    name.endsWith(".mkv") || name.endsWith(".avi")) output.add(file);
            return;
        }
        File[] children;
        try { children = file.listFiles(); } catch (SecurityException denied) { return; }
        if (children == null) return;
        for (File child : children) {
            collectRecordings(child, output, depth + 1);
            if (output.size() >= MAX_LIBRARY_FILES) return;
        }
    }

    private File recordingFromTarget(String target) {
        String id = queryValue(target, "id");
        if (id.length() == 0) return null;
        try {
            String decoded = new String(Base64.decode(id, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
            File requested = new File(decoded).getCanonicalFile();
            if (!requested.isFile() || !isVideoFile(requested)) return null;
            String requestedPath = requested.getPath();
            for (File root : recordingRoots()) {
                String rootPath = root.getCanonicalPath();
                if (requestedPath.startsWith(rootPath + File.separator)) return requested;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String queryValue(String target, String key) {
        int question = target.indexOf('?');
        if (question < 0) return "";
        String[] values = target.substring(question + 1).split("&");
        for (String value : values) {
            int equals = value.indexOf('=');
            if (equals > 0 && key.equals(value.substring(0, equals))) return value.substring(equals + 1);
        }
        return "";
    }

    private String encodeRecordingId(File file) {
        try {
            return Base64.encodeToString(file.getCanonicalPath().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (IOException error) {
            return "";
        }
    }

    private boolean isVideoFile(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".mp4") || name.endsWith(".ts") || name.endsWith(".mov") ||
                name.endsWith(".mkv") || name.endsWith(".avi");
    }

    private String recordingLabel(File file) {
        String value = file.getAbsolutePath().toUpperCase(Locale.US);
        String name = file.getName().toUpperCase(Locale.US);
        if (value.contains("/DVR2") || value.contains("\\DVR2") || name.startsWith("CL-D2-")) return "DVR CAMERA 2";
        if (value.contains("/REVERSE") || value.contains("\\REVERSE") || value.contains("REAR") ||
                name.startsWith("R-") || name.startsWith("CL-R-")) return "REVERSE CAMERA";
        return "FRONT DVR";
    }

    private String videoMimeType(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        if (name.endsWith(".ts")) return "video/mp2t";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".avi")) return "video/x-msvideo";
        return "video/mp4";
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f GB", bytes / (1024d * 1024d * 1024d));
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024d));
        return String.format(Locale.US, "%.0f KB", bytes / 1024d);
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private void writeHtml(OutputStream out, String value) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length +
                "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    private void writeJson(OutputStream out, String value) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length +
                "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    private void writeText(OutputStream out, int status, String value) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        String phrase = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 503 ? "Service Unavailable" : "Gateway Timeout";
        out.write(("HTTP/1.1 " + status + " " + phrase + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length +
                "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    private void writeUnauthorized(OutputStream out) throws IOException {
        byte[] body = "Authentication required\n".getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"HN7 Camera\"\r\n" +
                "Content-Type: text/plain\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "DVR recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when Camera Link DVR is recording or ready for remote viewing");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent activity = PendingIntent.getActivity(this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean activelyRecording = isActivelyRecording();
        int statusIcon = getResources().getIdentifier(
                activelyRecording ? "ic_stat_rec" : "ic_stat_camera", "drawable", getPackageName());
        if (statusIcon == 0) statusIcon = android.R.drawable.presence_video_online;
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(statusIcon)
                .setContentTitle(activelyRecording ? "REC • Camera Link DVR" : "Camera Link DVR")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setOngoing(true)
                .setContentIntent(activity)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private boolean isActivelyRecording() {
        return recording || (frontPipeline != null && frontPipeline.recording);
    }

    private String currentRecordingStatusText() {
        boolean front = frontPipeline != null && frontPipeline.recording;
        if (front && recording) return "Front and reverse cameras recording to USB";
        if (front) return "Front DVR recording to USB";
        if (recording) return "Reverse camera recording to USB";
        return "Camera server running";
    }

    private void publishSystemUiRecordingState() {
        publishSystemUiRecordingState(isActivelyRecording(), false);
    }

    private synchronized void publishSystemUiRecordingState(boolean active, boolean force) {
        if (!force && lastPublishedSystemUiRecordingState != null &&
                lastPublishedSystemUiRecordingState.booleanValue() == active) return;
        Intent state = new Intent(ACTION_ATOTO_DVR_RECORD);
        state.putExtra(EXTRA_ATOTO_DVR_RECORD_STATE, active ? 1 : 0);
        try {
            sendBroadcast(state);
            lastPublishedSystemUiRecordingState = Boolean.valueOf(active);
            Log.i(TAG, "ATOTO status-bar REC state: " + active);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to update the ATOTO status-bar REC state", error);
        }
    }
}
