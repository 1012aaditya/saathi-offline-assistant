package app.saathi.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import app.saathi.R
import app.saathi.agent.FloatingTexts
import kotlin.math.abs
import kotlin.math.min

/**
 * The 64 dp round Saathi button (Clarity accent, a thin contrasting ring so it shows on any app).
 * A classic View (not Compose) because it is dragged by moving its whole window, which needs the
 * finger's raw screen position.
 *
 * Tap (TalkBack: double-tap) = [onTap]; holding it still for [HIDE_HOLD_MS] (TalkBack: the "Hide"
 * action in the actions menu) = [onHide]. 14 Oct: the system's ~0.5 s long-press hid it by accident
 * while the owner tested, so hiding needs a deliberate 2 s hold. Dragging moves it ([onDrag] with the total raw offset);
 * [onDown] marks where a drag starts; letting go calls [onDrop] (it snaps to the nearer edge). Never focusable: it doesn't take focus
 * or the keyboard from the app; TalkBack reaches it only when the user moves to it.
 */
@SuppressLint("ViewConstructor")
internal class BubbleView(
    context: Context,
    private val accent: Int,
    private val onAccent: Int,
    private val onTap: () -> Unit,
    private val onHide: () -> Unit,
    private val onDown: () -> Unit,
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onDrop: () -> Unit,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = onAccent
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val mic = ContextCompat.getDrawable(context, R.drawable.ic_saathi_mic)?.mutate()?.apply { setTint(onAccent) }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var hidden = false
    private val hide = Runnable { hidden = true; onHide() }

    init {
        contentDescription = FloatingTexts.BUBBLE
        isClickable = true
        isLongClickable = false // a deliberate hold ([hide]) instead of the quick system long-press
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { onTap() }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) { onHide(); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, FloatingTexts.BUBBLE_HIDE))
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2, fill)
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)
        mic?.let {
            val s = (r * 0.95f).toInt()
            it.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            it.draw(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() runs through super.onTouchEvent
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                hidden = false
                removeCallbacks(hide)
                postDelayed(hide, HIDE_HOLD_MS)
                onDown()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    removeCallbacks(hide)
                    cancelTouch(event) // no click / hide from a drag
                }
                if (dragging) {
                    onDrag(dx.toInt(), dy.toInt())
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hide)
                if (hidden) { // the hold hid it: this lift is not a tap
                    hidden = false
                    cancelTouch(event)
                    return true
                }
                if (dragging) {
                    dragging = false
                    onDrop()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val HIDE_HOLD_MS = 2_000L
    }

    private fun cancelTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.onTouchEvent(cancel)
        cancel.recycle()
        cancelLongPress()
        isPressed = false
    }
}
