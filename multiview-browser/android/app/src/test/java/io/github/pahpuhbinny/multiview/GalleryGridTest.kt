package io.github.pahpuhbinny.multiview

import io.github.pahpuhbinny.multiview.vault.GalleryGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryGridTest {

    private fun assertValid(spans: List<Int>, cols: Int) {
        val p = GalleryGrid.pack(spans, cols)
        assertEquals(spans.size, p.cells.size)
        val taken = HashSet<Pair<Int, Int>>()
        p.cells.forEachIndexed { i, c ->
            assertEquals("span kept (capped at the column count)", spans[i].coerceAtMost(cols), c.span)
            assertTrue("inside the grid", c.col >= 0 && c.col + c.span <= cols && c.row + c.span <= p.rows)
            for (r in c.row until c.row + c.span) for (k in c.col until c.col + c.span) {
                assertTrue("tiles never overlap", taken.add(r to k))
            }
        }
    }

    @Test
    fun packsWithoutOverlapsForEveryLayout() {
        for (n in listOf(1, 2, 4, 8, 16, 24, 57, 120)) {
            for (cols in 1..8) {
                assertValid(List(n) { 1 }, cols)
                assertValid(List(n) { i -> 1 + (i * 7 % 4) }, cols)
            }
        }
    }

    @Test
    fun uniformTilesFillRowsCompletely() {
        val p = GalleryGrid.pack(List(24) { 1 }, 6)
        assertEquals(4, p.rows)
    }

    @Test
    fun smallTilesFillTheGapsBesideABigOne() {
        // One 2×2 video plus eight small ones in 4 columns: the small ones flow
        // around the big one instead of leaving holes.
        val p = GalleryGrid.pack(listOf(2) + List(8) { 1 }, 4)
        assertEquals(3, p.rows)
        assertEquals(GalleryGrid.Cell(0, 0, 2), p.cells[0])
        assertEquals(GalleryGrid.Cell(2, 0, 1), p.cells[1])
        assertEquals(GalleryGrid.Cell(2, 1, 1), p.cells[3])
    }

    @Test
    fun columnChoiceSuitsTheScreen() {
        val portrait = intArrayOf(1080, 2100)
        val landscape = intArrayOf(2100, 1080)
        fun cols(n: Int, s: IntArray) = GalleryGrid.bestColumns(List(n) { 1 }, s[0], s[1], 6)
        // Portrait phones stack a few videos; landscape puts them side by side.
        assertEquals(1, cols(2, portrait))
        assertEquals(2, cols(2, landscape))
        assertTrue(cols(4, portrait) in 1..2)
        assertEquals(2, cols(4, landscape))
        for (n in listOf(8, 16, 24, 100)) {
            for (s in listOf(portrait, landscape)) {
                val c = cols(n, s)
                val rows = GalleryGrid.pack(List(n) { 1 }, c).rows
                val cellW = (s[0] - (c - 1) * 6) / c.toDouble()
                val gridH = rows * cellW * 9 / 16 + (rows - 1) * 6
                // Everything fits on screen at the starting zoom without tiles getting needlessly small.
                assertTrue("n=$n cols=$c", c <= n)
                assertFalse("n=$n cols=$c leaves the screen mostly empty", gridH < s[1] * 0.35 && c > 1 && rows > 1)
            }
        }
    }

    @Test
    fun bigTilesForceEnoughColumns() {
        assertTrue(GalleryGrid.bestColumns(listOf(4, 1, 1), 1080, 2100, 6) >= 4)
    }
}
