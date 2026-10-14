package com.codex.hn7camerastream;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;

/** Starts ATOTO's existing recorder once per ignition session, without a screen. */
public final class StockDvrAutoRecorder {
    private static final String TAG = "StockDvrAutoRecorder";
    private static final long RETRY_MS = 1_000L;
    private static final long START_WINDOW_MS = 180_000L;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Context application;
    private static AtotoAccState accState;
    private static boolean armed;
    private static long deadline;
    private static final Runnable retry = new Runnable() {
        @Override public void run() { attempt(); }
    };

    private StockDvrAutoRecorder() { }

    /** Repeated server-recovery callbacks do not restart a manually stopped recorder. */
    static void onIgnitionReady(Context context) {
        if (context == null || !"com.hcn.dvr".equals(context.getPackageName()) || armed) return;
        if (new AtotoAccState().read() != 1) return;
        application = context.getApplicationContext();
        accState = new AtotoAccState();
        armed = true;
        deadline = SystemClock.elapsedRealtime() + START_WINDOW_MS;
        handler.postDelayed(retry, 650L);
        Log.i(TAG, "Scheduled factory auto-record after ignition/startup");
    }

    static void cancel() {
        handler.removeCallbacks(retry);
        application = null;
        accState = null;
        armed = false;
    }

    private static void attempt() {
        Context app = application;
        if (app == null || accState == null) return;
        if (accState.read() != 1) { cancel(); return; }
        try {
            Class<?> helperClass = Class.forName("com.hcn.dvr.utils.DataHelper");
            Object helper = helperClass.getMethod("getInstance", Context.class).invoke(null, app);
            if (!readBoolean(helper, "getRecordAutoSave")) return;
            Field controllerField = helperClass.getDeclaredField("cameraController");
            controllerField.setAccessible(true);
            Object controller = controllerField.get(helper);
            if (controller != null && readBoolean(controller, "isEncoding")) {
                Log.i(TAG, "Factory recording confirmed active");
                return;
            }
            boolean signal = Class.forName("com.hcn.dvr.utils.Utils").getField("CAMERA_SIGNAL").getBoolean(null);
            if (controller != null && signal && readBoolean(helper, "isReadyForRecord")) {
                if (accState.read() != 1) { cancel(); return; }
                if (!readBoolean(controller, "isOpened")) {
                    controller.getClass().getMethod("open").invoke(controller);
                } else {
                    if (readBoolean(helper, "getEnableAudioRecord")) {
                        Class<?> audioClass = Class.forName("com.hcn.dvr.manager.AudioCaptureManager");
                        Object audio = audioClass.getMethod("getInstance").invoke(null);
                        audioClass.getMethod("startCapture").invoke(audio);
                    }
                    String base = (String) helperClass.getMethod("getRecordSavePath").invoke(helper);
                    if (base == null || base.length() == 0) throw new IllegalStateException("DVR USB path unavailable");
                    if (!base.endsWith("/")) base += "/";
                    if (accState.read() != 1) { cancel(); return; }
                    controller.getClass().getMethod("startEncoding", String.class).invoke(controller, base + "daily/");
                    Log.i(TAG, "Requested native background recording; waiting for encoder confirmation");
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Factory auto-record not ready; retrying", error);
        }
        if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed(retry, RETRY_MS);
        else Log.w(TAG, "Factory auto-record startup window expired; check camera, USB, and auto-record setting");
    }

    private static boolean readBoolean(Object target, String method) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(target.getClass().getMethod(method).invoke(target));
    }
}
