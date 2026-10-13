# ATOTO Remote Dual DVR for S8

An independent DVR replacement and remote camera viewer for compatible ATOTO S8 radios. One APK can run in **Radio mode** on the head unit or **Phone mode** on an Android phone. The radio records directly to its DVR USB drive; the phone reaches it through a private Tailscale connection.

[Download the latest APK](https://github.com/mfoster978/ATOTO-Remote-Dual-DVR-for-S8/releases/latest) · [Read the setup guide](#radio-installation) · [See tested hardware](#tested-hardware)

> This is a community project. It is not made, supported, or endorsed by ATOTO or Tailscale.

## What it adds

- Native, low-latency radio views for **Front**, **Reverse**, and **Front + Reverse** split screen.
- Simultaneous front and reverse MP4 recording when both physical inputs are installed and available.
- Phone live view for Front, Reverse, or both feeds through the radio's private Tailscale address.
- Optional vehicle audio in recordings and phone live view. The radio microphone setting is the master privacy control.
- Native radio video player, photo viewer, protect/unprotect actions, deletion, and USB storage details.
- One, two, or three-minute H.264 loop clips with optional AAC audio.
- Loop-storage limits of 50%, 75%, 90%, or all available DVR space, plus an Until Full mode.
- Emergency clip protection using the ATOTO MCU G-sensor event and Android motion sensor.
- Optional date/time, speed, and GPS-coordinate watermark.
- Parking mode with 30-minute, 16-hour, or 24-hour limits and 11.6 V, 11.8 V, or 12.0 V battery cutoffs.
- Start-on-boot service and Tailscale reconnect request.
- Connection PIN, setup QR, browser username/password display, and an optional numeric owner lock.
- Multiple named vehicles in Phone mode.
- A bundled Tailscale installer so the release remains a single download.
- Signed in-app updates from this repository on both the radio and phone.
- Automatic S8 status-bar REC reconciliation while a camera encoder is active.

## Screenshots

| DVR and parking settings | Setup guide |
| --- | --- |
| ![Radio DVR and parking settings](docs/screenshots/radio-dvr-settings.png) | ![Radio setup guide](docs/screenshots/radio-setup-guide.png) |

![Signed app update controls](docs/screenshots/radio-app-updates.png)

The screenshots came from the tested S8. Camera pictures, GPS coordinates, private addresses, connection credentials, and personal recordings are intentionally excluded from this repository.

## Tested hardware

The current release was built and installed on an **ATOTO S8** that reports:

| Property | Tested value |
| --- | --- |
| Android model/device | `tb8768p1_64_bsp` |
| Android version | Android 13 / API 33 |
| ATOTO build | `HN7_2026.07.17.16.49` |
| Platform | MediaTek MT6765 |
| Verified physical DVR input | Front DVR / Android camera 0 |
| DVR USB mount | `/storage/udisk1` |

The front camera, recording service, USB library, boot flow, parking controls, native UI, and remote server were exercised on that unit. The simultaneous reverse pipeline can open and encode beside the front pipeline, but this test vehicle does not currently have a physical reverse camera connected. Verify every installed input before relying on it.

Other S8 hardware and firmware revisions may expose different camera IDs, storage mounts, power behavior, or vehicle services. Please include the radio model, Android build, and a description of the installed cameras when reporting a compatibility issue.

## Camera map and hardware limits

| App view | S8 source | Can run beside |
| --- | --- | --- |
| Front DVR | Android camera 0, front input | Reverse |
| Reverse | Android camera 1 | Front DVR |
| DVR Camera 2 | Second input on ATOTO's camera-0 video mux | Reverse, after privileged mux switching |

Front DVR and DVR Camera 2 share one hardware decoder. ATOTO's factory DVR switches that mux through `/dev/videomux13`; a normal shareable APK receives `EACCES`. Camera Link can index existing DVR Camera 2 recordings, but live switching to that lens requires ATOTO system privileges. Even the factory app switches between Front and DVR Camera 2 instead of opening both together.

An unavailable camera may appear black on some firmware. Confirm its live picture before enabling automatic recording.

## Radio installation

1. Download `ATOTO-Remote-Dual-DVR-for-S8-v1.5.apk` from the [latest release](https://github.com/mfoster978/ATOTO-Remote-Dual-DVR-for-S8/releases/latest).
2. Install it and choose **Set up this ATOTO radio**.
3. Allow Camera and Microphone access. Cabin audio can be disabled under **DVR Settings**.
4. Keep the DVR USB flash drive inserted, open **DVR Settings**, and enable automatic recording only after confirming the installed camera's live picture.
5. Open **Internet & Tailscale**, install the included Tailscale APK, sign in, and set Tailscale as Android's **Always-on VPN**.
6. Set an owner PIN if desired. The PIN protects recordings and settings while leaving local live view available.
7. Install the same release on the phone, choose Phone mode, and scan the radio's setup QR.

The factory DVR and Camera Link should not compete for camera 0. Use Wireless ADB once to remove the factory DVR for Android user 0:

```text
adb shell pm uninstall -k --user 0 com.hcn.dvr
```

The signed factory APK remains in the system image. Restore it at any time with:

```text
adb shell cmd package install-existing --user 0 com.hcn.dvr
```

Parking mode needs one additional one-time Wireless ADB permission. It survives restarts and ordinary app updates:

```text
adb shell pm grant com.codex.hn7camerastream android.permission.WRITE_SECURE_SETTINGS
```

To include readable factory DVR folders in the library, use **Allow DVR library access** and enable Android's all-files-access switch for Camera Link. Camera Link can write to its own USB folders without that optional permission.

## Phone installation

1. Install the same release APK and choose **Use this phone as the viewer**.
2. Install or open the included Tailscale client and sign into the same tailnet as the radio.
3. Open **Connection** and scan the setup QR on the radio. Manual setup accepts the radio's Tailscale IP, port `8765`, and connection PIN.
4. Save the profile, then choose Front, Reverse, or Both under **Live cameras**.
5. Enable **Vehicle sound** when audio is wanted. It remains unavailable whenever the radio microphone setting is off.

By default, the phone asks Tailscale to connect when Camera Link opens and disconnect when Camera Link closes. This behavior can be changed in Phone settings. Tailscale is split tunnel unless the user explicitly chooses an exit node, so ordinary Internet traffic continues to use the phone's normal connection.

## Browser access

The radio also serves a password-protected web viewer at `http://RADIO_TAILSCALE_IP:8765/`.

- Username: `camera`
- Password: the website password shown by the radio; it currently matches the generated app connection PIN

Do not forward port `8765` to the public Internet. Keep it reachable only inside the private tailnet.

## USB recording folders

Without all-files access, Camera Link uses removable app storage:

```text
/storage/udisk1/Android/data/com.codex.hn7camerastream/files/Movies/ATOTO-Camera-Link/Front
/storage/udisk1/Android/data/com.codex.hn7camerastream/files/Movies/ATOTO-Camera-Link/Reverse
/storage/udisk1/Android/data/com.codex.hn7camerastream/files/Pictures/ATOTO-Camera-Link/Photos
```

Protected clips are stored in a `Protected` subfolder. With all-files access, front clips can use `/storage/udisk1/dvr/daily` beside the factory library. Loop mode deletes only the oldest ordinary clips after the selected allocation is reached or free space falls below the 1 GB reserve. Protected clips are excluded. Until Full mode never deletes clips and pauses recording at the reserve.

## In-app updates

Open the side menu and select **Check for updates**, or use **Settings → App updates**. Camera Link reads this repository's latest GitHub Release, compares the version, and downloads the release APK over HTTPS.

Before opening Android's installer, it verifies all of the following:

- the file is an Android APK for `com.codex.hn7camerastream`;
- its version code is newer than the installed version;
- its signing certificate matches the installed app.

Android still requires the normal install/update confirmation. Future official releases must use the same private signing key; that key is deliberately excluded from the public source.

## Power and parking limits

Remote access works only while Android, camera hardware, the radio's SIM or Wi-Fi, and Tailscale remain powered. Parking mode sends the S8 MCU sentry heartbeat until its selected duration or battery cutoff is reached. An APK cannot wake a fully powered-off radio.

Some S8 firmware changes mobile-data or airplane-mode state after ACC turns off. Full remote viewing through a long parked period therefore depends on the vehicle wiring and firmware. That behavior has not yet been verified through a complete parked, key-off test on the tested unit.

The owner PIN protects this app's controls. Android system Settings can still force-stop or uninstall an app or disable VPN unless the radio is provisioned as a managed device.

## Building from source

Requirements on Windows:

- Android SDK platform and build-tools packages
- JDK 17+ or Android Studio's bundled JBR
- PowerShell

Run:

```powershell
./build.ps1
```

The build downloads the pinned official Tailscale APK when it is absent, verifies the Tailscale and ZXing checksums, compiles the source, signs the APK, and writes `ATOTO-Remote-Dual-DVR-for-S8-v1.5.apk` in the project root. The uncompressed universal Tailscale APK exceeds GitHub's normal per-file source limit, so it is not stored in this repository; it remains bundled inside the compressed release APK.

If the private release keystore is absent, the script creates a local development key. APKs signed with a different key cannot update installations from the official release. Never commit or share a signing keystore.

## Third-party software

The release includes the official universal **Tailscale Android 1.104.1** installer as a separate application. Camera Link does not modify it.

- Tailscale APK SHA-256: `b3a1a81b52d1437769e5570c89a95f5824518a6b376d10c39eb64af213a625b6`
- ZXing Core 3.5.3 SHA-256: `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), `app/assets/TAILSCALE_LICENSE.txt`, and `app/assets/ZXING_LICENSE.txt`.

## License

Camera Link source is available under the [MIT License](LICENSE). Third-party components retain their own licenses.
