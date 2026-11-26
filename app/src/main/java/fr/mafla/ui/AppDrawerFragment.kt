package fr.mafla.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Spannable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import fr.mafla.MainViewModel
import fr.mafla.R
import fr.mafla.data.AppGroup
import fr.mafla.data.AppGroups
import fr.mafla.data.AppModel
import fr.mafla.data.Constants
import fr.mafla.data.Prefs
import fr.mafla.databinding.FragmentAppDrawerBinding
import fr.mafla.databinding.ItemGroupTabBinding
import fr.mafla.helper.AlphabetIndex
import fr.mafla.helper.deletePinnedShortcut
import fr.mafla.helper.dpToPx
import fr.mafla.helper.getColorFromAttr
import fr.mafla.helper.hideKeyboard
import fr.mafla.helper.isEinkDisplay
import fr.mafla.helper.isMotionEnabled
import fr.mafla.helper.isSystemApp
import fr.mafla.helper.openAppInfo
import fr.mafla.helper.openSearch
import fr.mafla.helper.openUrl
import fr.mafla.helper.showKeyboard
import fr.mafla.helper.showPopupMenu
import fr.mafla.helper.showToast
import fr.mafla.helper.uninstall
import fr.mafla.ui.widget.AlphabetIndexView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AppDrawerFragment : BaseFragment() {

    private companion object {
        const val NEW_GROUP_ITEM = 10_000
    }

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked: Boolean = true
    private var currentPrivateSpaceAvailable: Boolean = false

    // Groups: tabs holds null for "All". The active group survives the view being recreated,
    // for example when coming back from the group editor.
    private var tabs: List<AppGroup?> = listOf(null)
    private var activeTab = 0
    private var activeGroupId: String? = null
    private var groupStateRestored = false
    private val showGroups get() = flag == Constants.FLAG_LAUNCH_APP
    private val activeGroup: AppGroup? get() = tabs.getOrNull(activeTab)

    // Alphabetical index
    private var sections: List<AlphabetIndex.Section> = emptyList()
    private var currentSectionLetter = ""

    private lateinit var standardInterpolator: Interpolator
    private lateinit var accelerateInterpolator: Interpolator

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
        }

        standardInterpolator = AnimationUtils.loadInterpolator(requireContext(), R.interpolator.standard)
        accelerateInterpolator = AnimationUtils.loadInterpolator(requireContext(), R.interpolator.emphasized_accelerate)

        initViews()
        initSearch()
        initAdapter()
        initGroups()
        initIndex()
        initObservers()
        initClickListeners()
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP)
            binding.search.queryHint = "Please select an app"
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            searchTextView?.gravity = prefs.appLabelAlignment
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (adapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    adapter.allowAutoLaunch = !isSearchComposing()
                    adapter.filter.filter(newText)
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun isSearchComposing(): Boolean {
        val text = searchTextView?.text
        if (text !is Spannable) return false
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        if (start !in 0 until end) return false
        return isCjkKeyboard()
    }

    private fun isCjkKeyboard(): Boolean {
        cachedIsCjkKeyboard?.let { return it }
        val result = try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val subtype = imm.currentInputMethodSubtype
            val language = when {
                subtype == null -> ""
                subtype.languageTag.isNotEmpty() -> subtype.languageTag // e.g. "zh-CN", "ja-JP", "en-US"
                else -> subtype.locale // deprecated fallback, e.g. "zh_CN"
            }
            language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
        } catch (e: Exception) {
            false
        }
        cachedIsCjkKeyboard = result
        return result
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                viewModel.selectedApp(appModel, flag)
                if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                    findNavController().popBackStack(R.id.mainFragment, false)
                else
                    closeDrawer()
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    closeDrawer()
                if (prefs.firstHide) {
                    binding.search.hideKeyboard()
                    prefs.firstHide = false
                    viewModel.showDialog.postValue(Constants.Dialog.HIDDEN)
                    findNavController().navigate(R.id.action_appListFragment_to_settingsFragment2)
                }
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.identity
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appGroupsListener = if (showGroups) { appModel, anchor -> showGroupsMenu(appModel, anchor) } else null,
        )

        linearLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    checkMessageAndExit()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        if (requireContext().isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        if (requireContext().isMotionEnabled())
            binding.recyclerView.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)
        binding.recyclerView.addOnItemTouchListener(GroupSwipeListener())
    }

    private fun initObservers() {
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                currentAppList = it
                updateCombinedAppList()
            }
            if (flag == Constants.FLAG_LAUNCH_APP) {
                viewModel.privateSpaceAvailable.observe(viewLifecycleOwner) {
                    currentPrivateSpaceAvailable = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                    currentPrivateSpaceLocked = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                    currentPrivateSpaceApps = it
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun updateCombinedAppList() {
        val apps = currentAppList ?: return
        val group = if (showGroups) activeGroup else null
        val combined = (
                if (group == null) apps
                else apps.filter { AppGroups.isMember(group.members, it) }
                ).toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && group == null && currentPrivateSpaceAvailable) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        adapter.setAppList(combined)
        adapter.filter.filter(binding.search.query)
    }

    private fun initClickListeners() {
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            closeDrawer()
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateCurrentSection()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        else if (!recyclerView.canScrollVertically(-1))
                            if (!onTop && isRemoving.not())
                                binding.search.showKeyboard(prefs.autoShowKeyboard)
                    }
                }
            }
        }
    }

    // Groups

    private fun initGroups() {
        binding.groupTabsScroll.isVisible = showGroups
        if (!showGroups) return
        loadGroups()
        renderTabs()
    }

    private fun loadGroups() {
        val groups = prefs.appGroups
        tabs = if (prefs.showAllTab || groups.isEmpty()) listOf<AppGroup?>(null) + groups else groups
        if (!groupStateRestored) {
            groupStateRestored = true
            activeGroupId = if (prefs.rememberLastGroup) prefs.lastGroupId.ifBlank { null } else null
        }
        activeTab = tabs.indexOfFirst { it?.id == activeGroupId }.let { if (it < 0) 0 else it }
        activeGroupId = activeGroup?.id
    }

    private fun renderTabs() {
        val container = binding.groupTabs
        container.removeAllViews()
        tabs.forEachIndexed { index, group ->
            val tab = ItemGroupTabBinding.inflate(layoutInflater, container, false)
            tab.tabLabel.text = group?.name ?: getString(R.string.all_apps_tab)
            tab.root.setOnClickListener { selectTab(index) }
            if (group != null)
                tab.root.setOnLongClickListener {
                    openGroupEditor(group.id)
                    true
                }
            container.addView(tab.root)
        }
        container.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = ColorStateList.valueOf(context.getColorFromAttr(R.attr.secondaryColor))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = getString(R.string.new_group)
            layoutParams = LinearLayout.LayoutParams(44.dpToPx(), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { openGroupEditor(null) }
        })
        updateTabState(animate = false)
        updateSearchHint()
    }

    private fun updateTabState(animate: Boolean) {
        val motion = animate && requireContext().isMotionEnabled()
        tabs.indices.forEach { index ->
            val tab = binding.groupTabs.getChildAt(index) ?: return@forEach
            val label = tab.findViewById<TextView>(R.id.tabLabel)
            val indicator = tab.findViewById<View>(R.id.tabIndicator)
            val selected = index == activeTab
            tab.isSelected = selected
            label.setTextColor(
                requireContext().getColorFromAttr(if (selected) R.attr.primaryColor else R.attr.secondaryColor)
            )
            val alpha = if (selected) 1f else 0f
            val scale = if (selected) 1f else 0.3f
            indicator.animate().cancel()
            if (motion)
                indicator.animate().alpha(alpha).scaleX(scale)
                    .setDuration(resources.getInteger(R.integer.motion_switch).toLong())
                    .setInterpolator(standardInterpolator)
                    .start()
            else {
                indicator.alpha = alpha
                indicator.scaleX = scale
            }
        }
        val selectedTab = binding.groupTabs.getChildAt(activeTab) ?: return
        binding.groupTabsScroll.post {
            val scroll = _binding?.groupTabsScroll ?: return@post
            val target = max(0, selectedTab.left - resources.getDimensionPixelSize(R.dimen.list_text_start))
            if (motion) scroll.smoothScrollTo(target, 0) else scroll.scrollTo(target, 0)
        }
    }

    private fun updateSearchHint() {
        if (!showGroups) return
        val group = activeGroup
        binding.search.queryHint =
            if (group == null) getString(R.string.search_hint)
            else getString(R.string.search_in_group, group.name)
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices || index == activeTab) {
            settleList()
            return
        }
        val direction = if (index > activeTab) 1 else -1
        activeTab = index
        activeGroupId = activeGroup?.id
        prefs.lastGroupId = activeGroupId ?: ""
        updateTabState(animate = true)
        updateSearchHint()

        val list = binding.recyclerView
        list.animate().cancel()
        if (!requireContext().isMotionEnabled()) {
            list.translationX = 0f
            list.alpha = 1f
            applyActiveGroup()
            return
        }
        // Out: slide 16dp the way the finger went and fade. In: come from the other side.
        val shift = 16.dpToPx() * direction.toFloat()
        list.animate().alpha(0f).translationX(-shift)
            .setDuration(120)
            .setInterpolator(accelerateInterpolator)
            .withEndAction {
                val rv = _binding?.recyclerView ?: return@withEndAction
                applyActiveGroup()
                rv.translationX = shift
                rv.animate().alpha(1f).translationX(0f)
                    .setDuration(resources.getInteger(R.integer.motion_switch).toLong())
                    .setInterpolator(standardInterpolator)
                    .start()
            }
            .start()
    }

    private fun applyActiveGroup() {
        currentSectionLetter = ""
        updateCombinedAppList()
        linearLayoutManager.scrollToPositionWithOffset(0, 0)
    }

    /** Brings the list back after a horizontal drag that didn't switch groups. */
    private fun settleList() {
        val list = _binding?.recyclerView ?: return
        if (list.translationX == 0f && list.alpha == 1f) return
        list.animate().alpha(1f).translationX(0f)
            .setDuration(resources.getInteger(R.integer.motion_exit).toLong())
            .setInterpolator(standardInterpolator)
            .start()
    }

    private fun canSwipeGroups() =
        showGroups && prefs.groupSwipe && tabs.size > 1 && !adapter.isSearching

    private fun openGroupEditor(groupId: String?, preselectMember: String? = null) {
        binding.search.hideKeyboard()
        try {
            findNavController().navigate(
                R.id.action_appListFragment_to_groupEditFragment,
                bundleOf(
                    Constants.Key.GROUP_ID to groupId,
                    Constants.Key.PRESELECT_MEMBER to preselectMember,
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Long-press menu entry: tick the groups this app belongs to. */
    private fun showGroupsMenu(app: AppModel, anchor: View) {
        val key = AppGroups.memberKey(app) ?: return
        val groups = prefs.appGroups
        anchor.showPopupMenu(
            configure = { menu ->
                groups.forEachIndexed { index, group ->
                    menu.add(Menu.NONE, index, index, group.name).apply {
                        isCheckable = true
                        isChecked = key in group.members
                    }
                }
                menu.add(Menu.NONE, NEW_GROUP_ITEM, groups.size, getString(R.string.new_group_ellipsis))
            }
        ) { item ->
            if (item.itemId == NEW_GROUP_ITEM) {
                openGroupEditor(null, preselectMember = key)
                return@showPopupMenu
            }
            val target = groups.getOrNull(item.itemId) ?: return@showPopupMenu
            prefs.appGroups = groups.map { group ->
                if (group.id != target.id) group
                else group.copy(members = if (key in group.members) group.members - key else group.members + key)
            }
            loadGroups()
            renderTabs()
            updateCombinedAppList()
        }
    }

    // Alphabetical index

    private fun initIndex() {
        adapter.indexEnabled = prefs.alphabetIndex
        adapter.onListChanged = { list -> updateIndex(list) }
        binding.indexRail.listener = object : AlphabetIndexView.Listener {
            override fun onScrubStart() {
                binding.search.hideKeyboard()
                if (binding.indexBubble.alpha == 0f) {
                    binding.indexBubble.scaleX = 0.9f
                    binding.indexBubble.scaleY = 0.9f
                }
                showBubble(true)
            }

            override fun onScrub(section: AlphabetIndex.Section, centerY: Float) {
                val bubble = binding.indexBubble
                bubble.text = section.letter
                bubble.translationY = centerY - bubble.height / 2f
                linearLayoutManager.scrollToPositionWithOffset(section.position, 0)
            }

            override fun onScrubEnd() = showBubble(false)
        }
    }

    private fun updateIndex(list: List<AppModel>) {
        val b = _binding ?: return
        val labels = list.map { if (it is AppModel.PrivateSpaceHeader) "" else it.appLabel }
        sections = AlphabetIndex.sections(labels)
        val show = prefs.alphabetIndex && !adapter.isSearching && sections.size >= 2
        b.indexRail.isVisible = show
        b.indexRail.setSections(if (show) sections else emptyList())
        currentSectionLetter = ""
        updateCurrentSection()
    }

    private fun updateCurrentSection() {
        val b = _binding ?: return
        val first = linearLayoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val letter = sections.lastOrNull { it.position <= first }?.letter ?: return
        if (letter == currentSectionLetter) return
        currentSectionLetter = letter
        b.indexRail.setCurrent(letter)
        // Not while RecyclerView is mid-layout or mid-scroll
        b.recyclerView.post { if (_binding != null) adapter.setCurrentSection(letter) }
    }

    private fun showBubble(show: Boolean) {
        val bubble = _binding?.indexBubble ?: return
        val alpha = if (show) 1f else 0f
        val scale = if (show) 1f else 0.9f
        bubble.animate().cancel()
        if (!requireContext().isMotionEnabled()) {
            bubble.alpha = alpha
            bubble.scaleX = scale
            bubble.scaleY = scale
            return
        }
        bubble.animate().alpha(alpha).scaleX(scale).scaleY(scale)
            .setDuration(150)
            .setInterpolator(standardInterpolator)
            .start()
    }

    /**
     * Horizontal drag on the list switches groups. Takes over the gesture only once it's clearly
     * sideways, so taps, long presses and vertical scrolling behave as before.
     */
    private inner class GroupSwipeListener : RecyclerView.OnItemTouchListener {
        private val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        private val flingVelocity = ViewConfiguration.get(requireContext()).scaledMinimumFlingVelocity * 4
        private var downX = 0f
        private var downY = 0f
        private var tracker: VelocityTracker? = null

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (!canSwipeGroups()) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                }

                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (rv.scrollState != RecyclerView.SCROLL_STATE_DRAGGING &&
                        abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 2
                    ) {
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycle()
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            tracker?.addMovement(e)
            val dx = e.x - downX
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    rv.translationX = dx * 0.25f
                    rv.alpha = 1f - min(0.5f, abs(dx) / max(1, rv.width))
                }

                MotionEvent.ACTION_UP -> {
                    val velocity = tracker?.let {
                        it.computeCurrentVelocity(1000)
                        it.xVelocity
                    } ?: 0f
                    val threshold = rv.width * 0.25f
                    when {
                        dx < -threshold || (dx < 0 && velocity < -flingVelocity) -> selectTab(activeTab + 1)
                        dx > threshold || (dx > 0 && velocity > flingVelocity) -> selectTab(activeTab - 1)
                        else -> settleList()
                    }
                    recycle()
                }

                MotionEvent.ACTION_CANCEL -> {
                    settleList()
                    recycle()
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

        private fun recycle() {
            tracker?.recycle()
            tracker = null
        }
    }

    private fun checkMessageAndExit() {
        if (!closeDrawer()) return
        if (flag == Constants.FLAG_LAUNCH_APP)
            viewModel.checkForMessages.call()
    }

    // Pops only the drawer. A plain popBackStack() from a late overscroll frame could pop the
    // home screen too, leaving a drawer that auto-launch and back can't close (#766)
    private fun closeDrawer() = findNavController().popBackStack(R.id.appListFragment, true)

    override fun onStart() {
        super.onStart()
        cachedIsCjkKeyboard = null
        binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    override fun onStop() {
        binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        binding.recyclerView.animate().cancel()
        binding.indexBubble.animate().cancel()
        super.onDestroyView()
        searchTextView = null
        _binding = null
    }
}
