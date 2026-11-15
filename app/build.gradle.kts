import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // Material3 on Compose Multiplatform Desktop is alpha (1.12.0-alpha03,
    // shipped with CMP 1.12.2). Pinned explicitly; the UI uses only basic
    // composables so alpha drift stays visible at compile time.
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.videox.pc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "VideoX"
            packageVersion = "1.0.0"
            vendor = "Avesh Khan"
            description = "VideoX — download public videos, extract text, upscale with AI."
            copyright = "Copyright 2026 Avesh Khan"
            // Sidecar binaries (yt-dlp.exe, ffmpeg.exe, ffprobe.exe) are
            // downloaded by CI into desktop-resources/windows-x64/ and land
            // in the install dir next to the app.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("desktop-resources"))
            // Do not trim JDK modules in v1: a missing module fails at
            // runtime on the user's PC, far from any log we can read.
            includeAllModules = true
        }
    }
}
