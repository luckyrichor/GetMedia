package com.getmedia.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun patchVersionIsNewer() {
        assertTrue(AppVersion.isNewer("0.1.27", "0.1.26"))
        assertFalse(AppVersion.isNewer("0.1.26", "0.1.26"))
        assertFalse(AppVersion.isNewer("0.1.25", "0.1.26"))
    }

    @Test
    fun minorAndMajorOutrankPatch() {
        assertTrue(AppVersion.isNewer("0.2", "0.1.99"))
        assertTrue(AppVersion.isNewer("1.0", "0.99.99"))
        assertTrue(AppVersion.isNewer("0.1.27.1", "0.1.27"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertFalse(AppVersion.isNewer("0.1", "0.1.0"))
        assertTrue(AppVersion.isNewer("0.1.1", "0.1"))
    }

    @Test
    fun unparsableNeverCountsAsNewer() {
        assertFalse(AppVersion.isNewer(null, "0.1.26"))
        assertFalse(AppVersion.isNewer("", "0.1.26"))
        assertFalse(AppVersion.isNewer("latest", "0.1.26"))
        assertFalse(AppVersion.isNewer("0.1.27", null))
        assertFalse(AppVersion.isNewer("v0.1.27", "0.1.26"))
        assertFalse(AppVersion.isNewer("0.1.27-beta", "0.1.26"))
    }
}
