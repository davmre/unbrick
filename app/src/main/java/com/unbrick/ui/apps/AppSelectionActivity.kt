package com.unbrick.ui.apps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.unbrick.R
import com.unbrick.UnbrickApplication
import com.unbrick.data.model.BlockedApp
import com.unbrick.data.model.BlockingMode
import com.unbrick.databinding.ActivityAppSelectionBinding
import com.unbrick.util.InstalledApp
import com.unbrick.util.InstalledAppsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectionBinding
    private val repository by lazy { (application as UnbrickApplication).repository }

    private lateinit var adapter: AppListAdapter
    private var installedApps: List<InstalledApp> = emptyList()
    private var blockedPackages: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.select_apps_title)

        setupTabs()
        setupRecyclerView()
        loadApps()
    }

    private fun setupTabs() {
        lifecycleScope.launch {
            val settings = repository.getSettings()
            val mode = BlockingMode.valueOf(settings.blockingMode)

            binding.tabLayout.getTabAt(if (mode == BlockingMode.BLOCKLIST) 0 else 1)?.select()
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val mode = when (tab?.position) {
                    0 -> BlockingMode.BLOCKLIST
                    else -> BlockingMode.ALLOWLIST
                }
                lifecycleScope.launch {
                    repository.setBlockingMode(mode)
                }
                updateModeDescription(mode)
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
                repository.setAppBlocked(app.packageName, app.appName, isChecked)
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

            // Get currently blocked apps
            val blockedApps = repository.blockedApps.first()
            blockedPackages = blockedApps.map { it.packageName }.toSet()

            // Update adapter
            adapter.setApps(installedApps, blockedPackages)

            binding.progressBar.visibility = View.GONE

            // Observe changes
            repository.blockedApps.collect { blocked ->
                blockedPackages = blocked.map { it.packageName }.toSet()
                adapter.updateBlockedPackages(blockedPackages)
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
        private var blocked: Set<String> = emptySet()

        fun setApps(apps: List<InstalledApp>, blocked: Set<String>) {
            this.apps = apps
            this.blocked = blocked
            notifyDataSetChanged()
        }

        fun updateBlockedPackages(blocked: Set<String>) {
            this.blocked = blocked
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.bind(app, app.packageName in blocked)
        }

        override fun getItemCount() = apps.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.appIcon)
            private val name: TextView = itemView.findViewById(R.id.appName)
            private val packageName: TextView = itemView.findViewById(R.id.appPackage)
            private val checkbox: CheckBox = itemView.findViewById(R.id.appCheckbox)

            fun bind(app: InstalledApp, isBlocked: Boolean) {
                app.icon?.let { icon.setImageDrawable(it) }
                name.text = app.appName
                packageName.text = app.packageName

                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = isBlocked
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
