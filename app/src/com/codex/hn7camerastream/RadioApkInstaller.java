package com.codex.hn7camerastream;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Stages radio APKs without URI grants, which Android denies to the system UID. */
public final class RadioApkInstaller extends BroadcastReceiver {
    private static final String TAG = "RadioApkInstaller";
    private static final String ACTION_RESULT = "com.codex.hn7camerastream.APK_INSTALL_RESULT";

    public static void install(Context context, final File apk, final String packageName) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                int sessionId = -1;
                try {
                    if (!apk.isFile() || apk.length() == 0) throw new IOException("The APK is missing.");
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(packageName);
                    params.setSize(apk.length());
                    if (Build.VERSION.SDK_INT >= 31) {
                        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                    }
                    sessionId = installer.createSession(params);
                    try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                        try (FileInputStream input = new FileInputStream(apk);
                             OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                            byte[] buffer = new byte[131072];
                            int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                            session.fsync(output);
                        }
                        Intent result = new Intent(app, RadioApkInstaller.class)
                                .setAction(ACTION_RESULT)
                                .setData(Uri.parse("cameralink-install://session/" + sessionId));
                        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                        PendingIntent status = PendingIntent.getBroadcast(app, sessionId, result, flags);
                        session.commit(status.getIntentSender());
                    }
                    Log.i(TAG, "APK staged for Android installer: " + packageName);
                } catch (final Exception error) {
                    if (sessionId >= 0) {
                        try { installer.abandonSession(sessionId); } catch (RuntimeException ignored) { }
                    }
                    Log.w(TAG, "Unable to stage APK", error);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(app, "Could not prepare installation: " + error.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "RadioApkInstaller").start();
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_RESULT.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                try { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException error) {
                    Log.w(TAG, "Android installation confirmation could not open", error);
                    Toast.makeText(context, "Return to DVR and retry installation.", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        Log.i(TAG, "Android installer result: " + status + " package=" + packageName);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Installation complete.", Toast.LENGTH_SHORT).show();
        } else {
            String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "Installation did not complete: " +
                    (detail == null ? "Android status " + status : detail), Toast.LENGTH_LONG).show();
        }
    }
}
