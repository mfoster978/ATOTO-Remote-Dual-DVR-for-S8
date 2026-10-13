package com.codex.hn7camerastream;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts additive Camera Link features without changing ATOTO DVR settings. */
public final class EnhancedDvrBootstrap {
    private static final String TAG = "EnhancedStockDvr";

    private EnhancedDvrBootstrap() { }

    public static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("device_mode", "radio").apply();

        AtotoParkingBridge vehicle = new AtotoParkingBridge();
        int acc = vehicle.readAccStatus();
        boolean factoryParkingArmed = AtotoParkingBridge.isSystemSentryEnabled(app);
        if (acc != 1 && !factoryParkingArmed) {
            Log.i(TAG, "Enhanced services remain stopped because ACC is off and factory parking is not armed: " + acc);
            return;
        }
        try {
            app.startForegroundService(new Intent(app, CameraStreamService.class));
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to start enhanced DVR service", error);
        }
        try {
            Intent tailscale = new Intent("com.tailscale.ipn.CONNECT_VPN")
                    .setPackage("com.tailscale.ipn");
            app.sendBroadcast(tailscale);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to request Tailscale connection", error);
        }
    }
}
