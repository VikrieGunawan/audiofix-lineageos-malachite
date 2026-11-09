# AudioFix

A lightweight Xposed/LSPosed module designed to resolve audio latency, volume drop, ad muting, and stuck ducking issues across AOSP and Custom ROMs.

## Features

- **Anti-Ducking & Volume Preservation**: Suppresses server-side ducking and fading in `system_server` (`PlaybackActivityMonitor` and `MediaFocusControl`), preventing unwanted audio volume drops during notifications or background events.
- **In-Game Ad Focus Protection**: Prevents in-game ad SDKs (Chromium WebView, InMobi, Moloco, etc.) from permanently purging the game from Android's audio focus stack (`FocusRequester`), avoiding audio loss when ads end or are canceled.
- **Unity & Game Audio Recovery**: Automatically detects and blocks `UnityPlayer.nativeMuteMasterAudio(true)`, shields audio focus listeners from negative focus loss, and restores master volume upon returning to the game.
- **Low-Latency Optimization**: Replaces deep-buffer flags (`FLAG_DEEP_BUFFER`) with low-latency flags (`FLAG_LOW_LATENCY`) and switches media usage attributes to game usage (`USAGE_GAME`) for faster audio response.
- **Glitch-Free Buffer Clamping**: Automatically sizes active audio buffers (1024-1280 frames) to eliminate audio delays without causing buffer underruns or crackling.
- **Communication Mode Protection**: Prevents game engines from improperly setting audio mode to `MODE_IN_COMMUNICATION`, preserving full media quality.

## Changelog

### Version 1.0.1 (2)

Fixed:
- Fixed audio disappearing or failing to resume when turning the device screen off and on.
- Basic audio focus listener shielding and audio track state recovery on activity resume.
- Preserved low-latency buffer sizing (1024–1280 frames) and game AudioAttributes flags.

> [!WARNING]
> **Important Note**:
> Do not force-exit or minimize affected applications (for example, *Subway Surfers City*) while an ad is actively playing and then re-enter the application to skip/dismiss the ad. Doing so will cause the game's audio to suddenly duck / dim. While certain applications may handle this action normally, affected games/apps will suffer sudden volume drop.

## Compatibility

- Android 8.0 (API 26) through Android 15+ (API 35)
- Compatible with LSPosed, EdXposed, and standard Xposed frameworks
- Works across all AOSP-based Custom ROMs (LineageOS, PixelOS, crDroid, Evolution X, etc.) and OEM skins

## Usage

1. Install `AudioFix.apk`.
2. Open **LSPosed Manager** and enable the **AudioFix** module.
3. Ensure **System Framework** (`android`, `system`) is selected in the scope (for system-wide volume protection).
4. Select target games or media apps (for low-latency buffer optimization and Unity protection).
5. Reboot your device to apply system framework hooks.

## Building

Open the project in Android Studio, or compile using Gradle:

```bash
./gradlew assembleRelease
```

The compiled APK will be located at:
`app/build/outputs/apk/release/app-release-unsigned.apk`

## License

This project is licensed under the [MIT License](LICENSE).
