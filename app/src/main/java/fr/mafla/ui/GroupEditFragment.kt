package fr.mafla.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.mafla.MainViewModel
import fr.mafla.R
import fr.mafla.data.AppGroup
import fr.mafla.data.AppGroups
import fr.mafla.data.AppModel
import fr.mafla.data.Constants
import fr.mafla.data.Prefs
import fr.mafla.databinding.FragmentGroupEditBinding
import fr.mafla.databinding.ItemGroupAppBinding
import fr.mafla.helper.MaflaDialog
import fr.mafla.helper.appLabelMatches
import fr.mafla.helper.createDialog
import fr.mafla.helper.hideKeyboard
import fr.mafla.helper.showKeyboard

/**
 * Creates or edits one group: its name and which apps are in it. Changes are saved when
 * leaving the screen, there's no explicit save button.
 */
class GroupEditFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentGroupEditBinding? = null
    private val binding get() = _binding!!

    private var groupId: String? = null
    private var initialized = false
    private val members = mutableSetOf<String>()
    private var deleted = false
    private var onlySelected = false
    private var query = ""
    private var allApps: List<AppModel> = emptyList()
    private var shown: List<AppModel> = emptyList()
    private val adapter = AppsAdapter()
    private var dialog: MaflaDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGroupEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())

        // Runs again when the view is recreated: keep what was edited so far
        if (!initialized) {
            initialized = true
            val fromArgs = arguments?.getString(Constants.Key.GROUP_ID)
                ?.let { id -> prefs.appGroups.firstOrNull { it.id == id } }
            groupId = fromArgs?.id
            fromArgs?.let { members.addAll(it.members) }
            arguments?.getString(Constants.Key.PRESELECT_MEMBER)?.let { members.add(it) }
        }
        val existing = groupId?.let { id -> prefs.appGroups.firstOrNull { it.id == id } }
        binding.groupName.setText(existing?.name.orEmpty())
        binding.deleteGroup.isVisible = existing != null

        binding.appsList.layoutManager = LinearLayoutManager(requireContext())
        binding.appsList.adapter = adapter

        binding.back.setOnClickListener {
            binding.groupName.hideKeyboard()
            findNavController().popBackStack()
        }
        binding.groupName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = save()
        })
        binding.groupName.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) v.hideKeyboard()
            false
        }
        binding.onlySelected.setOnClickListener {
            onlySelected = !onlySelected
            binding.onlySelected.setText(if (onlySelected) R.string.show_all else R.string.only_selected)
            refreshList()
        }
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                refreshList()
            }
        })
        binding.deleteGroup.setOnClickListener { confirmDelete() }

        viewModel.appList.observe(viewLifecycleOwner) { apps ->
            allApps = apps.orEmpty().filter { AppGroups.memberKey(it) != null }
            refreshList()
        }
        if (viewModel.appList.value == null) viewModel.getAppList()

        updateCount()
        if (existing == null) binding.groupName.showKeyboard()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshList() {
        shown = allApps.filter { app ->
            (!onlySelected || AppGroups.isMember(members, app)) &&
                    (query.isBlank() || appLabelMatches(app.appLabel, query))
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateCount() {
        _binding?.selectedCount?.text =
            resources.getQuantityString(R.plurals.selected_count, members.size, members.size)
    }

    private fun toggle(app: AppModel, position: Int) {
        val key = AppGroups.memberKey(app) ?: return
        if (!members.remove(key)) members.add(key)
        updateCount()
        save()
        if (onlySelected) refreshList() else adapter.notifyItemChanged(position)
    }

    private fun confirmDelete() {
        dialog?.dismiss()
        dialog = requireContext().createDialog(
            title = R.string.delete_group,
            action = R.string.delete_confirm,
            message = R.string.delete_group_message,
            neutral = R.string.cancel,
            onAction = {
                val id = groupId
                if (id != null) prefs.appGroups = prefs.appGroups.filterNot { it.id == id }
                if (prefs.lastGroupId == id) prefs.lastGroupId = ""
                deleted = true
                _binding?.let { findNavController().popBackStack() }
            },
        ).also { it.showRespectingStatusBar() }
    }

    private fun save() {
        if (deleted) return
        val name = _binding?.groupName?.text?.toString()?.trim().orEmpty()
        val groups = prefs.appGroups
        val id = groupId
        if (id == null) {
            // A brand new screen left untouched creates nothing
            if (name.isEmpty() && members.isEmpty()) return
            val created = AppGroup(
                id = AppGroups.newId(),
                name = name.ifEmpty { getString(R.string.new_group) },
                members = members.toSet(),
            )
            groupId = created.id
            prefs.appGroups = groups + created
        } else {
            prefs.appGroups = groups.map { group ->
                if (group.id != id) group
                else group.copy(name = name.ifEmpty { group.name }, members = members.toSet())
            }
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
        _binding = null
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.Holder>() {

        inner class Holder(val binding: ItemGroupAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemGroupAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = shown[position]
            val selected = AppGroups.isMember(members, app)
            with(holder.binding) {
                appName.text = app.appLabel
                appName.isSelected = selected
                appName.alpha = if (selected) 1f else 0.7f
                check.setBackgroundResource(if (selected) R.drawable.bg_check_on else R.drawable.bg_check_off)
                check.setImageResource(if (selected) R.drawable.ic_check_small else 0)
                root.isSelected = selected
                root.contentDescription = app.appLabel
                root.setOnClickListener {
                    val current = holder.bindingAdapterPosition
                    if (current != RecyclerView.NO_POSITION) toggle(shown[current], current)
                }
            }
        }
    }
}
