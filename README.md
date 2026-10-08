# Xyether HandBrake

A video compressor for Android, made by xyether. Add your clips, pick a size preset, and tap **Compress**. You can queue several videos at once.

[Download the APK](https://github.com/XYETHER/Xyether-HandBrake/releases/latest)

## Choosing your settings

| Preset | Maximum height | Video bitrate |
| --- | --- | --- |
| Balanced | 1080p | 5 Mbps |
| Small | 720p | 2.5 Mbps |
| Tiny | 480p | 1 Mbps |

Open **More options** to set your own bitrate, resolution, frame rate, or encoder. A higher bitrate gives the encoder more room to keep detail, but makes a larger file. H.265 availability and encoding speed controls depend on your phone's hardware.

Keep the app open while it compresses. Save or share the finished videos afterward. Save copies you want to keep before uninstalling; Android removes the app's private exports when you uninstall it.

## Supported videos

Requires Android 8.0 or newer. Processing runs locally through Android MediaCodec.

The app exports MP4 and keeps the first supported audio track. It doesn't include subtitles or extra tracks. Resolution limits only shrink videos, and frame-rate limits drop frames. HDR input isn't supported yet.

Still in development. If a video fails or gets stuck, [open an issue](https://github.com/XYETHER/Xyether-HandBrake/issues) with your phone model, Android version, and settings.

## Building

Open the project in Android Studio with JDK 17+ and Android SDK 34, or run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On Windows, use `gradlew.bat`. Release builds are unsigned unless you supply a signing key.

Uses Kotlin, Jetpack Compose, Android MediaCodec, OpenGL ES, and the [Inter](https://github.com/rsms/inter) font. Licensed under [MIT](LICENSE); see [third-party notices](THIRD_PARTY_NOTICES.md).

This app is independent of the HandBrake project.
