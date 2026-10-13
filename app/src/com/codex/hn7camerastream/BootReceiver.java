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
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putLong("last_boot_start", System.currentTimeMillis()).apply();
        String mode = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("device_mode", "radio");
        if ("phone".equals(mode)) return;
        if ("android.mcu.device.action.ACC".equals(intent.getAction())) {
            Intent service = new Intent(context, CameraStreamService.class);
            try { context.startForegroundService(service); }
            catch (RuntimeException ignored) { }
            return;
        }
        if ("android.mcu.device.action.GSENSOR".equals(intent.getAction())) {
            Log.w("HN7CameraStream", "ATOTO MCU G-sensor emergency event received");
            CameraStreamService running = CameraStreamService.getRunningInstance();
            if (running != null) {
                running.handleMcuEmergencyEvent();
                return;
            }
            Intent emergency = new Intent(context, CameraStreamService.class);
            emergency.setAction(CameraStreamService.ACTION_EMERGENCY_LOCK);
            try { context.startForegroundService(emergency); }
            catch (RuntimeException ignored) { }
            return;
        }
        Intent tailscale = new Intent("com.tailscale.ipn.CONNECT_VPN");
        tailscale.setPackage("com.tailscale.ipn");
        try { context.sendBroadcast(tailscale); }
        catch (RuntimeException ignored) { }
        Intent service = new Intent(context, CameraStreamService.class);
        try { context.startForegroundService(service); }
        catch (RuntimeException ignored) { }
        scheduleCameraBootstrap(context);
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
