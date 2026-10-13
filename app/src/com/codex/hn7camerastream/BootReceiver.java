package com.codex.hn7camerastream;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String ACTION_ACC_ON_RECOVERY =
            "com.codex.hn7camerastream.ACC_ON_RECOVERY";
    private static final long[] RECOVERY_DELAYS_MS = {12_000L, 35_000L, 70_000L, 120_000L, 180_000L};

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            StockDvrAccessGuard.clearSession(context);
        }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putLong("last_boot_start", System.currentTimeMillis()).apply();
        String mode = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("device_mode", "radio");
        if ("phone".equals(mode)) return;

        AtotoAccState accState = new AtotoAccState();
        if (ACTION_ACC_ON_RECOVERY.equals(action)) {
            int recoveredAcc = accState.read();
            if (recoveredAcc != 1) {
                if (recoveredAcc == 0) EnhancedDvrBootstrap.stopForAccOff(context);
                Log.i("HN7CameraStream", "ACC-on recovery skipped; state=" + recoveredAcc);
                return;
            }
            requestTailscale(context);
            EnhancedDvrBootstrap.initialize(context);
            Intent service = new Intent(context, CameraStreamService.class)
                    .setAction(CameraStreamService.ACTION_BOOTSTRAP_RETRY);
            try { context.startForegroundService(service); }
            catch (RuntimeException error) {
                Log.w("HN7CameraStream", "ACC-on recovery could not start server", error);
            }
            scheduleCameraBootstrap(context);
            return;
        }
        if ("android.mcu.device.action.ACC".equals(action)) {
            handleAccBroadcast(context, intent);
            return;
        }
        int acc = accState.read();
        if (acc != 1) {
            try { context.stopService(new Intent(context, CameraStreamService.class)); }
            catch (RuntimeException ignored) { }
            if (acc == 0) EnhancedDvrBootstrap.stopForAccOff(context);
            else {
                EnhancedDvrBootstrap.initialize(context);
                scheduleServerRecovery(context);
            }
            Log.i("HN7CameraStream", "Start deferred for ACC-on recovery checks; state=" + acc);
            return;
        }
        requestTailscale(context);
        EnhancedDvrBootstrap.initialize(context);
        Intent service = new Intent(context, CameraStreamService.class);
        try { context.startForegroundService(service); }
        catch (RuntimeException ignored) { }
        scheduleCameraBootstrap(context);
        scheduleServerRecovery(context);
    }

    static void handleAccBroadcast(Context context, Intent intent) {
        if (!intent.hasExtra("AccStatus")) return;
        boolean accOn = intent.getBooleanExtra("AccStatus", false);
        AtotoAccState.noteBroadcast(accOn);
        if (!accOn) {
            EnhancedDvrBootstrap.stopForAccOff(context);
            Log.i("HN7CameraStream", "ACC off; custom camera service stopped");
            return;
        }
        EnhancedDvrBootstrap.initialize(context);
        requestTailscale(context);
        scheduleCameraBootstrap(context);
        scheduleServerRecovery(context);
    }

    private static void requestTailscale(Context context) {
        Intent tailscale = new Intent("com.tailscale.ipn.CONNECT_VPN");
        tailscale.setPackage("com.tailscale.ipn");
        try { context.sendBroadcast(tailscale); }
        catch (RuntimeException ignored) { }
    }

    private static void scheduleCameraBootstrap(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) {
            Log.w("HN7CameraStream", "Alarm service unavailable; boot camera bootstrap skipped");
            return;
        }
        Intent bootstrap = new Intent(context, BootBootstrapActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION |
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        PendingIntent pending = PendingIntent.getActivity(context, 8766, bootstrap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = SystemClock.elapsedRealtime() + 1_500L;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME,
                        triggerAt, pending);
            } else {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME, triggerAt, pending);
            }
            Log.i("HN7CameraStream", "Scheduled transparent camera bootstrap after boot");
        } catch (SecurityException denied) {
            Log.w("HN7CameraStream", "Exact bootstrap alarm denied; using inexact alarm", denied);
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME,
                        triggerAt, pending);
            } else {
                alarms.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pending);
            }
        }
    }

    private static void scheduleServerRecovery(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        for (int index = 0; index < RECOVERY_DELAYS_MS.length; index++) {
            Intent retry = new Intent(context, BootReceiver.class)
                    .setAction(ACTION_ACC_ON_RECOVERY);
            PendingIntent pending = PendingIntent.getBroadcast(context, 8767 + index, retry,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long triggerAt = SystemClock.elapsedRealtime() + RECOVERY_DELAYS_MS[index];
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, triggerAt, pending);
                } else {
                    alarms.setExact(AlarmManager.ELAPSED_REALTIME, triggerAt, pending);
                }
            } catch (SecurityException denied) {
                if (Build.VERSION.SDK_INT >= 23) {
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, triggerAt, pending);
                } else {
                    alarms.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pending);
                }
            }
        }
        Log.i("HN7CameraStream", "Scheduled ACC-on server recovery checks");
    }

    static void cancelCameraBootstrap(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent bootstrap = new Intent(context, BootBootstrapActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION |
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        PendingIntent pending = PendingIntent.getActivity(context, 8766, bootstrap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { alarms.cancel(pending); }
        catch (RuntimeException ignored) { }
        pending.cancel();
        for (int index = 0; index < RECOVERY_DELAYS_MS.length; index++) {
            Intent retry = new Intent(context, BootReceiver.class)
                    .setAction(ACTION_ACC_ON_RECOVERY);
            PendingIntent recovery = PendingIntent.getBroadcast(context, 8767 + index, retry,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            try { alarms.cancel(recovery); }
            catch (RuntimeException ignored) { }
            recovery.cancel();
        }
    }
}
