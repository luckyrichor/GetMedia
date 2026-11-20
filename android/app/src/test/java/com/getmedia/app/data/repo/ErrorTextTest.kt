package com.getmedia.app.data.repo

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 失败提示的人话化（0.1.16）。
 *
 * 真机上遇到过的原样：断网点下载，弹出 `Unable to resolve host "www.douyin.com"`。
 * 现在要求：界面只出现中文提示，原始异常类型只进诊断日志。
 */
class ErrorTextTest {

    @Test
    fun unknownHostBecomesFriendlyLine() {
        assertEquals(
            "网络不可用，稍后再试",
            ErrorText.humanMessage(UnknownHostException("Unable to resolve host \"www.douyin.com\"")),
        )
    }

    /** 下载链路会把底层异常包一层，必须顺着 cause 找到根因。 */
    @Test
    fun wrappedNetworkErrorStillReadsAsNetworkDown() {
        val wrapped = IllegalStateException(
            "所有下载地址均失败：网络读写失败（UnknownHostException）",
            UnknownHostException("Unable to resolve host \"www.douyin.com\""),
        )
        assertEquals("网络不可用，稍后再试", ErrorText.humanMessage(wrapped))
    }

    /** 没有类型、只剩英文原文时，靠关键字认出来。 */
    @Test
    fun englishMessageKeywordIsRecognised() {
        assertEquals(
            "网络不可用，稍后再试",
            ErrorText.humanMessage(RuntimeException("Unable to resolve host \"www.douyin.com\"")),
        )
    }

    @Test
    fun timeoutAndConnectGetTheirOwnLines() {
        assertEquals("连接超时，请稍后再试", ErrorText.humanMessage(SocketTimeoutException("timeout")))
        assertEquals("无法连接服务器，请稍后再试", ErrorText.humanMessage(ConnectException("failed to connect")))
    }

    /** 中文原因如实透传：这些是业务判断，比任何通用话术都准。 */
    @Test
    fun chineseReasonPassesThrough() {
        assertEquals("这一条已被移除", ErrorText.humanMessage(IllegalStateException("这一条已被移除")))
        assertEquals(
            "这次取到的作品、分集或媒体类型与这条不符，已保留原样",
            ErrorText.humanMessage(IllegalStateException("这次取到的作品、分集或媒体类型与这条不符，已保留原样")),
        )
    }

    /** 纯英文且认不出来的，不给用户看原文。 */
    @Test
    fun unknownEnglishMessageIsHidden() {
        assertEquals("操作失败，请稍后再试", ErrorText.humanMessage(RuntimeException("Something exploded")))
    }
}
