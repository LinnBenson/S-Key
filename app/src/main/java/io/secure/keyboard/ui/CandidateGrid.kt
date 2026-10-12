package io.secure.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The full ordered result set, with only the visible cells (and GridView's small recycle bin). */
@SuppressLint("ViewConstructor")
class CandidateGrid(
    context: Context,
    private val candidateColor: Int,
    private val selectedColor: Int,
    private val cellColor: Int,
    private val selectionVersion: () -> Int? = { 0 },
    private val feedback: () -> KeyboardPressFeedback = { KeyboardPressFeedback() },
    private val textSizeSp: Float = 18f,
    private val chooseWithFeedback: ((String, () -> Unit) -> Unit)? = null,
    private val choose: (String) -> Unit,
) : GridView(context) {
    private var displayed = emptyList<String>()
    private val cells = CandidateAdapter()

    init {
        KeyboardFeedback.disableAutomatic(this)
        contentDescription = "全部候选"
        numColumns = AUTO_FIT
        columnWidth = dp((80 * (textSizeSp / 18f).coerceAtLeast(1f)).roundToInt())
        stretchMode = STRETCH_COLUMN_WIDTH
        horizontalSpacing = dp(6)
        verticalSpacing = dp(6)
        setPadding(dp(2), dp(3), dp(2), dp(6))
        clipToPadding = false
        isVerticalScrollBarEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        adapter = cells
    }

    /** Equal ordered results do not notify, bind, scroll, or replace any views or font spans. */
    fun updateCandidates(values: List<String>) {
        if (displayed == values) return
        displayed = values.toList()
        // GridView re-requests only its viewport and recycle buffer. bind() itself skips every
        // unchanged label, even when only an offscreen tail item or result count changed.
        cells.notifyDataSetChanged()
        if (values.isEmpty()) setSelection(0)
    }

    /** This grid is discarded by its owner after finish; clear attached and recycled cells. */
    fun releasePresentation() {
        cancelPendingInputEvents()
        displayed = emptyList()
        for (index in 0 until childCount) (getChildAt(index) as? Cell)?.releasePresentation()
        // GridView.setAdapter resets the attached layout and clears its recycle bin. A plain
        // empty notify could retain old labels in that bin until another layout/show.
        adapter = null
    }

    private inner class CandidateAdapter : BaseAdapter() {
        override fun getCount() = displayed.size
        override fun getItem(position: Int) = displayed[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView as? Cell ?: Cell()).apply { bind(position, displayed[position]) }
    }

    @SuppressLint("ClickableViewAccessibility") // Invalid gestures are consumed; valid taps and
    // accessibility clicks both pass through the same guarded OnClickListener and service choose.
    private inner class Cell : TextView(context) {
        private var position = -1
        private var value = ""
        private var binding = 0
        private val pressFeedback = feedback()
        private var touched = false
        private var pressedVersion: Int? = null
        private var pressedBinding = -1

        init {
            textSize = textSizeSp.coerceIn(12f, 32f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val twoLines = ceil((paint.fontMetrics.bottom - paint.fontMetrics.top) * 2).toInt()
            layoutParams = AbsListView.LayoutParams(-1, maxOf(dp(56), twoLines + dp(12)))
            val fill = GradientDrawable().apply {
                setColor(cellColor)
                cornerRadius = dp(10).toFloat()
            }
            background = RippleDrawable(
                ColorStateList.valueOf((candidateColor and 0x00ffffff) or 0x18000000), fill, null,
            )
            KeyboardFeedback.disableAutomatic(this)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touched = true
                        pressedVersion = selectionVersion()
                        pressedBinding = binding
                        if (pressedVersion == null) return@setOnTouchListener true
                        pressFeedback.onTouch(event)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!validTouch()) {
                            pressFeedback.cancel()
                            touched = false
                            view.cancelPendingInputEvents()
                            view.isPressed = false
                            return@setOnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        pressFeedback.onTouch(event)
                        // Scrolling / collapsing cannot turn a canceled press into selection.
                        touched = false
                        pressedVersion = null
                        view.cancelPendingInputEvents()
                    }
                }
                false
            }
            setOnClickListener {
                val valid = isAttachedToWindow && isShown && selectionVersion() != null &&
                    (!touched || validTouch()) &&
                    displayed.getOrNull(position) == value
                touched = false
                if (valid) {
                    if (chooseWithFeedback != null) chooseWithFeedback.invoke(value, pressFeedback.takeClick())
                    else { pressFeedback.click(); choose(value) } // Preserve complete text.
                } else pressFeedback.cancel()
            }
        }

        override fun onDetachedFromWindow() {
            pressFeedback.cancel()
            // A collapse/reopen (or a theme/editor rebuild) can reuse this cell with an equal
            // result and query generation. An old finger-up still must not become a new tap.
            binding++
            pressedVersion = null
            touched = false
            cancelPendingInputEvents()
            super.onDetachedFromWindow()
        }

        fun releasePresentation() {
            binding++
            position = -1
            value = ""
            text = ""
            pressedVersion = null
            touched = false
            cancelPendingInputEvents()
            setOnTouchListener(null)
            setOnClickListener(null)
        }

        private fun validTouch() = pressedVersion != null &&
            pressedVersion == selectionVersion() && pressedBinding == binding

        fun bind(index: Int, textValue: String) {
            if (position != index || value != textValue) {
                binding++
                if (position == 0 || index == 0 || position < 0)
                    setTextColor(if (index == 0) selectedColor else candidateColor)
                position = index
            }
            if (value != textValue) {
                value = textValue
                text = textValue
                CandidateFont.apply(this)
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
