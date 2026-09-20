package com.levabala.blackandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test fun parsesReleaseTagsAndRejectsNonVersionTags() {
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse("v1.2.3"))
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parse("1.2.3"))
        assertNull(ReleaseVersion.parse("v1.2.3-rc1"))
        assertNull(ReleaseVersion.parse("v1.2"))
    }

    @Test fun comparesEveryVersionComponentNumerically() {
        assertTrue(ReleaseVersion(1, 0, 10) > ReleaseVersion(1, 0, 9))
        assertTrue(ReleaseVersion(1, 1, 0) > ReleaseVersion(1, 0, 99))
        assertTrue(ReleaseVersion(2, 0, 0) > ReleaseVersion(1, 99, 99))
    }
}
