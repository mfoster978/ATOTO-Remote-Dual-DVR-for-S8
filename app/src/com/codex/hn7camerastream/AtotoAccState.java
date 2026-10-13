package com.codex.hn7camerastream;

import android.util.Log;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Read-only access to the radio's ignition state. */
final class AtotoAccState {
    private static final String TAG = "AtotoAccState";
    private static final long BROADCAST_GRACE_MS = 60_000L;
    private static volatile int lastBroadcastState = -1;
    private static volatile long lastBroadcastAt;

    private Object carStatus;
    private Method getAccStatus;

    AtotoAccState() {
        connect();
    }

    static void noteBroadcast(boolean accOn) {
        lastBroadcastState = accOn ? 1 : 0;
        lastBroadcastAt = SystemClock.elapsedRealtime();
    }

    private synchronized void connect() {
        if (carStatus != null && getAccStatus != null) return;
        try {
            Class<?> statusClass = Class.forName("android.carstatus.CarStatus");
            Constructor<?> constructor = statusClass.getConstructor();
            carStatus = constructor.newInstance();
            getAccStatus = statusClass.getMethod("getAccStatus");
        } catch (Throwable error) {
            carStatus = null;
            getAccStatus = null;
            Log.w(TAG, "ATOTO ACC state is unavailable", error);
        }
    }

    int read() {
        long broadcastAge = SystemClock.elapsedRealtime() - lastBroadcastAt;
        if (lastBroadcastState >= 0 && broadcastAge >= 0 && broadcastAge <= BROADCAST_GRACE_MS) {
            return lastBroadcastState;
        }
        if (carStatus == null || getAccStatus == null) connect();
        if (carStatus != null && getAccStatus != null) {
            try {
                Object value = getAccStatus.invoke(carStatus);
                if (value instanceof Number) return ((Number) value).intValue();
            } catch (Throwable error) {
                Log.w(TAG, "Unable to read ATOTO ACC state", error);
            }
        }

        File state = new File("/tmp/carinfo/carstatus.kv");
        if (!state.canRead()) return -1;
        try (BufferedReader reader = new BufferedReader(new FileReader(state))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("acc=")) return Integer.parseInt(line.substring(4).trim());
            }
        } catch (Throwable ignored) { }
        return -1;
    }
}
