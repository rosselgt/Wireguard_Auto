package com.example.wgautotoggle

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val onToggle: (AppEntry, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val items = mutableListOf<AppEntry>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val pkg: TextView = view.findViewById(R.id.appPackage)
        val switch: Switch = view.findViewById(R.id.appSwitch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.icon.setImageDrawable(entry.icon)
        holder.name.text = entry.label
        holder.pkg.text = entry.packageName
        // Rimuoviamo il listener prima di impostare isChecked, altrimenti
        // scatterebbe un evento di toggle indesiderato durante il bind.
        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = entry.excluded
        holder.switch.setOnCheckedChangeListener { _, checked ->
            onToggle(entry, checked)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<AppEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}