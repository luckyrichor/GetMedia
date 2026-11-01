package com.getmedia.app.data.local

/** 媒体类型。5a 只产生 AUDIO；5b 并入视频后产生 VIDEO。库里存枚举名，未知值按音频处理。 */
enum class MediaKind {
    AUDIO, VIDEO;

    companion object {
        fun of(name: String?): MediaKind = entries.firstOrNull { it.name == name } ?: AUDIO
    }
}
