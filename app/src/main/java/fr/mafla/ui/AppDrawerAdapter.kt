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
import fr.mafla.databinding.AdapterPrivateSpaceHeaderBinding
import fr.mafla.helper.AlphabetIndex
import fr.mafla.helper.appLabelMatches
import fr.mafla.helper.getColorFromAttr
import fr.mafla.helper.hideKeyboard
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
        private const val PAYLOAD_SECTION = "section"

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

    /** Highlights the gutter letter of the section now at the top of the list. */
    fun setCurrentSection(letter: String) {
        if (letter == currentSection) return
        currentSection = letter
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SECTION)
    }

    private fun sectionLetterAt(position: Int): String {
        if (!showLetters) return ""
        val labels = appFilteredList
        val app = labels.getOrNull(position) ?: return ""
        if (app is AppModel.PrivateSpaceHeader) return ""
        val letter = AlphabetIndex.sectionOf(app.appLabel)
        if (letter.isEmpty()) return ""
        val previous = labels.getOrNull(position - 1)
        if (previous != null && previous !is AppModel.PrivateSpaceHeader &&
            AlphabetIndex.sectionOf(previous.appLabel) == letter
        ) return ""
        return letter
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
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SECTION) && holder is ViewHolder) {
            holder.bindLetter(sectionLetterAt(holder.bindingAdapterPosition), currentSection, showLetters)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
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

                is ViewHolder -> holder.bind(
                    flag,
                    appLabelGravity,
                    myUserHandle,
                    appModel,
                    appClickListener,
                    appDeleteListener,
                    appInfoListener,
                    appHideListener,
                    appRenameListener,
                    appGroupsListener,
                    menuListener,
                ).also {
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

    class ViewHolder(private val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            flag: Int,
            appLabelGravity: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            appGroupsListener: ((AppModel, View) -> Unit)?,
            menuListener: (ViewHolder, Boolean) -> Unit,
        ) = with(binding) {
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            menuListener(this@ViewHolder, false)
            appTitle.visibility = View.VISIBLE

            // Show indicators in title based on app type and state
            appTitle.text = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
            }
            appTitle.gravity = appLabelGravity
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            appTitle.setOnClickListener { clickListener(appModel) }

            appTitle.setOnLongClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    appDelete.alpha = when (
                        appModel is AppModel.PinnedShortcut || !root.context.isSystemApp(appModel.appPackage, appModel.user)
                    ) {
                        true -> 1.0f
                        false -> 0.5f
                    }
                    appDelete.text = if (appModel is AppModel.PinnedShortcut)
                        root.context.getString(R.string.remove)
                    else
                        root.context.getString(R.string.delete)
                    appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                        root.context.getString(R.string.adapter_show)
                    else
                        root.context.getString(R.string.adapter_hide)
                    appTitle.visibility = View.INVISIBLE
                    appHide.alpha = when (appModel is AppModel.PinnedShortcut) {
                        true -> 0.5f
                        false -> 1.0f
                    }
                    appHideLayout.visibility = View.VISIBLE
                    menuListener(this@ViewHolder, true)
                    // Only allow renaming non hidden apps
                    appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                    appGroups.isVisible = appGroupsListener != null && flag == Constants.FLAG_LAUNCH_APP
                }
                true
            }

            // Configure rename behavior
            appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    etAppRename.setText(appModel.appLabel)
                    etAppRename.setSelectAllOnFocus(true)
                    renameLayout.visibility = View.VISIBLE
                    appHideLayout.visibility = View.GONE
                    etAppRename.showKeyboard()
                    etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
                }
            }
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            etAppRename.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            })
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val renameLabel = etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                        renameLayout.visibility = View.GONE
                    menuListener(this@ViewHolder, false)
                    }
                    true
                }
                false
            }
            tvSaveRename.setOnClickListener {
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                    appRenameListener(appModel, renameLabel)
                    renameLayout.visibility = View.GONE
                    menuListener(this@ViewHolder, false)
                } else {
                    appRenameListener(
                        appModel,
                        getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    )
                    renameLayout.visibility = View.GONE
                    menuListener(this@ViewHolder, false)
                }
            }
            appInfo.setOnClickListener { appInfoListener(appModel) }
            appDelete.setOnClickListener { appDeleteListener(appModel) }
            appMenuClose.setOnClickListener {
                menuListener(this@ViewHolder, false)
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appRenameClose.setOnClickListener {
                menuListener(this@ViewHolder, false)
                renameLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appHide.setOnClickListener {
                menuListener(this@ViewHolder, false)
                appHideListener(appModel, bindingAdapterPosition)
            }
            appGroups.setOnClickListener { appGroupsListener?.invoke(appModel, appGroups) }
        }

        fun closeMenu() = with(binding) {
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            appTitle.visibility = View.VISIBLE
        }

        fun bindLetter(letter: String, currentSection: String, gutter: Boolean) = with(binding) {
            appLetter.isVisible = letter.isNotEmpty()
            appLetter.text = letter
            appLetter.setTextColor(
                root.context.getColorFromAttr(
                    if (letter.isNotEmpty() && letter == currentSection) R.attr.primaryColor else R.attr.tertiaryColor
                )
            )
            val start = root.resources.getDimensionPixelSize(
                if (gutter) R.dimen.list_text_start else R.dimen.screen_margin
            )
            if (appTitle.paddingStart != start)
                appTitle.setPaddingRelative(start, appTitle.paddingTop, appTitle.paddingEnd, appTitle.paddingBottom)
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
