package com.freetv.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.freetv.app.databinding.ItemChipBinding

/**
 * One horizontal row of source chips (countries or categories) on top of Home, TV / large screens only.
 * The chip of the active source is "selected". Binding is cheap (text + selected flag only), and the RecyclerView that shows
 * this adapter has no item animator, so a change of the selection re-binds the two chips in place and the remote focus stays put.
 */
class ChipAdapter(
    private val items: List<Source>,
    private val onPick: (Source) -> Unit
) : RecyclerView.Adapter<ChipAdapter.VH>() {

    class VH(val b: ItemChipBinding) : RecyclerView.ViewHolder(b.root)

    private var selectedUrl: String? = null

    /** Position of the active source in this row, or -1 when it is not part of this row (e.g. a country while a category is active). */
    fun indexOfSelected(): Int {
        val url = selectedUrl ?: return -1
        return items.indexOfFirst { it.url == url }
    }

    fun select(url: String?) {
        if (url == selectedUrl) return
        val old = indexOfSelected()
        selectedUrl = url
        val new = indexOfSelected()
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        vh.b.root.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onPick(items[p])
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.b.root.text = s.name
        holder.b.root.isSelected = s.url == selectedUrl
    }
}
