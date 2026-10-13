package com.codex.hn7camerastream;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

/**
 * Brief transparent activity delivered by AlarmManager after boot. Android only
 * grants camera and microphone while-in-use access when this package is visible;
 * keeping this activity resumed while the DVR service opens its inputs gives the
 * automatic recorder the same permission state as launching Camera Link by hand.
 */
public final class BootBootstrapActivity extends Activity {
    private static final String TAG = "HN7BootBootstrap";
    private static final long VISIBLE_WINDOW_MS = 15_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().setDimAmount(0f);
        Log.i(TAG, "Transparent boot bootstrap created");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) return;
        started = true;
        if (new AtotoAccState().read() != 1) {
            Log.i(TAG, "Bootstrap cancelled because ACC is not confirmed on");
            finishAndRemoveTask();
            overridePendingTransition(0, 0);
            return;
        }
        Log.i(TAG, "Starting DVR service while camera permission is active");
        Intent service = new Intent(this, CameraStreamService.class);
        service.setAction(CameraStreamService.ACTION_BOOTSTRAP_RETRY);
        try {
            startForegroundService(service);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start DVR service from bootstrap", error);
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "Boot bootstrap complete");
                finishAndRemoveTask();
                overridePendingTransition(0, 0);
            }
        }, VISIBLE_WINDOW_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
