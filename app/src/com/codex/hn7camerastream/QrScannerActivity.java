package com.codex.hn7camerastream;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public class QrScannerActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final String EXTRA_SETUP_URI = "setup_uri";

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final MultiFormatReader reader = new MultiFormatReader();
    private Camera camera;
    private SurfaceView preview;
    private TextView status;
    private long lastDecodeAttempt;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setText("Point the phone camera at the setup QR on the radio");
        status.setTextColor(Color.WHITE);
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(18), dp(14), dp(18), dp(14));
        status.setBackgroundColor(Color.argb(190, 4, 14, 26));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(78), Gravity.TOP);
        root.addView(status, statusParams);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setTextColor(Color.WHITE);
        cancel.setBackgroundColor(Color.rgb(28, 55, 82));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(dp(150), dp(58), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cancelParams.bottomMargin = dp(24);
        root.addView(cancel, cancelParams);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setResult(RESULT_CANCELED);
            finish();
        } else if (preview.getHolder().getSurface().isValid()) {
            openCamera(preview.getHolder());
        }
    }

    @Override protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { openCamera(holder); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { releaseCamera(); }

    private void openCamera(SurfaceHolder holder) {
        if (camera != null || isFinishing()) return;
        Camera opened = null;
        try {
            int cameraId = findBackCamera();
            opened = Camera.open(cameraId);
            Camera.Parameters parameters = opened.getParameters();
            Camera.Size selected = choosePreviewSize(parameters);
            parameters.setPreviewSize(selected.width, selected.height);
            if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            opened.setParameters(parameters);
            opened.setDisplayOrientation(displayOrientation(cameraId));
            opened.setPreviewDisplay(holder);
            opened.setPreviewCallback(this);
            opened.startPreview();
            camera = opened;
        } catch (Throwable error) {
            if (opened != null) try { opened.release(); } catch (Exception ignored) { }
            status.setText("Unable to open the phone camera");
        }
    }

    private int findBackCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0;
    }

    private Camera.Size choosePreviewSize(Camera.Parameters parameters) {
        Camera.Size best = parameters.getPreviewSize();
        for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
            if (size.width <= 1280 && size.height <= 720 && size.width * size.height > best.width * best.height) {
                best = size;
            }
        }
        return best;
    }

    private int displayOrientation(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 :
                rotation == Surface.ROTATION_270 ? 270 : 0;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - ((info.orientation + degrees) % 360)) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    @Override public void onPreviewFrame(byte[] data, Camera source) {
        long now = System.currentTimeMillis();
        if (now - lastDecodeAttempt < 350 || !decoding.compareAndSet(false, true)) return;
        lastDecodeAttempt = now;
        Camera.Size size = source.getParameters().getPreviewSize();
        final byte[] frame = data.clone();
        final int width = size.width;
        final int height = size.height;
        decoder.execute(new Runnable() {
            @Override public void run() {
                try {
                    PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                            frame, width, height, 0, 0, width, height, false);
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                    Result result = reader.decodeWithState(bitmap);
                    final String text = result == null ? "" : result.getText();
                    if (text != null && text.startsWith("atotocam://setup?")) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Intent resultIntent = new Intent();
                                resultIntent.putExtra(EXTRA_SETUP_URI, text);
                                setResult(RESULT_OK, resultIntent);
                                finish();
                            }
                        });
                    } else if (text != null && text.length() > 0) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { status.setText("That QR is not a Camera Link setup code"); }
                        });
                    }
                } catch (Throwable ignored) {
                } finally {
                    reader.reset();
                    decoding.set(false);
                }
            }
        });
    }

    private void releaseCamera() {
        Camera existing = camera;
        camera = null;
        if (existing != null) {
            try { existing.setPreviewCallback(null); } catch (Exception ignored) { }
            try { existing.stopPreview(); } catch (Exception ignored) { }
            try { existing.release(); } catch (Exception ignored) { }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
