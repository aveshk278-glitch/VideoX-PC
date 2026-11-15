Sidecar binaries (yt-dlp.exe, ffmpeg.exe, ffprobe.exe) are downloaded
by the CI workflow into `windows-x64/` with pinned versions and SHA-256
verification. Never commit binaries here — the `.gitignore` excludes
`desktop-resources/windows-x64/`.

At packaging time, the Compose plugin copies this directory into the
install image, and the app finds the tools via the
`compose.application.resources.dir` system property (see `Tools.kt`).
