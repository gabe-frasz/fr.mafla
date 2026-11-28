package fr.mafla.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import fr.mafla.R
import fr.mafla.helper.isMotionEnabled
import kotlin.math.abs
import kotlin.math.min

/**
 * Holds the home screen (first child) and the widgets panel (second child). A sideways drag
 * brings the panel in from the right edge under the finger while the home screen drifts a
 * quarter of the way left and fades out. Letting go settles it open or closed: the way a fast
 * swipe was going, or else whichever side is nearer. A scrim behind both deepens as the panel
 * opens, so the panel reads over any wallpaper.
 */
class SwipePanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        /** The panel is now headed open or closed, after a drag or from code. */
        fun onPanelTarget(open: Boolean)
    }

    var listener: Listener? = null

    /** When false, a leftwards drag on the home screen is left alone (it opens an app instead). */
    var canOpen = true

    /** 0 is the home screen, 1 the panel fully open. */
    var progress = 0f
        private set

    /** Where the panel rests, or is headed. */
    var isOpen = false
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = FLING_DP_PER_SECOND * resources.displayMetrics.density
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartProgress = 0f
    private var settleAnimator: ValueAnimator? = null
    private var motionEnabled = true
    private val scrim = ColorDrawable(0)

    init {
        background = scrim
        applyProgress()
    }

    fun setScrimColor(color: Int) {
        scrim.color = color
        applyProgress()
    }

    fun open(animate: Boolean = true) = settle(open = true, velocity = 0f, animate = animate)

    fun close(animate: Boolean = true) = settle(open = false, velocity = 0f, animate = animate)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Reads settings: once per attach, not on every frame
        motionEnabled = context.isMotionEnabled()
    }

    override fun onDetachedFromWindow() {
        settleAnimator?.cancel()
        stopTracking()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The panel's offset depends on the width
        applyProgress()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTracking(ev)
                // Caught while settling: carry on from wherever it is
                if (settleAnimator?.isRunning == true) {
                    beginDrag(ev.x)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (shouldDrag(ev)) {
                    beginDrag(ev.x)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTracking()
        }
        return false
    }

    // Reached when no child took the touch, or once a drag began
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // onInterceptTouchEvent already started tracking it
            MotionEvent.ACTION_DOWN -> Unit

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (!dragging) {
                    if (!shouldDrag(ev)) return true
                    beginDrag(ev.x)
                }
                setProgress(dragStartProgress - (ev.x - dragStartX) / width.coerceAtLeast(1))
            }

            MotionEvent.ACTION_UP -> if (dragging) release() else stopTracking()

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(open = progress > 0.5f, velocity = 0f, animate = true)
                stopTracking()
            }
        }
        return true
    }

    private fun shouldDrag(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) <= touchSlop || abs(dx) <= abs(dy)) return false
        // Leftwards opens (when allowed), rightwards closes
        return if (dx < 0) progress < 1f && (canOpen || progress > 0f) else progress > 0f
    }

    private fun beginDrag(x: Float) {
        settleAnimator?.cancel()
        dragging = true
        dragStartX = x
        dragStartProgress = progress
        parent?.requestDisallowInterceptTouchEvent(true)
        setLayers(true)
        applyProgress()
    }

    private fun release() {
        val tracker = velocityTracker
        tracker?.computeCurrentVelocity(1000)
        val velocity = tracker?.xVelocity ?: 0f
        stopTracking()
        val open = when {
            velocity < -flingVelocity -> true
            velocity > flingVelocity -> false
            else -> progress > 0.5f
        }
        settle(open, velocity, animate = true)
    }

    private fun settle(open: Boolean, velocity: Float, animate: Boolean) {
        dragging = false
        settleAnimator?.cancel()
        if (open != isOpen) {
            isOpen = open
            listener?.onPanelTarget(open)
        }
        val target = if (open) 1f else 0f
        if (!animate || !motionEnabled || width == 0 || progress == target) {
            setProgress(target)
            setLayers(false)
            return
        }

        val distance = abs(target - progress)
        var duration = (resources.getInteger(R.integer.motion_panel) * distance).toLong()
        // A fast swipe finishes about as fast as the finger was going. The curve starts at
        // roughly twice its average speed, hence the 2.
        val towardsTarget = if (open) -velocity else velocity
        if (towardsTarget > 0f)
            duration = min(duration, (distance * width / towardsTarget * 1000f * 2f).toLong())

        settleAnimator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = duration.coerceAtLeast(MIN_SETTLE_MS)
            interpolator = AnimationUtils.loadInterpolator(context, R.interpolator.standard_decelerate)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) setLayers(false)
                }
            })
            start()
        }
    }

    private fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        applyProgress()
    }

    private fun applyProgress() {
        val home = getChildAt(0) ?: return
        val panel = getChildAt(1) ?: return
        val p = progress
        val w = width.toFloat()

        home.translationX = -HOME_SHIFT * w * p
        home.alpha = (1f - p * HOME_FADE).coerceAtLeast(0f)
        panel.translationX = w * (1f - p)

        // Whatever is fully off screen leaves touch and accessibility alone
        val homeVisibility = if (p >= 1f) View.INVISIBLE else View.VISIBLE
        if (home.visibility != homeVisibility) home.visibility = homeVisibility
        val panelVisibility = if (p <= 0f && !dragging) View.INVISIBLE else View.VISIBLE
        if (panel.visibility != panelVisibility) panel.visibility = panelVisibility

        scrim.alpha = (255 * p).toInt()
    }

    // While moving, both screens are drawn from GPU layers: moving and fading a layer is cheap
    private fun setLayers(on: Boolean) {
        val type = if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.layerType != type) child.setLayerType(type, null)
        }
    }

    private fun startTracking(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        dragging = false
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.clear()
        tracker.addMovement(ev)
    }

    private fun stopTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private companion object {
        /** How far left the home screen drifts as the panel opens, as a share of the width. */
        const val HOME_SHIFT = 0.25f

        /** The home screen is gone by the time the panel is 1 / HOME_FADE open. */
        const val HOME_FADE = 1.6f
        const val FLING_DP_PER_SECOND = 400f
        const val MIN_SETTLE_MS = 120L
    }
}
