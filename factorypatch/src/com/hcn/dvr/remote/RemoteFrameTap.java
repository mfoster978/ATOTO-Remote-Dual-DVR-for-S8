package com.hcn.dvr.remote;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback-only bridge that lets Camera Link share the factory DVR's camera
 * and ask the factory service to perform its normal recording operations.
 *
 * The factory camera callback offers frames here after applying its normal
 * watermark. Work is rate-limited and moved off the camera thread. The proof
 * server binds only to loopback so it cannot be reached from Wi-Fi, cellular,
 * or Tailscale. ADB port forwarding is required to inspect it.
 */
public final class RemoteFrameTap {
    private static final String TAG = "DvrRemoteFrameTap";
    private static final int PORT = 8787;
    private static final long MIN_FRAME_INTERVAL_MS = 100L;
    private static final int REMOTE_WIDTH = 960;
    private static final int REMOTE_HEIGHT = 540;
    private static final AtomicBoolean ENCODE_QUEUED = new AtomicBoolean(false);
    private static final Object FRAME_MONITOR = new Object();
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(
            new NamedDaemonThreadFactory("dvr-frame-encoder"));

    private static volatile long lastFrameOfferMs;
    private static volatile byte[] latestJpeg;
    private static volatile long latestFrameTimeMs;
    private static volatile int latestCameraId = -1;
    private static volatile boolean serverStarted;
    private static volatile Context applicationContext;

    private RemoteFrameTap() {
    }

    public static void offerNv21(byte[] frame, final int width, final int height,
                                 final int cameraId, Context context) {
        if (context != null && applicationContext == null) {
            applicationContext = context.getApplicationContext();
        }
        ensureServerStarted();
        if (frame == null || width <= 0 || height <= 0) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastFrameOfferMs < MIN_FRAME_INTERVAL_MS
                || !ENCODE_QUEUED.compareAndSet(false, true)) {
            return;
        }
        lastFrameOfferMs = now;
        // The factory preview is 1920x1080. Sending that as individual JPEG
        // requests was both CPU-heavy and limited the old bridge to roughly
        // two frames per second. Make a half-size NV21 frame while the camera
        // callback still owns the source buffer, then encode it away from the
        // camera thread. The factory app's local preview remains untouched.
        final int outputWidth = width >= REMOTE_WIDTH ? REMOTE_WIDTH : width & ~1;
        final int outputHeight = height >= REMOTE_HEIGHT ? REMOTE_HEIGHT : height & ~1;
        final byte[] frameCopy = downscaleNv21(frame, width, height, outputWidth, outputHeight);
        ENCODER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);
                    YuvImage image = new YuvImage(frameCopy, ImageFormat.NV21,
                            outputWidth, outputHeight, null);
                    if (image.compressToJpeg(new Rect(0, 0, outputWidth, outputHeight), 68, output)) {
                        synchronized (FRAME_MONITOR) {
                            latestJpeg = output.toByteArray();
                            latestCameraId = cameraId;
                            latestFrameTimeMs = System.currentTimeMillis();
                            FRAME_MONITOR.notifyAll();
                        }
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "Unable to encode preview frame", error);
                } finally {
                    ENCODE_QUEUED.set(false);
                }
            }
        });
    }

    private static byte[] downscaleNv21(byte[] source, int sourceWidth, int sourceHeight,
                                        int outputWidth, int outputHeight) {
        int sourceYSize = sourceWidth * sourceHeight;
        int outputYSize = outputWidth * outputHeight;
        if (source.length < sourceYSize * 3 / 2 || outputWidth <= 0 || outputHeight <= 0) {
            return source.clone();
        }
        byte[] output = new byte[outputYSize * 3 / 2];
        for (int row = 0; row < outputHeight; row++) {
            int sourceRow = (int) ((long) row * sourceHeight / outputHeight);
            int sourceOffset = sourceRow * sourceWidth;
            int outputOffset = row * outputWidth;
            for (int column = 0; column < outputWidth; column++) {
                int sourceColumn = (int) ((long) column * sourceWidth / outputWidth);
                output[outputOffset + column] = source[sourceOffset + sourceColumn];
            }
        }
        for (int row = 0; row < outputHeight / 2; row++) {
            int sourceRow = (int) ((long) row * sourceHeight / outputHeight);
            int sourceOffset = sourceYSize + sourceRow * sourceWidth;
            int outputOffset = outputYSize + row * outputWidth;
            for (int column = 0; column < outputWidth; column += 2) {
                int sourceColumn = (int) ((long) column * sourceWidth / outputWidth) & ~1;
                output[outputOffset + column] = source[sourceOffset + sourceColumn];
                output[outputOffset + column + 1] = source[sourceOffset + sourceColumn + 1];
            }
        }
        return output;
    }

    private static synchronized void ensureServerStarted() {
        if (serverStarted) {
            return;
        }
        serverStarted = true;
        Thread server = new Thread(new Runnable() {
            @Override
            public void run() {
                runServer();
            }
        }, "dvr-loopback-probe");
        server.setDaemon(true);
        server.start();
    }

    private static void runServer() {
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            // Android may resolve getLoopbackAddress() to ::1 while Camera Link
            // deliberately connects to the IPv4 loopback address. Bind the
            // exact private address so both sides always use the same socket.
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            Log.i(TAG, "Loopback frame probe listening on 127.0.0.1:" + PORT);
            while (!Thread.currentThread().isInterrupted()) {
                final Socket socket = serverSocket.accept();
                Thread client = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(socket);
                    }
                }, "dvr-probe-client");
                client.setDaemon(true);
                client.start();
            }
        } catch (Throwable error) {
            serverStarted = false;
            Log.e(TAG, "Loopback frame probe stopped", error);
        }
    }

    private static void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Drain request headers.
            }
            String path = "/";
            if (requestLine != null) {
                String[] requestParts = requestLine.split(" ");
                if (requestParts.length >= 2) {
                    path = requestParts[1];
                }
            }

            if ("/stream.mjpeg".equals(path)) {
                streamMjpeg(socket);
            } else if ("/frame.jpg".equals(path)) {
                byte[] jpeg = latestJpeg;
                if (jpeg == null) {
                    send(socket, 503, "text/plain; charset=utf-8",
                            "Waiting for the factory camera preview.\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    send(socket, 200, "image/jpeg", jpeg);
                }
            } else if ("/health".equals(path)) {
                boolean encoding = isFactoryEncoding();
                boolean audioEnabled = isFactoryAudioEnabled();
                String json = String.format(Locale.US,
                        "{\"ok\":true,\"frameReady\":%s,\"cameraId\":%d,\"frameTimeMs\":%d,"
                                + "\"encoding\":%s,\"audioEnabled\":%s}\n",
                        latestJpeg != null ? "true" : "false", latestCameraId, latestFrameTimeMs,
                        encoding ? "true" : "false", audioEnabled ? "true" : "false");
                send(socket, 200, "application/json; charset=utf-8",
                        json.getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/select?camera=")) {
                int requested = parseCameraId(path);
                if ((requested == 0 || requested == 2) && selectFactoryInput(requested)) {
                    latestJpeg = null;
                    latestCameraId = -1;
                    latestFrameTimeMs = 0L;
                    send(socket, 200, "application/json; charset=utf-8",
                            ("{\"ok\":true,\"requestedCameraId\":" + requested + "}\n")
                                    .getBytes(StandardCharsets.UTF_8));
                } else {
                    send(socket, 503, "application/json; charset=utf-8",
                            "{\"ok\":false}\n".getBytes(StandardCharsets.UTF_8));
                }
            } else if ("/record/start".equals(path)) {
                boolean started = setFactoryRecording(true);
                send(socket, started ? 200 : 503, "application/json; charset=utf-8",
                        ("{\"ok\":" + (started ? "true" : "false") +
                                ",\"encoding\":" + (isFactoryEncoding() ? "true" : "false") + "}\n")
                                .getBytes(StandardCharsets.UTF_8));
            } else if ("/record/stop".equals(path)) {
                boolean stopped = setFactoryRecording(false);
                send(socket, stopped ? 200 : 503, "application/json; charset=utf-8",
                        ("{\"ok\":" + (stopped ? "true" : "false") +
                                ",\"encoding\":" + (isFactoryEncoding() ? "true" : "false") + "}\n")
                                .getBytes(StandardCharsets.UTF_8));
            } else if ("/record/lock".equals(path)) {
                boolean locked = lockFactoryRecording();
                send(socket, locked ? 200 : 503, "application/json; charset=utf-8",
                        ("{\"ok\":" + (locked ? "true" : "false") + "}\n")
                                .getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/settings/audio?enabled=")) {
                boolean enabled = path.endsWith("=1") || path.endsWith("=true");
                boolean changed = setFactoryAudioEnabled(enabled);
                send(socket, changed ? 200 : 503, "application/json; charset=utf-8",
                        ("{\"ok\":" + (changed ? "true" : "false") +
                                ",\"audioEnabled\":" + (isFactoryAudioEnabled() ? "true" : "false") + "}\n")
                                .getBytes(StandardCharsets.UTF_8));
            } else {
                send(socket, 404, "text/plain; charset=utf-8",
                        "Use /health, /stream.mjpeg, /frame.jpg, /select?camera=0, /record/start, /record/stop, "
                                .concat("/record/lock, or /settings/audio?enabled=1.\n")
                                .getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            Log.w(TAG, "Probe client failed", error);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void streamMjpeg(Socket socket) throws Exception {
        socket.setSoTimeout(0);
        OutputStream output = socket.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        byte[] previous = null;
        while (!socket.isClosed() && !Thread.currentThread().isInterrupted()) {
            byte[] frame;
            synchronized (FRAME_MONITOR) {
                if (latestJpeg == null || latestJpeg == previous) FRAME_MONITOR.wait(2000L);
                frame = latestJpeg;
            }
            if (frame == null || frame == previous) continue;
            output.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " +
                    frame.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(frame);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            previous = frame;
        }
    }

    private static int parseCameraId(String path) {
        try {
            return Integer.parseInt(path.substring("/select?camera=".length()));
        } catch (Exception ignored) {
            return -1;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean selectFactoryInput(int cameraId) {
        Context context = applicationContext;
        if (context == null) {
            return false;
        }
        try {
            Class<?> helperClass = Class.forName("com.hcn.dvr.utils.DataHelper");
            Method getInstance = helperClass.getMethod("getInstance", Context.class);
            Object helper = getInstance.invoke(null, context);
            Field controllerField = helperClass.getField("cameraController");
            Object controller = controllerField.get(helper);
            if (controller == null) {
                return false;
            }
            boolean restartRecording = invokeBoolean(controller, "isEncoding", false);
            Class enumClass = Class.forName("com.hcn.dvr.ICameraController$CameraType");
            Object cameraType = Enum.valueOf(enumClass, cameraId == 2 ? "CABIN" : "FRONT");
            Method switchCamera = controller.getClass().getMethod("switchCameraType", enumClass);
            switchCamera.invoke(controller, cameraType);
            Method getCurrentCamera = controller.getClass().getMethod("getCurrentCameraType");
            long deadline = SystemClock.uptimeMillis() + 2000L;
            while (SystemClock.uptimeMillis() < deadline) {
                Object current = getCurrentCamera.invoke(controller);
                if (cameraType.equals(current)) break;
                SystemClock.sleep(50L);
            }
            if (restartRecording) {
                startFactoryRecording(helper, controller);
            }
            Log.i(TAG, "Factory DVR input switch requested: " + cameraId);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to select factory DVR input " + cameraId, error);
            return false;
        }
    }

    private static Object factoryHelper() throws Exception {
        Context context = applicationContext;
        if (context == null) return null;
        Class<?> helperClass = Class.forName("com.hcn.dvr.utils.DataHelper");
        Method getInstance = helperClass.getMethod("getInstance", Context.class);
        return getInstance.invoke(null, context);
    }

    private static Object factoryController(Object helper) throws Exception {
        if (helper == null) return null;
        Field controllerField = helper.getClass().getField("cameraController");
        return controllerField.get(helper);
    }

    private static boolean invokeBoolean(Object target, String methodName, boolean fallback) {
        if (target == null) return fallback;
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean isFactoryEncoding() {
        try {
            return invokeBoolean(factoryController(factoryHelper()), "isEncoding", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isFactoryAudioEnabled() {
        try {
            Object helper = factoryHelper();
            if (helper == null) return false;
            Object value = helper.getClass().getMethod("getEnableAudioRecord").invoke(helper);
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setFactoryAudioCapture(boolean enabled) throws Exception {
        Class<?> captureClass = Class.forName("com.hcn.dvr.manager.AudioCaptureManager");
        Object capture = captureClass.getMethod("getInstance").invoke(null);
        captureClass.getMethod(enabled ? "startCapture" : "stopCapture").invoke(capture);
    }

    private static boolean startFactoryRecording(Object helper, Object controller) throws Exception {
        if (helper == null || controller == null) return false;
        if (invokeBoolean(controller, "isEncoding", false)) return true;
        if (!invokeBoolean(controller, "isOpened", false)) {
            controller.getClass().getMethod("open").invoke(controller);
            SystemClock.sleep(250L);
        }
        // VideoService normally starts the factory AudioCaptureManager before
        // recording. Remote recording can begin without an Activity/boot intent,
        // so do that part explicitly or the muxer waits forever for its audio
        // track and leaves a zero-byte MP4.
        Object audio = helper.getClass().getMethod("getEnableAudioRecord").invoke(helper);
        if (audio instanceof Boolean && ((Boolean) audio).booleanValue()) {
            setFactoryAudioCapture(true);
        }
        String base = String.valueOf(helper.getClass().getMethod("getRecordSavePath").invoke(helper));
        if (!base.endsWith("/")) base += "/";
        controller.getClass().getMethod("startEncoding", String.class).invoke(controller, base + "daily/");
        long deadline = SystemClock.uptimeMillis() + 3000L;
        while (SystemClock.uptimeMillis() < deadline) {
            if (invokeBoolean(controller, "isEncoding", false)) return true;
            SystemClock.sleep(50L);
        }
        return invokeBoolean(controller, "isEncoding", false);
    }

    private static boolean setFactoryRecording(boolean enabled) {
        try {
            Object helper = factoryHelper();
            Object controller = factoryController(helper);
            if (controller == null) return false;
            if (enabled) return startFactoryRecording(helper, controller);
            if (invokeBoolean(controller, "isEncoding", false)) {
                controller.getClass().getMethod("stopEncoding").invoke(controller);
            }
            long deadline = SystemClock.uptimeMillis() + 3000L;
            while (SystemClock.uptimeMillis() < deadline) {
                if (!invokeBoolean(controller, "isEncoding", false)) return true;
                SystemClock.sleep(50L);
            }
            return !invokeBoolean(controller, "isEncoding", true);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to change factory recording state to " + enabled, error);
            return false;
        }
    }

    private static boolean lockFactoryRecording() {
        try {
            Object helper = factoryHelper();
            Object controller = factoryController(helper);
            if (controller == null || !invokeBoolean(controller, "isEncoding", false)) return false;
            Object encoder = helper.getClass().getMethod("getEncoderManager").invoke(helper);
            if (encoder == null) return false;
            encoder.getClass().getMethod("lockCurrentSlice", boolean.class).invoke(encoder, true);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to lock factory recording", error);
            return false;
        }
    }

    private static boolean setFactoryAudioEnabled(boolean enabled) {
        try {
            Object helper = factoryHelper();
            Object controller = factoryController(helper);
            if (helper == null || controller == null) return false;
            boolean restart = invokeBoolean(controller, "isEncoding", false);
            if (restart && !setFactoryRecording(false)) return false;
            helper.getClass().getMethod("setEnableAudioRecord", boolean.class).invoke(helper, enabled);
            setFactoryAudioCapture(enabled);
            if (restart && !startFactoryRecording(helper, controller)) return false;
            return isFactoryAudioEnabled() == enabled;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to change factory audio setting", error);
            return false;
        }
    }

    private static void send(Socket socket, int status, String contentType, byte[] body)
            throws Exception {
        String reason = status == 200 ? "OK" : status == 503 ? "Service Unavailable" : "Not Found";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String name;

        NamedDaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
