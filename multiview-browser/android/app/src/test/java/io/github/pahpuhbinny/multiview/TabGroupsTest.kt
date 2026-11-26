package io.github.pahpuhbinny.multiview

import io.github.pahpuhbinny.multiview.browser.GroupColors
import io.github.pahpuhbinny.multiview.browser.TabManager
import io.github.pahpuhbinny.multiview.data.Session
import io.github.pahpuhbinny.multiview.data.SessionGroup
import io.github.pahpuhbinny.multiview.data.SessionTab
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabGroupsTest {

    private data class T(val name: String, val group: Int)

    @Test
    fun groupMembersAreKeptTogetherWhereTheGroupStarts() {
        val tabs = listOf(T("a", 0), T("b", 1), T("c", 0), T("d", 2), T("e", 1), T("f", 0), T("g", 2))
        val out = TabManager.groupedOrder(tabs) { it.group }.map { it.name }
        assertEquals(listOf("a", "b", "e", "c", "d", "g", "f"), out)
    }

    @Test
    fun orderingIsStableWhenAlreadyGrouped() {
        val tabs = listOf(T("a", 1), T("b", 1), T("c", 0), T("d", 2))
        assertEquals(tabs, TabManager.groupedOrder(tabs) { it.group })
    }

    @Test
    fun sessionKeepsGroups() {
        val s = Session(
            "id1", "Evening", 1L, 2L,
            listOf(
                SessionTab("https://a.example/", "A", muted = false, inMultiview = true, videoTime = 61.5, group = 7),
                SessionTab("https://b.example/", "B", muted = true, inMultiview = false, videoTime = 0.0, group = 7),
                SessionTab("https://c.example/", "C", muted = false, inMultiview = false, videoTime = 0.0),
            ),
            focusedIndex = 1, multiview = true,
            groups = listOf(SessionGroup(7, "Sports", 4, collapsed = true)),
        )
        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s, back)
        assertEquals(listOf(7, 7, 0), back.tabs.map { it.group })
    }

    @Test
    fun sessionsSavedBeforeGroupsStillLoad() {
        val old = """{"id":"x","name":"Old","created":1,"updated":2,"focused":0,"multiview":false,
            "tabs":[{"url":"https://a.example/","title":"A","muted":false,"mv":false,"t":0}]}"""
        val s = Session.fromJson(JSONObject(old))
        assertTrue(s.groups.isEmpty())
        assertEquals(0, s.tabs[0].group)
    }

    @Test
    fun everyColourIndexMapsToAColour() {
        for (i in -3 until GroupColors.count * 2) assertTrue(GroupColors.argb(i) != 0)
        assertEquals(GroupColors.count, GroupColors.names.size)
    }
}
