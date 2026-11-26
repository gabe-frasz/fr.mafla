package fr.mafla.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import fr.mafla.R
import fr.mafla.data.AppModel
import fr.mafla.data.Constants
import fr.mafla.databinding.AdapterAppDrawerBinding
import fr.mafla.databinding.AdapterAppDrawerMenuBinding
import fr.mafla.databinding.AdapterAppDrawerRenameBinding
import fr.mafla.databinding.AdapterPrivateSpaceHeaderBinding
import fr.mafla.helper.AlphabetIndex
import fr.mafla.helper.appLabelMatches
import fr.mafla.helper.getColorFromAttr
import fr.mafla.helper.hideKeyboard
import fr.mafla.helper.isMotionEnabled
import fr.mafla.helper.isSystemApp
import fr.mafla.helper.showKeyboard

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
    private val appGroupsListener: ((AppModel, View) -> Unit)? = null,
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.identity == newItem.identity

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    private var autoLaunch = true
    private var isBangSearch = false
    private var autoLaunchedQuery: String? = null
    var allowAutoLaunch = true
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    /** Shows the section letter in the gutter. Off while searching. */
    var indexEnabled = true
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }
    var isSearching = false
        private set
    private var currentSection = ""

    /** Called with true while a row shows its actions (uninstall, rename...), false after. */
    var onMenuOpenChanged: ((Boolean) -> Unit)? = null
    private var openMenuHolder: ViewHolder? = null
    private val menuListener: (ViewHolder, Boolean) -> Unit = { holder, open ->
        if (open) {
            if (openMenuHolder !== holder) openMenuHolder?.closeMenu()
            openMenuHolder = holder
            onMenuOpenChanged?.invoke(true)
        } else if (openMenuHolder === holder) {
            openMenuHolder = null
            onMenuOpenChanged?.invoke(false)
        }
    }

    /** Called after every list change, with the list now on screen. */
    var onListChanged: ((List<AppModel>) -> Unit)? = null

    private val showLetters get() = indexEnabled && !isSearching
    private var recyclerView: RecyclerView? = null
    private var rowStyle: RowStyle? = null

    private val rowActions by lazy {
        RowActions(
            flag = flag,
            click = appClickListener,
            delete = appDeleteListener,
            info = appInfoListener,
            hide = appHideListener,
            rename = appRenameListener,
            groups = appGroupsListener,
            menuState = menuListener,
        )
    }

    // Gutter letter per row, worked out once per list instead of on every bind
    private var sectionLetters: Array<String> = emptyArray()
    private var sectionLettersFor: List<AppModel>? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /**
     * Highlights the gutter letter of the section now at the top of the list. Repaints the
     * rows on screen directly: no adapter notification, so no extra layout pass while scrolling.
     */
    fun setCurrentSection(letter: String) {
        if (letter == currentSection) return
        currentSection = letter
        val list = recyclerView ?: return
        for (i in 0 until list.childCount) {
            (list.getChildViewHolder(list.getChildAt(i)) as? ViewHolder)?.highlightLetter(letter)
        }
    }

    private fun sectionLetterAt(position: Int): String {
        if (!showLetters) return ""
        val list = appFilteredList
        if (sectionLettersFor !== list || sectionLetters.size != list.size) {
            var previous = ""
            sectionLetters = Array(list.size) { i ->
                val app = list[i]
                val letter = if (app is AppModel.PrivateSpaceHeader) "" else AlphabetIndex.sectionOf(app.appLabel)
                val shown = if (letter.isNotEmpty() && letter != previous) letter else ""
                previous = letter
                shown
            }
            sectionLettersFor = list
        }
        return sectionLetters.getOrElse(position) { "" }
    }

    override fun getItemViewType(position: Int): Int {
        return when (appFilteredList.getOrNull(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> ViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                ),
                rowActions,
                rowStyle ?: RowStyle(parent.context).also { rowStyle = it },
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            if (appFilteredList.isEmpty() || position == RecyclerView.NO_POSITION) return
            val appModel = appFilteredList[holder.bindingAdapterPosition]
            when (holder) {
                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is ViewHolder -> {
                    holder.bind(appLabelGravity, myUserHandle, appModel)
                    holder.bindLetter(sectionLetterAt(holder.bindingAdapterPosition), currentSection, showLetters)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                isSearching = !charSearch.isNullOrBlank()
                autoLaunch = allowAutoLaunch && (charSearch?.startsWith(" ")?.not() ?: true)

                val appFilteredList = (if (charSearch.isNullOrBlank()) appsList
                else appsList.filter { app ->
                    app !is AppModel.PrivateSpaceHeader && appLabelMatches(app.appLabel, charSearch)
                } as MutableList<AppModel>)

                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    submitList(appFilteredList) {
                        onListChanged?.invoke(appFilteredList)
                        autoLaunch(constraint?.toString())
                    }
                }
            }
        }
    }

    private fun autoLaunch(query: String?) {
        // App list refreshes re-run the current query, so launch only once until it changes
        if (query == autoLaunchedQuery) return
        autoLaunchedQuery = null
        try {
            if (itemCount == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
                && appFilteredList.isNotEmpty()
                && appFilteredList[0] !is AppModel.PrivateSpaceHeader
                // Skip the empty bottom-padding item, left alone when the app list is empty
                && appFilteredList[0].appPackage.isNotEmpty()
            ) {
                autoLaunchedQuery = query
                appClickListener(appFilteredList[0])
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setAppList(appsList: MutableList<AppModel>) {
        // Add empty app for bottom padding in recyclerview and assign to list
        appsList.add(
            AppModel.App(
                appLabel = "",
                key = null,
                appPackage = "",
                activityClassName = "",
                isNew = false,
                user = android.os.Process.myUserHandle()
            )
        )
        this.appsList = appsList
        this.appFilteredList = appsList
        submitList(appsList) { onListChanged?.invoke(appFilteredList) }
    }

    fun launchFirstInList() {
        val first = appFilteredList.firstOrNull { it !is AppModel.PrivateSpaceHeader && it.appPackage.isNotEmpty() }
        if (first != null) appClickListener(first)
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    /** Listeners shared by every row, created once per adapter. */
    class RowActions(
        val flag: Int,
        val click: (AppModel) -> Unit,
        val delete: (AppModel) -> Unit,
        val info: (AppModel) -> Unit,
        val hide: (AppModel, Int) -> Unit,
        val rename: (AppModel, String) -> Unit,
        val groups: ((AppModel, View) -> Unit)?,
        val menuState: (ViewHolder, Boolean) -> Unit,
    )

    /** Theme colors and sizes, resolved once instead of on every bind. */
    class RowStyle(context: Context) {
        val activeLetter = context.getColorFromAttr(R.attr.primaryColor)
        val idleLetter = context.getColorFromAttr(R.attr.tertiaryColor)
        val gutterStart = context.resources.getDimensionPixelSize(R.dimen.list_text_start)
        val plainStart = context.resources.getDimensionPixelSize(R.dimen.screen_margin)
    }

    class ViewHolder(
        private val binding: AdapterAppDrawerBinding,
        private val actions: RowActions,
        private val style: RowStyle,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var app: AppModel? = null
        private var letter = ""

        // Inflated on the first long press of this row, then reused
        private var menu: AdapterAppDrawerMenuBinding? = null
        private var renameRow: AdapterAppDrawerRenameBinding? = null

        init {
            binding.appTitle.setOnClickListener { app?.let(actions.click) }
            binding.appTitle.setOnLongClickListener {
                showMenu()
                true
            }
        }

        fun bind(appLabelGravity: Int, myUserHandle: UserHandle, appModel: AppModel) = with(binding) {
            app = appModel
            closeMenu()

            // Show indicators in title based on app type and state
            appTitle.text = if (appModel.isNew) appModel.appLabel + " ✦" else appModel.appLabel
            if (appTitle.gravity != appLabelGravity) appTitle.gravity = appLabelGravity
            otherProfileIndicator.isVisible = appModel.user != myUserHandle
        }

        fun bindLetter(letter: String, currentSection: String, gutter: Boolean) = with(binding) {
            this@ViewHolder.letter = letter
            appLetter.isVisible = letter.isNotEmpty()
            if (appLetter.text != letter) appLetter.text = letter
            highlightLetter(currentSection)
            val start = if (gutter) style.gutterStart else style.plainStart
            if (appTitle.paddingStart != start)
                appTitle.setPaddingRelative(start, appTitle.paddingTop, appTitle.paddingEnd, appTitle.paddingBottom)
        }

        fun highlightLetter(currentSection: String) {
            if (letter.isEmpty()) return
            binding.appLetter.setTextColor(
                if (letter == currentSection) style.activeLetter else style.idleLetter
            )
        }

        private fun showMenu() {
            val appModel = app ?: return
            if (appModel.appPackage.isEmpty()) return
            val context = binding.root.context
            val menu = menu ?: AdapterAppDrawerMenuBinding.bind(binding.menuStub.inflate())
                .also { wireMenu(it); this.menu = it }

            menu.appDelete.alpha =
                if (appModel is AppModel.PinnedShortcut || !context.isSystemApp(appModel.appPackage, appModel.user)) 1f
                else 0.5f
            menu.appDelete.setText(if (appModel is AppModel.PinnedShortcut) R.string.remove else R.string.delete)
            menu.appHide.setText(
                if (actions.flag == Constants.FLAG_HIDDEN_APPS) R.string.adapter_show else R.string.adapter_hide
            )
            menu.appHide.alpha = if (appModel is AppModel.PinnedShortcut) 0.5f else 1f
            // Only allow renaming non hidden apps
            menu.appRename.isVisible = actions.flag != Constants.FLAG_HIDDEN_APPS
            menu.appGroups.isVisible = actions.groups != null && actions.flag == Constants.FLAG_LAUNCH_APP

            binding.appTitle.visibility = View.INVISIBLE
            menu.root.visibility = View.VISIBLE
            fadeIn(menu.root)
            actions.menuState(this, true)
        }

        private fun wireMenu(menu: AdapterAppDrawerMenuBinding) = with(menu) {
            appInfo.setOnClickListener { app?.let(actions.info) }
            appDelete.setOnClickListener { app?.let(actions.delete) }
            appMenuClose.setOnClickListener { closeMenu() }
            appHide.setOnClickListener {
                val appModel = app ?: return@setOnClickListener
                actions.menuState(this@ViewHolder, false)
                actions.hide(appModel, bindingAdapterPosition)
            }
            appGroups.setOnClickListener { view -> app?.let { actions.groups?.invoke(it, view) } }
            appRename.setOnClickListener { showRename() }
        }

        private fun showRename() {
            val appModel = app ?: return
            if (appModel.appPackage.isEmpty()) return
            val row = renameRow ?: AdapterAppDrawerRenameBinding.bind(binding.renameStub.inflate())
                .also { wireRename(it); renameRow = it }
            row.etAppRename.hint = getAppName(row.etAppRename.context, appModel.appPackage, appModel.user)
            row.etAppRename.setText(appModel.appLabel)
            row.etAppRename.setSelectAllOnFocus(true)
            row.etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
            row.root.visibility = View.VISIBLE
            menu?.root?.visibility = View.GONE
            row.etAppRename.showKeyboard()
        }

        private fun wireRename(row: AdapterAppDrawerRenameBinding) = with(row) {
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                binding.appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            etAppRename.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val appModel = app ?: return
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            })
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val appModel = app
                    val renameLabel = etAppRename.text.toString().trim()
                    if (appModel != null && renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        actions.rename(appModel, renameLabel)
                        closeMenu()
                    }
                    true
                } else false
            }
            tvSaveRename.setOnClickListener {
                val appModel = app ?: return@setOnClickListener
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank())
                    actions.rename(appModel, renameLabel)
                else
                    actions.rename(appModel, getAppName(etAppRename.context, appModel.appPackage, appModel.user))
                closeMenu()
            }
            appRenameClose.setOnClickListener { closeMenu() }
        }

        fun closeMenu() {
            menu?.root?.visibility = View.GONE
            renameRow?.root?.visibility = View.GONE
            binding.appTitle.visibility = View.VISIBLE
            actions.menuState(this, false)
        }

        private fun fadeIn(view: View) {
            if (!view.context.isMotionEnabled()) return
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(150).start()
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                "" // As a fallback, display an empty string.
            }
        }
    }
}
