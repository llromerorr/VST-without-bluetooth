# WOB Android App (Android 10+ / API 29+)

Audio sender application for **WOB (Without Bluetooth)**. Captures pure internal digital audio from any app (YouTube, Spotify, dance rehearsals, etc.) and transmits it over UDP Wi-Fi directly to the WOB VST plugin in your DAW with ultra-low latency.

## Key Features
- **Internal Digital Audio Capture:** Powered by Android 10's official `AudioPlaybackCaptureConfiguration` API.
- **Physical Speaker Muting:** Routes digital PCM directly to the network while leaving the phone's physical hardware speaker silent.
- **Dual Volume Control:**
  1. **In-App Studio Fader (SeekBar):** Smooth 0-100% digital PCM gain slider.
  2. **Notification Quick Actions:** `[-] Bajar` and `[+] Subir` buttons in the persistent background notification bar, allowing volume adjustments on the fly without exiting YouTube or your music app.
- **Zero Configuration:** UDP broadcast beacon on port 58200 auto-locates your DAW running the WOB VST on the local Wi-Fi.
- **Anti-Sleep / Battery Optimization Immunity:**
  - Foreground service with persistent notification.
  - CPU `WakeLock` and `WifiLock.WIFI_MODE_FULL_HIGH_PERF` to ensure rock-solid streaming even with the phone screen locked.
  - Battery optimization whitelist request to prevent Samsung / Android power management from dropping audio packets.

## Build Requirements
- Android SDK Build-Tools (v34.0.0+)
- Android Platform `android-34`
- JDK 8 or JDK 17

To build the APK:
```cmd
build_apk.bat
```
Output will be placed in `bin\WOB.apk`.
