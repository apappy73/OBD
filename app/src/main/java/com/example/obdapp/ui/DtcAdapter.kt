package com.example.obdapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.obdapp.databinding.ItemDtcBinding
import com.example.obdapp.obd.DtcDescriptions

class DtcAdapter : RecyclerView.Adapter<DtcAdapter.VH>() {

    private var codes: List<String> = emptyList()

    fun submit(newCodes: List<String>) {
        codes = newCodes
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = codes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDtcBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val code = codes[position]
        holder.binding.txtCode.text = code
        holder.binding.txtDescription.text = DtcDescriptions.describe(code)
    }

    class VH(val binding: ItemDtcBinding) : RecyclerView.ViewHolder(binding.root)
}
