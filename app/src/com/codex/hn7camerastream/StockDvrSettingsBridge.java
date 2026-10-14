package com.codex.hn7camerastream;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.WeakHashMap;

/** Appends Camera Link controls to ATOTO's existing settings fragment. */
public final class StockDvrSettingsBridge {
    public static final String EXTRA_PAGE = "camera_link_page";
    public static final String PAGE_MENU = "radio_menu";
    public static final String PAGE_NETWORK = "radio_network";
    public static final String PAGE_RECORDING = "radio_recording";
    public static final String PAGE_SECURITY = "radio_security";
    public static final String PAGE_UPDATE = "app_update";

    private static final String PREFS = "settings";
    private static final WeakHashMap<View, Boolean> ATTACHED = new WeakHashMap<View, Boolean>();

    private StockDvrSettingsBridge() { }

    /** Called from the patched SettingsFragment after the factory view is fully initialized. */
    public static void attach(Object fragment, View root) {
        StockDvrParkingLock.apply(fragment);
        if (!(root instanceof ScrollView)) return;
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(root)) return;
            ATTACHED.put(root, Boolean.TRUE);
        }

        ScrollView scroll = (ScrollView) root;
        if (scroll.getChildCount() != 1) return;
        View factorySettings = scroll.getChildAt(0);
        scroll.removeView(factorySettings);

        Context context = root.getContext();
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, 0, dp(context, 40));
        page.addView(factorySettings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout cameraLink = buildCameraLinkSection(context);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionParams.setMargins(0, dp(context, 12), dp(context, 20), 0);
        page.addView(cameraLink, sectionParams);
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static LinearLayout buildCameraLinkSection(final Context context) {
        final SharedPreferences settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);

        TextView title = label(context, "Camera Link & remote access", 24, textColor(context));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, 0);
        section.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 60)));

        addSegmentedSetting(section, context, "Reverse camera recording",
                "Records the independent backup-camera input to the DVR USB drive",
                new String[]{"Off", "On"},
                settings.getBoolean("record_camera_1", false) ? 1 : 0,
                new SegmentListener() {
                    @Override public void onSelected(int index) {
                        boolean enabled = index == 1;
                        settings.edit().putBoolean("record_camera_1", enabled).apply();
                        Intent service = new Intent(context, CameraStreamService.class)
                                .setAction(enabled ? CameraStreamService.ACTION_RECORD_START :
                                        CameraStreamService.ACTION_RECORD_STOP);
                        try {
                            if (enabled) context.startForegroundService(service);
                            else context.startService(service);
                        } catch (RuntimeException error) {
                            Toast.makeText(context, "Reverse recorder could not be changed.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        addSegmentedSetting(section, context, "Default live layout",
                "SPLIT cycles through top/bottom, left/right, and off",
                new String[]{"Single", "Split"},
                settings.getBoolean("stock_overlay_split_default", false) ? 1 : 0,
                new SegmentListener() {
                    @Override public void onSelected(int index) {
                        settings.edit().putBoolean("stock_overlay_split_default", index == 1).apply();
                    }
                });

        addAction(section, context, "Phone connection & setup QR",
                "Tailscale status, connection PIN, website credentials, and QR pairing",
                PAGE_NETWORK);
        addAction(section, context, "Reverse camera & Camera Link",
                "Reverse recording, remote-stream privacy, USB storage, and added DVR controls",
                PAGE_RECORDING);
        addAction(section, context, "Vehicle name & owner lock",
                "Protect settings while leaving the local live camera visible",
                PAGE_SECURITY);
        addAction(section, context, "Check for updates",
                "Download and verify the newest enhanced stock DVR from GitHub",
                PAGE_UPDATE);
        return section;
    }

    private static void addAction(LinearLayout parent, final Context context, String title,
                                  String subtitle, final String page) {
        LinearLayout row = settingContainer(context);
        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(context, 24), dp(context, 12), dp(context, 16), dp(context, 12));
        TextView heading = label(context, title, 24, textColor(context));
        TextView detail = label(context, subtitle, 15, secondaryTextColor(context));
        copy.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        copy.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(copy, new LinearLayout.LayoutParams(0, dp(context, 86), 1f));
        TextView arrow = label(context, "›", 38, textColor(context));
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(context, 72), dp(context, 86)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openEnhancedPage(context, page); }
        });
        addRow(parent, context, row);
    }

    private static void addSegmentedSetting(LinearLayout parent, Context context, String title,
                                            String subtitle, String[] options, int selected,
                                            SegmentListener listener) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(context, title, 24, textColor(context));
        heading.setPadding(0, dp(context, 12), 0, 0);
        block.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52)));
        TextView detail = label(context, subtitle, 15, secondaryTextColor(context));
        detail.setPadding(0, 0, 0, dp(context, 8));
        block.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout choices = settingContainer(context);
        for (int index = 0; index < options.length; index++) {
            final int choice = index;
            TextView option = label(context, options[index], 24, textColor(context));
            option.setGravity(Gravity.CENTER);
            if (index == selected) option.setBackground(selectedBackground(context));
            option.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    LinearLayout holder = (LinearLayout) view.getParent();
                    for (int i = 0; i < holder.getChildCount(); i++) {
                        holder.getChildAt(i).setBackground(i == choice ?
                                selectedBackground(view.getContext()) : null);
                    }
                    listener.onSelected(choice);
                }
            });
            choices.addView(option, new LinearLayout.LayoutParams(0, dp(context, 58), 1f));
        }
        block.addView(choices, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 58)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 12);
        parent.addView(block, params);
    }

    private static void addRow(LinearLayout parent, Context context, View row) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 12);
        parent.addView(row, params);
    }

    private static LinearLayout settingContainer(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int background = context.getResources().getIdentifier(
                "item_bg", "drawable", context.getPackageName());
        if (background != 0) row.setBackgroundResource(background);
        else row.setBackground(round(Color.argb(52, 57, 57, 57), dp(context, 12)));
        return row;
    }

    private static GradientDrawable selectedBackground(Context context) {
        int color = Color.rgb(54, 118, 214);
        return round(color, dp(context, 10));
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static TextView label(Context context, String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private static int textColor(Context context) {
        int id = context.getResources().getIdentifier("text_color", "color", context.getPackageName());
        try { return id == 0 ? Color.WHITE : context.getColor(id); }
        catch (RuntimeException ignored) { return Color.WHITE; }
    }

    private static int secondaryTextColor(Context context) {
        return Color.argb(190, 220, 226, 232);
    }

    private static void openEnhancedPage(Context context, String page) {
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); }
        catch (RuntimeException error) {
            Toast.makeText(context, "Camera Link settings could not open.", Toast.LENGTH_SHORT).show();
        }
    }

    private interface SegmentListener { void onSelected(int index); }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
