package io.secure.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** A stable, horizontally scrolling candidate row. Loading state belongs to the caller. */
@SuppressLint("ViewConstructor") // Programmatic-only palette and selection callback dependency.
class CandidateStrip(
    context: Context,
    private val candidateColor: Int,
    private val selectedColor: Int,
    private val selectionVersion: () -> Int? = { 0 },
    private val feedback: () -> KeyboardPressFeedback = { KeyboardPressFeedback() },
    private val textSizeSp: Float = 20f,
    private val chooseWithFeedback: ((String, () -> Unit) -> Unit)? = null,
    private val loadMore: () -> Unit = {},
    private val choose: (String) -> Unit,
) : HorizontalScrollView(context) {
    private val row = LinearLayout(context)
    private val normalPaint = TextPaint(TextView(context).apply { textSize = textSizeSp.coerceIn(12f, 32f) }.paint)
    private var displayed = emptyList<String>()
    private var appendScheduled = false
    private val appendNearEnd = Runnable {
        appendScheduled = false
        // Animation callbacks run before traversal. Newly bound labels may still have the
        // previous empty/short row width here; wait for onLayout to schedule a fresh check.
        if (row.childCount < displayed.size && width > 0 && row.width > 0 &&
            !isLayoutRequested && !row.isLayoutRequested &&
            row.width - scrollX - width <= width) {
            bindThrough(minOf(displayed.size, row.childCount + BATCH_SIZE), displayed, row.childCount)
        } else if (displayed.isNotEmpty() && row.childCount == displayed.size && width > 0 && row.width > 0 &&
            !isLayoutRequested && !row.isLayoutRequested && row.width - scrollX - width <= width) {
            loadMore()
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }

    /** Copy on the main thread; the worker never reads or mutates a live TextView's paint. */
    fun normalPaintSnapshot(): TextPaint = TextPaint(normalPaint)

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        scheduleAppend()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        scheduleAppend()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(appendNearEnd)
        appendScheduled = false
        super.onDetachedFromWindow()
    }

    /** Drop finished-session data and posted work without replacing the reusable keyboard root. */
    fun releasePresentation() {
        removeCallbacks(appendNearEnd)
        appendScheduled = false
        cancelPendingInputEvents()
        displayed = emptyList()
        for (index in 0 until row.childCount) {
            (row.getChildAt(index) as TextView).apply {
                cancelPendingInputEvents()
                setOnTouchListener(null)
                setOnClickListener(null)
                text = ""
            }
        }
        row.removeAllViews()
        scrollTo(0, 0)
    }

    private fun scheduleAppend() {
        if (!appendScheduled && displayed.isNotEmpty()) {
            appendScheduled = true
            postOnAnimation(appendNearEnd)
        }
    }

    /**
     * An equal ordered result is a genuine no-op: no setText, font spans, layout, invalidation,
     * listener rebinding, or scrolling. Changed results reuse every surviving position and only
     * rebind changed labels. The callback reads its current text, never a captured old candidate.
     * Keep all candidate strings, but initially bind about two viewports (at most 40 views).
     * Long English labels therefore do not create dozens of offscreen TextViews on the first key.
     * Scrolling toward the end
     * appends another batch automatically; there is no result cap or extra expand button.
     * Viewed batches remain attached for stable backward scrolling until the result changes.
     */
    @SuppressLint("ClickableViewAccessibility") // Valid touches return false to TextView.performClick;
    // only invalid/stale gestures are consumed. The normal OnClickListener handles accessibility.
    fun updateCandidates(values: List<String>) {
        if (displayed == values) return
        val previous = displayed
        displayed = values.toList()
        val appended = previous.isNotEmpty() && values.size > previous.size && values.take(previous.size) == previous
        val initialCount = initialBindingCount(values)
        val visibleCount = if(appended) minOf(values.size, maxOf(row.childCount, initialCount)) else initialCount
        bindThrough(visibleCount, previous)
        if (row.childCount > visibleCount)
            row.removeViews(visibleCount, row.childCount - visibleCount)
        // A deliberately cleared composition starts the next one from its first candidate.
        if (values.isEmpty()) scrollTo(0, 0)
        // Tail-only changes may leave the first batch and its geometry untouched. Still make
        // newly available entries reachable when the user is already at that batch's end.
        scheduleAppend()
    }

    private fun initialBindingCount(values: List<String>): Int {
        val viewport=width.takeIf { it>0 } ?: resources.displayMetrics.widthPixels
        val target=scrollX + viewport * 2.25f
        val padding=(16 * resources.displayMetrics.density).roundToInt() * 2
        var occupied=0f
        var count=0
        while(count<minOf(BATCH_SIZE,values.size) && (count<4 || occupied<target)) {
            occupied+=normalPaint.measureText(values[count])+padding
            count++
        }
        return count
    }

    @SuppressLint("ClickableViewAccessibility") // Same guarded TextView clicks as updateCandidates.
    private fun bindThrough(count: Int, previous: List<String>, start: Int = 0) {
        for (index in start until count) {
            val value = displayed[index]
            val needsView = index >= row.childCount
            val label =
                if (!needsView) row.getChildAt(index) as TextView
                else
                    TextView(context).apply {
                        KeyboardFeedback.disableAutomatic(this)
                        textSize = textSizeSp.coerceIn(12f, 32f)
                        setTextColor(if (index == 0) selectedColor else candidateColor)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        val density = resources.displayMetrics.density
                        val horizontal = (16 * density).roundToInt()
                        setPadding(horizontal, 0, horizontal, (4 * density).roundToInt())
                        val pressFeedback = feedback()
                        var touched = false
                        var pressedVersion: Int? = null
                        var pressedValue = ""
                        setOnTouchListener { view, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    touched = true
                                    pressedVersion = selectionVersion()
                                    pressedValue = text.toString()
                                    // Loading rows retain their appearance, but cannot begin a tap
                                    // that would become valid only when a new result arrives.
                                    if (pressedVersion == null) return@setOnTouchListener true
                                    pressFeedback.onTouch(event)
                                }
                                MotionEvent.ACTION_UP -> {
                                    if (pressedVersion == null || pressedVersion != selectionVersion() ||
                                        pressedValue != text.toString()) {
                                        pressFeedback.cancel()
                                        touched = false
                                        view.cancelPendingInputEvents()
                                        view.isPressed = false
                                        return@setOnTouchListener true
                                    }
                                }
                                MotionEvent.ACTION_CANCEL -> {
                                    pressFeedback.onTouch(event)
                                    touched = false
                                }
                            }
                            false
                        }
                        setOnClickListener {
                            // View may post performClick after touch-up. Check the same token again
                            // then; direct accessibility/keyboard clicks use current selection state.
                            val valid = !touched || (pressedVersion != null &&
                                pressedVersion == selectionVersion() && pressedValue == text.toString())
                            touched = false
                            if (valid) {
                                val value = text.toString()
                                if (chooseWithFeedback != null) chooseWithFeedback.invoke(value, pressFeedback.takeClick())
                                else { pressFeedback.click(); choose(value) }
                            } else pressFeedback.cancel()
                        }
                        row.addView(this, LinearLayout.LayoutParams(-2, -1))
                    }
            if (needsView || index >= previous.size || previous[index] != value) {
                label.text = value
                CandidateFont.apply(label)
            }
        }
    }

    private companion object {
        const val BATCH_SIZE = 40
    }
}
