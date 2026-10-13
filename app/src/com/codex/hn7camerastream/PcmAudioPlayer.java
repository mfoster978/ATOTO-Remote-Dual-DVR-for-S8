package com.codex.hn7camerastream;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class PcmAudioPlayer {
    public interface Listener { void onStopped(String reason); }

    private volatile boolean running;
    private Thread worker;
    private HttpURLConnection connection;
    private AudioTrack track;

    public synchronized void start(final String url, final String username, final String password,
                                   final Listener listener) {
        stop();
        running = true;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                String reason = "";
                try {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(10_000);
                    connection.setReadTimeout(15_000);
                    String auth = "Basic " + Base64.encodeToString(
                            (username + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                    connection.setRequestProperty("Authorization", auth);
                    int response = connection.getResponseCode();
                    if (response != 200) throw new IllegalStateException(response == 403 ?
                            "The radio microphone is off" : "Vehicle sound is unavailable (HTTP " + response + ")");
                    BufferedInputStream input = new BufferedInputStream(connection.getInputStream(), 32 * 1024);
                    long skipped = 0;
                    while (skipped < 44) {
                        long count = input.skip(44 - skipped);
                        if (count <= 0) throw new IllegalStateException("Invalid audio stream");
                        skipped += count;
                    }
                    int minimum = AudioTrack.getMinBufferSize(44_100, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    track = new AudioTrack(AudioManager.STREAM_MUSIC, 44_100, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(16_384, minimum * 2), AudioTrack.MODE_STREAM);
                    track.play();
                    byte[] buffer = new byte[8_192];
                    int read;
                    while (running && (read = input.read(buffer)) > 0) track.write(buffer, 0, read);
                    input.close();
                } catch (Throwable error) {
                    if (running) reason = String.valueOf(error.getMessage());
                } finally {
                    release();
                    if (listener != null && reason.length() > 0) listener.onStopped(reason);
                }
            }
        }, "CameraLinkLiveAudio");
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (connection != null) connection.disconnect();
        if (worker != null) worker.interrupt();
        release();
        worker = null;
    }

    public boolean isRunning() { return running; }

    private synchronized void release() {
        running = false;
        connection = null;
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            try { track.release(); } catch (Exception ignored) { }
            track = null;
        }
    }
}
