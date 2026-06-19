package com.example.wgautotoggle

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NetworkAdapter(
    private val items: MutableList<String>,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<NetworkAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ssidText: TextView = view.findViewById(R.id.ssidText)
        val removeButton: TextView = view.findViewById(R.id.removeButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ssid = items[position]
        holder.ssidText.text = ssid
        holder.removeButton.setOnClickListener { onRemove(ssid) }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}