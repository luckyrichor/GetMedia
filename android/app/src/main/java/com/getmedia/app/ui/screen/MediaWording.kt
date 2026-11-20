package com.getmedia.app.ui.screen

import com.getmedia.app.data.local.MediaKind

/**
 * 批量操作与计数里出现的名词：**按所选条目的实际类型取词**，混合或为空时用「项」。
 *
 * 为什么不能跟着顶部的类型开关走：「音频/视频」开关只作用于「媒体库」页；
 * 类别页把音视频放在一起（视频也能「移动到…」进类别），多选还可以跨类型。
 * 文案要是跟着开关，就会出现「删除 5 个音频」而实际删掉的是视频。
 */
internal object MediaWording {

    const val AUDIO = "音频"
    const val VIDEO = "视频"
    const val ITEM = "项"

    /**
     * "音频" / "视频" / "项"。
     *
     * 只要有一条类型未知（null）或两种都有，就退回 [ITEM]——宁可说得笼统，也不说错。
     */
    fun noun(kinds: Collection<String?>): String {
        val set = kinds.toSet()
        val only = set.singleOrNull() ?: return ITEM
        return when (only) {
            MediaKind.AUDIO.name -> AUDIO
            MediaKind.VIDEO.name -> VIDEO
            else -> ITEM
        }
    }

    /**
     * "3 个视频" / "3 个音频" / "3 项"。
     *
     * [music] 为真时音频用「首」（分类卡片、列表计数沿用的量词），其余一律「个」。
     */
    fun counted(kinds: Collection<String?>, count: Int, music: Boolean = false): String {
        val noun = noun(kinds)
        return when {
            noun == ITEM -> "$count $ITEM"
            music && noun == AUDIO -> "$count 首$AUDIO"
            else -> "$count 个$noun"
        }
    }

    /** 批量删除的标题：`删除 5 个视频？` */
    fun batchDeleteTitle(kinds: Collection<String?>, count: Int): String = "删除 ${counted(kinds, count)}？"

    /** 批量删除的说明：只改被删对象的称呼，后半句不变。 */
    fun batchDeleteBody(kinds: Collection<String?>): String {
        val what = when (noun(kinds)) {
            AUDIO -> "音频文件"
            VIDEO -> "视频文件"
            else -> "文件"
        }
        return "将删除手机上的$what，并从所有播放列表及正在播放的队列中移除。"
    }

    /** 删完后的提示：`已删除 5 个视频` */
    fun batchDeleteDone(kinds: Collection<String?>, count: Int): String = "已删除 ${counted(kinds, count)}"

    /**
     * 批量删除里"哪些会动云端"的说明。三种条目分开计数，混选时不笼统说"都删"：
     * - 可移除的：会写云端墓碑，其他设备同步后也看不到；
     * - 云端归档：服务端拒绝写墓碑，云端保留，只删本机；
     * - 仅本机：本来就没绑定云端。
     *
     * 三项都为 0（纯本机）时返回 null，不显示这一行。
     */
    fun batchCloudNote(cloudCount: Int, archiveCount: Int, localOnlyCount: Int): String? {
        val parts = buildList {
            if (cloudCount > 0) add("$cloudCount 项会同时从云端曲库移除（其他设备也会同步到）")
            if (archiveCount > 0) add("$archiveCount 项云端归档会保留，只删除本机")
            if (localOnlyCount > 0) add("$localOnlyCount 项仅在本机删除")
        }
        return if (parts.isEmpty()) null else "其中 ${parts.joinToString("；")}。"
    }

    /** 单条删除的确认：`删除这首音频？` / `删除这个视频？` */
    fun deleteOneQuestion(kinds: Collection<String?>): String = when (noun(kinds)) {
        AUDIO -> "删除这首$AUDIO？"
        VIDEO -> "删除这个$VIDEO？"
        else -> "删除这一$ITEM？"
    }

    /** 类别副标题：`分类下的音频` / `分类下的视频` / 混合时说清楚是两种都有。 */
    fun categorySubtitle(kinds: Collection<String?>): String = when (noun(kinds)) {
        AUDIO -> "分类下的$AUDIO"
        VIDEO -> "分类下的$VIDEO"
        else -> "分类下混有音频和视频"
    }

    /** 类别计数：`5 首音频` / `5 个视频` / `5 项` */
    fun categoryCount(kinds: Collection<String?>, count: Int): String = counted(kinds, count, music = true)
}
