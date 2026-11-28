package fr.mafla.helper

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * The launcher's widget host, one per process: the panel, the widget picker and pinned-widget
 * requests all go through it. It gets widget updates while the launcher is started.
 */
class MaflaWidgetHost private constructor(context: Context) : AppWidgetHost(context, HOST_ID) {

    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        PanelWidgetHostView(context)

    // Both can throw on some systems (e.g. while the user is being switched); the panel then
    // just shows the last state it got.
    fun startListeningSafely() {
        try {
            startListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopListeningSafely() {
        try {
            stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Drops the host's references to the views it made, so they don't outlive their screen. */
    fun forgetViews() = clearViews()

    companion object {
        private const val HOST_ID = 0x4D61

        @Volatile
        private var instance: MaflaWidgetHost? = null

        fun get(context: Context): MaflaWidgetHost = instance ?: synchronized(this) {
            instance ?: MaflaWidgetHost(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * A widget on the panel. Touching and holding it without moving calls [onLongPress] (the panel
 * enters edit mode); while [editing], the widget's own views get no touches at all.
 */
class PanelWidgetHostView(context: Context) : AppWidgetHostView(context) {

    var onLongPress: (() -> Unit)? = null

    var editing = false
        set(value) {
            field = value
            if (value) removeCallbacks(longPressCheck)
        }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val longPressCheck = Runnable {
        if (!isAttachedToWindow) return@Runnable
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                longPressed = false
                if (!editing) postDelayed(longPressCheck, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) removeCallbacks(longPressCheck)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressCheck)
        }
        return super.dispatchTouchEvent(ev)
    }

    // Once the long press fired, or in edit mode, the rest of the touch is the panel's
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        editing || longPressed || super.onInterceptTouchEvent(ev)

    // Keep the touch over parts of the widget that don't react to it, so a long press there
    // can still be told apart from a tap
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        super.onTouchEvent(ev)
        return true
    }

    // A list inside the widget started scrolling: that's not a long press
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) removeCallbacks(longPressCheck)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressCheck)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressCheck)
        super.onDetachedFromWindow()
    }
}

/** Name of the app that provides this widget, as the user knows it. */
fun AppWidgetProviderInfo.appLabel(context: Context): String {
    val packageName = provider.packageName
    return try {
        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.getSystemService(LauncherApps::class.java).getApplicationInfo(packageName, 0, profile)
        else
            context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (e: Exception) {
        packageName
    }
}

/** The widget's own name, or "" when it has none. */
fun AppWidgetProviderInfo.widgetLabel(context: Context): String = try {
    loadLabel(context.packageManager)?.toString().orEmpty()
} catch (e: Exception) {
    ""
}
