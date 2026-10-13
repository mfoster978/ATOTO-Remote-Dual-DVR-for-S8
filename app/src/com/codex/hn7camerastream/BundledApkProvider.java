package com.codex.hn7camerastream;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class BundledApkProvider extends ContentProvider {
    public static final String FILE_NAME = "tailscale-android-universal-1.104.1.apk";
    public static final String UPDATE_FILE_NAME = "camera-link-update.apk";

    @Override public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileForUri(uri);
        if (!file.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = fileForUri(uri);
        } catch (FileNotFoundException error) {
            return null;
        }
        String[] columns = projection == null ?
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(file.getName());
            else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
            else row.add(null);
        }
        return cursor;
    }

    private File fileForUri(Uri uri) throws FileNotFoundException {
        String name = uri == null ? null : uri.getLastPathSegment();
        if ("tailscale.apk".equals(name)) return new File(getContext().getCacheDir(), FILE_NAME);
        if ("update.apk".equals(name)) return new File(getContext().getCacheDir(), UPDATE_FILE_NAME);
        throw new FileNotFoundException();
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
