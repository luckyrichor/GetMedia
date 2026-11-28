package com.getmedia.app.ui.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 批量操作用词的取词规则。
 *
 * 这些句子会被直接念给用户听（"删除 5 个音频？"），说错类型比说得笼统更糟，
 * 所以除了正常路径，这里把"类型未知""空集合""混选"都钉死。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MediaWordingTest {

    private val audio = listOf("AUDIO", "AUDIO", "AUDIO")
    private val video = listOf("VIDEO", "VIDEO")
    private val mixed = listOf("AUDIO", "VIDEO")

    @Test
    fun nounFollowsSelectedTracks() {
        assertEquals("音频", MediaWording.noun(audio))
        assertEquals("视频", MediaWording.noun(video))
        assertEquals("混选只能说项，不能挑一个代表", "项", MediaWording.noun(mixed))
    }

    @Test
    fun nounFallsBackToItemWhenNothingSelectedOrUnknown() {
        assertEquals("项", MediaWording.noun(emptyList()))
        // 类型缺失时宁可说"项"，也不要默认成音频
        assertEquals("项", MediaWording.noun(listOf(null)))
        assertEquals("项", MediaWording.noun(listOf("AUDIO", null)))
    }

    @Test
    fun countedKeepsTheRightMeasureWord() {
        assertEquals("3 个音频", MediaWording.counted(audio, 3))
        assertEquals("2 个视频", MediaWording.counted(video, 2))
        assertEquals("3 项", MediaWording.counted(mixed, 3))
        // 量词统一用「个」：音频视频混在一起时同一种说法才不会来回跳
        assertEquals("5 个音频", MediaWording.counted(audio, 5))
        assertEquals("5 个视频", MediaWording.counted(video, 5))
        assertEquals("5 项", MediaWording.counted(mixed, 5))
    }

    @Test
    fun batchDeleteCopyNeverSaysAudioForVideos() {
        assertEquals("删除 2 个视频？", MediaWording.batchDeleteTitle(video, 2))
        assertEquals("将删除手机上的视频文件，并从所有播放列表及正在播放的队列中移除。", MediaWording.batchDeleteBody(video))
        assertEquals("已删除 2 个视频", MediaWording.batchDeleteDone(video, 2))
    }

    @Test
    fun batchDeleteCopyForAudioStaysUnchanged() {
        assertEquals("删除 3 个音频？", MediaWording.batchDeleteTitle(audio, 3))
        assertEquals("将删除手机上的音频文件，并从所有播放列表及正在播放的队列中移除。", MediaWording.batchDeleteBody(audio))
        assertEquals("已删除 3 个音频", MediaWording.batchDeleteDone(audio, 3))
    }

    @Test
    fun batchDeleteCopyForMixedSelectionUsesItem() {
        assertEquals("删除 2 项？", MediaWording.batchDeleteTitle(mixed, 2))
        assertEquals("将删除手机上的文件，并从所有播放列表及正在播放的队列中移除。", MediaWording.batchDeleteBody(mixed))
        assertEquals("已删除 2 项", MediaWording.batchDeleteDone(mixed, 2))
    }

    @Test
    fun countedReadsNaturallyForASingleItem() {
        assertEquals("删除 1 个音频？", MediaWording.batchDeleteTitle(listOf("AUDIO"), 1))
        assertEquals("删除 1 个视频？", MediaWording.batchDeleteTitle(listOf("VIDEO"), 1))
        assertEquals("删除 1 项？", MediaWording.batchDeleteTitle(emptyList(), 1))
        assertEquals("已删除 1 个视频", MediaWording.batchDeleteDone(listOf("VIDEO"), 1))
        assertEquals("1 个音频", MediaWording.categoryCount(listOf("AUDIO"), 1))
    }

    @Test
    fun singleDeleteQuestionFollowsType() {
        assertEquals("删除这个音频？", MediaWording.deleteOneQuestion(audio))
        assertEquals("删除这个视频？", MediaWording.deleteOneQuestion(video))
    }

    @Test
    fun categoryCopyFollowsItsContent() {
        assertEquals("分类下的视频", MediaWording.categorySubtitle(video))
        assertEquals("分类下的音频", MediaWording.categorySubtitle(audio))
        assertEquals("分类下混有音频和视频", MediaWording.categorySubtitle(mixed))
        assertEquals("5 个视频", MediaWording.categoryCount(video, 5))
        assertEquals("5 个音频", MediaWording.categoryCount(audio, 5))
    }
}
