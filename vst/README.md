# WOB VST Plugin (Windows 64-bit)

Receiver VST2 plugin for digital audio streaming over UDP from Android devices running the **WOB** app.

## Features
- **Ultra-Low Latency:** 25ms elastic jitter buffer tuned for instant audio playback.
- **Dynamic Resampling:** High-precision real-time linear interpolation resampler converting 48,000 Hz (Android hardware) to any DAW host sample rate (e.g., 44,100 Hz, 48,000 Hz, 96,000 Hz) with zero clicks or pitch drift.
- **Auto-Discovery:** Automatically responds to broadcast beacon pings from phones on the local network (UDP Port `58200`).
- **Multi-Client Summing:** Mixes audio from multiple phones simultaneously with clean clipping prevention.
- **Soft Limiter:** Built-in tanh-style soft saturation ceiling to prevent harsh digital clipping.
- **Minimal Native Win32 UI:** Real-time VU meter, client counter, sample rate indicator, and buffer health monitor.

## Build from Source
Requirements:
- Windows 64-bit
- GCC (MinGW-w64 or MSYS2)

To compile:
```cmd
build_vst.bat
```
Output will be generated in `bin\WOB.dll`.

## Installation
1. Copy `WOB.dll` into your VST plugin folder:
   - `C:\Program Files\VSTPlugins\` or
   - `C:\Program Files\REAPER (x64)\Plugins\FX\`
2. In REAPER (or your DAW):
   - Go to `Options` -> `Preferences` -> `VST` -> `Re-scan`.
   - Insert `WOB` as a virtual instrument / effect on any track.
