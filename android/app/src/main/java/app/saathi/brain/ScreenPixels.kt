package app.saathi.brain

import android.graphics.Bitmap
import android.graphics.Color

/** Pixel checks on a screenshot. */
object ScreenPixels {
    /**
     * True when at least [share] of the screen is near-black: Android blanks secure windows
     * (UPI PIN pads, banking apps) in screenshots, so this is treated as a protected screen
     * (CLAUDE.md device notes: >= 95 % black = secure screen -> hand off, never send).
     */
    fun mostlyBlack(b: Bitmap, share: Double = 0.95): Boolean {
        val n = 48
        var dark = 0
        for (i in 0 until n) for (j in 0 until n) {
            val c = b.getPixel((b.width - 1) * i / (n - 1), (b.height - 1) * j / (n - 1))
            val luma = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            if (luma < 16) dark++
        }
        return dark >= share * n * n
    }
}
