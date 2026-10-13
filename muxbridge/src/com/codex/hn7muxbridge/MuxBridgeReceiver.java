package com.codex.hn7muxbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

public final class MuxBridgeReceiver extends BroadcastReceiver {
    private static final String TAG = "CameraLinkMuxBridge";

    @Override
    public void onReceive(final Context context, Intent intent) {
        final int requested = intent != null && intent.getIntExtra("camera_id", 0) == 2 ? 2 : 0;
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                Intent factory = new Intent("android.intent.action.VIDEOIN_SERVICE")
                        .setClassName("com.hcn.dvr", "com.hcn.dvr.service.VideoService");
                try {
                    try { context.stopService(factory); } catch (Throwable ignored) { }
                    boolean stored = Settings.System.putInt(
                            context.getContentResolver(), "camera_type", requested);
                    if (!stored) throw new IllegalStateException("ATOTO camera_type was not stored");
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(factory);
                    else context.startService(factory);
                    // Let VideoService complete onCreate (where it writes
                    // /dev/videomux13) and onStartCommand (where it promotes
                    // itself) before stopping the temporary bridge.
                    Thread.sleep(650L);
                    context.stopService(factory);
                    Log.i(TAG, "Selected ATOTO DVR input " + requested);
                } catch (Throwable error) {
                    Log.e(TAG, "Unable to select ATOTO DVR input " + requested, error);
                    try { context.stopService(factory); } catch (Throwable ignored) { }
                } finally {
                    pending.finish();
                }
            }
        }, "CameraLinkMuxBridge").start();
    }
}
