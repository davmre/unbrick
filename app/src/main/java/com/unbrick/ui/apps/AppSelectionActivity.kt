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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.unbrick.R
import com.unbrick.UnbrickApplication
import com.unbrick.data.model.BlockingMode
import com.unbrick.data.model.BlockingProfile
import com.unbrick.databinding.ActivityAppSelectionBinding
import com.unbrick.util.InstalledApp
import com.unbrick.util.InstalledAppsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    private var profile: BlockingProfile? = null

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
            profile = repository.getProfileById(profileId)
            if (profile == null) {
                Toast.makeText(this@AppSelectionActivity, "Profile not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            // Update toolbar with profile name
            supportActionBar?.subtitle = profile?.name

            setupTabs()
            loadApps()
        }
    }

    private fun setupTabs() {
        val mode = BlockingMode.valueOf(profile?.blockingMode ?: BlockingMode.BLOCKLIST.name)

        binding.tabLayout.getTabAt(if (mode == BlockingMode.BLOCKLIST) 0 else 1)?.select()
        updateModeDescription(mode)

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val newMode = when (tab?.position) {
                    0 -> BlockingMode.BLOCKLIST
                    else -> BlockingMode.ALLOWLIST
                }
                lifecycleScope.launch {
                    profile?.let {
                        repository.updateProfile(it.id, it.name, newMode)
                    }
                }
                updateModeDescription(newMode)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun updateModeDescription(mode: BlockingMode) {
        binding.modeDescription.text = when (mode) {
            BlockingMode.BLOCKLIST -> getString(R.string.mode_blocklist_desc)
            BlockingMode.ALLOWLIST -> getString(R.string.mode_allowlist_desc)
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
            adapter.setApps(installedApps, selectedPackages)

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

    private inner class AppListAdapter(
        private val onAppToggled: (InstalledApp, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        private var apps: List<InstalledApp> = emptyList()
        private var selected: Set<String> = emptySet()

        fun setApps(apps: List<InstalledApp>, selected: Set<String>) {
            this.apps = apps
            this.selected = selected
            notifyDataSetChanged()
        }

        fun updateSelectedPackages(selected: Set<String>) {
            this.selected = selected
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.bind(app, app.packageName in selected)
        }

        override fun getItemCount() = apps.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.appIcon)
            private val name: TextView = itemView.findViewById(R.id.appName)
            private val packageName: TextView = itemView.findViewById(R.id.appPackage)
            private val checkbox: CheckBox = itemView.findViewById(R.id.appCheckbox)

            fun bind(app: InstalledApp, isSelected: Boolean) {
                app.icon?.let { icon.setImageDrawable(it) }
                name.text = app.appName
                packageName.text = app.packageName

                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = isSelected
                checkbox.setOnCheckedChangeListener { _, checked ->
                    onAppToggled(app, checked)
                }

                itemView.setOnClickListener {
                    checkbox.isChecked = !checkbox.isChecked
                }
            }
        }
    }
}
