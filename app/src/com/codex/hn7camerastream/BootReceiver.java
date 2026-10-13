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

        AtotoParkingBridge vehicle = new AtotoParkingBridge();
        if ("android.mcu.device.action.ACC".equals(action)) {
            boolean accOn = intent.getBooleanExtra("AccStatus", false);
            boolean parkingArmed = AtotoParkingBridge.isSystemSentryEnabled(context);
            if (!accOn && !parkingArmed) {
                Log.i("HN7CameraStream", "ACC off; factory parking is not armed");
                return;
            }
            requestTailscale(context);
            Intent service = new Intent(context, CameraStreamService.class);
            try { context.startForegroundService(service); }
            catch (RuntimeException ignored) { }
            return;
        }
        int acc = vehicle.readAccStatus();
        if (acc != 1 && !AtotoParkingBridge.isSystemSentryEnabled(context)) {
            Log.i("HN7CameraStream", "Boot start deferred until ACC-on or factory parking; state=" + acc);
            return;
        }
        requestTailscale(context);
        Intent service = new Intent(context, CameraStreamService.class);
        try { context.startForegroundService(service); }
        catch (RuntimeException ignored) { }
        scheduleCameraBootstrap(context);
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
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt, pending);
            } else {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
            }
            Log.i("HN7CameraStream", "Scheduled transparent camera bootstrap after boot");
        } catch (SecurityException denied) {
            Log.w("HN7CameraStream", "Exact bootstrap alarm denied; using inexact alarm", denied);
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt, pending);
            } else {
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
            }
        }
    }
}
