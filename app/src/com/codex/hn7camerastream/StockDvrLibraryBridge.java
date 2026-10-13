package com.codex.hn7camerastream;

import android.content.Context;
import android.os.Environment;

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
            if (name.startsWith("r-") && name.endsWith(".mp4") &&
                    !name.endsWith("_recording.mp4")) {
                addUnique(output, known, child);
            }
        }
    }

    private static void addUnique(List<File> output, Set<String> known, File file) {
        if (file == null || !file.isFile()) return;
        try {
            String path = file.getCanonicalPath();
            if (known.add(path)) output.add(file);
        } catch (IOException ignored) { }
    }
}
