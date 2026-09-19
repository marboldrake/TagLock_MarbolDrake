package com.example.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemAppBinding
import com.example.model.AppItem

/**
 * Adapter para la lista de aplicaciones instaladas en [com.example.AppListActivity].
 */
class AppListAdapter(
    private val onBlockToggled: (appItem: AppItem, isBlocked: Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    private val allApps = mutableListOf<AppItem>()
    private val filteredApps = mutableListOf<AppItem>()
    private var currentQuery: String = ""

    fun submitList(apps: List<AppItem>) {
        allApps.clear()
        allApps.addAll(apps)
        sortList(allApps)
        applyFilter(currentQuery)
    }

    fun filter(query: String) {
        currentQuery = query
        applyFilter(query)
    }

    private fun sortList(list: MutableList<AppItem>) {
        list.sortWith(
            compareByDescending<AppItem> { it.isBlocked }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun applyFilter(query: String) {
        filteredApps.clear()
        if (query.isBlank()) {
            filteredApps.addAll(allApps)
        } else {
            val lower = query.trim().lowercase()
            for (app in allApps) {
                if (app.name.lowercase().contains(lower) || app.packageName.lowercase().contains(lower)) {
                    filteredApps.add(app)
                }
            }
        }
        sortList(filteredApps)
        notifyDataSetChanged()
    }

    fun getFilteredCount(): Int = filteredApps.size

    fun getSelectedCount(): Int = allApps.count { it.isBlocked }

    fun getTotalCount(): Int = allApps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(filteredApps[position])
    }

    override fun getItemCount(): Int = filteredApps.size

    inner class AppViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppItem) {
            binding.tvAppName.text = item.name
            binding.tvPackageName.text = item.packageName

            if (item.icon != null) {
                binding.ivAppIcon.setImageDrawable(item.icon)
            } else {
                binding.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // Desvincular listener antes de setear estado para evitar disparos accidentales
            binding.switchBlocked.setOnCheckedChangeListener(null)
            binding.switchBlocked.isChecked = item.isBlocked

            // Cambiar switch al hacer click en la fila completa
            binding.root.setOnClickListener {
                val newState = !item.isBlocked
                item.isBlocked = newState
                binding.switchBlocked.isChecked = newState
                handleToggle(item, newState)
            }

            binding.switchBlocked.setOnCheckedChangeListener { _, isChecked ->
                if (item.isBlocked != isChecked) {
                    item.isBlocked = isChecked
                    handleToggle(item, isChecked)
                }
            }
        }

        private fun handleToggle(item: AppItem, isBlocked: Boolean) {
            onBlockToggled(item, isBlocked)
            // Reordenar para que las apps seleccionadas suban arriba de todo
            sortList(allApps)
            applyFilter(currentQuery)
        }
    }
}
