package com.codex.hn7camerastream;

import android.content.Context;
import android.content.pm.PackageManager;
import android.Manifest;
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
    private static final String SYSTEM_SENTRY_SHUTDOWN_TIME = "sentry_shutdown_time";
    private static final String SYSTEM_VOLTAGE_DETECTION = "voltage_detection";
    private static final String SYSTEM_PARKING_MODE = "parking_monitoring_mode";
    private static final String SYSTEM_PARKING_BOOT_TIME = "parking_monitoring_boot_time";

    private Object mcuManager;
    private Method getBattery;
    private Object carStatus;
    private Method getAccStatus;
    private Method setSentryModeTime;
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
            setSentryModeTime = statusClass.getMethod("setSentryModeTime", int.class);
            lastError = "";
        } catch (Throwable error) {
            mcuManager = null;
            carStatus = null;
            getBattery = null;
            getAccStatus = null;
            setSentryModeTime = null;
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

    boolean sendSentryHeartbeat() {
        if (!isAvailable() || setSentryModeTime == null) return false;
        try {
            // The factory DVR sends a count of 3 every five seconds. The car
            // service decrements this count and permits sleep when it reaches 0.
            setSentryModeTime.invoke(carStatus, 3);
            lastError = "";
            return true;
        } catch (Throwable error) {
            lastError = describe(error);
            Log.w(TAG, "Unable to send parking heartbeat: " + lastError);
            return false;
        }
    }

    boolean clearSentryHeartbeat() {
        if (!isAvailable() || setSentryModeTime == null) return false;
        try {
            setSentryModeTime.invoke(carStatus, 0);
            lastError = "";
            return true;
        } catch (Throwable error) {
            lastError = describe(error);
            Log.w(TAG, "Unable to clear parking heartbeat: " + lastError);
            return false;
        }
    }

    static boolean canControlSystemSentry(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED;
    }

    static boolean setSystemSentryEnabled(Context context, boolean enabled) {
        if (!canControlSystemSentry(context)) return false;
        try {
            // ATOTO classifies this vendor key as Secure at runtime even though
            // the factory DVR calls Settings.System. Writing it directly avoids
            // the rejected System-to-Secure redirect applied to third-party apps.
            return Settings.Secure.putInt(context.getContentResolver(), SYSTEM_SENTRY_SETTING,
                    enabled ? 1 : 0);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to set ATOTO sentry mode", error);
            return false;
        }
    }

    static boolean syncSystemParkingSettings(Context context, boolean enabled, int durationMinutes,
                                             int cutoffTenths, String activationMode) {
        if (!canControlSystemSentry(context)) return false;
        int shutdownIndex = durationMinutes == 30 ? 0 : durationMinutes == 1440 ? 2 : 1;
        int voltageIndex = cutoffTenths <= 116 ? 0 : cutoffTenths >= 120 ? 2 : 1;
        int parkingMode = "once".equals(activationMode) ? 1 : 2;
        try {
            boolean success = setSystemSentryEnabled(context, enabled);
            success &= putVendorInt(context, SYSTEM_SENTRY_SHUTDOWN_TIME, shutdownIndex);
            success &= putVendorInt(context, SYSTEM_VOLTAGE_DETECTION, voltageIndex);
            success &= putVendorInt(context, SYSTEM_PARKING_MODE, parkingMode);
            if (!enabled) {
                success &= putVendorLong(context, SYSTEM_PARKING_BOOT_TIME, 0L);
            } else if (parkingMode == 1 && Settings.Secure.getLong(context.getContentResolver(),
                    SYSTEM_PARKING_BOOT_TIME, 0L) == 0L) {
                success &= putVendorLong(context, SYSTEM_PARKING_BOOT_TIME, System.currentTimeMillis());
            }
            Log.i(TAG, "ATOTO parking settings synced: enabled=" + enabled +
                    ", durationIndex=" + shutdownIndex + ", voltageIndex=" + voltageIndex +
                    ", mode=" + parkingMode + ", success=" + success);
            return success;
        } catch (Throwable error) {
            Log.w(TAG, "Unable to sync ATOTO parking settings", error);
            return false;
        }
    }

    static boolean isSystemSentryEnabled(Context context) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(), SYSTEM_SENTRY_SETTING, 0) == 1;
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean putVendorInt(Context context, String key, int value) {
        try {
            return Settings.Secure.putInt(context.getContentResolver(), key, value);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to write ATOTO parking setting " + key, error);
            return false;
        }
    }

    private static boolean putVendorLong(Context context, String key, long value) {
        try {
            return Settings.Secure.putLong(context.getContentResolver(), key, value);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to write ATOTO parking setting " + key, error);
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
