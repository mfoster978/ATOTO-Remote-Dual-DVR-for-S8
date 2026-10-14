# ATOTO Remote Dual DVR for S8

**PARKING MONITOR MODE IS AUTOMATICALLY DISABLED BY THIS UPDATE.** Version 2.0.4 checks the saved setting and turns it off if it was enabled; users do not have to remember to disable it first. Installing this update removes the ability to use or enable factory DVR parking monitoring. It is locked off because factory resets were reported during parking-mode testing, and that feature is still in development. Tapping its control displays an explanation. This release is for testing recording, playback, and remote viewing while the vehicle is on.

ATOTO Remote Dual DVR for S8 extends the factory DVR on compatible ATOTO S8 radios and adds a separate Android viewer for phones and tablets.

The release contains two APKs:

- **ATOTO S8 Enhanced Stock DVR** updates the existing `com.hcn.dvr` factory app. It keeps ATOTO's native live camera screen, recorder, video player, other settings, and hardware integration, then adds reverse-camera recording, remote access, split views, security, and updates. Parking monitoring is locked off in this release.
- **Camera Link Viewer** is the phone/tablet app. It connects through Tailscale for live camera viewing and media playback.

The intended radio setup is one factory DVR update plus one new app, Tailscale. Camera Link is no longer installed as a second DVR app on the radio.

This is an independent community project. It is not made, supported, or endorsed by ATOTO or Tailscale.

## Intended improvements over the factory S8 DVR

The factory S8 DVR records the selected Front/DVR Camera 2 input but does not provide phone live viewing or record the separate Android reverse-camera input on the tested HN7 radio. This project adds both features while retaining the factory app and its native camera pipeline.

ATOTO's own [parking recording comparison](https://www.atotodirect.com/blogs/guides/what-is-parking-recording-how-atoto-smart-parking-monitoring-helps-protect-your-vehicle-while-parked) lists mobile remote viewing for X10 and not for S8 Gen4. ATOTO's [X10 D-DVR documentation](https://www.atotodirect.com/en-us/blogs/x10/atoto-x10-d-dvr-dual-camera-recorder) describes vendor remote wake and SSHub integration. This project supplies private live access for S8 while Android, the radio's network connection, and Tailscale are awake.

| Capability | Factory S8 DVR | Enhanced DVR + Camera Link Viewer |
| --- | --- | --- |
| Radio interface | Native ATOTO DVR | Same native DVR with added controls |
| Front/DVR2 recording | Selected shared input | Selected shared input, still handled by ATOTO's recorder |
| Reverse-camera recording | Not available on the tested setup | Records independently beside the selected DVR input |
| Simultaneous recording | One shared DVR input | One shared DVR input plus Reverse |
| Phone/tablet live view | Not available | Front, DVR2, Reverse, or DVR + Reverse split view |
| Phone media playback | Not available | Streams completed USB recordings through the viewer |
| Radio playback | Factory DVR clips | Factory clips and Camera Link reverse clips in the native player |
| Vehicle-off recording | Factory parking mode | Disabled by this update |
| Vehicle-off live view | Not available | Unavailable while parking mode is disabled |

## Added features

Recording and remote access are intended for use while the radio and vehicle are on. Parking monitor mode remains unavailable during development.

- Remote live Front, DVR Camera 2, Reverse, and split-camera views through a private Tailscale connection.
- Continuous reverse-camera H.264 MP4 recording alongside whichever shared DVR input ATOTO is recording.
- Reverse clips in the factory DVR recordings screen with an **R** badge, an **R** filter beside **ALL/F/C**, and native playback.
- **REVERSE**, **SPLIT**, and **LINK** controls over the native DVR camera view.
- Direct reverse-preview rendering on the radio with a transparent surface background so the camera picture is not covered by a black view.
- Reverse capture reads the decoder's detected frame size, uses a matching supported capture size, and expands analog NTSC/PAL fields for viewing and recording. Digital zoom is reset and single/split previews fit the complete image.
- Split view cycle: top/bottom, left/right, then off.
- The factory camera button remains responsible for switching Front and DVR Camera 2.
- Temporary remote Front/DVR2 selection, followed by restoration of the radio's saved input when remote viewing ends.
- Optional cabin audio. The radio microphone switch remains the master privacy control for recordings and remote sound.
- Setup QR, app connection PIN, browser username/password, and named vehicle profiles.
- Optional owner PIN. One successful unlock opens every protected factory DVR and Camera Link screen for 10 minutes. Screen changes and briefly leaving the app do not ask again; the timer or a radio restart relocks it. Live camera viewing and the factory emergency-lock button remain available while locked.
- Automatic recording recovery when the stock DVR opens before its camera signal state is initialized.
- Automatic Camera Link server recovery after ACC-on, plus phone live-view retries while the radio and Tailscale reconnect.
- Signed one-click update checks for both APKs through GitHub Releases.
- Android package-installer sessions on the radio for the bundled Tailscale installer and GitHub updates, avoiding the system-account file-grant error.
- Parking monitor lock with an explanation popup; saved enabled settings are cleared and the factory parking getter/setter are forced off.

## Hardware limit

Front and DVR Camera 2 share one camera-0 decoder through ATOTO's video mux. They cannot be recorded simultaneously. The factory camera button selects which of those two inputs is live and recorded.

Reverse uses camera 1 and is independent, so the supported combination is:

```text
Front + Reverse
or
DVR Camera 2 + Reverse
```

Available inputs depend on the cameras installed in each vehicle. Confirm a real live image before enabling recording for an input.

## Screenshots

| Enhanced DVR settings | Radio setup guide |
| --- | --- |
| ![Enhanced DVR settings](docs/screenshots/radio-dvr-settings.png) | ![Radio setup guide](docs/screenshots/radio-setup-guide.png) |

![GitHub update controls](docs/screenshots/radio-app-updates.png)

Camera images, private addresses, credentials, GPS coordinates, and personal recordings are excluded from the repository screenshots.

## Tested radio

The project is being tested on the unit below. Version 2.0.4 disables parking monitoring so recording, playback, remote access, and the newly installed backup camera can be tested separately. Disabling parking does not establish the root cause of the earlier resets.

| Property | Tested value |
| --- | --- |
| Radio family | ATOTO S8 |
| Android model/device | `tb8768p1_64_bsp` |
| Android version | Android 13 / API 33 |
| ATOTO build | `HN7_2026.07.17.16.49` |
| Factory DVR version | `1.4.20260309-uis8581<bate>` |
| Platform | MediaTek MT6765 |
| DVR USB mount | `/storage/udisk1` |
| Verified installed DVR input | Front DVR / camera 0 |

Other S8 models and firmware builds may use different camera IDs, storage mounts, package certificates, or vehicle services. The enhanced radio APK is an update to a privileged factory package and has only been tested on the configuration above.

## Install on the radio

Installing this update disables factory DVR parking monitoring, including any previously saved enabled setting.

1. Download **`ATOTO-S8-Enhanced-Stock-DVR-v2.0.4.apk`** from the latest GitHub Release.
2. Install it on the radio as an update to the existing ATOTO DVR. Do not uninstall the factory DVR first; the package data and original app identity must remain available.
3. Open **DVR**. The familiar ATOTO camera screen and settings remain in place.
4. Tap **LINK** to open the added side menu.
5. Install the included Tailscale APK, sign in, and connect the radio to the same tailnet used by the phone. **Install bundled Tailscale APK** can also reinstall/update the included package without uninstalling it first.
6. Open **Phone connection & QR** and leave the QR code visible for phone setup.
7. Enable reverse recording only if the reverse camera has a confirmed live picture.
8. Parking monitor mode is locked off. Tapping its switch or parking controls explains why it is disabled and still in development.

Wireless ADB is needed only to install or service the radio APK when the normal package installer is unavailable. It is not required for daily recording, phone viewing, or playback after setup.

The release uses version code `210004` and version name `2.0.4-camera-link` for the enhanced radio package so Android and the in-app updater recognize it as newer than v2.0.3. Update the viewer to v2.0.4 as well for its connection recovery fix.

## Install on the phone or tablet

1. Download **`Camera-Link-Viewer-v2.0.4.apk`** from the same release.
2. Install Tailscale from the Play Store or use the installer offered by Camera Link Viewer.
3. Sign in to the same tailnet as the radio and approve Android's VPN request.
4. Open Camera Link Viewer and scan the setup QR shown on the radio.
5. Choose the vehicle and open Front, DVR 2, Reverse, or Both.

The phone can ask Tailscale to connect when Camera Link Viewer opens and disconnect when it closes. This can be changed in the phone settings. Tailscale normally uses split tunneling unless an exit node is selected, so it does not route all Internet traffic through the radio.

## Recordings and playback

The native ATOTO recordings screen remains the main radio library. Its **All** view now combines:

- factory Front/DVR2 daily and emergency clips;
- completed Camera Link Reverse clips, marked **R**;
- locked/protected clips.

Use the factory **F**, **C**, and added **R** buttons to show Front, DVR Camera 2, or Reverse clips by themselves. Reverse thumbnails are identified by a visible **R** badge.

The factory video player handles playback on the radio. The phone app lists the same completed recordings through the private radio server and uses its own native player. A clip that is still being written appears after the segment closes.

Reverse recordings are stored under the removable drive's app media directory, normally:

```text
/storage/udisk1/Android/data/com.hcn.dvr/files/Movies/ATOTO-Camera-Link/Reverse
```

Factory DVR clips remain in ATOTO's normal `/storage/udisk1/dvr` folders. Protected reverse clips stay outside automatic cleanup.

## Browser access

The radio also provides a private web viewer while it is awake:

```text
http://RADIO_TAILSCALE_IP:8765/
```

The radio connection page shows the website username and password. The app connection PIN is kept for Camera Link Viewer and QR setup. Do not forward port `8765` to the public Internet.

## Parking and power behavior

This update removes parking-monitor functionality from the enhanced factory DVR for now. The parking switch stays off and opens a popup explaining that parking mode is disabled due to factory-reset issues and is still in development. Parking duration and voltage controls cannot enable it. The saved `sentry_mode_enable` flag is cleared, the factory getter always returns false, and its setter persists false, so restoring an enabled setting cannot bypass the lock.

The native sentry implementation, battery thresholds, parking duration values, and MCU commands are retained without new keep-awake or power commands. Their parking activation path is disabled. Recording and remote live viewing while the vehicle is off are therefore unavailable in this release. Disabling parking is a workaround for testing the other features; it does not prove the cause of the previous factory resets.

At ACC-on, the added code starts the server from the factory DVR's background service, observes its ignition broadcast, checks for a missing service every 10 seconds, retries Tailscale every 20 seconds while its VPN interface is missing, and performs non-waking startup recovery checks through 180 seconds. Custom recovery work is canceled at ACC-off.

The phone viewer retries dropped streams and isolates each session so an old request cannot close a newer connection. Its connection message follows received camera frames.

## Updates

Use **LINK → Check updates** on the radio or **Settings → Check updates** on the phone. Each app reads the latest regular GitHub Release and selects its own APK:

- `ATOTO-S8-Enhanced-Stock-DVR-*.apk` for the radio;
- `Camera-Link-Viewer-*.apk` for phones and tablets.

Before Android's installer opens, the updater checks the package name, version, and signing certificate. Android still displays its normal update confirmation.

## Building from source

The phone/tablet viewer can be built on Windows with Android SDK build tools, PowerShell, and Android Studio's bundled JBR:

```powershell
./build.ps1
```

The script verifies the pinned ZXing and official Tailscale dependencies, compiles the source, signs the viewer, and writes `Camera-Link-Viewer-v2.0.4.apk`.

The radio build is an additive patch applied to a compatible factory DVR APK:

```powershell
./factorypatch/build-factory-patch.ps1
```

Run the offline startup and viewer recovery checks with `./tests/verify-startup.ps1`. They simulate late ignition-state readiness, a missing manifest broadcast, loss of the added service, key-off cancellation, and overlapping viewer requests. After building the radio APK, run `./tests/verify-parking-lock.ps1` to check the forced-off getter/setter and preservation of other native settings. These checks do not replace a real radio ignition-cycle test.

For copyright and security reasons, the repository does not include ATOTO's proprietary base APK, decoded factory code, ATOTO platform private key, release keystore, firmware research files, or generated build output. A radio build therefore requires a legally obtained compatible `factory-hn7-dvr.apk` and matching signing credentials. The published release APK is provided for the tested firmware family.

## Third-party software

The release includes the official universal **Tailscale Android 1.104.1** installer as a separate application. Camera Link does not modify it.

- Tailscale APK SHA-256: `b3a1a81b52d1437769e5570c89a95f5824518a6b376d10c39eb64af213a625b6`
- ZXing Core 3.5.3 SHA-256: `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), `app/assets/TAILSCALE_LICENSE.txt`, and `app/assets/ZXING_LICENSE.txt`.

## License

Project source is available under the [MIT License](LICENSE). ATOTO software and third-party components retain their own licenses.
