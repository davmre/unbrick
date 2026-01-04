package com.unbrick.ui.apps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unbrick.R
import com.unbrick.UnbrickApplication
import com.unbrick.data.model.BlockingMode
import com.unbrick.databinding.ActivityAppSelectionBinding
import com.unbrick.util.InstalledApp
import com.unbrick.util.InstalledAppsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }

    private lateinit var binding: ActivityAppSelectionBinding
    private val repository by lazy { (application as UnbrickApplication).repository }

    private lateinit var adapter: AppListAdapter
    private var installedApps: List<InstalledApp> = emptyList()
    private var selectedPackages: Set<String> = emptySet()
    private var profileId: Long = -1
    private var isAllowlistMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1)
        if (profileId == -1L) {
            Toast.makeText(this, "No profile selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.select_apps_title)

        setupRecyclerView()
        loadProfile()
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            val profile = repository.getProfileById(profileId)
            if (profile == null) {
                Toast.makeText(this@AppSelectionActivity, "Profile not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            // Set title based on blocking mode
            isAllowlistMode = profile.blockingMode == BlockingMode.ALLOWLIST.name
            val titleRes = if (isAllowlistMode) R.string.select_apps_title_allow else R.string.select_apps_title
            supportActionBar?.title = getString(titleRes)
            supportActionBar?.subtitle = profile.name

            loadApps()
        }
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter { app, isChecked ->
            lifecycleScope.launch {
                repository.setAppInProfile(profileId, app.packageName, app.appName, isChecked)
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Load installed apps in background
            installedApps = withContext(Dispatchers.IO) {
                InstalledAppsHelper.getLaunchableApps(this@AppSelectionActivity)
            }

            // Get currently selected apps for this profile
            val profileApps = repository.getAppsForProfileSync(profileId)
            selectedPackages = profileApps.map { it.packageName }.toSet()

            // Update adapter
            adapter.setApps(installedApps, selectedPackages, isAllowlistMode)

            binding.progressBar.visibility = View.GONE

            // Observe changes for this profile
            repository.getAppsForProfile(profileId).collect { apps ->
                selectedPackages = apps.map { it.packageName }.toSet()
                adapter.updateSelectedPackages(selectedPackages)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Wrapper that combines app info with selection state for proper DiffUtil comparison.
     */
    private data class AppListItem(
        val app: InstalledApp,
        val isSelected: Boolean,
        val isAllowlistMode: Boolean
    )

    private class AppListAdapter(
        private val onAppToggled: (InstalledApp, Boolean) -> Unit
    ) : ListAdapter<AppListItem, AppListAdapter.ViewHolder>(AppDiffCallback()) {

        private var apps: List<InstalledApp> = emptyList()
        private var selected: Set<String> = emptySet()
        private var allowlistMode: Boolean = false

        fun setApps(apps: List<InstalledApp>, selected: Set<String>, isAllowlistMode: Boolean) {
            this.apps = apps
            this.selected = selected
            this.allowlistMode = isAllowlistMode
            submitList(buildList())
        }

        fun updateSelectedPackages(selected: Set<String>) {
            this.selected = selected
            submitList(buildList())
        }

        private fun buildList(): List<AppListItem> {
            return apps.map { app ->
                AppListItem(app, app.packageName in selected, allowlistMode)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view, onAppToggled)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        class ViewHolder(
            itemView: View,
            private val onAppToggled: (InstalledApp, Boolean) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.appIcon)
            private val name: TextView = itemView.findViewById(R.id.appName)
            private val packageName: TextView = itemView.findViewById(R.id.appPackage)
            private val checkbox: CheckBox = itemView.findViewById(R.id.appCheckbox)

            fun bind(item: AppListItem) {
                val app = item.app
                app.icon?.let { icon.setImageDrawable(it) }
                name.text = app.appName
                packageName.text = app.packageName

                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = item.isSelected
                checkbox.setOnCheckedChangeListener { _, checked ->
                    onAppToggled(app, checked)
                }

                itemView.setOnClickListener {
                    checkbox.isChecked = !checkbox.isChecked
                }

                // Gray = blocked: allowlist grays unselected, blocklist grays selected
                val isBlocked = if (item.isAllowlistMode) !item.isSelected else item.isSelected
                itemView.alpha = if (isBlocked) 0.4f else 1.0f
            }
        }

        private class AppDiffCallback : DiffUtil.ItemCallback<AppListItem>() {
            override fun areItemsTheSame(oldItem: AppListItem, newItem: AppListItem): Boolean {
                return oldItem.app.packageName == newItem.app.packageName
            }

            override fun areContentsTheSame(oldItem: AppListItem, newItem: AppListItem): Boolean {
                return oldItem.app.packageName == newItem.app.packageName &&
                        oldItem.app.appName == newItem.app.appName &&
                        oldItem.isSelected == newItem.isSelected &&
                        oldItem.isAllowlistMode == newItem.isAllowlistMode
            }
        }
    }
}
