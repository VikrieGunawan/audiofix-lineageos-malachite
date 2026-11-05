# AudioFix

A lightweight Xposed/LSPosed module designed to resolve audio latency, volume drop, and stuck ducking issues across AOSP and Custom ROMs for Redmi Note 14 Pro 5G (malachite).

## Features

- **Anti-Ducking & Volume Preservation**: Suppresses server-side ducking and fading in `system_server` (`PlaybackActivityMonitor` and `MediaFocusControl`), preventing unwanted audio volume drops during notifications or background events.
- **Low-Latency Optimization**: Replaces deep-buffer flags (`FLAG_DEEP_BUFFER`) with low-latency flags (`FLAG_LOW_LATENCY`) and switches media usage attributes to game usage (`USAGE_GAME`) for faster audio response.
- **Glitch-Free Buffer Clamping**: Automatically sizes active audio buffers (1024-1280 frames) to eliminate audio delays without causing buffer underruns or crackling.
- **Communication Mode Protection**: Prevents game engines from improperly setting audio mode to `MODE_IN_COMMUNICATION`, preserving full media quality.

## Compatibility

- Android 8.0 (API 26) through Android 15+ (API 35)
- Compatible with LSPosed, EdXposed, and standard Xposed frameworks
- Works across all AOSP-based Custom ROMs (LineageOS, PixelOS, crDroid, Evolution X, etc.) and OEM skins

## Usage

1. Install `AudioFix.apk`.
2. Open **LSPosed Manager** and enable the **AudioFix** module.
3. Ensure **System Framework** is selected in the scope (for system-wide volume protection).
4. Select target games or media apps (for low-latency buffer optimization).
5. Restart target apps or reboot your device.

## Building

Open the project in Android Studio, or compile using Gradle:

```bash
./gradlew assembleRelease
```

The compiled APK will be located at:
`app/build/outputs/apk/release/app-release-unsigned.apk`

## License

This project is licensed under the [MIT License](LICENSE).
