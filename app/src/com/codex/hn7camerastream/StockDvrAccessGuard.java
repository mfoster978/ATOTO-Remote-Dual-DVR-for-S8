package com.codex.hn7camerastream;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.WeakHashMap;

/**
 * UI-only owner gate for ATOTO's native recordings, photos, and settings activity.
 * It deliberately does not start, stop, or reconfigure any DVR/background service.
 */
public final class StockDvrAccessGuard {
    private static final String PREFS = "settings";
    private static final String SALT_KEY = "owner_pin_salt";
    private static final String HASH_KEY = "owner_pin_hash";
    private static final String UNLOCK_UNTIL_KEY = "owner_unlock_until";
    public static final long SESSION_DURATION_MS = 10L * 60L * 1000L;
    private static final int BG = Color.rgb(7, 17, 31);
    private static final int PANEL = Color.rgb(16, 36, 59);
    private static final int KEY = Color.rgb(25, 49, 75);
    private static final int PRIMARY = Color.rgb(43, 125, 255);
    private static final int TEXT = Color.rgb(240, 247, 255);
    private static final int MUTED = Color.rgb(166, 189, 214);
    private static final int ERROR = Color.rgb(248, 113, 113);
    private static final WeakHashMap<Activity, View> ACTIVE = new WeakHashMap<Activity, View>();

    private StockDvrAccessGuard() { }

    /** Called immediately after FolderPlaybackActivity installs its factory layout. */
    public static void protect(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (!isEnabled(activity)) return;
        long remaining = sessionRemainingMs(activity);
        if (remaining > 0L) {
            activity.getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() { protect(activity); }
            }, remaining + 100L);
            return;
        }
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(activity)) return;
        }

        final FrameLayout overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(BG);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(activity, 28), dp(activity, 20), dp(activity, 28), dp(activity, 20));
        panel.setBackground(round(PANEL, dp(activity, 20), Color.rgb(48, 79, 108)));

        TextView title = label(activity, "Owner controls locked", 27, TEXT, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, wrap());

        TextView detail = label(activity,
                "Enter the numeric PIN to open ATOTO recordings, photos, or settings. " +
                        "Live DVR, recording, and remote access remain active.",
                15, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(activity, 7), 0, dp(activity, 12));
        panel.addView(detail, wrap());

        final TextView display = label(activity, "Enter PIN  •  4–12 digits", 17, MUTED, true);
        display.setGravity(Gravity.CENTER);
        display.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        display.setBackground(round(Color.rgb(10, 24, 42), dp(activity, 12), Color.rgb(48, 79, 108)));
        panel.addView(display, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)));

        final TextView message = label(activity, "", 14, ERROR, true);
        message.setGravity(Gravity.CENTER);
        panel.addView(message, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 28)));

        final StringBuilder entered = new StringBuilder();
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                if (entered.length() == 0) {
                    display.setText("Enter PIN  •  4–12 digits");
                    display.setTextColor(MUTED);
                    return;
                }
                StringBuilder dots = new StringBuilder();
                for (int i = 0; i < entered.length(); i++) {
                    if (i > 0) dots.append(' ');
                    dots.append('●');
                }
                display.setText(dots.toString());
                display.setTextColor(TEXT);
            }
        };

        for (int start = 1; start <= 7; start += 3) {
            LinearLayout row = keyRow(activity);
            for (int number = start; number < start + 3; number++) {
                final String digit = Integer.toString(number);
                row.addView(key(activity, digit, false, new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        if (entered.length() < 12) entered.append(digit);
                        message.setText("");
                        refresh.run();
                    }
                }), keyCell(activity));
            }
            panel.addView(row, rowParams(activity));
        }

        LinearLayout last = keyRow(activity);
        last.addView(key(activity, "⌫", false, new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (entered.length() > 0) entered.deleteCharAt(entered.length() - 1);
                message.setText("");
                refresh.run();
            }
        }), keyCell(activity));
        last.addView(key(activity, "0", false, new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (entered.length() < 12) entered.append('0');
                message.setText("");
                refresh.run();
            }
        }), keyCell(activity));
        last.addView(key(activity, "✓", true, new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (!isEnabled(activity) || verifyPin(activity, entered.toString())) {
                    unlockSession(activity);
                    unlock(activity, overlay);
                    activity.getWindow().getDecorView().postDelayed(new Runnable() {
                        @Override public void run() { protect(activity); }
                    }, SESSION_DURATION_MS + 100L);
                    return;
                }
                message.setText(entered.length() < 4 ? "Use at least 4 digits." : "Incorrect owner PIN.");
                entered.setLength(0);
                refresh.run();
            }
        }), keyCell(activity));
        panel.addView(last, rowParams(activity));

        TextView back = label(activity, "Back to live DVR", 16, TEXT, true);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(KEY, dp(activity, 12), Color.rgb(48, 79, 108)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { activity.finish(); }
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46));
        backParams.topMargin = dp(activity, 8);
        panel.addView(back, backParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                Math.min(dp(activity, 470), activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 36)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(panel, panelParams);
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        overlay.requestFocus();
        synchronized (ACTIVE) { ACTIVE.put(activity, overlay); }
    }

    private static void unlock(Activity activity, View overlay) {
        ViewParentCompat.remove(overlay);
        synchronized (ACTIVE) { ACTIVE.remove(activity); }
    }

    public static boolean isEnabled(Context context) {
        return context != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(HASH_KEY, "").length() > 0;
    }

    /** One successful PIN entry opens every protected DVR screen for ten minutes. */
    public static void unlockSession(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(UNLOCK_UNTIL_KEY, System.currentTimeMillis() + SESSION_DURATION_MS)
                .commit();
    }

    public static void clearSession(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(UNLOCK_UNTIL_KEY).commit();
    }

    public static boolean isSessionUnlocked(Context context) {
        return sessionRemainingMs(context) > 0L;
    }

    public static long sessionRemainingMs(Context context) {
        if (context == null || !isEnabled(context)) return 0L;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long until = preferences.getLong(UNLOCK_UNTIL_KEY, 0L);
        long remaining = until - now;
        // Reject stale sessions and implausible future values after a clock change.
        if (remaining <= 0L || remaining > SESSION_DURATION_MS + 5_000L) {
            if (until != 0L) preferences.edit().remove(UNLOCK_UNTIL_KEY).apply();
            return 0L;
        }
        return remaining;
    }

    public static boolean verifyPin(Activity activity, String pin) {
        if (activity == null) return false;
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        if (!pin.matches("[0-9]{4,12}")) return false;
        String salt = preferences.getString(SALT_KEY, "");
        String expected = preferences.getString(HASH_KEY, "");
        if (salt.length() == 0 || expected.length() == 0) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String actual = Base64.encodeToString(digest.digest((salt + ":" + pin)
                    .getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static LinearLayout keyRow(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private static TextView key(Activity activity, String value, boolean primary,
                                View.OnClickListener listener) {
        TextView view = label(activity, value, 22, TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(primary ? PRIMARY : KEY, dp(activity, 100),
                primary ? Color.rgb(105, 165, 255) : Color.rgb(48, 79, 108)));
        view.setOnClickListener(listener);
        return view;
    }

    private static LinearLayout.LayoutParams keyCell(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 58));
        params.leftMargin = dp(activity, 12);
        params.rightMargin = dp(activity, 12);
        return params;
    }

    private static LinearLayout.LayoutParams rowParams(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 64));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }

    private static TextView label(Activity activity, String text, int sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(1, stroke);
        return drawable;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** Kept tiny to avoid depending on AndroidX from the secondary dex. */
    private static final class ViewParentCompat {
        static void remove(View view) {
            if (view != null && view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }
    }
}
