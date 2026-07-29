package com.example.applock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial

class AppListAdapter(
    private val fullList: MutableList<AppInfo>,
    private val onToggle: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    private var visibleList: MutableList<AppInfo> = fullList.toMutableList()

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val name: TextView = itemView.findViewById(R.id.tvAppName)
        val toggle: SwitchMaterial = itemView.findViewById(R.id.switchLock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = visibleList[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.appName

        // Listener'ı geçici olarak kaldırıp geri koymak, recycle sırasında
        // yanlış tetiklenmeleri (checked change) önler.
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = app.isLocked
        holder.toggle.setOnCheckedChangeListener { _, isChecked ->
            app.isLocked = isChecked
            onToggle(app, isChecked)
        }
    }

    override fun getItemCount(): Int = visibleList.size

    fun filter(query: String) {
        visibleList = if (query.isBlank()) {
            fullList.toMutableList()
        } else {
            fullList.filter {
                it.appName.contains(query, ignoreCase = true)
            }.toMutableList()
        }
        notifyDataSetChanged()
    }
}
