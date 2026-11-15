package com.videox.pc.store

import com.videox.pc.shared.QualitySelector
import com.videox.pc.shared.SettingsBackup
import java.io.File

/**
 * Settings stored as a flat JSON object of string keys/values in
 * %APPDATA%/VideoX/settings.json (same keys and defaults as the
 * Android app, minus the settings with no desktop equivalent:
 * wifi_only, show_notifications and warn_metered are dropped, not
 * faked — see the plan's port map).
 *
 * Values are validated on write: max_concurrent stays in 1..3,
 * subtitle_langs is sanitised, and unknown keys from a backup file
 * are ignored. A corrupt file falls back to defaults rather than
 * crashing the app; the next save repairs it.
 */
class JsonSettings(
    private val file: File = defaultFile()
) {
    private val lock = Any()
    private var values: MutableMap<String, String> = loadLocked()

    var darkTheme: Boolean
        get() = getBoolean("dark_theme", true)
        set(value) = put("dark_theme", value.toString())

    var maxConcurrentDownloads: Int
        get() = getString("max_concurrent", "2")?.toIntOrNull()?.coerceIn(1, 3) ?: 2
        set(value) = put("max_concurrent", value.coerceIn(1, 3).toString())

    var proxyUrl: String
        get() = getString("proxy_url", "") ?: ""
        set(value) = put("proxy_url", value.trim())

    var subtitlesEnabled: Boolean
        get() = getBoolean("subtitles_enabled", false)
        set(value) = put("subtitles_enabled", value.toString())

    var subtitleLangs: String
        get() = getString("subtitle_langs", "en") ?: "en"
        set(value) = put("subtitle_langs", QualitySelector.sanitizeSubtitleLangs(value))

    var embedChapters: Boolean
        get() = getBoolean("embed_chapters", false)
        set(value) = put("embed_chapters", value.toString())

    var transcribeFallback: Boolean
        get() = getBoolean("transcribe_fallback", true)
        set(value) = put("transcribe_fallback", value.toString())

    var extractionLanguage: String
        get() = getString("extraction_language", "auto") ?: "auto"
        set(value) = put("extraction_language", value)

    var translateEnabled: Boolean
        get() = getBoolean("translate_enabled", false)
        set(value) = put("translate_enabled", value.toString())

    var translateTargetLanguage: String
        get() = getString("translate_target", "en") ?: "en"
        set(value) = put("translate_target", value)

    /** Upscaler provider (Phase 2): "auto" or a provider id. Kept for forward compatibility. */
    var upscaleProvider: String
        get() = getString("upscale_provider", "auto") ?: "auto"
        set(value) = put("upscale_provider", value)

    /** Upscaler mode (Phase 2): "performance" or "efficiency". */
    var upscaleMode: String
        get() = getString("upscale_mode", "performance") ?: "performance"
        set(value) = put("upscale_mode", value)

    /** Last on-device benchmark, JSON-encoded (Phase 2). */
    var upscaleBenchmark: String
        get() = getString("upscale_benchmark", "") ?: ""
        set(value) = put("upscale_benchmark", value)

    /** Encode all settings for backup (same format as the Android backup). */
    fun exportBackup(): String = synchronized(lock) {
        SettingsBackup.encode(values.toMap())
    }

    /**
     * Restore settings from a backup string. Returns false when the
     * text is not a valid backup; unknown keys are ignored and every
     * restored value is re-validated through the property setters.
     */
    fun importBackup(json: String): Boolean {
        val decoded = SettingsBackup.decode(json) ?: return false
        synchronized(lock) {
            decoded["dark_theme"]?.toBooleanStrictOrNull()?.let { darkTheme = it }
            decoded["max_concurrent"]?.toIntOrNull()?.let { maxConcurrentDownloads = it }
            decoded["proxy_url"]?.let { proxyUrl = it }
            decoded["subtitles_enabled"]?.toBooleanStrictOrNull()?.let { subtitlesEnabled = it }
            decoded["subtitle_langs"]?.let { subtitleLangs = it }
            decoded["embed_chapters"]?.toBooleanStrictOrNull()?.let { embedChapters = it }
            decoded["transcribe_fallback"]?.toBooleanStrictOrNull()?.let { transcribeFallback = it }
            decoded["extraction_language"]?.let { extractionLanguage = it }
            decoded["translate_enabled"]?.toBooleanStrictOrNull()?.let { translateEnabled = it }
            decoded["translate_target"]?.let { translateTargetLanguage = it }
            decoded["upscale_provider"]?.let { upscaleProvider = it }
            decoded["upscale_mode"]?.let { upscaleMode = it }
            decoded["upscale_benchmark"]?.let { upscaleBenchmark = it }
        }
        return true
    }

    private fun getBoolean(key: String, default: Boolean): Boolean =
        synchronized(lock) { values[key]?.toBooleanStrictOrNull() ?: default }

    private fun getString(key: String, default: String): String? =
        synchronized(lock) { values[key] ?: default }

    private fun put(key: String, value: String) {
        synchronized(lock) {
            values[key] = value
            saveLocked()
        }
    }

    private fun loadLocked(): MutableMap<String, String> {
        return try {
            if (!file.isFile) return mutableMapOf()
            SettingsBackup.decode(file.readText())?.toMutableMap() ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveLocked() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(SettingsBackup.encode(values.toMap()))
        } catch (_: Exception) {
            // Settings live in memory for this run; a failed save must
            // never crash the app. The next successful save repairs it.
        }
    }

    companion object {
        fun defaultFile(): File {
            val appData = System.getenv("APPDATA")
                ?: File(System.getProperty("user.home"), "AppData/Roaming").absolutePath
            return File(appData, "VideoX/settings.json")
        }
    }
}
