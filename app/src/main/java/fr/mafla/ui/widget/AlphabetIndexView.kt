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
import kotlin.math.abs

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
        fun onScrubEnd()
    }

    var listener: Listener? = null

    private var sections: List<AlphabetIndex.Section> = emptyList()
    private var current: String = ""
    private var touched = -1
    private var fisheye = 0f
    private var fisheyeAnimator: ValueAnimator? = null

    private val rowHeight = resources.getDimension(R.dimen.index_rail_row)
    private val dotRadius = dp(2f)
    private val dotInset = dp(6f)
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
            val distance = if (touched >= 0) abs(index - touched) else Int.MAX_VALUE
            val scale = 1f + fisheye * when (distance) {
                0 -> 0.6f
                1 -> 0.25f
                2 -> 0.1f
                else -> 0f
            }
            val shift = -fisheye * dp(
                when (distance) {
                    0 -> 14f
                    1 -> 8f
                    2 -> 3f
                    else -> 0f
                }
            )
            val isCurrent = if (touched >= 0) index == touched else section.letter == current
            textPaint.color = if (isCurrent) activeColor else idleColor

            canvas.save()
            canvas.translate(centerX + shift, rowCenter)
            canvas.scale(scale, scale)
            canvas.drawText(section.letter, 0f, -baselineShift, textPaint)
            canvas.restore()

            if (isCurrent && touched < 0)
                canvas.drawCircle(dotInset, rowCenter, dotRadius, dotPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sections.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onScrubStart()
                animateFisheye(1f)
                select(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
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

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
