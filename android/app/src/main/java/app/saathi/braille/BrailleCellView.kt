package app.saathi.braille

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min

/**
 * One big braille cell filling the view: 2 columns × 3 rows of touch zones. Sliding a finger onto
 * a raised dot buzzes; flat dots and the gaps between zones are silent.
 *
 * Works with TalkBack on and off:
 * - TalkBack off: plain touch events; double-tap = next cell.
 * - TalkBack on: sliding one finger slowly ("explore by touch") arrives as hover events, which we
 *   treat exactly like touches; TalkBack's double-tap arrives as a click = next cell.
 *
 * Private mode (OTPs, passwords): all six dots are drawn the same, so someone looking over the
 * user's shoulder can't read the dots, and nothing about the cell's content reaches TalkBack.
 */
@SuppressLint("ViewConstructor")
class BrailleCellView(context: Context, private val haptics: BrailleHaptics) : View(context) {
    var cell: Cell? = null
        set(value) {
            if (field == value) return
            field = value
            haptics.off()
            invalidate()
        }
    var private: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }
    var onNext: () -> Unit = {}
    var colors = Colors(0xFF000000.toInt(), 0x33000000, 0xFF888888.toInt())
        set(value) { if (field != value) { field = value; invalidate() } }

    /** [raised] fills dots in public mode; [flat] is the outline; [divider] splits the zones. */
    data class Colors(val raised: Int, val divider: Int, val flat: Int)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onNext()
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Braille cell. Slide one finger slowly. A buzz means a raised dot. Double-tap for the next cell."
        setOnClickListener { onNext() } // TalkBack double-tap
    }

    /** Fraction of each zone's width/height that is a silent gap, so neighbouring dots feel separate. */
    private val gap = 0.18f

    /** Dot number under (x, y), or null when the finger is in a gap or outside. */
    private fun dotUnder(x: Float, y: Float): Int? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        val zw = width / 2f
        val zh = height / 3f
        val col = (x / zw).toInt().coerceIn(0, 1)
        val row = (y / zh).toInt().coerceIn(0, 2)
        val fx = x / zw - col
        val fy = y / zh - row
        val g = gap / 2
        if (fx < g || fx > 1 - g || fy < g || fy > 1 - g) return null
        return dotAt(col, row)
    }

    private fun feel(x: Float, y: Float) {
        val d = dotUnder(x, y)
        if (d != null && cell?.raised(d) == true) haptics.on() else haptics.off()
    }

    @SuppressLint("ClickableViewAccessibility") // TalkBack users get the click via setOnClickListener.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        // The screen scrolls; keep the finger on the cell instead of scrolling the page.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> feel(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> haptics.off()
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> feel(event.x, event.y)
            MotionEvent.ACTION_HOVER_EXIT -> haptics.off()
        }
        // Let TalkBack still focus the view and read its fixed description.
        return super.onHoverEvent(event) || true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }

    override fun onDetachedFromWindow() {
        haptics.off()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val zw = width / 2f
        val zh = height / 3f
        val stroke = resources.displayMetrics.density * 3
        line.color = colors.divider
        line.strokeWidth = stroke / 2
        canvas.drawLine(zw, 0f, zw, height.toFloat(), line)
        canvas.drawLine(0f, zh, width.toFloat(), zh, line)
        canvas.drawLine(0f, zh * 2, width.toFloat(), zh * 2, line)

        val r = min(zw, zh) * 0.28f
        ring.strokeWidth = stroke
        ring.color = colors.flat
        fill.color = colors.raised
        for (col in 0..1) for (row in 0..2) {
            val cx = zw * col + zw / 2
            val cy = zh * row + zh / 2
            val raised = !private && cell?.raised(dotAt(col, row)) == true
            if (raised) canvas.drawCircle(cx, cy, r, fill) else canvas.drawCircle(cx, cy, r, ring)
        }
    }
}
