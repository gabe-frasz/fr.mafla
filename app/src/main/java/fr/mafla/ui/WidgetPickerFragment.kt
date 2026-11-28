package fr.mafla.ui

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.mafla.MainActivity
import fr.mafla.R
import fr.mafla.databinding.FragmentWidgetPickerBinding
import fr.mafla.databinding.ItemWidgetPickerAppBinding
import fr.mafla.databinding.ItemWidgetPickerWidgetBinding
import fr.mafla.helper.WidgetSizes
import fr.mafla.helper.appLabel
import fr.mafla.helper.appLabelMatches
import fr.mafla.helper.hideKeyboard
import fr.mafla.helper.isMotionEnabled
import fr.mafla.helper.widgetLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * Every app's widgets, grouped by app, each with a preview. Tapping one adds it to the widgets
 * panel; the system may first ask for permission, and the widget may show its own setup.
 */
class WidgetPickerFragment : BaseFragment() {

    private var _binding: FragmentWidgetPickerBinding? = null
    private val binding get() = _binding!!

    private class WidgetOption(val info: AppWidgetProviderInfo, val label: String, val cols: Int, val rows: Int)
    private class AppWidgets(val key: String, val label: String, val widgets: List<WidgetOption>)

    private sealed class Row {
        class App(val app: AppWidgets, val expanded: Boolean) : Row()
        class Widget(val option: WidgetOption) : Row()
    }

    private var apps: List<AppWidgets> = emptyList()
    private var loaded = false
    private val expanded = HashSet<String>()
    private var query = ""
    private var rows: List<Row> = emptyList()
    private val previews = HashMap<ComponentName, Drawable?>()
    private val adapter = Adapter()
    private var motionEnabled = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWidgetPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyOverlayScrim(view)
        motionEnabled = requireContext().isMotionEnabled()

        binding.widgetList.layoutManager = LinearLayoutManager(requireContext())
        binding.widgetList.adapter = adapter
        binding.back.setOnClickListener {
            binding.search.hideKeyboard()
            findNavController().popBackStack()
        }
        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            refreshRows()
        }
        binding.search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) v.hideKeyboard()
            false
        }
        loadApps()
    }

    private fun loadApps() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { readProviders(context) }
            apps = found
            loaded = true
            if (found.size == 1) expanded += found[0].key
            refreshRows()
        }
    }

    private fun readProviders(context: Context): List<AppWidgets> {
        val manager = AppWidgetManager.getInstance(context)
        val userManager = context.getSystemService(UserManager::class.java)
        val density = context.resources.displayMetrics.density
        val collator = Collator.getInstance()
        val providers = userManager.userProfiles.flatMap { profile ->
            try {
                manager.getInstalledProvidersForProfile(profile)
            } catch (e: Exception) {
                emptyList()
            }
        }
        return providers
            .groupBy { it.provider.packageName + "|" + it.profile }
            .map { (key, infos) ->
                val first = infos.first()
                var label = first.appLabel(context)
                if (first.profile != Process.myUserHandle())
                    label = context.packageManager.getUserBadgedLabel(label, first.profile).toString()
                val widgets = infos.map { info ->
                    val (cols, rows) = WidgetSizes.cells(info, density)
                    WidgetOption(info, info.widgetLabel(context).ifBlank { label }, cols, rows)
                }
                AppWidgets(key, label, widgets.sortedWith(compareBy(collator) { it.label }))
            }
            .sortedWith(compareBy(collator) { it.label })
    }

    private fun buildRows(): List<Row> {
        val search = query.trim()
        val list = ArrayList<Row>()
        for (app in apps) {
            if (search.isEmpty()) {
                val open = app.key in expanded
                list += Row.App(app, open)
                if (open) app.widgets.mapTo(list) { Row.Widget(it) }
            } else {
                val widgets = if (appLabelMatches(app.label, search)) app.widgets
                else app.widgets.filter { appLabelMatches(it.label, search) }
                if (widgets.isEmpty()) continue
                list += Row.App(app, true)
                widgets.mapTo(list) { Row.Widget(it) }
            }
        }
        return list
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshRows() {
        rows = buildRows()
        adapter.notifyDataSetChanged()
        _binding?.noResults?.isVisible = loaded && rows.isEmpty()
    }

    // Opening or closing one app moves only its rows, so the list can animate them
    private fun toggle(app: AppWidgets) {
        if (query.isNotBlank()) return
        val index = rows.indexOfFirst { it is Row.App && it.app.key == app.key }
        if (index < 0) return
        val opening = expanded.add(app.key)
        if (!opening) expanded.remove(app.key)
        rows = buildRows()
        adapter.notifyItemChanged(index, PAYLOAD_EXPANDED)
        if (opening) adapter.notifyItemRangeInserted(index + 1, app.widgets.size)
        else adapter.notifyItemRangeRemoved(index + 1, app.widgets.size)
    }

    private fun pick(option: WidgetOption) {
        _binding?.search?.hideKeyboard()
        (activity as? MainActivity)?.widgetSetup?.add(option.info)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.App) TYPE_APP else TYPE_WIDGET

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_APP) AppHolder(ItemWidgetPickerAppBinding.inflate(inflater, parent, false))
            else WidgetHolder(ItemWidgetPickerWidgetBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
            onBindViewHolder(holder, position, mutableListOf())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            when (val row = rows[position]) {
                is Row.App -> (holder as AppHolder).bind(row, animate = PAYLOAD_EXPANDED in payloads)
                is Row.Widget -> (holder as WidgetHolder).bind(row.option)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? WidgetHolder)?.clear()
        }
    }

    private inner class AppHolder(private val b: ItemWidgetPickerAppBinding) : RecyclerView.ViewHolder(b.root) {
        private var app: AppWidgets? = null

        init {
            b.root.setOnClickListener { app?.let { toggle(it) } }
        }

        fun bind(row: Row.App, animate: Boolean) {
            app = row.app
            b.appName.text = row.app.label
            b.count.text = row.app.widgets.size.toString()
            val rotation = if (row.expanded) 180f else 0f
            b.chevron.animate().cancel()
            if (animate && motionEnabled) {
                b.chevron.animate()
                    .rotation(rotation)
                    .setDuration(resources.getInteger(R.integer.motion_switch).toLong())
                    .setInterpolator(AnimationUtils.loadInterpolator(requireContext(), R.interpolator.standard))
                    .start()
            } else {
                b.chevron.rotation = rotation
            }
        }
    }

    private inner class WidgetHolder(private val b: ItemWidgetPickerWidgetBinding) : RecyclerView.ViewHolder(b.root) {
        private var option: WidgetOption? = null
        private var livePreview: View? = null

        init {
            b.root.setOnClickListener { option?.let { pick(it) } }
        }

        fun bind(option: WidgetOption) {
            this.option = option
            b.widgetName.text = option.label
            b.widgetSize.text = getString(R.string.widget_size, option.cols, option.rows)
            b.root.contentDescription = getString(R.string.add_widget_named, option.label, option.cols, option.rows)
            showPreview(option.info)
        }

        fun clear() {
            livePreview?.let { b.previewFrame.removeView(it) }
            livePreview = null
            b.preview.setImageDrawable(null)
            option = null
        }

        private fun showPreview(info: AppWidgetProviderInfo) {
            livePreview?.let { b.previewFrame.removeView(it) }
            livePreview = null
            val context = requireContext()
            val density = resources.displayMetrics.density
            val image = previews.getOrPut(info.provider) {
                try {
                    info.loadPreviewImage(context, resources.displayMetrics.densityDpi)
                } catch (e: Exception) {
                    null
                }
            }
            // Newer widgets may only describe their preview as a layout: draw it live
            if (image == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.previewLayout != 0) {
                try {
                    val preview = AppWidgetHostView(context).apply {
                        setAppWidget(AppWidgetManager.INVALID_APPWIDGET_ID, info)
                        updateAppWidget(RemoteViews(info.provider.packageName, info.previewLayout))
                    }
                    val height = (WidgetSizes.defaultHeightDp(info, density) * density).toInt()
                        .coerceAtMost(resources.getDimensionPixelSize(R.dimen.widget_preview_max_height))
                    b.previewFrame.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height))
                    livePreview = preview
                    b.preview.isVisible = false
                    return
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            b.preview.setImageDrawable(image)
            b.preview.isVisible = image != null
        }
    }

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_WIDGET = 1
        const val PAYLOAD_EXPANDED = "expanded"
    }
}
