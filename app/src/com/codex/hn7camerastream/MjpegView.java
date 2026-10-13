package com.codex.hn7camerastream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Base64;
import android.view.View;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MjpegView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Object frameLock = new Object();
    private volatile boolean running;
    private volatile Bitmap frame;
    private Thread worker;
    private HttpURLConnection connection;

    public MjpegView(Context context) { super(context); setBackgroundColor(Color.BLACK); }
    public MjpegView(Context context, AttributeSet attrs) { super(context, attrs); setBackgroundColor(Color.BLACK); }

    public synchronized void start(String url, String username, String password) {
        stop();
        running = true;
        final String streamUrl = url;
        final String auth = "Basic " + Base64.encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        worker = new Thread(new Runnable() {
            @Override public void run() { readStream(streamUrl, auth); }
        }, "CameraLinkMjpeg");
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (connection != null) connection.disconnect();
        connection = null;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private void readStream(String streamUrl, String auth) {
        try {
            connection = (HttpURLConnection) new URL(streamUrl).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Authorization", auth);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != 200) return;
            BufferedInputStream input = new BufferedInputStream(connection.getInputStream(), 64 * 1024);
            ByteArrayOutputStream jpeg = null;
            int previous = -1;
            int value;
            while (running && (value = input.read()) >= 0) {
                if (jpeg == null) {
                    if (previous == 0xff && value == 0xd8) {
                        jpeg = new ByteArrayOutputStream(256 * 1024);
                        jpeg.write(0xff);
                        jpeg.write(0xd8);
                    }
                } else {
                    jpeg.write(value);
                    if (previous == 0xff && value == 0xd9) {
                        byte[] bytes = jpeg.toByteArray();
                        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (decoded != null) {
                            synchronized (frameLock) {
                                Bitmap old = frame;
                                frame = decoded;
                                if (old != null && old != decoded && !old.isRecycled()) old.recycle();
                            }
                            postInvalidate();
                        }
                        jpeg = null;
                    } else if (jpeg.size() > 6 * 1024 * 1024) {
                        jpeg = null;
                    }
                }
                previous = value;
            }
            input.close();
        } catch (Throwable ignored) {
        } finally {
            if (connection != null) connection.disconnect();
            connection = null;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        synchronized (frameLock) {
            Bitmap bitmap = frame;
            if (bitmap == null || bitmap.isRecycled()) return;
            // Fit the complete camera image inside the viewer. The radio screen and
            // phone screen have different aspect ratios, so filling by cropping can
            // make a correct stream look heavily zoomed on the phone.
            float scale = Math.min((float) getWidth() / bitmap.getWidth(),
                    (float) getHeight() / bitmap.getHeight());
            int width = Math.round(bitmap.getWidth() * scale);
            int height = Math.round(bitmap.getHeight() * scale);
            int left = (getWidth() - width) / 2;
            int top = (getHeight() - height) / 2;
            canvas.drawBitmap(bitmap, null, new Rect(left, top, left + width, top + height), paint);
        }
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        synchronized (frameLock) {
            Bitmap old = frame;
            frame = null;
            if (old != null && !old.isRecycled()) old.recycle();
        }
        super.onDetachedFromWindow();
    }
}
