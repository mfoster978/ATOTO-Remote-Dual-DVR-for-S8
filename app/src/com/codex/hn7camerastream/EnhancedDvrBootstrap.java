package com.codex.hn7camerastream;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Starts additive Camera Link features without changing ATOTO DVR settings. */
public final class EnhancedDvrBootstrap {
    private static final String TAG = "EnhancedStockDvr";
    private static final long CHECK_INTERVAL_MS = 10_000L;
    private static final long UNKNOWN_ACC_RETRY_MS = 180_000L;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Context application;
    private static AtotoAccState accState;
    private static long unknownAccDeadline;
    private static final Runnable recovery = new Runnable() {
        @Override public void run() { reconcileSafely(); }
    };

    private EnhancedDvrBootstrap() { }

    public static void initialize(Context context) {
        if (context == null) return;
        try {
            application = context.getApplicationContext();
            StockDvrParkingLock.disableSavedSetting(application);
            application.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                    .putString("device_mode", "radio").apply();
            if (accState == null) accState = new AtotoAccState();
            unknownAccDeadline = SystemClock.elapsedRealtime() + UNKNOWN_ACC_RETRY_MS;
            handler.removeCallbacks(recovery);
            reconcileSafely();
        } catch (RuntimeException error) {
            Log.w(TAG, "Remote startup unavailable; factory DVR continues", error);
        }
    }

    /** Called by the factory's already-registered receiver, before its original logic. */
    public static void onFactoryBroadcast(Context context, Intent intent) {
        if (intent == null || !"android.mcu.device.action.ACC".equals(intent.getAction())) return;
        try { BootReceiver.handleAccBroadcast(context, intent); }
        catch (RuntimeException error) {
            Log.w(TAG, "Remote ACC recovery unavailable; factory ACC handling continues", error);
        }
    }

    public static void onFactoryServiceDestroyed() {
        handler.removeCallbacks(recovery);
        application = null;
    }

    static void stopForAccOff(Context context) {
        handler.removeCallbacks(recovery);
        try { BootReceiver.cancelCameraBootstrap(context); }
        catch (RuntimeException error) { Log.w(TAG, "Unable to cancel recovery alarms", error); }
        try { context.stopService(new Intent(context, CameraStreamService.class)); }
        catch (RuntimeException error) { Log.w(TAG, "Unable to stop remote service", error); }
    }

    private static void reconcileSafely() {
        try { reconcile(); }
        catch (RuntimeException error) {
            Log.w(TAG, "Remote recovery failed; factory DVR continues", error);
            if (application != null && accState != null && accState.read() == 1) {
                handler.postDelayed(recovery, CHECK_INTERVAL_MS);
            }
        }
    }

    private static void reconcile() {
        Context app = application;
        if (app == null) return;
        int acc = accState.read();
        if (acc == 0) {
            stopForAccOff(app);
            return;
        }
        if (acc == 1 && CameraStreamService.getRunningInstance() == null) {
            try {
                app.startForegroundService(new Intent(app, CameraStreamService.class)
                        .setAction(CameraStreamService.ACTION_BOOTSTRAP_RETRY));
                Log.i(TAG, "Factory background service requested remote server recovery");
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to start enhanced DVR service; will retry", error);
            }
        }
        // Handler delays never wake the radio. Stop checking as soon as ACC-off
        // is observed; ATOTO's original service exclusively owns parking power.
        if (acc == 1 || SystemClock.elapsedRealtime() < unknownAccDeadline) {
            handler.postDelayed(recovery, CHECK_INTERVAL_MS);
        }
    }
}
