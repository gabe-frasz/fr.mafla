package fr.mafla.helper

import android.animation.ValueAnimator
import android.os.Build
import android.view.Window
import android.view.animation.AnimationUtils
import androidx.annotation.RequiresApi
import fr.mafla.R

/**
 * Blurs the wallpaper behind the launcher window (Android 12+), for screens drawn over it:
 * the app list and the settings screens. The home screen keeps the wallpaper sharp.
 * Does nothing where the system has cross-window blur off (battery saver, unsupported
 * hardware, the developer option) or on e-ink displays.
 */
object BackgroundBlur {

    private class State(var fraction: Float = 0f, var animator: ValueAnimator? = null)

    /** True when the wallpaper behind the window can actually be blurred right now. */
    fun isAvailable(window: Window): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !window.context.isEinkDisplay() &&
                window.windowManager.isCrossWindowBlurEnabled

    /** Animates the blur to fully on ([on] = true) or off, alongside a screen change. */
    fun set(window: Window, on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        animateTo(window, if (on && isAvailable(window)) 1f else 0f)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun animateTo(window: Window, target: Float) {
        val decor = window.decorView
        val state = decor.getTag(R.id.background_blur_state) as? State
            ?: State().also { decor.setTag(R.id.background_blur_state, it) }
        state.animator?.cancel()
        if (state.fraction == target) return

        val context = window.context
        if (!context.isMotionEnabled()) {
            apply(window, state, target)
            return
        }
        state.animator = ValueAnimator.ofFloat(state.fraction, target).apply {
            duration = context.resources.getInteger(R.integer.motion_blur).toLong()
            interpolator = AnimationUtils.loadInterpolator(context, R.interpolator.standard)
            addUpdateListener { apply(window, state, it.animatedValue as Float) }
            start()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun apply(window: Window, state: State, fraction: Float) {
        state.fraction = fraction
        val radius = window.context.resources.getDimensionPixelSize(R.dimen.overlay_blur_radius)
        window.setBackgroundBlurRadius((radius * fraction).toInt())
    }
}
