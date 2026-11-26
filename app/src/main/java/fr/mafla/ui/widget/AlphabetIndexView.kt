package fr.mafla.ui.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.content.res.ResourcesCompat
import fr.mafla.R
import fr.mafla.helper.AlphabetIndex
import fr.mafla.helper.getColorFromAttr
import fr.mafla.helper.isMotionEnabled
import androidx.core.graphics.ColorUtils
import kotlin.math.exp

/**
 * The letter rail on the side of the app list. Shows only letters that have apps, marks the
 * section currently on top of the list, and lets the user drag along it to jump between
 * sections. Letters near the finger grow a little (fisheye) while dragging.
 */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onScrubStart()
        fun onScrub(section: AlphabetIndex.Section, centerY: Float)

        /** Finger position while dragging, in the parent's coordinates. */
        fun onFingerMoved(y: Float) = Unit
        fun onScrubEnd()
    }

    var listener: Listener? = null

    private var sections: List<AlphabetIndex.Section> = emptyList()
    private var current: String = ""
    private var touched = -1
    private var fingerY = 0f
    private var fisheye = 0f
    private var fisheyeAnimator: ValueAnimator? = null

    private val rowHeight = resources.getDimension(R.dimen.index_rail_row)
    private val dotRadius = dp(2f)
    private val dotInset = dp(12f)
    private val activeColor = context.getColorFromAttr(R.attr.primaryColor)
    private val idleColor = context.getColorFromAttr(R.attr.tertiaryColor)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = activeColor }

    fun setSections(newSections: List<AlphabetIndex.Section>) {
        if (newSections == sections) return
        sections = newSections
        contentDescription = context.getString(R.string.alphabet_index)
        requestLayout()
        invalidate()
    }

    fun setCurrent(letter: String) {
        if (letter == current) return
        current = letter
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (sections.size * rowHeight + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sections.isEmpty()) return
        val centerX = width / 2f
        val baselineShift = (textPaint.descent() + textPaint.ascent()) / 2f
        sections.forEachIndexed { index, section ->
            val rowCenter = paddingTop + rowHeight * index + rowHeight / 2f
            // Fisheye follows the finger continuously, not row by row
            val distance = (rowCenter - fingerY) / rowHeight
            val influence = fisheye * exp(-(distance * distance) / (2 * SPREAD * SPREAD))
            val scale = 1f + MAX_GROWTH * influence
            val shift = -dp(MAX_SHIFT_DP) * influence

            val isCurrent = touched < 0 && section.letter == current
            textPaint.color = when {
                touched >= 0 -> ColorUtils.blendARGB(idleColor, activeColor, if (index == touched) 1f else influence * 0.6f)
                isCurrent -> activeColor
                else -> idleColor
            }

            canvas.save()
            canvas.translate(centerX + shift, rowCenter)
            canvas.scale(scale, scale)
            canvas.drawText(section.letter, 0f, -baselineShift, textPaint)
            canvas.restore()

            if (isCurrent)
                canvas.drawCircle(centerX - dotInset, rowCenter, dotRadius, dotPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || sections.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onScrubStart()
                moveFinger(event.y)
                animateFisheye(1f)
                select(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                moveFinger(event.y)
                select(event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (touched >= 0) current = sections[touched].letter
                touched = -1
                animateFisheye(0f)
                listener?.onScrubEnd()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun moveFinger(y: Float) {
        fingerY = y.coerceIn(paddingTop.toFloat(), (height - paddingBottom).toFloat())
        listener?.onFingerMoved(top + fingerY)
        invalidate()
    }

    private fun select(y: Float) {
        val index = ((y - paddingTop) / rowHeight).toInt().coerceIn(0, sections.lastIndex)
        if (index == touched) return
        touched = index
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val centerY = top + paddingTop + rowHeight * index + rowHeight / 2f
        listener?.onScrub(sections[index], centerY)
        invalidate()
    }

    private fun animateFisheye(target: Float) {
        fisheyeAnimator?.cancel()
        if (!context.isMotionEnabled()) {
            fisheye = target
            invalidate()
            return
        }
        fisheyeAnimator = ValueAnimator.ofFloat(fisheye, target).apply {
            duration = 150
            interpolator = AnimationUtils.loadInterpolator(context, R.interpolator.standard)
            addUpdateListener {
                fisheye = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        fisheyeAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SPREAD = 1.2f // rows
        const val MAX_GROWTH = 0.6f
        const val MAX_SHIFT_DP = 10f
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
