package fr.mafla.ui

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Canvas
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.mafla.R
import fr.mafla.data.PanelWidget
import fr.mafla.data.Prefs
import fr.mafla.databinding.ItemPanelFooterBinding
import fr.mafla.databinding.ItemPanelWidgetBinding
import fr.mafla.databinding.ViewWidgetsPanelBinding
import fr.mafla.helper.MaflaWidgetHost
import fr.mafla.helper.PanelWidgetHostView
import fr.mafla.helper.WidgetSizes
import fr.mafla.helper.appLabel
import fr.mafla.helper.getColorFromAttr
import fr.mafla.helper.isMotionEnabled
import fr.mafla.helper.widgetLabel
import kotlin.math.roundToInt

/**
 * The widgets panel beside the home screen: the user's widgets in one column, an empty state
 * when there are none, and an edit mode to reorder, resize and remove them.
 *
 * [widgetViews] outlives the panel (it belongs to the activity), so a widget isn't rebuilt
 * every time the home screen comes back from another screen. Call [release] when the home
 * screen's view goes away.
 */
class WidgetsPanel(
    private val binding: ViewWidgetsPanelBinding,
    private val widgetViews: MutableMap<Int, PanelWidgetHostView>,
    private val onAddWidget: () -> Unit,
) {
    private val context = binding.root.context
    private val density = context.resources.displayMetrics.density
    private val outlineOffset = context.resources.getDimensionPixelSize(R.dimen.widget_outline_offset)
    private val prefs = Prefs(context)
    private val host = MaflaWidgetHost.get(context)
    private val manager = AppWidgetManager.getInstance(context)
    private val motionEnabled = context.isMotionEnabled()
    private val widthDp = WidgetSizes.panelWidthDp(context)

    private class Shown(var widget: PanelWidget, val info: AppWidgetProviderInfo)

    /** Widgets shown, top to bottom. */
    private val shown = mutableListOf<Shown>()

    /** Stored widgets whose app can't be reached right now (a paused work profile, say): kept, not shown. */
    private var unavailable: List<PanelWidget> = emptyList()
    private var loadedJson: String? = null

    var editing = false
        private set

    private val hasFooter get() = !editing && shown.isNotEmpty()
    private val adapter = Adapter()
    private val touchHelper = ItemTouchHelper(ReorderCallback())

    init {
        binding.widgetsList.layoutManager = LinearLayoutManager(context)
        binding.widgetsList.adapter = adapter
        binding.widgetsList.setItemViewCacheSize(CACHED_ROWS)
        touchHelper.attachToRecyclerView(binding.widgetsList)
        binding.panelEdit.setOnClickListener { setEditing(!editing) }
        binding.emptyAdd.setOnClickListener { onAddWidget() }
        binding.addWidgetPinned.setOnClickListener { onAddWidget() }
        initEmptySpaceLongPress()
        reload()
    }

    /** Reads the stored widgets again, if they changed since the last time (or always, with [force]). */
    @SuppressLint("NotifyDataSetChanged")
    fun reload(force: Boolean = false) {
        val json = prefs.panelWidgetsJson
        if (!force && json == loadedJson) return
        loadedJson = json

        val visible = ArrayList<Shown>()
        val missing = ArrayList<PanelWidget>()
        prefs.panelWidgets.forEach { widget ->
            val info = manager.getAppWidgetInfo(widget.id)
            if (info != null) visible += Shown(widget, info) else missing += widget
        }
        shown.clear()
        shown.addAll(visible)
        unavailable = missing
        val ids = visible.mapTo(HashSet()) { it.widget.id }
        widgetViews.keys.retainAll(ids)
        if (shown.isEmpty()) editing = false
        adapter.notifyDataSetChanged()
        updateChrome(animate = false)
    }

    fun scrollTo(widgetId: Int) {
        val position = shown.indexOfFirst { it.widget.id == widgetId }
        if (position >= 0) binding.widgetsList.post { binding.widgetsList.smoothScrollToPosition(position) }
    }

    fun setEditing(on: Boolean) {
        val target = on && shown.isNotEmpty()
        if (target == editing) return
        val hadFooter = hasFooter
        editing = target
        adapter.notifyItemRangeChanged(0, shown.size, PAYLOAD_EDITING)
        if (hadFooter && !hasFooter) adapter.notifyItemRemoved(shown.size)
        else if (!hadFooter && hasFooter) adapter.notifyItemInserted(shown.size)
        updateChrome(animate = true)
    }

    /** Lets go of the widget views, which stay cached for the next time the panel is built. */
    fun release() {
        widgetViews.values.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.onLongPress = null
            view.editing = false
            view.alpha = 1f
        }
    }

    private fun updateChrome(animate: Boolean) {
        if (animate && motionEnabled) {
            TransitionManager.beginDelayedTransition(
                binding.root,
                AutoTransition()
                    .setDuration(context.resources.getInteger(R.integer.motion_switch).toLong())
                    .setInterpolator(AnimationUtils.loadInterpolator(context, R.interpolator.standard))
                    // The list animates its own rows
                    .excludeChildren(binding.widgetsList, true)
            )
        }
        val empty = shown.isEmpty()
        binding.emptyState.isVisible = empty
        binding.widgetsListFrame.isVisible = !empty
        binding.panelEdit.isVisible = !empty
        binding.panelEdit.setText(if (editing) R.string.done else R.string.edit)
        binding.panelEdit.setTextColor(
            context.getColorFromAttr(if (editing) R.attr.primaryColor else R.attr.secondaryColor)
        )
        binding.editHint.isVisible = editing
        binding.addWidgetPinned.isVisible = editing
    }

    private fun save() {
        prefs.panelWidgets = shown.map { it.widget } + unavailable
        loadedJson = prefs.panelWidgetsJson
    }

    private fun remove(position: Int) {
        val removed = shown.removeAt(position)
        widgetViews.remove(removed.widget.id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        host.deleteAppWidgetId(removed.widget.id)
        save()
        adapter.notifyItemRemoved(position)
        if (shown.isEmpty()) editing = false
        updateChrome(animate = true)
    }

    // Touching and holding empty space adds a widget, or with widgets there, starts editing
    @SuppressLint("ClickableViewAccessibility")
    private fun initEmptySpaceLongPress() {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onLongPress(e: MotionEvent) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (shown.isEmpty()) onAddWidget() else setEditing(true)
            }
        })
        binding.root.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
        // Space below the last widget belongs to the list
        binding.widgetsList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var blank = false

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) blank = rv.findChildViewUnder(e.x, e.y) == null
                if (blank) detector.onTouchEvent(e)
                return false
            }
        })
    }

    private fun caption(info: AppWidgetProviderInfo, appName: String, widgetName: String): String {
        val (cols, rows) = WidgetSizes.cells(info, density)
        val size = context.getString(R.string.widget_size, cols, rows)
        return listOfNotNull(appName, widgetName.takeIf { it.isNotBlank() && it != appName }, size)
            .joinToString(" · ")
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        init {
            setHasStableIds(true)
        }

        override fun getItemCount() = shown.size + if (hasFooter) 1 else 0

        override fun getItemViewType(position: Int) = if (position < shown.size) TYPE_WIDGET else TYPE_FOOTER

        override fun getItemId(position: Int): Long =
            if (position < shown.size) shown[position].widget.id.toLong() else FOOTER_ID

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_WIDGET) WidgetHolder(ItemPanelWidgetBinding.inflate(inflater, parent, false))
            else FooterHolder(ItemPanelFooterBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
            onBindViewHolder(holder, position, mutableListOf())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (holder !is WidgetHolder) return
            if (PAYLOAD_EDITING in payloads) holder.bindEditing(animate = true)
            else holder.bind(shown[position])
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? WidgetHolder)?.unbind()
        }
    }

    private inner class FooterHolder(binding: ItemPanelFooterBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.addWidget.setOnClickListener { onAddWidget() }
        }
    }

    private inner class WidgetHolder(private val b: ItemPanelWidgetBinding) : RecyclerView.ViewHolder(b.root) {

        private var item: Shown? = null
        private var widgetView: PanelWidgetHostView? = null
        private var resizeStartY = 0f
        private var resizeStartDp = 0
        private var resizeDp = 0

        init {
            b.remove.setOnClickListener {
                val position = bindingAdapterPosition
                if (position in shown.indices) remove(position)
            }
            b.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && editing) touchHelper.startDrag(this)
                false
            }
            b.resizeHandle.setOnTouchListener { handle, event -> resize(handle, event) }
        }

        fun bind(entry: Shown) {
            item = entry
            val view = widgetViews.getOrPut(entry.widget.id) {
                host.createView(context, entry.widget.id, entry.info) as PanelWidgetHostView
            }
            if (view.parent !== b.frame) {
                (view.parent as? ViewGroup)?.removeView(view)
                b.frame.removeAllViews()
                b.frame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            widgetView = view
            view.onLongPress = { setEditing(true) }

            val heightDp = WidgetSizes.heightDp(entry.widget.heightDp, entry.info, density)
            setHeight(heightDp)
            WidgetSizes.report(view, widthDp, heightDp)

            val appName = entry.info.appLabel(context)
            val widgetName = entry.info.widgetLabel(context)
            val name = widgetName.ifBlank { appName }
            b.label.text = caption(entry.info, appName, widgetName)
            b.dragHandle.contentDescription = context.getString(R.string.reorder_widget, name)
            b.resizeHandle.contentDescription = context.getString(R.string.resize_widget, name)
            b.remove.contentDescription = context.getString(R.string.remove_widget, name)
            bindEditing(animate = false)
        }

        fun bindEditing(animate: Boolean) {
            val entry = item ?: return
            val resizable = WidgetSizes.canResize(entry.info)
            val fade = animate && motionEnabled
            b.caption.isVisible = editing
            b.resizeHandle.isVisible = editing && resizable
            b.frame.background = if (editing) ContextCompat.getDrawable(context, R.drawable.bg_widget_edit) else null
            b.root.updatePadding(bottom = bottomGap(resizable))

            widgetView?.let { view ->
                view.editing = editing
                val alpha = if (editing) EDITING_ALPHA else 1f
                view.animate().cancel()
                if (fade) view.animate().alpha(alpha).setDuration(fadeDuration()).start()
                else view.alpha = alpha
            }
            if (fade && editing) {
                b.caption.alpha = 0f
                b.caption.animate().alpha(1f).setDuration(fadeDuration()).start()
            }
        }

        fun unbind() {
            b.frame.removeAllViews()
            widgetView = null
            item = null
        }

        private fun setHeight(heightDp: Int) {
            b.frame.updateLayoutParams { height = (heightDp * density).roundToInt() + 2 * outlineOffset }
        }

        // Normal: 16dp between widgets. Editing: 28dp, part of it taken by the resize handle.
        private fun bottomGap(resizable: Boolean): Int {
            val res = context.resources
            val gap = res.getDimensionPixelSize(if (editing) R.dimen.widget_gap_editing else R.dimen.widget_gap)
            val handle = if (editing && resizable) b.resizeHandle.layoutParams.height / 2 else 0
            return (gap - 2 * outlineOffset - handle).coerceAtLeast(0)
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun resize(handle: View, event: MotionEvent): Boolean {
            val entry = item ?: return false
            if (!editing) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Neither the list nor the panel should take this touch
                    handle.parent?.requestDisallowInterceptTouchEvent(true)
                    resizeStartY = event.rawY
                    resizeStartDp = WidgetSizes.heightDp(entry.widget.heightDp, entry.info, density)
                    resizeDp = resizeStartDp
                    handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }

                MotionEvent.ACTION_MOVE -> {
                    val wanted = resizeStartDp + ((event.rawY - resizeStartY) / density).roundToInt()
                    val clamped = wanted.coerceIn(
                        WidgetSizes.minHeightDp(entry.info, density),
                        WidgetSizes.maxHeightDp(entry.info, density),
                    )
                    if (clamped != resizeDp) {
                        resizeDp = clamped
                        setHeight(clamped)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (resizeDp != resizeStartDp) {
                        entry.widget = entry.widget.copy(heightDp = resizeDp)
                        widgetView?.let { WidgetSizes.report(it, widthDp, resizeDp) }
                        save()
                    }
                }
            }
            return true
        }

        private fun fadeDuration() = context.resources.getInteger(R.integer.motion_fade).toLong()
    }

    private inner class ReorderCallback : ItemTouchHelper.Callback() {

        override fun isLongPressDragEnabled() = false

        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            if (editing && viewHolder is WidgetHolder) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            else 0

        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ) = target is WidgetHolder

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from !in shown.indices || to !in shown.indices) return false
            shown.add(to, shown.removeAt(from))
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        // Moved by translation only: no lifting with a shadow
        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            viewHolder.itemView.translationX = dX
            viewHolder.itemView.translationY = dY
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            viewHolder.itemView.translationX = 0f
            viewHolder.itemView.translationY = 0f
            save()
        }
    }

    private companion object {
        const val TYPE_WIDGET = 0
        const val TYPE_FOOTER = 1
        const val FOOTER_ID = -1L
        const val PAYLOAD_EDITING = "editing"
        const val CACHED_ROWS = 16
        const val EDITING_ALPHA = 0.6f
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}
