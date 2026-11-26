package io.github.pahpuhbinny.multiview.vault

import kotlin.math.max
import kotlin.math.min

/**
 * Grid maths for the gallery, kept free of Android classes so it can be
 * tested on its own.
 */
object GalleryGrid {

    /** Where a tile sits: column, row and size, all in cells. */
    data class Cell(val col: Int, val row: Int, val span: Int)

    class Packing(val cells: List<Cell>, val rows: Int)

    /**
     * Places tiles (given by their spans, in order) into [cols] columns. Each
     * goes in the first free spot, row by row, where its span×span block fits,
     * so small tiles fill the gaps left beside big ones.
     */
    fun pack(spans: List<Int>, cols: Int): Packing {
        val used = ArrayList<BooleanArray>()
        fun isFree(r: Int, c: Int, s: Int): Boolean {
            if (c + s > cols) return false
            for (rr in r until r + s) {
                while (used.size <= rr) used.add(BooleanArray(cols))
                for (cc in c until c + s) if (used[rr][cc]) return false
            }
            return true
        }
        val out = ArrayList<Cell>(spans.size)
        var rows = 0
        for (span in spans) {
            val s = span.coerceIn(1, cols)
            var r = 0
            while (true) {
                val c = (0..cols - s).firstOrNull { isFree(r, it, s) }
                if (c != null) {
                    for (rr in r until r + s) for (cc in c until c + s) used[rr][cc] = true
                    out.add(Cell(c, r, s))
                    rows = max(rows, r + s)
                    break
                }
                r++
            }
        }
        return Packing(out, rows)
    }

    /**
     * The column count that makes 16:9 cells as big as possible while the
     * whole grid fits in a [width]×[height] screen.
     */
    fun bestColumns(spans: List<Int>, width: Int, height: Int, gap: Int): Int {
        if (spans.isEmpty() || width <= 0 || height <= 0) return 1
        val maxSpan = spans.maxOrNull()!!.coerceAtLeast(1)
        var best = maxSpan
        var bestScore = -1.0
        val upper = max(maxSpan, min(40, spans.sumOf { it * it }))
        for (cols in maxSpan..upper) {
            val rows = pack(spans, cols).rows
            val cellW = (width - (cols - 1) * gap) / cols.toDouble()
            if (cellW <= 0) break
            val cellH = cellW * 9 / 16
            val gridH = rows * cellH + (rows - 1) * gap
            val fit = min(1.0, height / gridH)
            val score = cellW * cellH * fit * fit
            if (score > bestScore * 1.0001) {
                bestScore = score
                best = cols
            }
        }
        return best
    }
}
