package com.example.obdapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.obdapp.databinding.ItemPidValueBinding
import com.example.obdapp.obd.PidCatalog
import com.example.obdapp.obd.PidSample

/** Grid of current values for all polled PIDs. */
class PidValueAdapter : RecyclerView.Adapter<PidValueAdapter.VH>() {

    private val items = LinkedHashMap<String, PidSample>()

    fun update(sample: PidSample) {
        val isNew = !items.containsKey(sample.pidKey)
        items[sample.pidKey] = sample
        val index = orderedKeys().indexOf(sample.pidKey)
        if (isNew) notifyItemInserted(index) else notifyItemChanged(index)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    private fun orderedKeys(): List<String> =
        items.keys.sortedBy { key -> PidCatalog.all.indexOfFirst { it.key == key } }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPidValueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sample = items[orderedKeys()[position]] ?: return
        holder.binding.txtPidLabel.text = sample.label
        holder.binding.txtPidValue.text =
            if (sample.unit.isBlank()) sample.display else "${sample.display} ${sample.unit}"
    }

    class VH(val binding: ItemPidValueBinding) : RecyclerView.ViewHolder(binding.root)
}
