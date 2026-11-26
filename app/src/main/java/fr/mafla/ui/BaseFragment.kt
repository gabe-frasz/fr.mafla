package fr.mafla.ui

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import fr.mafla.R
import fr.mafla.helper.BackgroundBlur
import fr.mafla.helper.getColorFromAttr
import fr.mafla.helper.isMotionEnabled

open class BaseFragment : Fragment() {

    /**
     * Screen transitions fade and move the whole screen. Drawing it from a hardware layer while
     * that runs lets the GPU move one texture instead of redrawing every view each frame.
     */
    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (nextAnim == 0 || !requireContext().isMotionEnabled()) return null
        val animation = try {
            AnimationUtils.loadAnimation(requireContext(), nextAnim)
        } catch (e: Exception) {
            return null
        }
        val target = view ?: return animation
        animation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                target.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            override fun onAnimationEnd(animation: Animation) {
                target.post { target.setLayerType(View.LAYER_TYPE_NONE, null) }
            }

            override fun onAnimationRepeat(animation: Animation) = Unit
        })
        return animation
    }

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? {
        if (nextAnim != 0 && !requireContext().isMotionEnabled())
            return ValueAnimator.ofFloat(0f, 1f).setDuration(0)
        return null
    }

    /**
     * Screens drawn over the wallpaper (app list, settings) get a light scrim when the
     * wallpaper behind them is blurred, and a stronger one when it can't be.
     */
    protected fun applyOverlayScrim(root: View) {
        val window = activity?.window ?: return
        if (!BackgroundBlur.isAvailable(window))
            root.setBackgroundColor(requireContext().getColorFromAttr(R.attr.dialogShadeColor))
    }
}
