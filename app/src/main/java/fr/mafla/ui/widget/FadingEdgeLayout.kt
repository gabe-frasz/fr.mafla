package fr.mafla.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import fr.mafla.R
import kotlin.math.min

/**
 * Wraps one scrolling child and softly fades its content where it scrolls under the top or
 * bottom edge. Unlike the built-in fading edges the fade is eased (no visible line where it
 * ends) and it grows in with the scroll, so the first row isn't faded while the list is at
 * the top.
 */
class FadingEdgeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val fadeTop: Float
    private val fadeBottom: Float

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    // Unit gradients (0..1 tall), scaled to the fade height when drawn
    private val topShader = easedShader(fromTop = true)
    private val bottomShader = easedShader(fromTop = false)
    private val matrix = Matrix()

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.FadingEdgeLayout)
        fadeTop = a.getDimension(
            R.styleable.FadingEdgeLayout_fadeTop,
            resources.getDimension(R.dimen.list_fade_top)
        )
        fadeBottom = a.getDimension(
            R.styleable.FadingEdgeLayout_fadeBottom,
            resources.getDimension(R.dimen.list_fade_bottom)
        )
        a.recycle()
        setWillNotDraw(false)
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        // Re-draw the fades as the child scrolls
        when (child) {
            is RecyclerView -> child.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = invalidate()
            })

            else -> child.setOnScrollChangeListener { _, _, _, _, _ -> invalidate() }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val child = getChildAt(0)
        val (above, below) = child?.let { scrollDistances(it) } ?: (0 to 0)
        val topStrength = if (fadeTop > 0) min(1f, above / fadeTop) else 0f
        val bottomStrength = if (fadeBottom > 0) min(1f, below / fadeBottom) else 0f
        if (topStrength <= 0f && bottomStrength <= 0f) {
            super.dispatchDraw(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        super.dispatchDraw(canvas)

        if (topStrength > 0f) {
            matrix.setScale(1f, fadeTop)
            topShader.setLocalMatrix(matrix)
            paint.shader = topShader
            paint.alpha = (255 * topStrength).toInt()
            canvas.drawRect(0f, 0f, w, fadeTop, paint)
        }
        if (bottomStrength > 0f) {
            matrix.setScale(1f, fadeBottom)
            matrix.postTranslate(0f, h - fadeBottom)
            bottomShader.setLocalMatrix(matrix)
            paint.shader = bottomShader
            paint.alpha = (255 * bottomStrength).toInt()
            canvas.drawRect(0f, h - fadeBottom, w, h, paint)
        }
        canvas.restoreToCount(layer)
    }

    /** How far the child's content extends above and below what's visible, in px. */
    private fun scrollDistances(child: View): Pair<Int, Int> = when (child) {
        is RecyclerView -> {
            val offset = child.computeVerticalScrollOffset()
            val rest = child.computeVerticalScrollRange() - child.computeVerticalScrollExtent() - offset
            offset to rest
        }

        is NestedScrollView, is ScrollView -> {
            val content = (child as? android.view.ViewGroup)?.getChildAt(0)?.height ?: 0
            child.scrollY to (content - child.height - child.scrollY)
        }

        else -> (if (child.canScrollVertically(-1)) Int.MAX_VALUE else 0) to
                (if (child.canScrollVertically(1)) Int.MAX_VALUE else 0)
    }

    companion object {
        /**
         * Black = erase, fading to clear. Smoothstep-sampled stops so the fade has no visible
         * start or end.
         */
        private fun easedShader(fromTop: Boolean): LinearGradient {
            val steps = 8
            val positions = FloatArray(steps + 1) { it / steps.toFloat() }
            val colors = IntArray(steps + 1) { i ->
                val t = positions[i]
                val eased = t * t * (3 - 2 * t)
                val erase = if (fromTop) 1f - eased else eased
                (255 * erase).toInt() shl 24
            }
            return LinearGradient(0f, 0f, 0f, 1f, colors, positions, Shader.TileMode.CLAMP)
        }
    }
}
