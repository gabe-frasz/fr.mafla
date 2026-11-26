package fr.mafla.ui

import android.animation.Animator
import android.animation.ValueAnimator
import androidx.fragment.app.Fragment
import fr.mafla.helper.isMotionEnabled

open class BaseFragment : Fragment() {

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? {
        if (nextAnim != 0 && !requireContext().isMotionEnabled())
            return ValueAnimator.ofFloat(0f, 1f).setDuration(0)
        return null
    }
}
