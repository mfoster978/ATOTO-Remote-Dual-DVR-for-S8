# ATOTO Remote Dual DVR for S8

ATOTO Remote Dual DVR for S8 extends the factory DVR on compatible ATOTO S8 radios and adds a separate Android viewer for phones and tablets.

The release contains two APKs:

- **ATOTO S8 Enhanced Stock DVR** updates the existing `com.hcn.dvr` factory app. It keeps ATOTO's native live camera screen, recorder, video player, settings, parking controls, and hardware integration, then adds reverse-camera recording, remote access, split views, security, and updates.
- **Camera Link Viewer** is the phone/tablet app. It connects through Tailscale for live camera viewing and media playback.

The intended radio setup is one factory DVR update plus one new app, Tailscale. Camera Link is no longer installed as a second DVR app on the radio.

> [!IMPORTANT]
> Remote live viewing while the vehicle is off is **not working yet**. On the tested radio, ATOTO's factory parking mode continues recording when it keeps the DVR powered, but Tailscale and the remote camera connection currently go offline after ACC-off. A safe solution is still being investigated. Version 2.0.0 leaves ATOTO's parking, battery cutoff, and MCU behavior unchanged.

> [!CAUTION]
> Do not install the withdrawn v1.5 or v1.6 experiments. Those versions attempted to manage S8 ACC-off behavior outside the factory DVR and caused factory resets during testing. Version 2.0.0 removes that approach and never writes the vendor parking flag or MCU keep-awake heartbeat.

This is an independent community project. It is not made, supported, or endorsed by ATOTO or Tailscale.

## Why this is more useful than the factory S8 DVR

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
| Vehicle-off recording | Factory parking mode | Factory parking mode remains in control and records on the tested radio |
| Vehicle-off live view | Not available | Not working yet |

## Added features

- Remote live Front, DVR Camera 2, Reverse, and split-camera views through a private Tailscale connection.
- Continuous reverse-camera H.264 MP4 recording alongside whichever shared DVR input ATOTO is recording.
- Reverse clips in the factory DVR recordings screen with an **R** badge and native playback.
- **REVERSE**, **SPLIT**, and **LINK** controls over the native DVR camera view.
- Split view cycle: top/bottom, left/right, then off.
- The factory camera button remains responsible for switching Front and DVR Camera 2.
- Temporary remote Front/DVR2 selection, followed by restoration of the radio's saved input when remote viewing ends.
- Optional cabin audio. The radio microphone switch remains the master privacy control for recordings and remote sound.
- Setup QR, app connection PIN, browser username/password, and named vehicle profiles.
- Optional owner PIN. One successful unlock opens every protected factory DVR and Camera Link screen for 10 minutes. Screen changes and briefly leaving the app do not ask again; the timer or a radio restart relocks it. Live camera viewing and the factory emergency-lock button remain available while locked.
- Automatic recording recovery when the stock DVR opens before its camera signal state is initialized.
- Signed one-click update checks for both APKs through GitHub Releases.

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

Version 2.0.0 was built and installed on this unit:

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

1. Download **`ATOTO-S8-Enhanced-Stock-DVR-v2.0.0.apk`** from the latest GitHub Release.
2. Install it on the radio as an update to the existing ATOTO DVR. Do not uninstall the factory DVR first; the package data and original app identity must remain available.
3. Open **DVR**. The familiar ATOTO camera screen and settings remain in place.
4. Tap **LINK** to open the added side menu.
5. Install the included Tailscale APK, sign in, and connect the radio to the same tailnet used by the phone.
6. Open **Phone connection & QR** and leave the QR code visible for phone setup.
7. Enable reverse recording only if the reverse camera has a confirmed live picture.
8. Configure parking mode, duration, and battery cutoff in ATOTO's original DVR settings. The added code only reads whether factory parking is armed; it does not change those controls.

Wireless ADB is needed only to install or service the radio APK when the normal package installer is unavailable. It is not required for daily recording, phone viewing, or playback after setup.

The release uses version code `210000` and version name `2.0.0-camera-link` for the enhanced radio package so an older ordinary DVR build is not treated as a newer update.

## Install on the phone or tablet

1. Download **`Camera-Link-Viewer-v2.0.0.apk`** from the same release.
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

ATOTO's factory DVR remains the sole owner of parking mode, parking duration, battery cutoff, and MCU power commands. On the tested radio, parking recording works when the factory parking setting keeps the DVR powered after ACC-off.

Remote live viewing after ACC-off is a separate problem. The tested radio currently takes Tailscale and the Camera Link remote endpoint offline even while the factory parking recorder continues. Version 2.0.0 does not attempt to defeat that power transition. Work on a safe solution will require preserving network access without writing undocumented MCU power state.

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

The script verifies the pinned ZXing and official Tailscale dependencies, compiles the source, signs the viewer, and writes `Camera-Link-Viewer-v2.0.0.apk`.

The radio build is an additive patch applied to a compatible factory DVR APK:

```powershell
./factorypatch/build-factory-patch.ps1
```

For copyright and security reasons, the repository does not include ATOTO's proprietary base APK, decoded factory code, ATOTO platform private key, release keystore, firmware research files, or generated build output. A radio build therefore requires a legally obtained compatible `factory-hn7-dvr.apk` and matching signing credentials. The published release APK is provided for the tested firmware family.

## Third-party software

The release includes the official universal **Tailscale Android 1.104.1** installer as a separate application. Camera Link does not modify it.

- Tailscale APK SHA-256: `b3a1a81b52d1437769e5570c89a95f5824518a6b376d10c39eb64af213a625b6`
- ZXing Core 3.5.3 SHA-256: `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), `app/assets/TAILSCALE_LICENSE.txt`, and `app/assets/ZXING_LICENSE.txt`.

## License

Project source is available under the [MIT License](LICENSE). ATOTO software and third-party components retain their own licenses.
