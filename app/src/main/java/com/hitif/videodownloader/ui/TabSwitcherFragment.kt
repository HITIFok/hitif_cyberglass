package com.hitif.videodownloader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hitif.videodownloader.R
import com.hitif.videodownloader.databinding.FragmentTabSwitcherBinding

class TabSwitcherFragment : BottomSheetDialogFragment() {

    private var _b: FragmentTabSwitcherBinding? = null
    private val b get() = _b!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: TabAdapter

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentTabSwitcherBinding.inflate(inf, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.behavior?.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        setupList()
        setupButtons()
        observeTabs()
    }

    private fun setupList() {
        adapter = TabAdapter(
            onSwitch = { tab ->
                // Dismiss and let BrowserActivity handle the switch
                dismiss()
                (activity as? TabSwitcherListener)?.onTabSwitched(tab)
            },
            onClose = { tab ->
                if (vm.tabs.size <= 1) {
                    // Don't allow closing the last tab
                    return@TabAdapter
                }
                val result = vm.closeTab(tab.id)
                if (result.newUrl != null) {
                    dismiss()
                    (activity as? TabSwitcherListener)?.onTabSwitchedResult(result)
                }
            }
        )
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter
        b.recyclerView.itemAnimator = null
    }

    private fun setupButtons() {
        b.btnClose.setOnClickListener { dismiss() }
        b.btnNewTab.setOnClickListener {
            dismiss()
            (activity as? TabSwitcherListener)?.onNewTabRequested()
        }
    }

    private fun observeTabs() {
        vm.tabsLiveData.observe(viewLifecycleOwner) { tabs ->
            adapter.activeTabId = vm.currentTabId
            adapter.submitList(tabs)
            b.tvTabCount.text = if (tabs.size == 1) "1 onglet"
                                else "${tabs.size} onglets"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
    override fun getTheme() = R.style.Theme_BottomSheet_Holo
}

/** Interface for communication with BrowserActivity */
interface TabSwitcherListener {
    fun onTabSwitched(tab: Tab)
    fun onTabSwitchedResult(result: TabSwitchResult)
    fun onNewTabRequested()
}
