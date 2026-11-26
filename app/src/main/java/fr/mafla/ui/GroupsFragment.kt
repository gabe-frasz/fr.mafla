package fr.mafla.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.mafla.R
import fr.mafla.data.AppGroup
import fr.mafla.data.Constants
import fr.mafla.data.Prefs
import fr.mafla.databinding.FragmentGroupsBinding
import fr.mafla.databinding.ItemGroupBinding

/** Settings › Groups: the list of groups (drag to reorder), a way to add one, and options. */
class GroupsFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private var _binding: FragmentGroupsBinding? = null
    private val binding get() = _binding!!

    private val groups = mutableListOf<AppGroup>()
    private lateinit var adapter: GroupsAdapter
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGroupsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        applyOverlayScrim(view)

        adapter = GroupsAdapter()
        binding.groupsList.layoutManager = LinearLayoutManager(requireContext())
        binding.groupsList.adapter = adapter
        touchHelper = ItemTouchHelper(ReorderCallback())
        touchHelper.attachToRecyclerView(binding.groupsList)

        binding.back.setOnClickListener { findNavController().popBackStack() }
        binding.addGroup.setOnClickListener { openEditor(null) }
        binding.groupSwipeRow.setOnClickListener {
            prefs.groupSwipe = !prefs.groupSwipe
            populateOptions()
        }
        binding.rememberLastGroupRow.setOnClickListener {
            prefs.rememberLastGroup = !prefs.rememberLastGroup
            populateOptions()
        }
        binding.showAllTabRow.setOnClickListener {
            prefs.showAllTab = !prefs.showAllTab
            populateOptions()
        }
        populateOptions()
    }

    override fun onResume() {
        super.onResume()
        loadGroups()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadGroups() {
        groups.clear()
        groups.addAll(prefs.appGroups)
        adapter.notifyDataSetChanged()
        binding.emptyGroups.isVisible = groups.isEmpty()
        binding.reorderHint.isVisible = groups.size > 1
    }

    private fun populateOptions() {
        binding.groupSwipe.setText(if (prefs.groupSwipe) R.string.on else R.string.off)
        binding.rememberLastGroup.setText(if (prefs.rememberLastGroup) R.string.on else R.string.off)
        binding.showAllTab.setText(if (prefs.showAllTab) R.string.on else R.string.off)
    }

    private fun openEditor(groupId: String?) {
        findNavController().navigate(
            R.id.action_groupsFragment_to_groupEditFragment,
            bundleOf(Constants.Key.GROUP_ID to groupId)
        )
    }

    private inner class GroupsAdapter : RecyclerView.Adapter<GroupsAdapter.Holder>() {

        inner class Holder(val binding: ItemGroupBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = groups.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val group = groups[position]
            holder.binding.groupName.text = group.name
            holder.binding.groupCount.text = group.members.size.toString()
            holder.binding.groupRow.setOnClickListener { openEditor(group.id) }
            holder.binding.dragHandle.contentDescription = getString(R.string.reorder_group, group.name)
            holder.binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }
        }
    }

    private inner class ReorderCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

        override fun isLongPressDragEnabled() = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            groups.add(to, groups.removeAt(from))
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            prefs.appGroups = groups.toList()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
