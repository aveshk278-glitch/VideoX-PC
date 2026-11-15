package com.videox.pc.store

import java.io.File

data class HistoryItem(
    val id: String,
    val url: String,
    val title: String,
    val fileName: String,
    val fileSize: Long,
    val status: String,
    val kind: String,
    val timestamp: Long
)

/**
 * Download history stored as a JSON array in
 * %APPDATA%/VideoX/history.json — the same hand-rolled JSON approach
 * as the Android app's UpscaleHistory (no database, no new dependency;
 * Room's JVM driver is alpha, so JSON is the v1 choice).
 *
 * Newest first, capped at 200 entries. A corrupt file yields an empty
 * history rather than a crash; the next save repairs it.
 */
class JsonHistory(
    private val file: File = defaultFile()
) {
    private val lock = Any()

    fun all(): List<HistoryItem> = synchronized(lock) { loadLocked() }

    fun add(item: HistoryItem) {
        synchronized(lock) {
            val items = (listOf(item) + loadLocked())
                .distinctBy { it.id }
                .take(MAX_ITEMS)
            saveLocked(items)
        }
    }

    fun update(item: HistoryItem) {
        synchronized(lock) {
            val items = loadLocked().map { if (it.id == item.id) item else it }
            saveLocked(items)
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            saveLocked(loadLocked().filterNot { it.id == id })
        }
    }

    fun clear() {
        synchronized(lock) {
            saveLocked(emptyList())
        }
    }

    fun encode(items: List<HistoryItem>): String {
        val sb = StringBuilder("[")
        items.forEachIndexed { index, item ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":").append(quoted(item.id)).append(',')
            sb.append("\"url\":").append(quoted(item.url)).append(',')
            sb.append("\"title\":").append(quoted(item.title)).append(',')
            sb.append("\"fileName\":").append(quoted(item.fileName)).append(',')
            sb.append("\"fileSize\":").append(item.fileSize).append(',')
            sb.append("\"status\":").append(quoted(item.status)).append(',')
            sb.append("\"kind\":").append(quoted(item.kind)).append(',')
            sb.append("\"timestamp\":").append(item.timestamp)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    fun decode(stored: String): List<HistoryItem> {
        val text = stored.trim()
        if (text.isEmpty() || text == "[]") return emptyList()
        if (!text.startsWith("[")) return emptyList()
        val items = mutableListOf<HistoryItem>()
        var i = 1
        while (i < text.length) {
            while (i < text.length && text[i] != '{' && text[i] != ']') i++
            if (i >= text.length || text[i] == ']') break
            val end = findObjectEnd(text, i) ?: break
            parseObject(text.substring(i + 1, end))?.let { items.add(it) }
            i = end + 1
        }
        return items
    }

    private fun loadLocked(): List<HistoryItem> {
        return try {
            if (!file.isFile) emptyList() else decode(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveLocked(items: List<HistoryItem>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(encode(items))
        } catch (_: Exception) {
            // History lives in memory for this run; a failed save must
            // never crash the app.
        }
    }

    private fun findObjectEnd(text: String, open: Int): Int? {
        var depth = 0
        var inString = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

    private fun parseObject(body: String): HistoryItem? {
        val fields = splitFields(body)
        val map = mutableMapOf<String, String>()
        for (field in fields) {
            val colon = indexOfColonOutsideString(field) ?: continue
            val key = unquote(field.substring(0, colon).trim()) ?: continue
            map[key] = field.substring(colon + 1).trim()
        }
        val id = map["id"]?.let { unquote(it) } ?: return null
        val url = map["url"]?.let { unquote(it) } ?: ""
        val title = map["title"]?.let { unquote(it) } ?: ""
        val fileName = map["fileName"]?.let { unquote(it) } ?: ""
        val fileSize = map["fileSize"]?.toLongOrNull() ?: 0L
        val status = map["status"]?.let { unquote(it) } ?: ""
        val kind = map["kind"]?.let { unquote(it) } ?: ""
        val timestamp = map["timestamp"]?.toLongOrNull() ?: 0L
        return HistoryItem(id, url, title, fileName, fileSize, status, kind, timestamp)
    }

    private fun splitFields(body: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        var start = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                    ',' -> if (depth == 0) {
                        out.add(body.substring(start, i))
                        start = i + 1
                    }
                }
            }
            i++
        }
        if (start < body.length) out.add(body.substring(start))
        return out
    }

    private fun indexOfColonOutsideString(field: String): Int? {
        var inString = false
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else if (c == ':') {
                return i
            }
            i++
        }
        return null
    }

    private fun quoted(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun unquote(raw: String): String? {
        val text = raw.trim()
        if (text.length < 2 || !text.startsWith("\"") || !text.endsWith("\"")) return null
        val body = text.substring(1, text.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (body[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(body[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    companion object {
        const val MAX_ITEMS = 200

        fun defaultFile(): File {
            val appData = System.getenv("APPDATA")
                ?: File(System.getProperty("user.home"), "AppData/Roaming").absolutePath
            return File(appData, "VideoX/history.json")
        }
    }
}
