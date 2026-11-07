# TV Ambient Sync

An experimental Android TV app that matches a Bluetooth RGB LED strip to colors on your screen. Runs on the streaming device, processes colors locally, and sends commands directly to the LED controller—no lighting server, subscription, or additional capture hardware.

**Current version: 0.6 · Hardware-tested prototype**

## What it does

- Scans for Bluetooth LE controllers and lists their GATT services and writable characteristics.
- Provides manual red, green, blue, white, and off tests.
- Captures the screen with Android's permission-based MediaProjection API.
- Picks a dominant color family by sampled screen percentage, with a five-percentage-point switching margin for near ties.
- Offers fast 50 ms sampling without additional smoothing, or a smoother 250 ms mode. Actual response depends on the device and controller.
- Provides optional color enhancement and a remote-controlled 0–100% brightness slider.
- Saves brightness and the selected controller/characteristic.
- Runs synchronization in a foreground service while other apps are open and retries interrupted BLE connections.
- Records delayed color diagnostics for troubleshooting, without saving screenshots or video.

The whole strip displays one color at a time. Independent left/right/top/bottom zones are not implemented.

## Tested setup and results

Reported by the project owner on November 7, 2026. These are observations from one setup, not general compatibility guarantees.

| Component | Tested hardware |
| --- | --- |
| Streaming device | onn. Google TV 4K Pro |
| Display | TCL Roku TV 50S421 |
| LEDs | Generic USB-powered analog RGB strip |
| BLE controller name | BLEDDM |
| Original controller app | Magic Lantern |
| Tested GATT path | Service FFF0 → characteristic FFF3 |

- Manual RGB colors and off work.
- Screen synchronization works on Google TV menus and SmartTube using **AVC / H.264** playback.
- SmartTube VP9 playback produced nearly uniform dark-gray capture samples (RGB 16,16,16), despite visible colorful video. AVC produced changing blue and warm-color samples on the same setup.
- Returning the onn. to its normal display settings and using AVC resolved the reported playback-start lag. Forcing SDR is not a requirement for the working configuration.
- The owner reports slight LED color changes during **Paramount live UFC**. Accurate capture of the program image has not been verified; changes could include capturable interface elements. Earlier episode playback did not track correctly. Do not interpret this as confirmed Paramount or DRM compatibility.

Codec and rendering paths can affect capture. Choosing AVC helped this device in SmartTube; it is not a universal fix for every app.

## Download and install

1. Open [GitHub Actions](https://github.com/ukhan1500/tv-ambient-sync/actions) and select the latest successful **Build test APK** run.
2. While signed into GitHub, download **tv-ambient-sync-debug** under Artifacts.
3. Extract the ZIP to obtain `app-debug.apk`.
4. Install [LocalSend](https://localsend.org/download) on your iPhone or computer and on the Android TV device. Connect both to the same local network.
5. Send the extracted APK to the TV and accept the transfer.
6. Open the APK on the TV, using a file manager if needed. Allow that app to install unknown apps when prompted, then install.

No Android phone is required. If LocalSend fails when overwriting a previous filename, rename the new APK before sending; this resolved a transfer error in testing. If Android reports a signature conflict, uninstall the older debug build first. This clears its saved settings. CI debug signing keys are not guaranteed to remain the same across runs.

## First connection and synchronization

1. Close Magic Lantern or other apps connected to the strip.
2. Power the strip and open TV Ambient Sync on the Android TV device.
3. Press **Scan**, grant Bluetooth/Nearby devices permission, then scan again.
4. Select your controller. Inspect its service and characteristic list.
5. On the tested controller, select **Use experimental write: 0000fff3-0000-1000-8000-00805f9b34fb** under service FFF0.
6. Test the manual colors before enabling synchronization.
7. Press **Start Sync** and approve Android's screen-sharing prompt. Select the entire screen if offered.
8. Press Home and open your video app.

The foreground service takes over the BLE connection, so a temporary disconnect message in the activity log is expected. After saving the controller once, future sessions can use Start Sync without scanning. Android still requires fresh capture consent for each new session.

Use **Stop Sync** to release capture and BLE. Brightness changes apply during active sync and are saved. Speed and color-enhancement changes apply to the next session.

At 100%, brightness preserves the selected screen color's intensity; it does not normalize every color to maximum LED output. Dark sampled colors therefore remain dim.

## SmartTube configuration

In SmartTube, open **Settings → Video player → Video presets** and choose an AVC preset if available. Alternatively, its documented **None** preset remembers the last player format selection; select an AVC format while playing a video, then verify another video uses it. Availability and fallback depend on the video and app version.

See [SmartTube's preset documentation](https://github.com/yuliskov/SmartTube#which-quality-to-choose).

## Diagnose a video

Opening the app changes the captured screen. Use saved diagnostics to inspect playback instead:

1. Start sync.
2. Press **Record video diagnostics**.
3. Return to your video within 10 seconds.
4. Leave it playing for at least 20 seconds.
5. Return and select **Show saved video diagnostics**.

The app waits 10 seconds, then records 10 seconds of measurements at roughly one sample per second. Results include dominant-group share, selected RGB, output RGB, neutral-pixel share, and BLE readiness. “Raw” RGB is the selected group after optional color enhancement, before brightness scaling. Saved results remain unchanged when returning to the app.

Uniform dark readings alone cannot distinguish a dark scene, protected video, or an unavailable hardware video layer.

## Limitations

- **BLEDDM is an advertised name, not a protocol specification.** Other controllers with that name may be incompatible.
- Tested RGB packet: `7E 00 05 03 RR GG BB 00 EF`. Off sends RGB zero rather than a separately verified power command.
- Screen capture may exclude protected video or particular hardware rendering paths. The app does not bypass DRM.
- Android controls screen-sharing consent and the recording indicator. The app does not hide the indicator or automatically restart capture after reboot/process death.
- Captures only the onn. device's display output, not another HDMI input such as a PS5 or the Roku TV's own apps.
- USB powers the LEDs; it supplies no screen-color data.
- This remains a prototype. A successful build is not evidence of compatibility with untested hardware.

## Build and source

Kotlin, native Android SDK APIs, minimum Android 8 / API 26, target/compile SDK 35.

GitHub Actions installs Java 17, Android SDK packages and Gradle 8.9, builds with AGP 8.7.3 / Kotlin 2.0.21, and uploads a debug APK. To build locally with those tools installed:

```sh
gradle assembleDebug
```

A Gradle wrapper is not currently bundled.

- `MainActivity.kt`: remote interface, BLE discovery/manual testing, capture consent.
- `SyncService.kt`: foreground capture, dominant-color selection, BLE synchronization and diagnostics.

## Feedback

[Open an issue](https://github.com/ukhan1500/tv-ambient-sync/issues) with the device/Android version, app version, controller GATT UUIDs, playback app, codec/resolution, expected versus observed LED behavior, and saved diagnostics if available. Avoid including private device addresses or account details.

## References

[LedBridge](https://github.com/bbernalm/LedBridge) and [LotusLantern](https://github.com/Rxflex/LotusLantern) were protocol research references; no source code was copied.
[Android MediaProjection documentation](https://developer.android.com/media/grow/media-projection) describes capture consent, foreground-service requirements, and lifecycle restrictions.
