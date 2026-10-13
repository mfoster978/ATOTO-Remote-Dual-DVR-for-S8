package com.codex.hn7camerastream;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
        boolean primaryDvr = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("primary_dvr_mode", false);
        if (primaryDvr) {
            Intent dashboard = new Intent(context, MainActivity.class);
            dashboard.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try { context.startActivity(dashboard); }
            catch (RuntimeException ignored) { }
        }
        Intent tailscale = new Intent("com.tailscale.ipn.CONNECT_VPN");
        tailscale.setPackage("com.tailscale.ipn");
        try { context.sendBroadcast(tailscale); }
        catch (RuntimeException ignored) { }
        Intent service = new Intent(context, CameraStreamService.class);
        try { context.startForegroundService(service); }
        catch (RuntimeException ignored) { }
    }
}
