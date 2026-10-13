package com.codex.hn7camerastream;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Adds Camera Link controls directly over ATOTO's native DVR activity. */
public final class StockDvrUiBridge {
    private static final WeakHashMap<Activity, Controls> INSTANCES = new WeakHashMap<Activity, Controls>();

    private StockDvrUiBridge() { }

    public static void attach(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        EnhancedDvrBootstrap.initialize(activity);
        synchronized (INSTANCES) {
            if (INSTANCES.containsKey(activity)) return;
            Controls controls = new Controls(activity);
            INSTANCES.put(activity, controls);
            controls.attach();
        }
    }

    public static void detach(Activity activity) {
        Controls controls;
        synchronized (INSTANCES) { controls = INSTANCES.remove(activity); }
        if (controls != null) controls.detach();
    }

    public static void refreshOwnerLock(Activity activity) {
        Controls controls;
        synchronized (INSTANCES) { controls = INSTANCES.get(activity); }
        if (controls != null) controls.refreshOwnerLock();
    }

    public static void onFactoryDvrResume(Activity activity) {
        Controls controls;
        synchronized (INSTANCES) { controls = INSTANCES.get(activity); }
        if (controls != null) {
            controls.refreshOwnerLock();
            controls.scheduleFactoryAutoRecord();
        }
    }

    public static void lockOwnerControls(Activity activity) {
        Controls controls;
        synchronized (INSTANCES) { controls = INSTANCES.get(activity); }
        if (controls != null) controls.lockOwnerControls();
    }

    private static final class Controls implements SurfaceHolder.Callback {
        private static final int MODE_FRONT = 0;
        private static final int MODE_REVERSE = 1;
        private static final int MODE_SPLIT_TOP_BOTTOM = 2;
        private static final int MODE_SPLIT_LEFT_RIGHT = 3;

        private final WeakReference<Activity> activityRef;
        private final Handler main = new Handler(Looper.getMainLooper());
        private SurfaceView reverseSurface;
        private LinearLayout controls;
        private Button reverseButton;
        private Button splitButton;
        private View factoryCameraLayout;
        private View ownerDockGate;
        private View ownerPinOverlay;
        private int mode = MODE_FRONT;
        private int attachGeneration;
        private int autoRecordGeneration;

        Controls(Activity activity) { activityRef = new WeakReference<Activity>(activity); }

        void attach() {
            final Activity activity = activityRef.get();
            if (activity == null) return;

            reverseSurface = new SurfaceView(activity);
            reverseSurface.setBackgroundColor(Color.BLACK);
            reverseSurface.setZOrderMediaOverlay(true);
            reverseSurface.setVisibility(View.GONE);
            reverseSurface.getHolder().addCallback(this);
            activity.addContentView(reverseSurface, reverseLayout(activity, MODE_REVERSE));

            int cameraLayoutId = activity.getResources().getIdentifier(
                    "camera_layout", "id", activity.getPackageName());
            factoryCameraLayout = cameraLayoutId == 0 ? null : activity.findViewById(cameraLayoutId);

            controls = new LinearLayout(activity);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            controls.setPadding(dp(activity, 6), dp(activity, 5), dp(activity, 6), dp(activity, 5));
            controls.setBackground(round(Color.argb(205, 4, 14, 26), dp(activity, 15)));
            reverseButton = addButton(activity, controls, "REVERSE", new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (mode == MODE_REVERSE) hideReverse();
                    else showReverse(MODE_REVERSE);
                }
            });
            splitButton = addButton(activity, controls, "SPLIT", new View.OnClickListener() {
                @Override public void onClick(View view) { cycleSplit(); }
            });
            addButton(activity, controls, "LINK", new View.OnClickListener() {
                @Override public void onClick(View view) { openEnhancedMenu(); }
            });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 56), Gravity.TOP | Gravity.END);
            params.topMargin = dp(activity, 68);
            params.rightMargin = dp(activity, 14);
            activity.addContentView(controls, params);
            controls.bringToFront();
            installOwnerDockGate(activity);
            scheduleFactoryAutoRecord();
            if (activity.getSharedPreferences("settings", Activity.MODE_PRIVATE)
                    .getBoolean("stock_overlay_split_default", false)) {
                showReverse(MODE_SPLIT_TOP_BOTTOM);
            }
        }

        private Button addButton(Activity activity, LinearLayout row, String label,
                                 View.OnClickListener listener) {
            Button button = new Button(activity);
            button.setText(label);
            button.setTextColor(Color.WHITE);
            button.setTextSize(11);
            button.setAllCaps(false);
            button.setPadding(dp(activity, 4), 0, dp(activity, 4), 0);
            int factoryButton = activity.getResources().getIdentifier(
                    "nav_rec_selector", "drawable", activity.getPackageName());
            if (factoryButton != 0) button.setBackgroundResource(factoryButton);
            else button.setBackground(round(Color.argb(220, 19, 49, 82), dp(activity, 11)));
            button.setOnClickListener(listener);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dp(activity, label.length() > 6 ? 82 : 70), dp(activity, 44));
            params.leftMargin = dp(activity, 4);
            row.addView(button, params);
            return button;
        }

        private void cycleSplit() {
            if (mode == MODE_SPLIT_TOP_BOTTOM) showReverse(MODE_SPLIT_LEFT_RIGHT);
            else if (mode == MODE_SPLIT_LEFT_RIGHT) hideReverse();
            else showReverse(MODE_SPLIT_TOP_BOTTOM);
        }

        private void showReverse(int requestedMode) {
            Activity activity = activityRef.get();
            if (activity == null || reverseSurface == null) return;
            mode = requestedMode;
            updateControlState();
            attachGeneration++;
            resizeFactoryCamera(activity, requestedMode);
            reverseSurface.setLayoutParams(reverseLayout(activity, requestedMode));
            reverseSurface.setVisibility(View.VISIBLE);
            reverseSurface.bringToFront();
            if (controls != null) controls.bringToFront();
            if (ownerDockGate != null && ownerDockGate.getVisibility() == View.VISIBLE) {
                ownerDockGate.bringToFront();
            }
            if (ownerPinOverlay != null) ownerPinOverlay.bringToFront();
            ensureEnhancedService(activity);
            attachReverseWhenReady(attachGeneration, 0);
        }

        private void hideReverse() {
            mode = MODE_FRONT;
            updateControlState();
            attachGeneration++;
            Activity activity = activityRef.get();
            if (activity != null) resizeFactoryCamera(activity, MODE_FRONT);
            if (reverseSurface != null) {
                CameraStreamService service = CameraStreamService.getRunningInstance();
                if (service != null) service.detachRadioPreview(reverseSurface.getHolder(), 1);
                reverseSurface.setVisibility(View.GONE);
            }
        }

        private void updateControlState() {
            int selected = Color.rgb(72, 150, 255);
            if (reverseButton != null) reverseButton.setTextColor(
                    mode == MODE_REVERSE ? selected : Color.WHITE);
            if (splitButton != null) splitButton.setTextColor(
                    mode == MODE_SPLIT_TOP_BOTTOM || mode == MODE_SPLIT_LEFT_RIGHT ?
                            selected : Color.WHITE);
        }

        private void ensureEnhancedService(Activity activity) {
            try { activity.startForegroundService(new Intent(activity, CameraStreamService.class)); }
            catch (RuntimeException ignored) { }
        }

        /**
         * The factory activity checks lastSignal before onCreate assigns it, so its
         * own auto-record branch can be skipped every time the screen opens. Retry
         * the same native conditions after the camera and USB controller settle.
         */
        private void scheduleFactoryAutoRecord() {
            final int generation = ++autoRecordGeneration;
            main.postDelayed(new Runnable() {
                @Override public void run() { tryFactoryAutoRecord(generation, 0); }
            }, 650L);
        }

        private void tryFactoryAutoRecord(final int generation, final int attempt) {
            final Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || generation != autoRecordGeneration) return;
            // Never issue a camera or encoder command while ATOTO is entering
            // its MCU-managed parking state.
            if (new AtotoParkingBridge().readAccStatus() != 1) return;
            boolean retry = true;
            try {
                Class<?> helperClass = Class.forName("com.hcn.dvr.utils.DataHelper");
                Object helper = helperClass.getMethod("getInstance", android.content.Context.class)
                        .invoke(null, activity);
                boolean automatic = (Boolean) helperClass.getMethod("getRecordAutoSave").invoke(helper);
                if (!automatic) return;

                java.lang.reflect.Field controllerField = helperClass.getDeclaredField("cameraController");
                controllerField.setAccessible(true);
                Object controller = controllerField.get(helper);
                boolean ready = (Boolean) helperClass.getMethod("isReadyForRecord").invoke(helper);
                Class<?> utilsClass = Class.forName("com.hcn.dvr.utils.Utils");
                boolean signal = utilsClass.getField("CAMERA_SIGNAL").getBoolean(null);
                if (controller != null) {
                    boolean encoding = (Boolean) controller.getClass().getMethod("isEncoding").invoke(controller);
                    if (encoding) return;
                    if (ready && signal) {
                        String base = (String) helperClass.getMethod("getRecordSavePath").invoke(helper);
                        controller.getClass().getMethod("startEncoding", String.class)
                                .invoke(controller, base + "daily/");
                        activity.sendBroadcast(new Intent("action.refresh_watermark"));
                        return;
                    }
                }
            } catch (Throwable ignored) { }
            if (retry && attempt < 24) {
                main.postDelayed(new Runnable() {
                    @Override public void run() { tryFactoryAutoRecord(generation, attempt + 1); }
                }, 500L);
            }
        }

        private void attachReverseWhenReady(final int generation, final int attempt) {
            if (generation != attachGeneration || mode == MODE_FRONT || reverseSurface == null) return;
            CameraStreamService service = CameraStreamService.getRunningInstance();
            if (service != null && reverseSurface.getHolder().getSurface().isValid()) {
                service.attachRadioPreview(reverseSurface.getHolder(), 1);
                return;
            }
            if (attempt < 40) {
                main.postDelayed(new Runnable() {
                    @Override public void run() { attachReverseWhenReady(generation, attempt + 1); }
                }, 100L);
            }
        }

        private void openEnhancedMenu() {
            Activity activity = activityRef.get();
            if (activity == null) return;
            try {
                activity.startActivity(new Intent(activity, MainActivity.class)
                        .putExtra(StockDvrSettingsBridge.EXTRA_PAGE,
                                StockDvrSettingsBridge.PAGE_MENU));
            } catch (RuntimeException error) {
                Toast.makeText(activity, "Enhanced DVR menu could not open.", Toast.LENGTH_SHORT).show();
            }
        }

        private void installOwnerDockGate(final Activity activity) {
            int dockId = activity.getResources().getIdentifier(
                    "dockView", "id", activity.getPackageName());
            if (dockId == 0) dockId = activity.getResources().getIdentifier(
                    "dock", "id", activity.getPackageName());
            final View dock = dockId == 0 ? null : activity.findViewById(dockId);
            if (dock == null) return;
            dock.post(new Runnable() {
                @Override public void run() {
                    if (ownerDockGate != null || activity.isFinishing()) return;
                    FrameLayout gate = new FrameLayout(activity);
                    gate.setClickable(false);
                    gate.setFocusable(false);

                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    TextView record = dockBlocker(activity, "🔒");
                    View camera = availableControl(activity);
                    TextView media = dockBlocker(activity,
                            "🔒  OWNER LOCKED  •  TAP TO UNLOCK");
                    View emergency = availableControl(activity);
                    TextView right = dockBlocker(activity, "🔒");
                    row.addView(record, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                    row.addView(camera, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                    row.addView(media, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 2f));
                    row.addView(emergency, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                    row.addView(right, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 2f));
                    gate.addView(row, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));

                    int height = dock.getHeight() > 0 ? dock.getHeight() : dp(activity, 133);
                    activity.addContentView(gate, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM));
                    ownerDockGate = gate;
                    refreshOwnerLock();
                }
            });
        }

        private TextView dockBlocker(final Activity activity, String label) {
            TextView blocker = new TextView(activity);
            blocker.setText(label);
            blocker.setTextColor(Color.WHITE);
            blocker.setTextSize(13);
            blocker.setGravity(Gravity.CENTER);
            blocker.setBackgroundColor(Color.argb(232, 8, 21, 36));
            blocker.setClickable(true);
            blocker.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showOwnerPin(activity); }
            });
            return blocker;
        }

        private View availableControl(Activity activity) {
            View available = new View(activity);
            available.setClickable(false);
            return available;
        }

        void refreshOwnerLock() {
            Activity activity = activityRef.get();
            if (activity == null || ownerDockGate == null) return;
            main.removeCallbacks(ownerSessionTimeout);
            long remaining = StockDvrAccessGuard.sessionRemainingMs(activity);
            boolean locked = StockDvrAccessGuard.isEnabled(activity) && remaining <= 0L;
            ownerDockGate.setVisibility(locked ? View.VISIBLE : View.GONE);
            if (locked) ownerDockGate.bringToFront();
            if (controls != null) controls.bringToFront();
            if (remaining > 0L) main.postDelayed(ownerSessionTimeout, remaining + 100L);
        }

        void lockOwnerControls() {
            closeOwnerPin();
            refreshOwnerLock();
        }

        private final Runnable ownerSessionTimeout = new Runnable() {
            @Override public void run() { refreshOwnerLock(); }
        };

        private void showOwnerPin(final Activity activity) {
            if (ownerPinOverlay != null) return;
            final FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(Color.argb(232, 2, 10, 19));
            overlay.setClickable(true);
            overlay.setFocusable(true);

            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setGravity(Gravity.CENTER_HORIZONTAL);
            panel.setPadding(dp(activity, 28), dp(activity, 18), dp(activity, 28), dp(activity, 18));
            panel.setBackground(round(Color.rgb(16, 36, 59), dp(activity, 20)));
            TextView title = pinText(activity, "Unlock DVR controls", 25, true);
            title.setGravity(Gravity.CENTER);
            panel.addView(title, wrap());
            TextView detail = pinText(activity,
                    "Live camera viewing and emergency lock stay available while owner controls are locked.",
                    14, false);
            detail.setGravity(Gravity.CENTER);
            detail.setPadding(0, dp(activity, 5), 0, dp(activity, 10));
            panel.addView(detail, wrap());
            final TextView display = pinText(activity, "Enter PIN  •  4–12 digits", 17, true);
            display.setGravity(Gravity.CENTER);
            display.setTextColor(Color.rgb(166, 189, 214));
            panel.addView(display, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 42)));
            final TextView message = pinText(activity, "", 14, true);
            message.setGravity(Gravity.CENTER);
            message.setTextColor(Color.rgb(248, 113, 113));
            panel.addView(message, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 26)));
            final StringBuilder entered = new StringBuilder();
            final Runnable refresh = new Runnable() {
                @Override public void run() {
                    if (entered.length() == 0) {
                        display.setText("Enter PIN  •  4–12 digits");
                        return;
                    }
                    StringBuilder dots = new StringBuilder();
                    for (int i = 0; i < entered.length(); i++) {
                        if (i > 0) dots.append(' ');
                        dots.append('●');
                    }
                    display.setText(dots.toString());
                }
            };
            for (int first = 1; first <= 7; first += 3) {
                LinearLayout row = pinRow(activity);
                for (int number = first; number < first + 3; number++) {
                    final String digit = Integer.toString(number);
                    row.addView(pinKey(activity, digit, false, new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            if (entered.length() < 12) entered.append(digit);
                            message.setText("");
                            refresh.run();
                        }
                    }), pinCell(activity));
                }
                panel.addView(row, pinRowParams(activity));
            }
            LinearLayout last = pinRow(activity);
            last.addView(pinKey(activity, "⌫", false, new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (entered.length() > 0) entered.deleteCharAt(entered.length() - 1);
                    refresh.run();
                }
            }), pinCell(activity));
            last.addView(pinKey(activity, "0", false, new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (entered.length() < 12) entered.append('0');
                    refresh.run();
                }
            }), pinCell(activity));
            last.addView(pinKey(activity, "✓", true, new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (StockDvrAccessGuard.verifyPin(activity, entered.toString())) {
                        StockDvrAccessGuard.unlockSession(activity);
                        closeOwnerPin();
                        refreshOwnerLock();
                    } else {
                        message.setText(entered.length() < 4 ?
                                "Use at least 4 digits." : "Incorrect owner PIN.");
                        entered.setLength(0);
                        refresh.run();
                    }
                }
            }), pinCell(activity));
            panel.addView(last, pinRowParams(activity));
            TextView cancel = pinText(activity, "Keep controls locked", 15, true);
            cancel.setGravity(Gravity.CENTER);
            cancel.setBackground(round(Color.rgb(25, 49, 75), dp(activity, 12)));
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { closeOwnerPin(); }
            });
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44));
            cancelParams.topMargin = dp(activity, 7);
            panel.addView(cancel, cancelParams);
            overlay.addView(panel, new FrameLayout.LayoutParams(
                    Math.min(dp(activity, 470), activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 36)),
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            activity.addContentView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ownerPinOverlay = overlay;
            overlay.bringToFront();
        }

        private void closeOwnerPin() {
            if (ownerPinOverlay != null && ownerPinOverlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) ownerPinOverlay.getParent()).removeView(ownerPinOverlay);
            }
            ownerPinOverlay = null;
        }

        private TextView pinText(Activity activity, String value, int size, boolean bold) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(size);
            view.setTextColor(Color.WHITE);
            if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
            return view;
        }

        private LinearLayout pinRow(Activity activity) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            return row;
        }

        private TextView pinKey(Activity activity, String value, boolean primary,
                                View.OnClickListener listener) {
            TextView key = pinText(activity, value, 21, true);
            key.setGravity(Gravity.CENTER);
            key.setBackground(round(primary ? Color.rgb(43, 125, 255) :
                    Color.rgb(25, 49, 75), dp(activity, 100)));
            key.setOnClickListener(listener);
            return key;
        }

        private LinearLayout.LayoutParams pinCell(Activity activity) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dp(activity, 56), dp(activity, 56));
            params.leftMargin = dp(activity, 12);
            params.rightMargin = dp(activity, 12);
            return params;
        }

        private LinearLayout.LayoutParams pinRowParams(Activity activity) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 62));
            params.gravity = Gravity.CENTER_HORIZONTAL;
            return params;
        }

        private LinearLayout.LayoutParams wrap() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private FrameLayout.LayoutParams reverseLayout(Activity activity, int layoutMode) {
            int width = ViewGroup.LayoutParams.MATCH_PARENT;
            int height = ViewGroup.LayoutParams.MATCH_PARENT;
            int gravity = Gravity.CENTER;
            if (layoutMode == MODE_SPLIT_TOP_BOTTOM) {
                height = activity.getResources().getDisplayMetrics().heightPixels / 2;
                gravity = Gravity.BOTTOM;
            } else if (layoutMode == MODE_SPLIT_LEFT_RIGHT) {
                width = activity.getResources().getDisplayMetrics().widthPixels / 2;
                gravity = Gravity.END;
            }
            return new FrameLayout.LayoutParams(width, height, gravity);
        }

        private void resizeFactoryCamera(Activity activity, int layoutMode) {
            if (factoryCameraLayout == null) return;
            ViewGroup.LayoutParams params = factoryCameraLayout.getLayoutParams();
            if (params == null) return;
            params.width = layoutMode == MODE_SPLIT_LEFT_RIGHT ?
                    activity.getResources().getDisplayMetrics().widthPixels / 2 :
                    ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = layoutMode == MODE_SPLIT_TOP_BOTTOM ?
                    activity.getResources().getDisplayMetrics().heightPixels / 2 :
                    ViewGroup.LayoutParams.MATCH_PARENT;
            if (params instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) params).gravity = layoutMode == MODE_SPLIT_LEFT_RIGHT ?
                        Gravity.START : layoutMode == MODE_SPLIT_TOP_BOTTOM ? Gravity.TOP : Gravity.CENTER;
            }
            factoryCameraLayout.setLayoutParams(params);
            factoryCameraLayout.requestLayout();
        }

        void detach() {
            attachGeneration++;
            autoRecordGeneration++;
            main.removeCallbacksAndMessages(null);
            if (reverseSurface != null) {
                CameraStreamService service = CameraStreamService.getRunningInstance();
                if (service != null) service.detachRadioPreview(reverseSurface.getHolder(), 1);
                reverseSurface.getHolder().removeCallback(this);
            }
            Activity activity = activityRef.get();
            if (activity != null) resizeFactoryCamera(activity, MODE_FRONT);
            closeOwnerPin();
            if (ownerDockGate != null && ownerDockGate.getParent() instanceof ViewGroup) {
                ((ViewGroup) ownerDockGate.getParent()).removeView(ownerDockGate);
            }
            ownerDockGate = null;
        }

        @Override public void surfaceCreated(SurfaceHolder holder) {
            if (mode != MODE_FRONT) attachReverseWhenReady(attachGeneration, 0);
        }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mode != MODE_FRONT) attachReverseWhenReady(attachGeneration, 0);
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            CameraStreamService service = CameraStreamService.getRunningInstance();
            if (service != null) service.detachRadioPreview(holder, 1);
        }
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
