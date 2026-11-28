package fr.mafla.helper

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import fr.mafla.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sizes of widgets on the panel. A panel widget is always as wide as the screen margins allow;
 * its height starts from the rows it asks for on a home screen grid and can then be changed in
 * edit mode, within the limits the widget sets.
 */
object WidgetSizes {

    /** Height of one home-screen row on the panel. */
    const val CELL_HEIGHT_DP = 88
    private const val MIN_HEIGHT_DP = 40
    private const val MAX_HEIGHT_DP = 640

    /**
     * Cells needed for a widget's minimum size, by Android's sizing rule (n cells take
     * 70 × n − 30 dp), so a widget made for two rows comes out as 2.
     */
    fun cellsFor(minSizeDp: Float): Int = ((minSizeDp + 30f) / 70f).roundToInt().coerceAtLeast(1)

    /** Columns and rows the widget is designed for, as shown next to its name. */
    fun cells(info: AppWidgetProviderInfo, density: Float): Pair<Int, Int> {
        var cols = cellsFor(info.minWidth / density)
        var rows = cellsFor(info.minHeight / density)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (info.targetCellWidth > 0) cols = info.targetCellWidth
            if (info.targetCellHeight > 0) rows = info.targetCellHeight
        }
        return cols to rows
    }

    fun canResize(info: AppWidgetProviderInfo): Boolean =
        info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0

    /** Height a newly added widget gets. */
    fun defaultHeightDp(info: AppWidgetProviderInfo, density: Float): Int {
        val (_, rows) = cells(info, density)
        val minDp = (info.minHeight / density).roundToInt()
        return max(rows * CELL_HEIGHT_DP, minDp).coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
    }

    fun minHeightDp(info: AppWidgetProviderInfo, density: Float): Int {
        val resizeMin = info.minResizeHeight
        val px = if (canResize(info) && resizeMin in 1 until info.minHeight) resizeMin else info.minHeight
        return (px / density).roundToInt().coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
    }

    fun maxHeightDp(info: AppWidgetProviderInfo, density: Float): Int {
        if (!canResize(info)) return defaultHeightDp(info, density)
        val max = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.maxResizeHeight > 0)
            (info.maxResizeHeight / density).roundToInt()
        else MAX_HEIGHT_DP
        return max.coerceIn(minHeightDp(info, density), MAX_HEIGHT_DP)
    }

    /** The height to show a stored widget at: what the user set, if the widget allows it. */
    fun heightDp(storedDp: Int, info: AppWidgetProviderInfo, density: Float): Int =
        if (storedDp <= 0 || !canResize(info)) defaultHeightDp(info, density)
        else storedDp.coerceIn(minHeightDp(info, density), maxHeightDp(info, density))

    /** Width of every panel widget. */
    fun panelWidthDp(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val margin = context.resources.getDimensionPixelSize(R.dimen.screen_margin)
        return ((metrics.widthPixels - 2 * margin) / metrics.density).roundToInt()
    }

    /** Tells the widget the size it's shown at, so it can pick a fitting layout. */
    fun report(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
        val size = widthDp to heightDp
        if (view.getTag(R.id.widget_reported_size) == size) return
        view.setTag(R.id.widget_reported_size, size)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
            else
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(Bundle(), widthDp, heightDp, widthDp, heightDp)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
