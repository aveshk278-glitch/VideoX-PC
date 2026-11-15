# Third-party notices — VideoX for Windows

## Bundled in Phase 1

### yt-dlp (Unlicense — public domain)
Standalone Windows binary `yt-dlp.exe`, downloaded by CI from the
official GitHub releases (pinned 2026.10.26, SHA-256 verified).
Source: https://github.com/yt-dlp/yt-dlp

### FFmpeg (GPLv3)
Static Windows build (release essentials, 9.0.2) from gyan.dev,
downloaded by CI (pinned, SHA-256 verified). FFmpeg is invoked as a
separate process; VideoX's own code is not derived from FFmpeg.
Licence text: the GPLv3 licence ships with the FFmpeg distribution.
Corresponding source is available from https://ffmpeg.org and
https://www.gyan.dev/ffmpeg/builds/ (build sources linked there).
This notice is standard practice, not a legal opinion.

### Kotlin standard library, kotlinx-coroutines, Compose Multiplatform, Skiko (Apache 2.0)
Runtime and UI libraries, resolved from Maven Central by Gradle.
Skiko provides the graphics bindings used by Compose Desktop.

## Planned for later phases (not bundled in Phase 1)

### ONNX Runtime (MIT)
AI inference runtime for the upscaler (Phase 2).
Source: https://github.com/microsoft/onnxruntime

### Vosk (Apache 2.0)
Offline speech recognition for transcription (Phase 3).
Source: https://github.com/alphacep/vosk-api

### Real-ESRGAN model weights (BSD-3-Clause with attribution)
The `realesr-general-x4v3.onnx` model is downloaded at runtime with a
SHA-256 check and is never bundled. Attribution: Real-ESRGAN,
Copyright (c) 2021 Xintao Wang. The weights ship under the BSD-3
licence of the Real-ESRGAN repository, the same basis approved for
the Android app; no separate weights licence exists in that repo.
Source: https://github.com/xinntao/Real-ESRGAN

## Test-only

### JUnit 4.13.2 (Eclipse Public License 1.0)
Used only for unit tests; not shipped in the installer.
