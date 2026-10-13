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
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MjpegView extends View {
    public interface ConnectionListener { void onConnectionChanged(); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Object frameLock = new Object();
    private volatile boolean running;
    private volatile long generation;
    private volatile boolean streaming;
    private ConnectionListener listener;
    private volatile Bitmap frame;
    private Thread worker;
    private HttpURLConnection connection;

    public MjpegView(Context context) { super(context); setBackgroundColor(Color.BLACK); }
    public MjpegView(Context context, AttributeSet attrs) { super(context, attrs); setBackgroundColor(Color.BLACK); }

    public synchronized void start(String url, String username, String password) {
        stop();
        running = true;
        final long session = generation;
        final String streamUrl = url;
        final String auth = "Basic " + Base64.encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        worker = new Thread(new Runnable() {
            @Override public void run() { readStreamLoop(streamUrl, auth, session); }
        }, "CameraLinkMjpeg");
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        generation++;
        streaming = false;
        if (connection != null) connection.disconnect();
        connection = null;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    public void setConnectionListener(ConnectionListener listener) { this.listener = listener; }
    public boolean isStreaming() { return streaming; }
    private boolean isCurrent(long session) { return running && generation == session; }

    private void connectionChanged(final long session, boolean connected) {
        synchronized (this) {
            if (!isCurrent(session) || streaming == connected) return;
            streaming = connected;
        }
        post(new Runnable() {
            @Override public void run() {
                if (isCurrent(session) && listener != null) listener.onConnectionChanged();
            }
        });
    }

    private void readStreamLoop(String streamUrl, String auth, long session) {
        while (isCurrent(session)) {
            readStreamOnce(streamUrl, auth, session);
            if (!isCurrent(session)) return;
            try { Thread.sleep(1_500L); }
            catch (InterruptedException ignored) { return; }
        }
    }

    private void readStreamOnce(String streamUrl, String auth, long session) {
        BufferedInputStream input = null;
        HttpURLConnection attempt = null;
        try {
            attempt = (HttpURLConnection) new URL(streamUrl).openConnection();
            synchronized (this) {
                if (!isCurrent(session)) return;
                connection = attempt;
            }
            attempt.setConnectTimeout(5_000);
            attempt.setReadTimeout(15_000);
            attempt.setRequestProperty("Authorization", auth);
            attempt.setUseCaches(false);
            if (attempt.getResponseCode() != 200) return;
            input = new BufferedInputStream(attempt.getInputStream(), 64 * 1024);
            ByteArrayOutputStream jpeg = null;
            int previous = -1;
            int value;
            while (isCurrent(session) && (value = input.read()) >= 0) {
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
                                if (!isCurrent(session)) {
                                    decoded.recycle();
                                    return;
                                }
                                Bitmap old = frame;
                                frame = decoded;
                                if (old != null && old != decoded && !old.isRecycled()) old.recycle();
                            }
                            postInvalidate();
                            connectionChanged(session, true);
                        }
                        jpeg = null;
                    } else if (jpeg.size() > 6 * 1024 * 1024) {
                        jpeg = null;
                    }
                }
                previous = value;
            }
        } catch (IOException | RuntimeException ignored) {
        } finally {
            if (input != null) {
                try { input.close(); } catch (Throwable ignored) { }
            }
            if (attempt != null) attempt.disconnect();
            synchronized (this) {
                if (connection == attempt) connection = null;
            }
            connectionChanged(session, false);
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
