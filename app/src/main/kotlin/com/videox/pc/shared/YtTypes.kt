package com.videox.pc.shared

/**
 * Format and video-info types shared by the desktop engine and the
 * quality-selection logic. Same fields as the Android app's YtFormat /
 * YtVideoInfo so [QualitySelector] works unchanged.
 */
data class YtFormat(
    val formatId: String,
    val ext: String,
    val resolution: String,
    val height: Int,
    val label: String,
    val fileSize: Long,
    val isAudioOnly: Boolean,
    val isVideoOnly: Boolean,
    val vcodec: String,
    val acodec: String
)

data class YtVideoInfo(
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val formats: List<YtFormat>
)
