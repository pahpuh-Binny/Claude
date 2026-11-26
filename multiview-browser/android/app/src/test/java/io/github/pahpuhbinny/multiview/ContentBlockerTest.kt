package io.github.pahpuhbinny.multiview

import io.github.pahpuhbinny.multiview.security.ContentBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockerTest {

    private val sample = """
        ! EasyList-style comment
        [Adblock Plus 2.0]
        # hosts-style comment
        0.0.0.0 ads.example.com
        127.0.0.1 tracker.example.org # trailing comment
        0.0.0.0 localhost
        0.0.0.0 0.0.0.0
        ||doubleclick.net^
        ||third.example^${'$'}third-party
        ||sitespecific.com^${'$'}domain=foo.com
        ||popunder.net^${'$'}popup
        @@||safe.cdn.com^
        ||example.com/ads/*
        example.com##.ad-banner
        plainlisted.io
        UPPERCASE.TEST
        not a domain
    """.trimIndent()

    private fun parsed(): Triple<Set<String>, Set<String>, Set<String>> {
        val into = HashSet<String>()
        val third = HashSet<String>()
        val exceptions = HashSet<String>()
        ContentBlocker.parse(sample.reader().buffered(), into, third, exceptions)
        return Triple(into, third, exceptions)
    }

    @Test
    fun parsesHostsAbpAndPlainLists() {
        val (into, third, exceptions) = parsed()
        assertEquals(setOf("ads.example.com", "tracker.example.org", "doubleclick.net", "plainlisted.io", "uppercase.test"), into)
        assertEquals(setOf("third.example"), third)
        assertEquals(setOf("safe.cdn.com"), exceptions)
    }

    @Test
    fun skipsSiteSpecificPopupPathAndCosmeticRules() {
        val (into, third, _) = parsed()
        for (d in listOf("sitespecific.com", "popunder.net", "example.com", "localhost", "0.0.0.0")) {
            assertFalse(d, d in into || d in third)
        }
    }

    @Test
    fun matchesDomainAndSubdomainsOnly() {
        val set = setOf("doubleclick.net", "ads.example.com")
        assertTrue(ContentBlocker.matches(set, "doubleclick.net"))
        assertTrue(ContentBlocker.matches(set, "stats.g.doubleclick.net"))
        assertTrue(ContentBlocker.matches(set, "x.ads.example.com"))
        assertFalse(ContentBlocker.matches(set, "notdoubleclick.net"))
        assertFalse(ContentBlocker.matches(set, "example.com"))
        assertFalse(ContentBlocker.matches(set, "net"))
        assertFalse(ContentBlocker.matches(emptySet(), "doubleclick.net"))
    }

    @Test
    fun bundledListsParse() {
        for (name in listOf("ads.txt", "trackers.txt", "malware.txt")) {
            val f = java.io.File("src/main/assets/filters/$name")
            val into = HashSet<String>()
            ContentBlocker.parse(f.bufferedReader(), into, HashSet(), HashSet())
            val lines = f.readLines().count { it.isNotBlank() && !it.startsWith("#") }
            assertEquals("every line of $name should be a valid domain", lines, into.size)
        }
    }
}
