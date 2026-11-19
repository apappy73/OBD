package com.example.obdapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.obdapp.databinding.ItemKeyValueBinding

class KeyValueAdapter : RecyclerView.Adapter<KeyValueAdapter.VH>() {

    private var entries: List<Pair<String, String>> = emptyList()

    fun submit(map: Map<String, String>) {
        entries = map.entries.map { it.key to it.value }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemKeyValueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (key, value) = entries[position]
        holder.binding.txtKey.text = key
        holder.binding.txtValue.text = value
    }

    class VH(val binding: ItemKeyValueBinding) : RecyclerView.ViewHolder(binding.root)
}
