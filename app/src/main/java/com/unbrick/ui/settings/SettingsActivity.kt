package com.unbrick.ui.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unbrick.R
import com.unbrick.UnbrickApplication
import com.unbrick.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val repository by lazy { (application as UnbrickApplication).repository }

    // Delay options: label to milliseconds
    private val delayOptions = listOf(
        R.string.unlock_delay_30m to 30 * 60 * 1000L,
        R.string.unlock_delay_1h to 60 * 60 * 1000L,
        R.string.unlock_delay_4h to 4 * 60 * 60 * 1000L,
        R.string.unlock_delay_8h to 8 * 60 * 60 * 1000L,
        R.string.unlock_delay_24h to 24 * 60 * 60 * 1000L
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupDelayDropdown()
        setupBlockSettingsSwitch()
        observeSettings()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupDelayDropdown() {
        val labels = delayOptions.map { getString(it.first) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.unlockDelayDropdown.setAdapter(adapter)

        binding.unlockDelayDropdown.setOnItemClickListener { _, _, position, _ ->
            val delayMs = delayOptions[position].second
            lifecycleScope.launch {
                repository.setUnlockDelay(delayMs)
            }
        }
    }

    private fun setupBlockSettingsSwitch() {
        binding.blockSettingsSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                repository.setBlockSettingsWhenLocked(isChecked)
            }
        }
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            repository.settings.collectLatest { settings ->
                settings?.let {
                    // Update delay dropdown selection
                    val currentDelayMs = it.unlockDelayMs
                    val matchingIndex = delayOptions.indexOfFirst { option -> option.second == currentDelayMs }
                    if (matchingIndex >= 0) {
                        binding.unlockDelayDropdown.setText(getString(delayOptions[matchingIndex].first), false)
                    } else {
                        // If current value doesn't match any option, show the closest one
                        val closestIndex = delayOptions.indexOfFirst { option -> option.second >= currentDelayMs }
                            .takeIf { it >= 0 } ?: (delayOptions.size - 1)
                        binding.unlockDelayDropdown.setText(getString(delayOptions[closestIndex].first), false)
                    }

                    // Update block settings switch
                    binding.blockSettingsSwitch.isChecked = it.blockSettingsWhenLocked
                }
            }
        }
    }
}
