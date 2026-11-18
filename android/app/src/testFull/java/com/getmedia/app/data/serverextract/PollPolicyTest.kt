package com.getmedia.app.data.serverextract

import com.getmedia.app.data.local.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollPolicyTest {

    @Test fun backoffGrowsThenCaps() {
        assertEquals(800L, PollPolicy.delayFor(0))
        assertEquals(1_200L, PollPolicy.delayFor(1))
        assertEquals(1_800L, PollPolicy.delayFor(2))
        assertEquals(2_700L, PollPolicy.delayFor(3))
        assertEquals(4_000L, PollPolicy.delayFor(4))
        assertEquals(4_000L, PollPolicy.delayFor(20))
    }

    @Test fun timeoutDependsOnMediaKind() {
        assertEquals(180_000L, PollPolicy.timeoutFor(MediaKind.AUDIO))
        assertEquals(600_000L, PollPolicy.timeoutFor(MediaKind.VIDEO))
        assertTrue(PollPolicy.timeoutFor(MediaKind.VIDEO) > PollPolicy.timeoutFor(MediaKind.AUDIO))
    }
}
