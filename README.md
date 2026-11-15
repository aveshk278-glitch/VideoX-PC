# VideoX for Windows

A full Windows desktop version of VideoX: download public videos,
extract their text, and upscale them with on-device AI. Same rules as
the Android app — strictly free, only sources that explicitly permit
downloading, never bypass DRM/paywalls/logins, never collect
passwords, never fake a result.

**Status: Phase 1 (downloader core).** The app downloads videos and
audio with yt-dlp, plus direct media files over HTTP. Text extraction
(Phase 3) and the AI upscaler (Phase 2) are not in this build yet.
Translation is not available in the PC version (ML Kit is
Android-only) — see the honest limits below.

## What Phase 1 does

- Paste a link: VideoX fetches the real title and format list with
  `yt-dlp --dump-single-json`, shows qualities by resolution (Best
  Quality default), and downloads video+audio merged to MP4 — never a
  bare video-only file, never a silent downgrade.
- Audio-only downloads (MP3/M4A/Opus), custom file names, a queue of
  1–3 concurrent downloads, cancel, and download history.
- Direct MP4/WebM links (including extension-less CDN links proven by
  a content-type probe) download over plain HTTP.
- A 360p fallback is offered only as an explicit choice when the full
  quality is blocked — never applied silently.
- Engine self-update: newer yt-dlp releases download to the per-user
  tools folder with SHA-256 verification; the install folder is never
  modified.
- `--selftest`: a headless smoke test CI runs on the packaged app
  (sidecars run, settings/history round-trip, download dir writable).

## Honest limits

- Windows 10+ x64 only. No Windows 7/8, no 32-bit, no ARM64 in v1.
- Unsigned installer: Windows SmartScreen will warn on first run
  (click "More info" then "Run anyway"). A signing certificate costs
  money every year, which the zero-cost rule forbids.
- Translation is unavailable in PC v1.
- No thermal/battery/metered-network features (no desktop equivalents).
- Downloads stop if the app is closed (no background services).
- Upscaler speed/quality and transcription accuracy are unverified
  until a human tests on a real PC.
- The installer is large (bundled JVM + FFmpeg + yt-dlp).
- FFmpeg is GPLv3 (bundled binary + licence + source offer included);
  yt-dlp is Unlicense. See THIRD_PARTY_NOTICES.md.

## Build

Built by GitHub Actions on `windows-latest` (`.github/workflows/
build-windows.yml`): pinned sidecar binaries are downloaded with
SHA-256 verification, then `gradle test packageMsi
createDistributable` runs, then the packaged exe's `--selftest` must
pass, and the MSI is uploaded as the `videox-pc-msi` artifact.

Stack: Kotlin 2.4.21, Compose Multiplatform Desktop 1.12.2 (Material3
alpha 1.12.0-alpha03), Gradle 8.14.3, Temurin 17. Cross-compilation is
not supported — the MSI must be built on Windows.

Local checks that run without a Windows PC: the pure-Kotlin modules
(shared/engine/store/state) compile with kotlinc and their JUnit
suite passes (156 tests). The Compose UI compiles only in CI — the
Actions build is its first compile check.

## Layout

- `app/src/main/kotlin/com/videox/pc/shared/` — pure-Kotlin logic
  copied from the Android app (quality selection, JSON parsing,
  subtitle/transcription text, settings backup). Android is the
  reference implementation; shared-logic fixes are ported by hand.
- `app/src/main/kotlin/com/videox/pc/engine/` — yt-dlp.exe subprocess
  wrapper, FFmpeg/ffprobe runner, direct HTTP downloader, tool lookup.
- `app/src/main/kotlin/com/videox/pc/store/` — JSON settings/history.
- `app/src/main/kotlin/com/videox/pc/state/` — download controller.
- `app/src/main/kotlin/com/videox/pc/ui/` — Compose screens.
- `desktop-resources/` — populated by CI, never committed.

## Licence

The project licence is not yet decided (the Android repo has no
LICENSE file either). Third-party notices are in
THIRD_PARTY_NOTICES.md.
