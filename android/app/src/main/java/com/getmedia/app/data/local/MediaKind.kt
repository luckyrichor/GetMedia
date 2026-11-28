package com.getmedia.app.data.local

/** 媒体类型。5a 只产生 AUDIO；5b 并入视频后产生 VIDEO。库里存枚举名，未知值按音频处理。 */
enum class MediaKind {
    AUDIO, VIDEO;

    companion object {
        /**
         * 「类型还不知道」的存储值（空串）。**不是**第三个枚举项：枚举多一项就要改下拉菜单、
         * 导出、播放队列等一长串分支，而这里要的只是"别猜"。
         *
         * 目前只有一个产生点：播放列表成员对不上曲库行时补出来的占位行——云端播放列表只存
         * 平台三元组，不带类型，猜 AUDIO 会让视频条目跑到「音频」分类里（0.1.40 的真实故障）。
         * 标成空串后：媒体库的两个类型页签都不会收录它（`mediaType == kind.name` 比不中），
         * 措辞退化成「项」（`MediaWording.noun` 已按"类型未知"处理），等云端曲库行到了
         * 再按真类型回填。
         */
        const val UNKNOWN_NAME = ""

        fun of(name: String?): MediaKind = entries.firstOrNull { it.name == name } ?: AUDIO
    }
}
