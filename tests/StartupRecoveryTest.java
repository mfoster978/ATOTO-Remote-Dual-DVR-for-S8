package com.codex.hn7camerastream;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.carstatus.CarStatus;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class StartupRecoveryTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static Context reset(int acc) {
        EnhancedDvrBootstrap.onFactoryServiceDestroyed();
        Handler.reset();
        SystemClock.now += 1_000_000;
        CarStatus.state = acc;
        CameraStreamService.instance = null;
        com.hcn.dvr.utils.DataHelper.instance = new com.hcn.dvr.utils.DataHelper();
        com.hcn.dvr.utils.Utils.CAMERA_SIGNAL = false;
        com.hcn.dvr.manager.AudioCaptureManager.starts = 0;
        return new Context();
    }
    static Intent ignition(boolean on) {
        return new Intent("android.mcu.device.action.ACC").putExtra("AccStatus", on);
    }
    public static void main(String[] args) throws Exception {
        Context context = reset(1);
        EnhancedDvrBootstrap.initialize(context);
        check(context.starts == 1, "Factory background startup must not require an activity");
        check(context.factoryStarts==1,"Package replacement must request native VideoService before the remote camera service");
        EnhancedDvrBootstrap.initialize(context);
        Handler.advance(650);
        check(Handler.pending() == 1 && context.starts == 1, "Repeated native starts must have one watcher");
        CameraStreamService.instance = null;
        Handler.advance(10_000);
        check(context.starts == 2, "Recover a killed remote service while native DVR is still running");
        System.out.println("PASS background start and service-loss recovery");

        EnhancedDvrBootstrap.onFactoryBroadcast(context, ignition(false));
        int previousStarts = context.starts, previousVpn = context.vpn, previousFactory = context.factoryStarts;
        Handler.advance(300_000);
        check(context.starts == previousStarts && context.vpn == previousVpn && context.factoryStarts==previousFactory && Handler.pending() == 0,
                "ACC-off must stop remote work and VPN requests");
        check(context.alarms.alarms.isEmpty(), "ACC-off must cancel every recovery alarm");
        EnhancedDvrBootstrap.onFactoryBroadcast(context, ignition(true));
        check(context.starts == previousStarts + 1 && context.vpn > previousVpn,
                "Native ACC receiver alone must restore server and VPN, without manifest delivery");
        check(context.alarms.alarms.size() == 6, "Schedule bootstrap plus delayed recovery through 180 seconds");
        EnhancedDvrBootstrap.onFactoryBroadcast(context, ignition(false));
        check(context.alarms.alarms.isEmpty() && Handler.pending() == 0,
                "Second key-off must cancel the whole recovery window");
        System.out.println("PASS native ignition events, restart, and ACC-off cancellation");

        context = reset(-1);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Handler.advance(80_000);
        check(context.starts == 0 && context.vpn == 0, "Unknown ACC must never start camera or VPN");
        CarStatus.state = 1;
        Handler.advance(10_000);
        check(context.starts == 1, "Recover delayed ACC readiness after the old 35-second limit");
        System.out.println("PASS late boot readiness without an ignition broadcast");

        context = reset(-1);
        EnhancedDvrBootstrap.initialize(context);
        Handler.advance(300_000);
        check(context.starts == 0 && Handler.pending() == 0, "Unknown state must have a bounded retry window");
        context = reset(1);
        context.failStarts = 2;
        EnhancedDvrBootstrap.initialize(context);
        Handler.advance(20_000);
        check(context.starts == 3 && CameraStreamService.instance != null, "Retry rejected service starts");
        EnhancedDvrBootstrap.onFactoryServiceDestroyed();
        CameraStreamService.instance = null;
        Handler.advance(30_000);
        check(context.starts == 3 && Handler.pending() == 0, "Factory destruction must detach its watcher");
        context.alarms.denyCancel = true;
        EnhancedDvrBootstrap.onFactoryBroadcast(context, ignition(false));
        check(context.stops > 0, "Alarm errors must not prevent stopping the remote service or escape into stock code");
        System.out.println("PASS bounded unknown state, launch retries, and error isolation");

        verifyAutoRecording();
        verifyViewerSessions();
    }

    static void verifyAutoRecording() {
        Context context=reset(1);
        com.hcn.dvr.utils.DataHelper helper=com.hcn.dvr.utils.DataHelper.instance;
        helper.auto=true; helper.audio=true;
        EnhancedDvrBootstrap.initialize(context);
        Handler.advance(10_000);
        check(helper.cameraController.starts==0 && helper.cameraController.opens==0,"Do not record before USB and camera signal settle");
        helper.usb=true; com.hcn.dvr.utils.Utils.CAMERA_SIGNAL=true;
        Handler.advance(1_000);
        check(helper.cameraController.opens>0 && helper.cameraController.starts==0,"Wait for native camera open completion");
        helper.cameraController.opened=true;
        Handler.advance(2_000);
        check(helper.cameraController.encoding && helper.cameraController.starts==1,"Start native recording with no activity or second input controller");
        check(helper.cameraController.path.equals("/storage/udisk1/dvr/daily/"),"Use native DVR USB directory");
        check(com.hcn.dvr.manager.AudioCaptureManager.starts==1,"Start factory microphone only when enabled");
        helper.cameraController.encoding=false;
        EnhancedDvrBootstrap.initialize(context); Handler.advance(60_000);
        check(helper.cameraController.starts==1,"Manual stop must survive repeated server recovery callbacks");
        EnhancedDvrBootstrap.onFactoryBroadcast(context,ignition(false));
        EnhancedDvrBootstrap.onFactoryBroadcast(context,ignition(true)); Handler.advance(2_000);
        check(helper.cameraController.starts==2 && helper.cameraController.encoding,"Next key-on must rearm factory auto-record");
        System.out.println("PASS background native auto-record, delayed USB/signal/open readiness, microphone, and key-on rearm");

        context=reset(1); helper=com.hcn.dvr.utils.DataHelper.instance;
        helper.auto=true; helper.usb=true; helper.cameraController.opened=true;
        com.hcn.dvr.utils.Utils.CAMERA_SIGNAL=true;
        EnhancedDvrBootstrap.initialize(context);
        EnhancedDvrBootstrap.onFactoryBroadcast(context,ignition(false)); Handler.advance(200_000);
        check(helper.cameraController.starts==0 && com.hcn.dvr.manager.AudioCaptureManager.starts==0,"Key-off must cancel pending encoder and audio commands");
        context=reset(1); helper=com.hcn.dvr.utils.DataHelper.instance;
        helper.usb=true; helper.cameraController.opened=true; com.hcn.dvr.utils.Utils.CAMERA_SIGNAL=true;
        EnhancedDvrBootstrap.initialize(context); Handler.advance(200_000);
        check(helper.cameraController.starts==0,"Respect disabled factory auto-record setting");
        context=reset(1); helper=com.hcn.dvr.utils.DataHelper.instance;
        helper.auto=true; helper.usb=true; helper.failStart=true; helper.cameraController.opened=true; com.hcn.dvr.utils.Utils.CAMERA_SIGNAL=true;
        EnhancedDvrBootstrap.initialize(context); Handler.advance(200_000);
        int failedStarts=helper.cameraController.starts; Handler.advance(100_000);
        check(failedStarts>0 && helper.cameraController.starts==failedStarts,"Encoder startup failures must have a bounded retry window");
        check(com.hcn.dvr.manager.AudioCaptureManager.starts==0,"Disabled microphone must remain disabled");
        EnhancedDvrBootstrap.onFactoryServiceDestroyed(); Handler.advance(20_000);
        check(Handler.pending()==0,"Service destruction must cancel all recording work");
        System.out.println("PASS disabled auto-record, key-off cancellation, manual stop, finite retries, and audio privacy");
    }

    static FakeConnection oldRequest, newRequest;
    static void verifyViewerSessions() throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "http".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                FakeConnection connection = new FakeConnection(url, url.getPath().equals("/old"));
                if (connection.delayed) oldRequest = connection; else newRequest = connection;
                return connection;
            }
        } : null);
        MjpegView viewer = new MjpegView(new Context());
        viewer.start("http://test/old", "camera", "123456");
        await(() -> oldRequest != null && oldRequest.waiting.getCount() == 0);
        viewer.start("http://test/new", "camera", "123456");
        await(viewer::isStreaming);
        oldRequest.responseReady.countDown();
        await(() -> oldRequest.disconnects >= 2);
        check(newRequest.disconnects == 0 && viewer.isStreaming(),
                "A stale HTTP request must not disconnect or change the current viewer session");
        viewer.stop();
        await(() -> newRequest.disconnects > 0);
        check(!viewer.isStreaming(), "Leaving live view must stop reconnecting");
        System.out.println("PASS delayed stale stream cannot close a newer camera connection");
    }
    interface Condition { boolean ready(); }
    static void await(Condition condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.ready() && System.nanoTime() < until) Thread.sleep(10);
        check(condition.ready(), "Timed out waiting for controlled viewer scenario");
    }
    static final class FakeConnection extends HttpURLConnection {
        final boolean delayed;
        final CountDownLatch waiting = new CountDownLatch(1), responseReady = new CountDownLatch(1), closed = new CountDownLatch(1);
        volatile int disconnects;
        FakeConnection(URL url, boolean delayed) { super(url); this.delayed = delayed; }
        @Override public int getResponseCode() {
            if (delayed) {
                waiting.countDown();
                boolean complete = false;
                while (!complete) try { responseReady.await(); complete = true; } catch (InterruptedException ignored) { }
            }
            return 200;
        }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                byte[] jpeg = {(byte)0xff, (byte)0xd8, 1, (byte)0xff, (byte)0xd9};
                int index;
                @Override public int read() throws IOException {
                    if (index < jpeg.length) return jpeg[index++] & 255;
                    try { closed.await(); } catch (InterruptedException error) { throw new IOException(error); }
                    throw new IOException("closed");
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (index >= jpeg.length) return read();
                    int count = Math.min(len, jpeg.length - index);
                    System.arraycopy(jpeg, index, b, off, count); index += count; return count;
                }
            };
        }
        @Override public void disconnect() { disconnects++; closed.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
