# Preload

A simple native Android video downloader built with Kotlin and Jetpack Compose.

## Current development status

- URL input and clipboard paste
- Android Share integration
- yt-dlp metadata analysis
- Format detection and selection
- Experimental downloading of already-combined video and audio formats

## Planned

- Reliable saving to the public Downloads folder
- Audio-only downloads
- High-quality video and audio merging
- Improved download management

## Technology

- Kotlin
- Jetpack Compose
- Material 3
- youtubedl-android / yt-dlp

> Only download media that you have permission or the legal right to download.

## Build on Windows

Install JDK 17 and Android SDK Platform 37 with Build Tools 36.0.0. Set
`JAVA_HOME` to your JDK directory and set `sdk.dir` in the ignored
`local.properties` file to your Android SDK directory.

```powershell
.\gradlew.bat clean assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
The Gradle Wrapper downloads the pinned Gradle distribution; no global Gradle
installation or Android Studio is needed.
