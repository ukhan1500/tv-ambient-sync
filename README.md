# TV Ambient Sync
Phase 1 Android TV Bluetooth diagnostic build. No screen capture or background sync yet.

## Download and install
Open this repository's Actions tab, select the latest successful **Build test APK** run, then download **tv-ambient-sync-debug** under Artifacts while signed in to GitHub. Extract the ZIP.
Transfer app-debug.apk to Google Drive from your iPhone, then use a Google TV file manager that supports your Drive account to download and open it on the onn. device. Allow installation from that file manager when Google TV prompts. Disable that permission afterward. No Android phone is required.

## Hardware test
1. Close Magic Lantern on the iPhone to free the controller connection. Power the strip normally.
2. Open TV Ambient Sync on onn. Google TV, press Scan, grant Nearby devices (or location on older Android), then press Scan again.
3. Select BLEDDM from the results. Record connection status, service UUIDs, characteristic UUIDs and properties.
4. Select a writable characteristic only when ready to test the experimental protocol. Start with FFF3 under FFF0, or FFE1 under FFE0 if present. Unknown characteristics are not automatically written.
5. Press Red, Green, Blue, White, Off, waiting two seconds between commands. Off sends RGB zero; true power-off support is not verified.
6. Report the UUID used, each requested versus observed color, write status, and any disconnections. If nothing changes, stop and report the GATT listing rather than trying every characteristic.
7. Disconnect and reconnect to test repeatability.

The candidate packet is 7E 00 05 03 RR GG BB 00 EF. BLEDDM is a device name, not proof of this protocol. A successful GATT write does not prove command compatibility. All controls work with remote directional navigation. This diagnostic app closes BLE when its activity is destroyed; automatic reconnect and persistent background operation are deferred.

## Research
[LedBridge](https://github.com/bbernalm/LedBridge) documents a BLEDDM controller using the ELK-BLEDOM family, FFF0/FFF3 or FFE0/FFE1 and 7E…EF frames. Its controller uses Lotus Lantern; compatibility with this Magic Lantern controller remains unverified.
[LotusLantern protocol implementation](https://github.com/Rxflex/LotusLantern) is a further research reference. No source code is copied.

## Build
GitHub Actions installs Java 17, Android SDK and Gradle 8.9, builds with AGP 8.7.3 / Kotlin 2.0.21 / SDK 35 and uploads the debug APK. Locally install those tools and run gradle assembleDebug. A Gradle wrapper is not bundled.

## Phase 2 test (v0.2)
Manual RGB and off were confirmed on the user's BLEDDM FFF0/FFF3 controller.
Install the newest debug APK using LocalSend. If installation fails with a signature conflict, uninstall the previous debug build first: CI debug signing keys can change between runs.

Connect to BLEDDM, select the tested FFF3 write characteristic, then press Start Sync and accept Android's screen sharing prompt. Choose entire screen if offered. The foreground service takes over the Bluetooth connection; a temporary disconnect in the activity log is expected. Press Home and navigate colorful Google TV screens or unprotected content. Return and press Sync status for the current sampled RGB / connection state. Stop Sync releases capture and BLE; reconnect and grant fresh capture consent to start again.

Default interval is 250ms with exponential smoothing and 80% brightness. Speed and brightness buttons change the next session. Frames are downsampled to width 160; only average RGB is processed locally and no screenshots are saved. Dark or protected frames produce dark LEDs; black alone cannot identify DRM. The service retries BLE after interruption. Screen capture does not restart automatically after reboot or process death.

Report: Android capture prompt outcome, whether lights change on Home, behavior after opening another app, Sync status text, whether Stop Sync works, and whether LEDs recover after temporarily powering the controller off/on. Hardware validation of capture and background operation is pending.
