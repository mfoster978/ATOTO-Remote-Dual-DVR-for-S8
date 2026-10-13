# ATOTO Remote Dual DVR for S8 v1.5

Version 1.5 introduces signed in-app updates for both Radio and Phone modes. Use **Check for updates** at the bottom of the side menu or open the App updates card in Settings. Camera Link verifies the package name, newer version code, and signing certificate before opening Android's update screen.

This release also makes the ATOTO factory status-bar REC indicator self-correcting. While an encoder is active, Camera Link reconciles the factory indicator every five seconds so it recovers after a missed broadcast, application replacement, or SystemUI restart.

## Tested radio

- ATOTO S8
- Android model/device: `tb8768p1_64_bsp`
- Android 13 / API 33
- ATOTO build: `HN7_2026.07.17.16.49`
- MediaTek MT6765
- Physical front DVR camera installed and verified

The test vehicle does not currently have a physical reverse camera installed. Verify each installed camera before enabling its automatic recording. Long parked, key-off remote access also depends on radio firmware, vehicle wiring, mobile-data state, and Tailscale remaining active.

## Install

Download `ATOTO-Remote-Dual-DVR-for-S8-v1.5.apk`, install it on the radio and phone, and choose the appropriate role on first launch. Existing Camera Link installations can use Android's normal update flow and retain their settings.

The release APK includes the separate official Tailscale Android 1.104.1 installer.

SHA-256: `129639e6951b181392dafd3e8dd041af6af52e411f82d5a6381af663116d3bd5`
