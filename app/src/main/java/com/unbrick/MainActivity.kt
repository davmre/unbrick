package com.unbrick

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButtonToggleGroup
import com.unbrick.data.model.BlockingMode
import com.unbrick.data.model.BlockingProfile
import com.unbrick.databinding.ActivityMainBinding
import com.unbrick.nfc.NfcHandler
import com.unbrick.ui.apps.AppSelectionActivity
import com.unbrick.util.PermissionHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcHandler: NfcHandler

    private val repository by lazy { (application as UnbrickApplication).repository }

    private var isRegistrationMode = false
    private var countdownJob: Job? = null
    private var profiles: List<BlockingProfile> = emptyList()
    private var activeProfile: BlockingProfile? = null
    private var isLocked = false

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcHandler = NfcHandler(this)

        setupUI()
        observeState()
        handleIntent(intent)
    }

    private fun setupUI() {
        // Lock status card click
        binding.lockStatusCard.setOnClickListener {
            showLockToggleInfo()
        }

        // Register NFC tag button
        binding.btnRegisterTag.setOnClickListener {
            startTagRegistration()
        }

        // Manage apps button - passes active profile ID
        binding.btnManageApps.setOnClickListener {
            activeProfile?.let { profile ->
                val intent = Intent(this, AppSelectionActivity::class.java)
                intent.putExtra(AppSelectionActivity.EXTRA_PROFILE_ID, profile.id)
                startActivity(intent)
            } ?: Toast.makeText(this, "No active profile", Toast.LENGTH_SHORT).show()
        }

        // Enable accessibility button
        binding.btnEnableAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        // Enable device admin button
        binding.btnEnableDeviceAdmin.setOnClickListener {
            val intent = PermissionHelper.createDeviceAdminIntent(this)
            deviceAdminLauncher.launch(intent)
        }

        // Emergency unlock button
        binding.btnEmergencyUnlock.setOnClickListener {
            handleEmergencyUnlock()
        }

        // Profile dropdown setup
        binding.profileDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position == profiles.size) {
                // "Add new profile..." selected
                showCreateProfileDialog()
            } else {
                // Existing profile selected
                val selectedProfile = profiles[position]
                if (selectedProfile.id != activeProfile?.id) {
                    lifecycleScope.launch {
                        repository.setActiveProfile(selectedProfile.id)
                    }
                }
            }
        }

        // Edit profile button
        binding.btnEditProfile.setOnClickListener {
            activeProfile?.let { showEditProfileDialog(it) }
        }
    }

    private fun observeState() {
        // Observe profiles and lock state together
        lifecycleScope.launch {
            combine(
                repository.allProfiles,
                repository.activeProfile,
                repository.lockState
            ) { profileList, active, lockState ->
                Triple(profileList, active, lockState)
            }.collectLatest { (profileList, active, lockState) ->
                profiles = profileList
                activeProfile = active
                isLocked = lockState?.isLocked ?: false

                updateProfileDropdown()
                updateLockUI(isLocked)
                updateEmergencyUnlockUI(lockState)
                updateProfileUIEnabledState()
            }
        }

        lifecycleScope.launch {
            repository.registeredTags.collectLatest { tags ->
                updateTagStatus(tags.isNotEmpty())
            }
        }
    }

    private fun updateProfileDropdown() {
        val items = profiles.map { it.name }.toMutableList()
        items.add(getString(R.string.add_new_profile))

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
        binding.profileDropdown.setAdapter(adapter)

        // Set current selection text
        activeProfile?.let { profile ->
            binding.profileDropdown.setText(profile.name, false)
        }
    }

    private fun updateProfileUIEnabledState() {
        // Disable profile selection when locked
        binding.profileDropdown.isEnabled = !isLocked
        binding.profileDropdownLayout.isEnabled = !isLocked
        binding.btnEditProfile.isEnabled = !isLocked
        binding.profileCard.alpha = if (isLocked) 0.5f else 1.0f
    }

    private fun updateLockUI(isLocked: Boolean) {
        if (isLocked) {
            binding.lockStatusIcon.setImageResource(R.drawable.ic_lock)
            binding.lockStatusText.text = getString(R.string.status_locked)
            binding.lockStatusCard.setCardBackgroundColor(getColor(R.color.locked_background))
        } else {
            binding.lockStatusIcon.setImageResource(R.drawable.ic_unlock)
            binding.lockStatusText.text = getString(R.string.status_unlocked)
            binding.lockStatusCard.setCardBackgroundColor(getColor(R.color.unlocked_background))
        }
        binding.lockHintText.text = getString(R.string.tap_nfc_to_toggle)
    }

    private fun updateTagStatus(hasTag: Boolean) {
        if (hasTag) {
            binding.btnRegisterTag.text = "Re-register NFC Tag"
            binding.tagStatusText.text = "NFC tag registered"
            binding.tagStatusText.visibility = View.VISIBLE
        } else {
            binding.btnRegisterTag.text = getString(R.string.register_tag)
            binding.tagStatusText.text = getString(R.string.no_tag_registered)
            binding.tagStatusText.visibility = View.VISIBLE
        }
    }

    private fun updateEmergencyUnlockUI(state: com.unbrick.data.model.LockState?) {
        if (state?.isLocked != true) {
            binding.emergencyUnlockSection.visibility = View.GONE
            countdownJob?.cancel()
            return
        }

        binding.emergencyUnlockSection.visibility = View.VISIBLE

        if (state.emergencyUnlockRequestedAt != null) {
            // Unlock has been requested, show countdown or unlock button
            startCountdownTimer()
        } else {
            // No unlock requested yet
            countdownJob?.cancel()
            binding.btnEmergencyUnlock.text = getString(R.string.request_unlock)
            binding.emergencyUnlockStatus.visibility = View.GONE
        }
    }

    private fun startCountdownTimer() {
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            while (isActive) {
                val remaining = repository.getEmergencyUnlockTimeRemaining()
                if (remaining == null || remaining <= 0) {
                    binding.btnEmergencyUnlock.text = getString(R.string.perform_unlock)
                    binding.emergencyUnlockStatus.text = getString(R.string.unlock_available)
                    binding.emergencyUnlockStatus.visibility = View.VISIBLE
                    break
                } else {
                    val formatted = PermissionHelper.formatDuration(remaining)
                    binding.btnEmergencyUnlock.text = getString(R.string.cancel_request)
                    binding.emergencyUnlockStatus.text = getString(R.string.unlock_requested, formatted)
                    binding.emergencyUnlockStatus.visibility = View.VISIBLE
                }
                delay(1000)
            }
        }
    }

    private fun handleEmergencyUnlock() {
        lifecycleScope.launch {
            val remaining = repository.getEmergencyUnlockTimeRemaining()

            when {
                remaining == null -> {
                    // No request yet, start one
                    repository.requestEmergencyUnlock()
                    Toast.makeText(this@MainActivity, "Emergency unlock requested", Toast.LENGTH_SHORT).show()
                }
                remaining <= 0 -> {
                    // Ready to unlock
                    if (repository.performEmergencyUnlock()) {
                        Toast.makeText(this@MainActivity, "Unlocked!", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    // Request in progress, offer to cancel
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Cancel unlock request?")
                        .setMessage("Are you sure you want to cancel the emergency unlock request?")
                        .setPositiveButton("Yes") { _, _ ->
                            lifecycleScope.launch {
                                repository.cancelEmergencyUnlock()
                            }
                        }
                        .setNegativeButton("No", null)
                        .show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcHandler.enableForegroundDispatch()
        updatePermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        nfcHandler.disableForegroundDispatch()
    }

    private fun updatePermissionStatus() {
        val accessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        val deviceAdminEnabled = PermissionHelper.isDeviceAdminEnabled(this)

        // Accessibility section
        if (accessibilityEnabled) {
            binding.accessibilityStatusIcon.setImageResource(R.drawable.ic_check)
            binding.btnEnableAccessibility.visibility = View.GONE
        } else {
            binding.accessibilityStatusIcon.setImageResource(R.drawable.ic_warning)
            binding.btnEnableAccessibility.visibility = View.VISIBLE
        }

        // Device admin section
        if (deviceAdminEnabled) {
            binding.deviceAdminStatusIcon.setImageResource(R.drawable.ic_check)
            binding.btnEnableDeviceAdmin.visibility = View.GONE
        } else {
            binding.deviceAdminStatusIcon.setImageResource(R.drawable.ic_warning)
            binding.btnEnableDeviceAdmin.visibility = View.VISIBLE
        }

        // NFC section
        if (!nfcHandler.isNfcAvailable) {
            binding.nfcStatusText.text = "NFC not available on this device"
            binding.btnRegisterTag.isEnabled = false
        } else if (!nfcHandler.isNfcEnabled) {
            binding.nfcStatusText.text = "NFC is disabled. Tap to enable."
            binding.nfcStatusText.setOnClickListener {
                PermissionHelper.openNfcSettings(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (!NfcHandler.isNfcIntent(intent)) return

        val tagId = NfcHandler.getTagIdFromIntent(intent)
        if (tagId == null) {
            Toast.makeText(this, "Could not read NFC tag", Toast.LENGTH_SHORT).show()
            return
        }

        if (isRegistrationMode) {
            // Register this tag
            lifecycleScope.launch {
                repository.registerTag(tagId)
                isRegistrationMode = false
                binding.registrationOverlay.visibility = View.GONE
                Toast.makeText(this@MainActivity, getString(R.string.tag_registered), Toast.LENGTH_SHORT).show()
            }
        } else {
            // Check if this is a registered tag and toggle lock
            lifecycleScope.launch {
                if (repository.isTagRegistered(tagId)) {
                    val newState = repository.toggleLock()
                    val message = if (newState) "Locked" else "Unlocked"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                } else if (!repository.hasRegisteredTag()) {
                    // No tag registered yet, offer to register this one
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Register this tag?")
                        .setMessage("No NFC tag is registered. Would you like to use this tag?")
                        .setPositiveButton("Yes") { _, _ ->
                            lifecycleScope.launch {
                                repository.registerTag(tagId)
                                Toast.makeText(this@MainActivity, getString(R.string.tag_registered), Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("No", null)
                        .show()
                } else {
                    Toast.makeText(this@MainActivity, "Unrecognized tag", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startTagRegistration() {
        if (!nfcHandler.isNfcEnabled) {
            PermissionHelper.openNfcSettings(this)
            return
        }

        isRegistrationMode = true
        binding.registrationOverlay.visibility = View.VISIBLE
        binding.registrationOverlay.setOnClickListener {
            isRegistrationMode = false
            binding.registrationOverlay.visibility = View.GONE
        }
    }

    private fun showLockToggleInfo() {
        lifecycleScope.launch {
            if (!repository.hasRegisteredTag()) {
                Toast.makeText(this@MainActivity, "Register an NFC tag first", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.tap_nfc_to_toggle), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreateProfileDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_profile, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.profileNameInput)
        val modeToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.modeToggleGroup)

        // Default to blocklist mode
        modeToggle.check(R.id.btnBlocklist)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_profile)
            .setView(dialogView)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = nameInput.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    val mode = if (modeToggle.checkedButtonId == R.id.btnBlocklist) {
                        BlockingMode.BLOCKLIST
                    } else {
                        BlockingMode.ALLOWLIST
                    }
                    lifecycleScope.launch {
                        val newId = repository.createProfile(name, mode)
                        repository.setActiveProfile(newId)
                        Toast.makeText(this@MainActivity, R.string.profile_created, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditProfileDialog(profile: BlockingProfile) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_profile, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.profileNameInput)
        val modeToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.modeToggleGroup)

        // Pre-fill with current values
        nameInput.setText(profile.name)
        val currentMode = BlockingMode.valueOf(profile.blockingMode)
        modeToggle.check(if (currentMode == BlockingMode.BLOCKLIST) R.id.btnBlocklist else R.id.btnAllowlist)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_profile)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    val mode = if (modeToggle.checkedButtonId == R.id.btnBlocklist) {
                        BlockingMode.BLOCKLIST
                    } else {
                        BlockingMode.ALLOWLIST
                    }
                    lifecycleScope.launch {
                        repository.updateProfile(profile.id, name, mode)
                        Toast.makeText(this@MainActivity, R.string.profile_updated, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete_profile) { _, _ ->
                showDeleteProfileConfirmation(profile)
            }
            .show()
    }

    private fun showDeleteProfileConfirmation(profile: BlockingProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_profile)
            .setMessage(getString(R.string.delete_profile_confirm, profile.name))
            .setPositiveButton(R.string.delete_profile) { _, _ ->
                lifecycleScope.launch {
                    val deleted = repository.deleteProfile(profile.id)
                    if (deleted) {
                        Toast.makeText(this@MainActivity, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, R.string.cannot_delete_last_profile, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
