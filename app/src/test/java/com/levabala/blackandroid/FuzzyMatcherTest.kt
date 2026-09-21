package com.levabala.blackandroid

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {
    @Test fun directMatchesRankAboveSubsequenceMatches() {
        val direct = FuzzyMatcher.score("maps", "Google Maps com.google.android.apps.maps")!!
        val subsequence = FuzzyMatcher.score("maps", "My Android Photo Search")!!
        assertTrue(direct > subsequence)
    }

    @Test fun matchesPackageNamesAndRejectsMissingCharacters() {
        assertNotNull(FuzzyMatcher.score("ytube", "YouTube com.google.android.youtube"))
        assertNull(FuzzyMatcher.score("zzzz", "YouTube com.google.android.youtube"))
    }
}
