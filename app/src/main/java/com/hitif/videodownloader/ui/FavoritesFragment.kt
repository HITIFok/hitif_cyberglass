package com.hitif.videodownloader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hitif.videodownloader.R
import com.hitif.videodownloader.databinding.FragmentFavoritesBinding

class FavoritesFragment : BottomSheetDialogFragment() {

    private var _b: FragmentFavoritesBinding? = null
    private val b get() = _b!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: FavoritesAdapter

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentFavoritesBinding.inflate(inf, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.behavior?.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        setupList()
        setupButtons()
        observeFavorites()
    }

    private fun setupList() {
        adapter = FavoritesAdapter(
            onOpen = { fav ->
                vm.openFavoriteUrl(fav.url)
                dismiss()
            },
            onDelete = { fav ->
                vm.deleteFavorite(fav)
                Toast.makeText(requireContext(), "Favori supprime", Toast.LENGTH_SHORT).show()
            }
        )
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter
        b.recyclerView.itemAnimator = null
    }

    private fun setupButtons() {
        b.btnClose.setOnClickListener { dismiss() }
    }

    private fun observeFavorites() {
        vm.favorites.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            val empty = list.isEmpty()
            b.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            b.recyclerView.visibility = if (empty) View.GONE  else View.VISIBLE
            b.tvCount.text = if (empty) "Aucun favori"
                             else "${list.size} favori(s)"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
    override fun getTheme() = R.style.Theme_BottomSheet_Holo
}
