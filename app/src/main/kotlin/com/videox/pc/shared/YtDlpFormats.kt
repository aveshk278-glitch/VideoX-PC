package com.videox.pc.shared

/**
 * Parses the JSON printed by `yt-dlp --dump-single-json --skip-download`
 * into [YtVideoInfo]. Hand-rolled, zero dependencies — the same approach
 * as [YtDlpJson], which only reads language/subtitle fields.
 *
 * Only the fields VideoX needs are read: the top-level title, uploader
 * and duration, plus each entry of the top-level "formats" array
 * (format_id, ext, width, height, vcodec, acodec, filesize,
 * filesize_approx, format_note). Nested values (fragments, headers,
 * thumbnails) are skipped, never parsed. A missing or malformed formats
 * array yields an empty format list rather than an exception — the
 * caller decides whether that means "no formats" or an error.
 */
object YtDlpFormats {

    fun parse(json: String?): YtVideoInfo {
        val text = json?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("{")) {
            return YtVideoInfo(title = "", uploader = "", durationSeconds = 0L, formats = emptyList())
        }
        val cursor = Cursor(text)
        val title = cursor.findTopLevelString("title").orEmpty()
        val uploader = cursor.findTopLevelString("uploader").orEmpty()
        val duration = cursor.findTopLevelNumber("duration")?.toLong() ?: 0L
        val formats = cursor.findTopLevelFormats()
        return YtVideoInfo(title, uploader, duration, sortFormats(formats))
    }

    /** Highest resolution first, so the best quality is never buried. */
    private fun sortFormats(formats: List<YtFormat>): List<YtFormat> =
        formats.sortedWith(
            compareByDescending<YtFormat> { it.height }
                .thenByDescending { !it.isVideoOnly }
        )

    internal fun buildFormat(
        formatId: String,
        ext: String,
        width: Int,
        height: Int,
        vcodec: String,
        acodec: String,
        fileSize: Long,
        fileSizeApprox: Long,
        formatNote: String
    ): YtFormat {
        val resolution = if (width > 0 && height > 0) "${width}x$height" else ""
        val isAudioOnly = vcodec == "none" && acodec != "none"
        val isVideoOnly = acodec == "none" && vcodec != "none"
        val label = buildString {
            append(if (isAudioOnly) "Audio" else if (isVideoOnly) "Video only" else "Video")
            if (resolution.isNotEmpty()) append(" • $resolution")
            if (ext.isNotEmpty()) append(" • $ext")
            if (formatNote.isNotBlank()) append(" • $formatNote")
        }
        return YtFormat(
            formatId = formatId,
            ext = ext,
            resolution = resolution,
            height = height,
            label = label,
            fileSize = if (fileSize > 0) fileSize else fileSizeApprox,
            isAudioOnly = isAudioOnly,
            isVideoOnly = isVideoOnly,
            vcodec = vcodec,
            acodec = acodec
        )
    }

    /**
     * Minimal cursor over one JSON object. It only understands the
     * outermost object and the shape of the "formats" array; everything
     * else is skipped by balancing brackets or reading one literal.
     */
    private class Cursor(private val text: String) {

        fun findTopLevelString(key: String): String? {
            val start = findTopLevelValueStart(key) ?: return null
            val pos = skipWhitespace(start)
            if (pos >= text.length || text[pos] != '"') return null
            return readString(pos)?.first
        }

        fun findTopLevelNumber(key: String): Double? {
            val start = findTopLevelValueStart(key) ?: return null
            val pos = skipWhitespace(start)
            if (pos >= text.length) return null
            var i = pos
            while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == '-' || text[i] == '+' || text[i] == 'e' || text[i] == 'E')) i++
            if (i == pos) return null
            return text.substring(pos, i).toDoubleOrNull()
        }

        fun findTopLevelFormats(): List<YtFormat> {
            val start = findTopLevelValueStart("formats") ?: return emptyList()
            var pos = skipWhitespace(start)
            if (pos >= text.length || text[pos] != '[') return emptyList()
            pos++
            val out = mutableListOf<YtFormat>()
            while (true) {
                pos = skipWhitespace(pos)
                if (pos >= text.length) return out
                when (text[pos]) {
                    ']' -> return out
                    ',' -> pos++
                    '{' -> {
                        val parsed = readFormatObject(pos)
                        if (parsed != null) {
                            out.add(parsed.first)
                            pos = parsed.second
                        } else {
                            return out
                        }
                    }
                    else -> return out
                }
            }
        }

        /** Read one format object; returns the format and the position after '}'. */
        private fun readFormatObject(from: Int): Pair<YtFormat, Int>? {
            var pos = from + 1
            var formatId: String? = null
            var ext = ""
            var width = 0
            var height = 0
            var vcodec = ""
            var acodec = ""
            var fileSize = 0L
            var fileSizeApprox = 0L
            var formatNote = ""
            while (true) {
                pos = skipWhitespace(pos)
                if (pos >= text.length) return null
                if (text[pos] == '}') {
                    val id = formatId ?: return null
                    return buildFormat(id, ext, width, height, vcodec, acodec, fileSize, fileSizeApprox, formatNote) to (pos + 1)
                }
                if (text[pos] == ',') {
                    pos++
                    continue
                }
                if (text[pos] != '"') return null
                val keyRead = readString(pos) ?: return null
                val key = keyRead.first
                pos = skipWhitespace(keyRead.second)
                if (pos >= text.length || text[pos] != ':') return null
                pos = skipWhitespace(pos + 1)
                when (key) {
                    "format_id" -> {
                        val v = readStringOrNull(pos) ?: return null
                        formatId = v.first
                        pos = v.second
                    }
                    "ext" -> {
                        val v = readStringOrNull(pos) ?: return null
                        ext = v.first.orEmpty()
                        pos = v.second
                    }
                    "vcodec" -> {
                        val v = readStringOrNull(pos) ?: return null
                        vcodec = v.first.orEmpty()
                        pos = v.second
                    }
                    "acodec" -> {
                        val v = readStringOrNull(pos) ?: return null
                        acodec = v.first.orEmpty()
                        pos = v.second
                    }
                    "format_note" -> {
                        val v = readStringOrNull(pos) ?: return null
                        formatNote = v.first.orEmpty()
                        pos = v.second
                    }
                    "width" -> {
                        val v = readNumberOrNull(pos) ?: return null
                        width = v.first?.toInt() ?: 0
                        pos = v.second
                    }
                    "height" -> {
                        val v = readNumberOrNull(pos) ?: return null
                        height = v.first?.toInt() ?: 0
                        pos = v.second
                    }
                    "filesize" -> {
                        val v = readNumberOrNull(pos) ?: return null
                        fileSize = v.first?.toLong() ?: 0L
                        pos = v.second
                    }
                    "filesize_approx" -> {
                        val v = readNumberOrNull(pos) ?: return null
                        fileSizeApprox = v.first?.toLong() ?: 0L
                        pos = v.second
                    }
                    else -> {
                        pos = skipValue(pos) ?: return null
                    }
                }
            }
        }

        /** String value, or null when the JSON value is null. */
        private fun readStringOrNull(from: Int): Pair<String?, Int>? {
            if (text.startsWith("null", from)) return null to (from + 4)
            if (from >= text.length || text[from] != '"') return null
            return readString(from)
        }

        /** Number value, or null when the JSON value is null. */
        private fun readNumberOrNull(from: Int): Pair<Double?, Int>? {
            if (text.startsWith("null", from)) return null to (from + 4)
            var i = from
            while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == '-' || text[i] == '+' || text[i] == 'e' || text[i] == 'E')) i++
            if (i == from) return null
            return text.substring(from, i).toDoubleOrNull() to i
        }

        /** Position just after the ':' of a depth-1 member named [key]. */
        private fun findTopLevelValueStart(key: String): Int? {
            var depth = 0
            var i = 0
            while (i < text.length) {
                when (val c = text[i]) {
                    '{', '[' -> {
                        depth++
                        i++
                    }
                    '}', ']' -> {
                        depth--
                        i++
                    }
                    '"' -> {
                        val str = readString(i) ?: return null
                        if (depth == 1 && str.first == key) {
                            var j = skipWhitespace(str.second)
                            if (j < text.length && text[j] == ':') return j + 1
                        }
                        i = str.second
                    }
                    else -> i++
                }
                if (depth < 0) return null
            }
            return null
        }

        /** Read a JSON string starting at the opening quote; returns value + position after closing quote. */
        private fun readString(from: Int): Pair<String, Int>? {
            if (from >= text.length || text[from] != '"') return null
            val out = StringBuilder()
            var i = from + 1
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '"' -> return out.toString() to (i + 1)
                    c == '\\' && i + 1 < text.length -> {
                        when (val esc = text[i + 1]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 5 >= text.length) return null
                                val code = text.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> out.append(esc)
                        }
                        i += 2
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return null
        }

        /** Skip one JSON value; returns the position after it. */
        private fun skipValue(from: Int): Int? {
            val pos = skipWhitespace(from)
            if (pos >= text.length) return null
            return when (text[pos]) {
                '"' -> readString(pos)?.second
                '{', '[' -> skipBalanced(pos)
                else -> skipLiteral(pos)
            }
        }

        private fun skipBalanced(from: Int): Int? {
            val open = text[from]
            val close = if (open == '{') '}' else ']'
            var depth = 0
            var i = from
            while (i < text.length) {
                when (val c = text[i]) {
                    '"' -> {
                        i = readString(i)?.second ?: return null
                    }
                    open -> {
                        depth++
                        i++
                    }
                    close -> {
                        depth--
                        i++
                        if (depth == 0) return i
                    }
                    else -> i++
                }
            }
            return null
        }

        private fun skipLiteral(from: Int): Int? {
            var i = from
            while (i < text.length && text[i] != ',' && text[i] != '}' && text[i] != ']') i++
            return if (i == from) null else i
        }

        private fun skipWhitespace(from: Int): Int {
            var i = from
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }
    }
}
