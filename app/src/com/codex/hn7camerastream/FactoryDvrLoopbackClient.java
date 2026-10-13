package com.codex.hn7camerastream;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Talks only to the loopback endpoint injected into the factory DVR process. */
final class FactoryDvrLoopbackClient {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8787;
    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    static final class Health {
        final boolean ok;
        final boolean frameReady;
        final int cameraId;
        final long frameTimeMs;
        final boolean encoding;
        final boolean audioEnabled;
        Health(boolean ok, boolean frameReady, int cameraId, long frameTimeMs,
               boolean encoding, boolean audioEnabled) {
            this.ok = ok;
            this.frameReady = frameReady;
            this.cameraId = cameraId;
            this.frameTimeMs = frameTimeMs;
            this.encoding = encoding;
            this.audioEnabled = audioEnabled;
        }
    }

    private static final class Response {
        final int status;
        final byte[] body;

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    Health health(int timeoutMs) {
        try {
            Response response = request("/health", timeoutMs);
            if (response.status != 200) return unavailableHealth();
            String json = new String(response.body, StandardCharsets.UTF_8);
            return new Health(true, json.contains("\"frameReady\":true"),
                    parseInt(json, "\"cameraId\":", -1),
                    parseLong(json, "\"frameTimeMs\":", 0L),
                    json.contains("\"encoding\":true"),
                    json.contains("\"audioEnabled\":true"));
        } catch (Exception ignored) {
            return unavailableHealth();
        }
    }

    private static Health unavailableHealth() {
        return new Health(false, false, -1, 0L, false, false);
    }

    byte[] frame(int timeoutMs) {
        try {
            Response response = request("/frame.jpg", timeoutMs);
            return response.status == 200 && response.body.length > 0 ? response.body : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Proxies the factory process' continuous MJPEG body without opening a new
     * loopback socket for every frame. Authentication remains on Camera Link's
     * external server; the factory endpoint itself is IPv4 loopback-only.
     */
    void proxyMjpeg(OutputStream clientOutput, int connectTimeoutMs) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), connectTimeoutMs);
            socket.setSoTimeout(15_000);
            OutputStream backendOutput = socket.getOutputStream();
            backendOutput.write(("GET /stream.mjpeg HTTP/1.1\r\nHost: " + HOST
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            backendOutput.flush();

            InputStream backendInput = socket.getInputStream();
            String statusLine = readLine(backendInput);
            if (statusLine == null || !statusLine.startsWith("HTTP/1.1 200 ")) {
                throw new IllegalStateException("Factory DVR stream is unavailable");
            }
            String line;
            while ((line = readLine(backendInput)) != null && !line.isEmpty()) {
                // Camera Link supplies its own authenticated response headers.
            }
            clientOutput.write(("HTTP/1.1 200 OK\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            clientOutput.flush();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = backendInput.read(buffer)) >= 0) {
                clientOutput.write(buffer, 0, count);
                clientOutput.flush();
            }
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    boolean selectInput(int cameraId, int timeoutMs) {
        if (cameraId != 0 && cameraId != 2) return false;
        try {
            return request("/select?camera=" + cameraId, timeoutMs).status == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean setRecording(boolean enabled, int timeoutMs) {
        try {
            return request(enabled ? "/record/start" : "/record/stop", timeoutMs).status == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean lockRecording(int timeoutMs) {
        try {
            return request("/record/lock", timeoutMs).status == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean setAudioEnabled(boolean enabled, int timeoutMs) {
        try {
            return request("/settings/audio?enabled=" + (enabled ? "1" : "0"), timeoutMs).status == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Response request(String path, int timeoutMs) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\nHost: " + HOST
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            InputStream input = socket.getInputStream();
            String statusLine = readLine(input);
            if (statusLine == null || !statusLine.startsWith("HTTP/1.1 ")) {
                throw new IllegalStateException("Invalid factory DVR response");
            }
            String[] statusParts = statusLine.split(" ", 3);
            int status = Integer.parseInt(statusParts[1]);
            int contentLength = -1;
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int separator = line.indexOf(':');
                if (separator > 0 && "content-length".equalsIgnoreCase(line.substring(0, separator).trim())) {
                    contentLength = Integer.parseInt(line.substring(separator + 1).trim());
                }
            }
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                throw new IllegalStateException("Invalid factory DVR response size");
            }
            byte[] body = new byte[contentLength];
            int offset = 0;
            while (offset < body.length) {
                int read = input.read(body, offset, body.length - offset);
                if (read < 0) throw new IllegalStateException("Truncated factory DVR response");
                offset += read;
            }
            return new Response(status, body);
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int previous = -1;
        int value;
        while ((value = input.read()) >= 0) {
            if (previous == '\r' && value == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            line.write(value);
            previous = value;
            if (line.size() > 8192) throw new IllegalStateException("Factory DVR header is too large");
        }
        return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static int parseInt(String source, String marker, int fallback) {
        long parsed = parseLong(source, marker, fallback);
        return parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE ? fallback : (int) parsed;
    }

    private static long parseLong(String source, String marker, long fallback) {
        int start = source.indexOf(marker);
        if (start < 0) return fallback;
        start += marker.length();
        int end = start;
        if (end < source.length() && source.charAt(end) == '-') end++;
        while (end < source.length() && Character.isDigit(source.charAt(end))) end++;
        try { return Long.parseLong(source.substring(start, end)); }
        catch (Exception ignored) { return fallback; }
    }
}
