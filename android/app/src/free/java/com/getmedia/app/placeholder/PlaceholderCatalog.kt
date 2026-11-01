package com.getmedia.app.placeholder

/** 免费版的占位入口（APK 内静态数据，改动需重新构建）。文案不写日期、不承诺。 */
object PlaceholderCatalog {
    val items: List<Placeholder> = listOf(
        Placeholder("多设备同步与云端备份", "把媒体库与播放列表在多台设备间保持一致"),
        Placeholder("云端协助提取", "遇到手机上提取不了的链接时，交给云端协助"),
        Placeholder("博主主页批量提取", "按博主主页一次提取多个作品"),
        Placeholder("定时抓取与订阅", "关注的博主有新作品时自动保存"),
        Placeholder("提取 Agent", "用对话的方式描述想要的内容"),
        Placeholder("AI 视频分析与重建", "对已保存的视频做内容分析与再创作"),
    )
}
