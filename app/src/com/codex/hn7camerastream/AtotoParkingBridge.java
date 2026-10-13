package com.codex.hn7camerastream;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Small reflection bridge to the ATOTO framework classes that are present on the
 * radio but are not part of the public Android SDK. Keeping the references out of
 * the bytecode lets the same APK open normally on a phone.
 */
final class AtotoParkingBridge {
    private static final String TAG = "AtotoParkingBridge";
    static final String SYSTEM_SENTRY_SETTING = "sentry_mode_enable";

    private Object mcuManager;
    private Method getBattery;
    private Object carStatus;
    private Method getAccStatus;
    private volatile String lastError = "ATOTO vehicle services have not been checked";

    AtotoParkingBridge() {
        connect();
    }

    private synchronized void connect() {
        if (mcuManager != null && carStatus != null) return;
        try {
            Class<?> mcuClass = Class.forName("android.carsource.McuManager");
            Method instance = mcuClass.getMethod("getsInstance");
            mcuManager = instance.invoke(null);
            getBattery = mcuClass.getMethod("getBattery");

            Class<?> statusClass = Class.forName("android.carstatus.CarStatus");
            Constructor<?> constructor = statusClass.getConstructor();
            carStatus = constructor.newInstance();
            getAccStatus = statusClass.getMethod("getAccStatus");
            lastError = "";
        } catch (Throwable error) {
            mcuManager = null;
            carStatus = null;
            getBattery = null;
            getAccStatus = null;
            lastError = describe(error);
            Log.w(TAG, "ATOTO parking services unavailable: " + lastError);
        }
    }

    boolean isAvailable() {
        if (mcuManager == null || carStatus == null) connect();
        return mcuManager != null && carStatus != null;
    }

    float readBatteryVoltage() {
        if (!isAvailable() || getBattery == null) return -1f;
        try {
            Object value = getBattery.invoke(mcuManager);
            float voltage = value instanceof Number ? ((Number) value).floatValue() : -1f;
            if (voltage > 0f) lastError = "";
            return voltage;
        } catch (Throwable error) {
            lastError = describe(error);
            Log.w(TAG, "Unable to read vehicle voltage: " + lastError);
            return -1f;
        }
    }

    int readAccStatus() {
        if (isAvailable() && getAccStatus != null) {
            try {
                Object value = getAccStatus.invoke(carStatus);
                if (value instanceof Number) {
                    lastError = "";
                    return ((Number) value).intValue();
                }
            } catch (Throwable error) {
                lastError = describe(error);
                Log.w(TAG, "Unable to read ACC state: " + lastError);
            }
        }
        // The ATOTO car-status service publishes a world-readable fallback.
        File state = new File("/tmp/carinfo/carstatus.kv");
        if (!state.canRead()) return -1;
        try (BufferedReader reader = new BufferedReader(new FileReader(state))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("acc=")) continue;
                return Integer.parseInt(line.substring(4).trim());
            }
        } catch (Throwable error) {
            lastError = describe(error);
        }
        return -1;
    }

    static boolean isSystemSentryEnabled(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(), SYSTEM_SENTRY_SETTING, 0) == 1;
        } catch (Throwable error) {
            return false;
        }
    }

    String getLastError() {
        return lastError;
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.length() == 0 ? "" :
                String.format(Locale.US, ": %s", message));
    }
}
