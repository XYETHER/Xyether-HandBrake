# Xyether HandBrake 🎬

A video compressor for Android, made by **xyether**. Pick your clips, choose how small you want them, and compress them on your phone.

**[Download the APK](https://github.com/XYETHER/Xyether-HandBrake/releases/latest)** · [Report a bug](https://github.com/XYETHER/Xyether-HandBrake/issues)

## What you can do

- Compress one video or a whole batch.
- Choose H.264 or supported H.265 encoding.
- Adjust bitrate, resolution, and frame rate.
- Adjust encoding speed when your phone’s encoder supports it.
- Keep the original audio, save your results, or share them.

Everything runs on your device. No account or upload needed.

## Getting started

1. Install the APK from Releases. Android may ask you to allow installation from your browser.
2. Add your videos.
3. Pick **Balanced**, **Small**, or **Tiny**. Open **More options** for the individual settings.
4. Tap **Compress** and keep the app open until it finishes.
5. Save or share the finished videos.

| Preset | Maximum height | Video bitrate |
| --- | --- | --- |
| Balanced | 1080p | 5 Mbps |
| Small | 720p | 2.5 Mbps |
| Tiny | 480p | 1 Mbps |

For better quality, try a higher bitrate. That also makes the file larger. The speed slider depends on your phone; some hardware encoders only offer a fixed speed.

## A few things to know

- Requires **Android 8.0 or newer**.
- Uses your phone’s Android MediaCodec encoder. Speed and codec support vary by device.
- Outputs MP4. It keeps the first supported audio track; subtitles and extra tracks aren’t included.
- Videos aren’t enlarged. A frame-rate cap removes frames rather than adding new ones.
- HDR input isn’t supported yet.
- Save wanted exports before uninstalling; uninstalling removes the app’s private copies.

> 🚧 Still being developed. If a clip fails or gets stuck, open an issue with your phone model, Android version, and the settings you used. Avoid posting private footage.

## Build from source

Open the project in Android Studio with **JDK 17+** and **Android SDK 34**, or run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On Windows, use `gradlew.bat`. Release builds are unsigned unless you supply your own signing key.

## Credits and license

Created by **xyether**. Built with Kotlin, Jetpack Compose, Android MediaCodec, and OpenGL ES. Uses the [Inter](https://github.com/rsms/inter) font.

Licensed under MIT. See [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md).

This is an independent app and is not affiliated with the HandBrake project.
