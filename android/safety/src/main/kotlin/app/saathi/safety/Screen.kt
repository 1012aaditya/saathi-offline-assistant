package app.saathi.safety

/** (x1, y1, x2, y2) in screen pixels, like the Python bounds tuple. */
data class Bounds(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

data class Point(val x: Int, val y: Int)

/** Port of saathi/device.py Element: one on-screen node as the gate and the model see it. */
data class Element(
    val index: Int,
    val cls: String, // "Button", "EditText", "TextView", ...
    val text: String,
    val desc: String, // content-description
    val rid: String, // short resource-id
    val clickable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val bounds: Bounds,
    val center: Point,
    val sender: String = "", // chat messages only: "me" or "them"
)

/**
 * Port of saathi/device.py Screen. `screenshot` carries decoded luminance instead of JPEG
 * bytes so this module stays free of image codecs (see [Screenshot]).
 */
data class Screen(
    val packageName: String,
    val elements: List<Element>,
    val hash: String,
    val keyboardVisible: Boolean = false,
    val screenshot: Screenshot? = null,
) {
    fun labeledClickables(): Int = elements.count { it.clickable && (it.text.isNotEmpty() || it.desc.isNotEmpty()) }
}

/**
 * A screenshot as 8-bit luminance (Pillow mode "L"), row-major, width * height bytes.
 * The Android side decodes the capture and calls [fromArgb]; Pillow's RGB -> L formula is used.
 */
class Screenshot(val width: Int, val height: Int, private val luma: ByteArray) {
    init {
        require(width >= 0 && height >= 0 && luma.size == width * height) { "luma size must be width * height" }
    }

    fun at(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF

    val pixelCount: Int get() = width * height

    companion object {
        fun solid(width: Int, height: Int, value: Int): Screenshot =
            Screenshot(width, height, ByteArray(width * height) { value.toByte() })

        /** Pillow's convert("L"): L = (R*19595 + G*38470 + B*7471 + 0x8000) >> 16; alpha ignored. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): Screenshot =
            Screenshot(
                width,
                height,
                ByteArray(argb.size) { i ->
                    val p = argb[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    ((r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16).toByte()
                },
            )
    }
}
