package io.github.pahpuhbinny.multiview.browser

import android.graphics.Color

/** A named, coloured set of tabs. */
class TabGroup(val id: Int, var name: String, var color: Int, var collapsed: Boolean = false) {
    val argb: Int get() = GroupColors.argb(color)
}

object GroupColors {
    val names = arrayOf("Grey", "Blue", "Red", "Yellow", "Green", "Pink", "Purple", "Cyan", "Orange")
    private val values = intArrayOf(
        0xFF9AA1B2.toInt(), 0xFF5B8CFF.toInt(), 0xFFFF5C6C.toInt(), 0xFFFFC94D.toInt(), 0xFF2BD9A8.toInt(),
        0xFFFF6FB5.toInt(), 0xFFA47CFF.toInt(), 0xFF3CC8F0.toInt(), 0xFFFF9248.toInt(),
    )
    val count get() = values.size

    fun argb(index: Int) = values[index.mod(values.size)]

    /** The colour at reduced opacity, for fills behind text. */
    fun soft(index: Int, alpha: Int = 0x40): Int {
        val c = argb(index)
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
    }
}
