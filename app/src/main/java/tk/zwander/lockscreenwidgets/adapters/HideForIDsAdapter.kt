package tk.zwander.lockscreenwidgets.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedList
import tk.zwander.common.util.PrefManager
import tk.zwander.lockscreenwidgets.R
import tk.zwander.lockscreenwidgets.databinding.HideForIdsItemBinding

/**
 * The adapter to handle hosting the IDs listed in [PrefManager.presentIds] or [PrefManager.nonPresentIds]
 */
class HideForIDsAdapter : RecyclerView.Adapter<HideForIDsAdapter.HideForIDsVH>() {
    val items = SortedList(
        String::class.java,
        object : SortedList.Callback<String>() {
            override fun areItemsTheSame(item1: String?, item2: String?): Boolean {
                return item1 == item2
            }

            override fun areContentsTheSame(oldItem: String?, newItem: String?): Boolean {
                return oldItem == newItem
            }

            override fun compare(o1: String, o2: String): Int {
                return o1.compareTo(o2)
            }

            override fun onInserted(position: Int, count: Int) {
                notifyItemRangeInserted(position, count)
            }

            override fun onChanged(position: Int, count: Int) {
                notifyItemRangeChanged(position, count)
            }

            override fun onRemoved(position: Int, count: Int) {
                notifyItemRangeRemoved(position, count)
            }

            override fun onMoved(fromPosition: Int, toPosition: Int) {
                notifyItemMoved(fromPosition, toPosition)
            }
        }
    )

    override fun getItemCount(): Int {
        return items.size()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HideForIDsVH {
        return HideForIDsVH(
            LayoutInflater.from(parent.context).inflate(R.layout.hide_for_ids_item, parent, false)
        )
    }

    override fun onBindViewHolder(holder: HideForIDsVH, position: Int) {
        holder.onBind(items[position])
    }

    /**
     * Handle displaying the ID representing this item
     */
    inner class HideForIDsVH(view: View) : RecyclerView.ViewHolder(view) {
        val binding = HideForIdsItemBinding.bind(itemView)

        fun onBind(id: String) {
            binding.idText.text = id
        }
    }
}