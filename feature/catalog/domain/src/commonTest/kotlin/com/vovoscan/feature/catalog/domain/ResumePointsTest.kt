package com.vovoscan.feature.catalog.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumePointsTest {
    @Test
    fun theMostRecentPointOfEachTitleWinsNewestFirst() {
        val device =
            listOf(
                ResumePoint("vovoscan", "t1", "c4", 12, readAt = 300),
                ResumePoint("mangadex", "x", "k1", 0, readAt = 100),
            )
        // The library synced another device's reading: newer for t1? No, older; newer for x.
        val library =
            listOf(
                ResumePoint("vovoscan", "t1", "c2", 3, readAt = 200),
                ResumePoint("mangadex", "x", "k7", 5, readAt = 400),
                ResumePoint("gutenberg", "84", "84", 2, readAt = 50),
            )
        assertEquals(
            listOf(
                ResumePoint("mangadex", "x", "k7", 5, readAt = 400),
                ResumePoint("vovoscan", "t1", "c4", 12, readAt = 300),
                ResumePoint("gutenberg", "84", "84", 2, readAt = 50),
            ),
            ResumePoints.merge(device, library),
        )
    }

    @Test
    fun theSameTitleIdAtTwoSourcesIsTwoTitles() {
        val points = ResumePoints.merge(listOf(ResumePoint("a", "1", "c", 0, 1), ResumePoint("b", "1", "c", 0, 2)))
        assertEquals(listOf("b", "a"), points.map { it.sourceKey })
    }
}
