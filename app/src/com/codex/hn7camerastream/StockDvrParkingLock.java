package com.codex.hn7camerastream;

import android.app.AlertDialog;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.WeakHashMap;

/** Keeps the factory parking setting unavailable until its reset issue is resolved. */
public final class StockDvrParkingLock {
    private static final String TAG = "StockDvrParkingLock";
    private static final String SETTING = "sentry_mode_enable";
    private static final String MESSAGE = "Parking monitor mode is currently disabled due to " +
            "factory-reset issues and is still in development. It cannot be enabled in this version.";
    private static final WeakHashMap<Context, AlertDialog> DIALOGS =
            new WeakHashMap<Context, AlertDialog>();

    private StockDvrParkingLock() { }

    public static void disableSavedSetting(Context context) {
        if (context == null || !"com.hcn.dvr".equals(context.getPackageName())) return;
        try {
            if (Settings.System.getInt(context.getContentResolver(), SETTING, 0) != 0) {
                if (!Settings.System.putInt(context.getContentResolver(), SETTING, 0)) {
                    Log.w(TAG, "Saved parking flag could not be cleared; factory getter remains locked off");
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Saved parking flag unavailable; factory getter remains locked off", error);
        }
    }

    /** Reapplied after every native parking-mask refresh, including theme changes. */
    public static void apply(Object fragment) {
        if (fragment == null) return;
        try {
            final View parkingSwitch = fieldView(fragment, "switchParking");
            if (parkingSwitch == null) return;
            final Context context = parkingSwitch.getContext();
            disableSavedSetting(context);
            Object checked = parkingSwitch.getClass().getMethod("isChecked").invoke(parkingSwitch);
            if (Boolean.TRUE.equals(checked)) {
                parkingSwitch.getClass().getMethod("setChecked", boolean.class, boolean.class)
                        .invoke(parkingSwitch, false, false);
            }
            parkingSwitch.getClass().getMethod("setSubtitle", String.class)
                    .invoke(parkingSwitch, "Locked • still in development");
            bindInfo(parkingSwitch, context);

            View title = fieldView(fragment, "titParking");
            if (title instanceof TextView) ((TextView) title).setText("Parking monitor — disabled");
            if (title != null) bindInfo(title, context);
            for (String name : new String[]{"optionParking", "optionMonitoringTime", "optionVoltage"}) {
                View option = fieldView(fragment, name);
                if (option != null) {
                    option.setEnabled(false);
                    option.setFocusable(false);
                    option.setAlpha(0.55f);
                }
            }
            View mask = fieldView(fragment, "parkingMask");
            if (mask != null) {
                mask.setVisibility(View.VISIBLE);
                mask.setContentDescription("Parking monitor disabled. Tap for information.");
                bindInfo(mask, context);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            // The factory getter/setter are independently locked off even if a
            // firmware variant's view fields differ. Other native settings continue.
            Log.w(TAG, "Parking information control unavailable", error);
        }
    }

    private static View fieldView(Object fragment, String name) throws ReflectiveOperationException {
        Field field = fragment.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(fragment);
        return value instanceof View ? (View) value : null;
    }

    private static void bindInfo(View view, final Context context) {
        view.setEnabled(true);
        view.setFocusable(true);
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) { showInfo(context); }
        });
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View target, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) target.performClick();
                return true;
            }
        });
    }

    private static void showInfo(Context context) {
        AlertDialog existing = DIALOGS.get(context);
        if (existing != null && existing.isShowing()) return;
        try {
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("Parking monitor disabled")
                    .setMessage(MESSAGE)
                    .setPositiveButton("OK", null)
                    .create();
            DIALOGS.put(context, dialog);
            dialog.show();
        } catch (RuntimeException error) {
            Toast.makeText(context, MESSAGE, Toast.LENGTH_LONG).show();
        }
    }
}
