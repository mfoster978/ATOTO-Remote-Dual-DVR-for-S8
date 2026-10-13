package com.codex.hn7camerastream;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Adds completed Camera Link reverse clips to ATOTO's native DVR library. */
public final class StockDvrLibraryBridge {
    private StockDvrLibraryBridge() { }

    /** Adds an R filter beside ATOTO's native ALL/F/C controls. */
    public static void attachReverseFilter(final Activity activity) {
        if (activity == null) return;
        try {
            int groupId = activity.getResources().getIdentifier(
                    "rg_filter", "id", activity.getPackageName());
            int cameraId = activity.getResources().getIdentifier(
                    "rb_c", "id", activity.getPackageName());
            RadioGroup group = activity.findViewById(groupId);
            RadioButton camera = activity.findViewById(cameraId);
            if (group == null || camera == null || group.findViewWithTag("camera_link_reverse_filter") != null) {
                return;
            }

            RadioButton reverse = new RadioButton(activity);
            reverse.setId(View.generateViewId());
            reverse.setTag("camera_link_reverse_filter");
            reverse.setText("R");
            reverse.setTextColor(camera.getTextColors());
            reverse.setTextSize(TypedValue.COMPLEX_UNIT_PX, camera.getTextSize());
            reverse.setGravity(Gravity.CENTER);
            reverse.setButtonDrawable((Drawable) null);
            reverse.setPadding(camera.getPaddingLeft(), camera.getPaddingTop(),
                    camera.getPaddingRight(), camera.getPaddingBottom());
            Drawable background = camera.getBackground();
            if (background != null && background.getConstantState() != null) {
                reverse.setBackground(background.getConstantState().newDrawable().mutate());
            }

            RadioGroup.LayoutParams source = (RadioGroup.LayoutParams) camera.getLayoutParams();
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(source.width, source.height);
            params.leftMargin = source.leftMargin;
            params.topMargin = source.topMargin;
            params.rightMargin = source.rightMargin;
            params.bottomMargin = source.bottomMargin;
            reverse.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View ignored) {
                    applyReverseFilter(activity);
                }
            });
            group.addView(reverse, group.indexOfChild(camera) + 1, params);
        } catch (RuntimeException ignored) { }
    }

    private static void applyReverseFilter(Activity activity) {
        try {
            java.lang.reflect.Method current = activity.getClass()
                    .getDeclaredMethod("getCurrentFolderFragment");
            current.setAccessible(true);
            Object fragment = current.invoke(activity);
            if (fragment != null) {
                fragment.getClass().getMethod("fileFilter", String.class).invoke(fragment, "R");
            }
        } catch (ReflectiveOperationException ignored) { }
    }

    /** Forces a visible R badge from the actual file name, independent of cached item metadata. */
    public static void ensureReverseBadge(TextView badge, File file) {
        if (badge != null && isReverseVideo(file)) {
            badge.setText("R");
            badge.setVisibility(View.VISIBLE);
        }
    }

    public static List<File> mergeReverseVideos(Context context, List<File> factoryFiles,
                                                 String folderType) {
        if (!"daily".equals(folderType)) return factoryFiles;
        ArrayList<File> merged = new ArrayList<File>();
        Set<String> known = new HashSet<String>();
        if (factoryFiles != null) {
            for (File file : factoryFiles) addUnique(merged, known, file);
        }
        if (context == null) return merged;

        File[] movieRoots = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
        if (movieRoots != null) {
            for (File root : movieRoots) {
                if (root == null) continue;
                collectCompleted(new File(root, "ATOTO-Camera-Link"), merged, known, 0);
                File volume = volumeRoot(root);
                if (volume != null) {
                    collectCompleted(new File(volume,
                            "Android/data/com.codex.hn7camerastream/files/Movies/ATOTO-Camera-Link"),
                            merged, known, 0);
                }
            }
        }
        return merged;
    }

    private static File volumeRoot(File path) {
        String value = path.getAbsolutePath().replace('\\', '/');
        int marker = value.indexOf("/Android/data/");
        return marker > 0 ? new File(value.substring(0, marker)) : null;
    }

    private static void collectCompleted(File directory, List<File> output, Set<String> known,
                                         int depth) {
        if (directory == null || depth > 4 || !directory.canRead()) return;
        File[] children;
        try { children = directory.listFiles(); }
        catch (SecurityException denied) { return; }
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectCompleted(child, output, known, depth + 1);
                continue;
            }
            String name = child.getName().toLowerCase(Locale.US);
            if (isReverseVideo(child) && name.endsWith(".mp4") &&
                    !name.endsWith("_recording.mp4")) {
                addUnique(output, known, child);
            }
        }
    }

    private static boolean isReverseVideo(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(Locale.US);
        if (name.startsWith("r-") || name.startsWith("cl-r-")) return true;
        File parent = file.getParentFile();
        return parent != null && "reverse".equalsIgnoreCase(parent.getName());
    }

    private static void addUnique(List<File> output, Set<String> known, File file) {
        if (file == null || !file.isFile()) return;
        try {
            String path = file.getCanonicalPath();
            if (known.add(path)) output.add(file);
        } catch (IOException ignored) { }
    }
}
