package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.StaleReason
import com.vovoscan.feature.catalog.domain.CatalogFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogFiltersTest {
    private val a = CatalogSource("a", "Alpha")
    private val b = CatalogSource("b", "Beta")
    private val c = CatalogSource("c", "Gamma")
    private val visible = listOf(a, b, c)

    @Test
    fun togglingSources() {
        val all = CatalogFilter()
        assertEquals(listOf("b"), CatalogSelection.toggle(visible, all, "b").sourceKeys)
        val two = CatalogSelection.toggle(visible, CatalogFilter(listOf("b")), "a")
        assertEquals(listOf("a", "b"), two.sourceKeys)
        assertEquals("2 sources", CatalogSelection.label(visible, two))
        // Every source chosen, or none left: "All".
        assertEquals(emptyList(), CatalogSelection.toggle(visible, two, "c").sourceKeys)
        assertEquals(emptyList(), CatalogSelection.toggle(visible, CatalogFilter(listOf("b")), "b").sourceKeys)
        // A source not shown can't be toggled; the category is kept.
        val withCategory = CatalogFilter(listOf("b"), "Book")
        assertEquals(withCategory, CatalogSelection.toggle(visible, withCategory, "zzz"))
        assertEquals("Book", CatalogSelection.toggle(visible, withCategory, "a").categoryName)
        assertEquals("Beta", CatalogSelection.label(visible, withCategory))
        assertEquals("All sources", CatalogSelection.label(visible, CatalogFilter(listOf("gone"))))
        assertEquals(visible, CatalogSelection.selected(visible, CatalogFilter(listOf("gone"))))
    }

    @Test
    fun oneLineForEveryProblem() {
        val down = SourceProblem("b", "Beta", StaleReason.SourceDown("Beta"))
        val down2 = SourceProblem("c", "Gamma", StaleReason.SourceDown("Gamma"))
        val unreadable = SourceProblem("a", "Alpha", StaleReason.SourceUnreadable)
        assertNull(CatalogNotices.of(emptyList(), 3, true))
        assertEquals("Beta isn't answering right now. Showing what's saved.", CatalogNotices.of(listOf(down), 1, true))
        assertEquals("This source couldn't be read. It may need an update.", CatalogNotices.of(listOf(unreadable), 1, true))
        assertEquals("Beta isn't answering right now.", CatalogNotices.of(listOf(down), 3, true))
        assertEquals(
            "Beta and Gamma aren't answering right now. Alpha couldn't be read. It may need an update.",
            CatalogNotices.of(listOf(down, unreadable, down2), 3, true),
        )
        val offline = SourceProblem("a", "Alpha", StaleReason.Offline)
        assertEquals("Offline: showing what's saved on this device.", CatalogNotices.of(listOf(down, offline), 3, true))
        assertEquals("You're offline.", CatalogNotices.of(listOf(offline), 3, false))
        assertEquals("Alpha, Beta and Gamma", CatalogNotices.names(listOf("Alpha", "Beta", "Gamma")))
        assertEquals(
            "Beta and Gamma have no “Webtoon” category, so they're left out.",
            CatalogNotices.categoryLeftOut("Webtoon", listOf("Beta", "Gamma")),
        )
    }
}
