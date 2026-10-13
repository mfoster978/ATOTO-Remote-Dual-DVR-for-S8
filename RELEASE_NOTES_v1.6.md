# ATOTO Remote Dual DVR for S8 v1.6

> [!CAUTION]
> **Withdrawn:** do not use v1.6 parking mode. It wrote ATOTO's sentry setting to the wrong Android settings table, so the S8 could enter sleep on the first five-second ACC-off check. This release is being replaced after live power-cycle testing.

Version 1.6 fixes cold-boot recording, remote live video, shared Front/DVR2 input switching, and ATOTO parking-power integration found during testing on the S8.

## Why it matters

The tested HN7 S8 factory DVR has no Internet live-camera viewer and does not record its separate backup/reverse-camera input alongside the selected shared DVR input. Camera Link adds both capabilities:

- View Front DVR, DVR Camera 2, backup/reverse, or DVR + Reverse split screen from an Android phone or tablet over a private Tailscale connection.
- Keep remote viewing available after the ignition turns off while parking mode keeps the radio, cameras, Internet connection, and VPN powered.
- Record the independent backup/reverse camera and one selected DVR input to USB at the same time. Front and DVR2 share one hardware decoder, so those two inputs cannot be recorded simultaneously.
- Temporarily switch Front/DVR2 from the phone, then restore the radio's saved input and its recording when remote live view closes.

ATOTO's [official parking-recording comparison](https://www.atotodirect.com/blogs/guides/what-is-parking-recording-how-atoto-smart-parking-monitoring-helps-protect-your-vehicle-while-parked) lists mobile-app remote viewing for X10 but not S8 Gen4. Its [X10 D-DVR overview](https://www.atotodirect.com/en-us/blogs/x10/atoto-x10-d-dvr-dual-camera-recorder) advertises remote wake and live camera viewing through SSHub. This project provides the core remote-view capability for compatible S8 hardware without requiring an X10 radio. A completely powered-off radio remains unreachable.

## Fixed

- Parking mode now mirrors the factory DVR's sentry, duration, voltage, and activation settings and pre-arms the ATOTO MCU keep-awake heartbeat before ACC-off.
- Configured front and reverse recording retry every ten seconds when camera hardware is temporarily unavailable during boot.
- A transparent boot bootstrap uses Android's overlay special access to reopen Camera and Microphone automatically without leaving the Camera Link screen open.
- The REC status-bar indicator continues to reflect actual encoder state and recovers when a delayed boot retry begins recording.
- Remote Front video now downsizes the complete 1920×1080 camera buffer instead of cropping its upper-left quarter.
- The native phone viewer fits the complete frame inside its viewing box without zooming or cropping it.
- MJPEG streaming publishes changing frames continuously; the tested stream produced six different consecutive JPEG frames.

## DVR Camera 2 switching

- The radio can select Front or DVR Camera 2, and split view pairs the selected DVR input with Reverse.
- The phone can temporarily select either shared DVR input. Closing or leaving the live-view screen restores the input saved on the radio and resumes its recording.
- Camera Link includes the radio-only `ATOTO-Mux-Bridge.apk`. It keeps the factory `com.hcn.dvr` package installed and briefly uses its signed video service for `/dev/videomux13` access.
- The bridge is staged from Camera Link to `/sdcard/Download/ATOTO-Mux-Bridge.apk` and installed once through the documented Wireless ADB commands.

## One-time radio setup

Keep the factory `com.hcn.dvr` package installed. In **DVR Settings**, select **Prepare included bridge for ADB**, then run:

```text
adb shell settings put global verifier_verify_adb_installs 0
adb shell settings put global package_verifier_enable 0
adb shell pm install -r /sdcard/Download/ATOTO-Mux-Bridge.apk
adb shell settings delete global verifier_verify_adb_installs
adb shell settings delete global package_verifier_enable
```

For parking power and cold-boot camera access, run these commands once while Wireless ADB is connected:

```text
adb shell pm grant com.codex.hn7camerastream android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.codex.hn7camerastream SYSTEM_ALERT_WINDOW allow
```

These settings apply only to Camera Link and survive ordinary app updates and radio restarts. Camera Link does not draw floating overlays; the access lets its transparent startup activity run long enough to open the configured cameras.

## Tested radio

- ATOTO S8
- Android model/device: `tb8768p1_64_bsp`
- Android 13 / API 33
- ATOTO build: `HN7_2026.07.17.16.49`
- MediaTek MT6765
- Physical front DVR camera installed

The release verifies a real cold boot without opening Camera Link: the service started from `BOOT_COMPLETED`, Android granted camera access, camera 0 opened with its factory 1920×1080 buffer, recording resumed on the DVR USB drive, the REC indicator followed encoder state, and the ATOTO Secure parking keys were synchronized. A real key-off interval is still needed to confirm that the MCU heartbeat keeps this specific radio powered for the selected parking duration.
