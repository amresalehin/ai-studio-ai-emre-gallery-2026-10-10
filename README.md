# Emre Gallery

An advanced, private, local-first Android gallery, media downloader, and visual bookmarking vault built with Kotlin, Jetpack Compose, and Material Design 3.

---

## Key Highlights

- **Photo & Video Gallery**: Smooth, high-performance local media viewing, custom albums, bin/recycle management, and photo map integration using OpenStreetMap.
- **On-Device Intelligence**:
  - **Face Recognition**: On-device InsightFace Buffalo_L SCRFD detector and ArcFace vector embedding clustering powered by ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android`).
  - **OCR (Optical Character Recognition)**: Dual-engine text recognition featuring Native Tesseract 4 Android and Google ML Kit with multilingual script support (Latin, Chinese, Devanagari, Japanese, Korean).
- **Embedded Python Media Downloader**:
  - Embedded Python 3.10 runtime via **Chaquopy** running **yt-dlp** and **gallery-dl**.
  - Concurrent multi-engine discovery across direct HTTP probes, Python extractors, and headless Chromium sessions.
  - Native stream remuxing (DASH A/V track muxing and HLS `.m3u8` conversion) using **FFmpegKit** (`dev.ffmpegkit-maintained:ffmpeg-kit-full`).
  - Android Jetpack **WorkManager** background queue with HTTP `Range` resumable downloads and `MediaStore` synchronization.
- **Pocket Bookmarking Vault**:
  - Visual bookmark manager with an interactive **5:7 3D Card Deck** featuring Tinder-like swipe physics and ambient backdrops.
  - **Pocket Deep Scan**: Hardware-accelerated infinite-scroll web scraping governed by a **30% RAM limit governor** (`RamGovernor`) to prevent mobile renderer crashes.
  - Granular format, dimension, and size filters.
  - Built-in Reader and Browser view with integrated ad blocking (`AdBlockEngine`).

---

## Architectural Documentation

For an in-depth architectural breakdown and competitive comparison against **Snaptube** (media downloader) and **Karakeep / Hoarder** (bookmarking & archival), see:

👉 [**Architecture & Comparison Documentation**](docs/ARCHITECTURE_COMPARISON.md)

---

## Tech Stack

- **UI**: Jetpack Compose, Material Design 3 (M3)
- **Architecture**: MVVM, Kotlin Coroutines, StateFlow
- **Image & Video Loading**: Coil (`io.coil-kt:coil-compose`, `coil-video`)
- **Video Playback**: AndroidX Media3 / ExoPlayer (`media3-exoplayer`, `media3-ui`, `media3-datasource-okhttp`)
- **Background Tasks**: AndroidX WorkManager (`androidx.work:work-runtime-ktx`)
- **Python Engine**: Chaquopy 17.2.0 (Python 3.10 with `yt-dlp` and `gallery-dl`)
- **Multimedia Processing**: FFmpegKit Full 8.1.9 (`dev.ffmpegkit-maintained:ffmpeg-kit-full`)
- **On-Device AI / ML**: ONNX Runtime Android 1.20.0, Tesseract4Android 4.9.0, Google ML Kit Text Recognition 16.0.1
- **Networking & Scraping**: OkHttp 4.12.0, JSoup 1.18.3, Android WebView Chromium
- **Mapping**: OSMDroid 6.1.20
- **Storage**: Android Jetpack DataStore Preferences, SharedPreferences, App-Private JSON
