package fr.mafla.ui

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.View
import androidx.fragment.app.Fragment
import fr.mafla.R
import fr.mafla.helper.BackgroundBlur
import fr.mafla.helper.getColorFromAttr
import fr.mafla.helper.isMotionEnabled

open class BaseFragment : Fragment() {

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
