package com.unbrick

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unbrick.data.model.BlockingProfile
import com.unbrick.databinding.ActivityMainBinding
import com.unbrick.nfc.NfcHandler
import com.unbrick.service.AppBlockerAccessibilityService
import com.unbrick.ui.EmergencyUnlockController
import com.unbrick.ui.ProfileDialogManager
import com.unbrick.ui.apps.AppSelectionActivity
import com.unbrick.ui.settings.SettingsActivity
import com.unbrick.util.PermissionHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcHandler: NfcHandler
    private lateinit var emergencyUnlockController: EmergencyUnlockController
    private lateinit var profileDialogManager: ProfileDialogManager

    private val repository by lazy { (application as UnbrickApplication).repository }

    // UI state (derived from DB via Flows)
    private var profiles: List<BlockingProfile> = emptyList()
    private var activeProfile: BlockingProfile? = null
    private var isLocked = false

    // NFC registration mode (ephemeral)
    private var isRegistrationMode = false

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
    }

    private val appSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        profileDialogManager.onAppSelectionResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcHandler = NfcHandler(this)
        emergencyUnlockController = EmergencyUnlockController(
            binding = binding,
            repository = repository,
            lifecycleScope = lifecycleScope,
            showToast = { message -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        )
        profileDialogManager = ProfileDialogManager(
            context = this,
            repository = repository,
            lifecycleScope = lifecycleScope,
            appSelectionLauncher = appSelectionLauncher
        )

        setupUI()
        observeState()
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        emergencyUnlockController.cleanup()
    }

    // ==================== UI Setup ====================

    private fun setupUI() {
        // Lock status card
        binding.lockStatusCard.setOnClickListener {
            showLockToggleInfo()
        }

        // NFC registration
        binding.btnRegisterTag.setOnClickListener {
            startTagRegistration()
        }

        // Permission buttons
        binding.btnEnableAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }
        binding.btnEnableDeviceAdmin.setOnClickListener {
            deviceAdminLauncher.launch(PermissionHelper.createDeviceAdminIntent(this))
        }
        binding.btnEnableNotifications.setOnClickListener {
            PermissionHelper.openNotificationSettings(this)
        }
        binding.btnEnableNotificationListener.setOnClickListener {
            PermissionHelper.openNotificationListenerSettings(this)
        }

        // Emergency unlock
        binding.btnEmergencyUnlock.setOnClickListener {
            emergencyUnlockController.handleButtonClick {
                showCancelEmergencyUnlockDialog()
            }
        }

        // Profile dropdown
        binding.profileDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position == profiles.size) {
                profileDialogManager.createNewProfile()
            } else {
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
            activeProfile?.let { profileDialogManager.editProfile(it) }
        }

        // Create first profile button
        binding.btnCreateFirstProfile.setOnClickListener {
            profileDialogManager.createNewProfile()
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Debug toggle lock button (only in debug builds on emulator)
        if (BuildConfig.DEBUG && (PermissionHelper.isEmulator() || !nfcHandler.isNfcAvailable)) {
            binding.btnDebugToggleLock.visibility = View.VISIBLE
            binding.btnDebugToggleLock.setOnClickListener {
                toggleLock()
            }
        }
    }

    // ==================== State Observation ====================

    private fun observeState() {
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
                updateLockUI()
                emergencyUnlockController.updateUI(lockState)
                updateProfileUIEnabledState()
            }
        }

        lifecycleScope.launch {
            repository.registeredTags.collectLatest { tags ->
                updateTagStatus(tags.isNotEmpty())
            }
        }
    }

    // ==================== UI Updates ====================

    private fun updateProfileDropdown() {
        if (profiles.isEmpty()) {
            binding.profileCard.visibility = View.GONE
            binding.noProfilesCard.visibility = View.VISIBLE
        } else {
            binding.profileCard.visibility = View.VISIBLE
            binding.noProfilesCard.visibility = View.GONE

            val items = profiles.map { it.name }.toMutableList()
            items.add(getString(R.string.add_new_profile))

            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
            binding.profileDropdown.setAdapter(adapter)

            activeProfile?.let { profile ->
                binding.profileDropdown.setText(profile.name, false)
            }
        }
    }

    private fun updateProfileUIEnabledState() {
        val enabled = !isLocked && profiles.isNotEmpty()
        binding.profileDropdown.isEnabled = enabled
        binding.profileDropdownLayout.isEnabled = enabled
        binding.btnEditProfile.isEnabled = enabled
        binding.profileCard.alpha = if (isLocked) 0.5f else 1.0f
        binding.noProfilesCard.alpha = if (isLocked) 0.5f else 1.0f
        binding.btnCreateFirstProfile.isEnabled = !isLocked
        binding.btnSettings.isEnabled = !isLocked
        binding.btnSettings.alpha = if (isLocked) 0.5f else 1.0f
    }

    private fun updateLockUI() {
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
        } else {
            binding.btnRegisterTag.text = getString(R.string.register_tag)
            binding.tagStatusText.text = getString(R.string.no_tag_registered)
        }
        binding.tagStatusText.visibility = View.VISIBLE
    }

    // ==================== Permissions ====================

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
        // Accessibility
        val accessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        binding.accessibilityStatusIcon.setImageResource(
            if (accessibilityEnabled) R.drawable.ic_check else R.drawable.ic_warning
        )
        binding.btnEnableAccessibility.visibility = if (accessibilityEnabled) View.GONE else View.VISIBLE

        // Device admin
        val deviceAdminEnabled = PermissionHelper.isDeviceAdminEnabled(this)
        binding.deviceAdminStatusIcon.setImageResource(
            if (deviceAdminEnabled) R.drawable.ic_check else R.drawable.ic_warning
        )
        binding.btnEnableDeviceAdmin.visibility = if (deviceAdminEnabled) View.GONE else View.VISIBLE

        // Notifications
        val notificationsEnabled = PermissionHelper.areNotificationsEnabled(this)
        binding.notificationStatusIcon.setImageResource(
            if (notificationsEnabled) R.drawable.ic_check else R.drawable.ic_warning
        )
        binding.btnEnableNotifications.visibility = if (notificationsEnabled) View.GONE else View.VISIBLE

        // Notification listener
        val notificationListenerEnabled = PermissionHelper.isNotificationListenerEnabled(this)
        binding.notificationListenerStatusIcon.setImageResource(
            if (notificationListenerEnabled) R.drawable.ic_check else R.drawable.ic_warning
        )
        binding.btnEnableNotificationListener.visibility = if (notificationListenerEnabled) View.GONE else View.VISIBLE

        // NFC
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

    // ==================== NFC Handling ====================

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
            registerTag(tagId)
        } else {
            handleTagTap(tagId)
        }
    }

    private fun registerTag(tagId: String) {
        lifecycleScope.launch {
            repository.registerTag(tagId)
            isRegistrationMode = false
            binding.registrationOverlay.visibility = View.GONE
            Toast.makeText(this@MainActivity, getString(R.string.tag_registered), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTagTap(tagId: String) {
        lifecycleScope.launch {
            when {
                repository.isTagRegistered(tagId) -> {
                    toggleLock()
                }
                !repository.hasRegisteredTag() -> {
                    showRegisterTagDialog(tagId)
                }
                else -> {
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

    // ==================== Lock Logic ====================

    private fun toggleLock() {
        lifecycleScope.launch {
            val currentlyLocked = repository.isLocked()
            if (!currentlyLocked) {
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

    private fun canEnterLockedMode(): Pair<Boolean, String?> {
        if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
            return Pair(false, "Enable accessibility service before locking")
        }
        if (!AppBlockerAccessibilityService.isRunning) {
            return Pair(false, "Accessibility service not connected. Try disabling and re-enabling it in Settings.")
        }
        return Pair(true, null)
    }

    // ==================== Dialogs ====================

    private fun showLockToggleInfo() {
        lifecycleScope.launch {
            val message = if (!repository.hasRegisteredTag()) {
                "Register an NFC tag first"
            } else {
                getString(R.string.tap_nfc_to_toggle)
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRegisterTagDialog(tagId: String) {
        AlertDialog.Builder(this)
            .setTitle("Register this tag?")
            .setMessage("No NFC tag is registered. Would you like to use this tag?")
            .setPositiveButton("Yes") { _, _ ->
                registerTag(tagId)
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun showCancelEmergencyUnlockDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cancel unlock request?")
            .setMessage("Are you sure you want to cancel the emergency unlock request?")
            .setPositiveButton("Yes") { _, _ ->
                emergencyUnlockController.cancelRequest()
            }
            .setNegativeButton("No", null)
            .show()
    }
}
