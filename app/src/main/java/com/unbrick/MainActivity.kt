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
import com.unbrick.service.AppBlockerAccessibilityService
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

    // For profile dialog - track the profile being edited and if it's new
    private var editingProfileId: Long? = null
    private var isNewProfile = false
    private var profileDialog: androidx.appcompat.app.AlertDialog? = null

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
    }

    private val appSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // After returning from app selection, refresh the dialog if it's open
        editingProfileId?.let { profileId ->
            lifecycleScope.launch {
                val appCount = repository.getAppCountForProfile(profileId)
                updateDialogSaveButtonState(appCount)
            }
        }
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
                createNewProfile()
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
            activeProfile?.let { showProfileDialog(it, isNew = false) }
        }

        // Create first profile button (shown when no profiles exist)
        binding.btnCreateFirstProfile.setOnClickListener {
            createNewProfile()
        }

        // Debug toggle lock button (only in debug builds on emulator or when NFC unavailable)
        if (BuildConfig.DEBUG && (PermissionHelper.isEmulator() || !nfcHandler.isNfcAvailable)) {
            binding.btnDebugToggleLock.visibility = View.VISIBLE
            binding.btnDebugToggleLock.setOnClickListener {
                lifecycleScope.launch {
                    val currentlyLocked = repository.isLocked()
                    if (!currentlyLocked) {
                        // Trying to lock - validate first
                        val (canLock, error) = canEnterLockedMode()
                        if (!canLock) {
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                    val newState = repository.toggleLock()
                    val message = if (newState) "Locked" else "Unlocked"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
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
        if (profiles.isEmpty()) {
            // Show no-profiles state
            binding.profileCard.visibility = View.GONE
            binding.noProfilesCard.visibility = View.VISIBLE
        } else {
            // Show normal profile selector
            binding.profileCard.visibility = View.VISIBLE
            binding.noProfilesCard.visibility = View.GONE

            val items = profiles.map { it.name }.toMutableList()
            items.add(getString(R.string.add_new_profile))

            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
            binding.profileDropdown.setAdapter(adapter)

            // Set current selection text
            activeProfile?.let { profile ->
                binding.profileDropdown.setText(profile.name, false)
            }
        }
    }

    private fun updateProfileUIEnabledState() {
        // Disable profile selection when locked
        val enabled = !isLocked && profiles.isNotEmpty()
        binding.profileDropdown.isEnabled = enabled
        binding.profileDropdownLayout.isEnabled = enabled
        binding.btnEditProfile.isEnabled = enabled
        binding.profileCard.alpha = if (isLocked) 0.5f else 1.0f
        binding.noProfilesCard.alpha = if (isLocked) 0.5f else 1.0f
        binding.btnCreateFirstProfile.isEnabled = !isLocked
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
                    val currentlyLocked = repository.isLocked()
                    if (!currentlyLocked) {
                        // Trying to lock - validate first
                        val (canLock, error) = canEnterLockedMode()
                        if (!canLock) {
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
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

    private fun createNewProfile() {
        lifecycleScope.launch {
            // Create profile immediately with default name
            val newId = repository.createProfile("New Profile", BlockingMode.BLOCKLIST)
            val newProfile = repository.getProfileById(newId)
            newProfile?.let {
                showProfileDialog(it, isNew = true)
            }
        }
    }

    private fun showProfileDialog(profile: BlockingProfile, isNew: Boolean) {
        editingProfileId = profile.id
        isNewProfile = isNew

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_profile, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.profileNameInput)
        val modeToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.modeToggleGroup)
        val modeDescription = dialogView.findViewById<android.widget.TextView>(R.id.modeDescription)
        val btnConfigureApps = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfigureApps)
        val appCountText = dialogView.findViewById<android.widget.TextView>(R.id.appCountText)

        // Pre-fill with current values
        nameInput.setText(if (isNew) "" else profile.name)
        nameInput.hint = getString(R.string.profile_name_hint)
        val currentMode = BlockingMode.valueOf(profile.blockingMode)
        modeToggle.check(if (currentMode == BlockingMode.BLOCKLIST) R.id.btnBlocklist else R.id.btnAllowlist)

        // Update mode description when toggle changes
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                modeDescription.text = when (checkedId) {
                    R.id.btnBlocklist -> getString(R.string.mode_blocklist_desc)
                    else -> getString(R.string.mode_allowlist_desc)
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (isNew) R.string.create_profile else R.string.edit_profile)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null) // Set listener later to prevent auto-dismiss
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (isNew) {
                    // Delete the draft profile
                    lifecycleScope.launch {
                        repository.deleteProfile(profile.id)
                    }
                }
                clearDialogState()
            }

        // Only show delete button for existing profiles (not the last one)
        if (!isNew) {
            builder.setNeutralButton(R.string.delete_profile) { _, _ ->
                showDeleteProfileConfirmation(profile)
            }
        }

        profileDialog = builder.create()
        profileDialog?.setOnShowListener { dialog ->
            val alertDialog = dialog as androidx.appcompat.app.AlertDialog
            val saveButton = alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)

            // Configure apps button
            btnConfigureApps.setOnClickListener {
                // Save current name/mode before navigating
                val name = nameInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "New Profile"
                val mode = if (modeToggle.checkedButtonId == R.id.btnBlocklist) {
                    BlockingMode.BLOCKLIST
                } else {
                    BlockingMode.ALLOWLIST
                }
                lifecycleScope.launch {
                    repository.updateProfile(profile.id, name, mode)
                }

                // Navigate to app selection
                val intent = Intent(this, AppSelectionActivity::class.java)
                intent.putExtra(AppSelectionActivity.EXTRA_PROFILE_ID, profile.id)
                appSelectionLauncher.launch(intent)
            }

            // Update app count and save button state
            lifecycleScope.launch {
                val appCount = repository.getAppCountForProfile(profile.id)
                updateAppCountText(appCountText, appCount)
                saveButton.isEnabled = appCount > 0
            }

            // Save button click handler
            saveButton.setOnClickListener {
                val name = nameInput.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    nameInput.error = "Name required"
                    return@setOnClickListener
                }

                lifecycleScope.launch {
                    val appCount = repository.getAppCountForProfile(profile.id)
                    if (appCount == 0) {
                        Toast.makeText(this@MainActivity, "Configure at least one app", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val mode = if (modeToggle.checkedButtonId == R.id.btnBlocklist) {
                        BlockingMode.BLOCKLIST
                    } else {
                        BlockingMode.ALLOWLIST
                    }
                    repository.updateProfile(profile.id, name, mode)
                    repository.setActiveProfile(profile.id)

                    val message = if (isNew) R.string.profile_created else R.string.profile_updated
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()

                    clearDialogState()
                    alertDialog.dismiss()
                }
            }
        }

        profileDialog?.setOnDismissListener {
            // If dialog is dismissed without saving a new profile, delete it
            if (isNewProfile && editingProfileId != null) {
                lifecycleScope.launch {
                    repository.deleteProfile(editingProfileId!!)
                }
            }
            clearDialogState()
        }

        profileDialog?.show()
    }

    private fun updateAppCountText(textView: android.widget.TextView, count: Int) {
        textView.text = when (count) {
            0 -> "No apps selected"
            1 -> "1 app selected"
            else -> "$count apps selected"
        }
    }

    private fun updateDialogSaveButtonState(appCount: Int) {
        profileDialog?.let { dialog ->
            val saveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            saveButton?.isEnabled = appCount > 0

            // Also update the app count text
            dialog.findViewById<android.widget.TextView>(R.id.appCountText)?.let {
                updateAppCountText(it, appCount)
            }
        }
    }

    private fun clearDialogState() {
        editingProfileId = null
        isNewProfile = false
        profileDialog = null
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

    /**
     * Checks if the app can enter locked mode.
     * Returns a pair of (canLock, errorMessage).
     */
    private fun canEnterLockedMode(): Pair<Boolean, String?> {
        if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
            return Pair(false, "Enable accessibility service before locking")
        }
        if (!AppBlockerAccessibilityService.isRunning) {
            return Pair(false, "Accessibility service not connected. Try disabling and re-enabling it in Settings.")
        }
        return Pair(true, null)
    }
}
