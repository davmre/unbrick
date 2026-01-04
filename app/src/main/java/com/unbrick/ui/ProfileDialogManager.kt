package com.unbrick.ui

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.unbrick.R
import com.unbrick.data.model.BlockingMode
import com.unbrick.data.model.BlockingProfile
import com.unbrick.data.repository.UnbrickRepository
import com.unbrick.ui.apps.AppSelectionActivity
import kotlinx.coroutines.launch

/**
 * Manages profile creation, editing, and deletion dialogs.
 * Extracted from MainActivity for better separation of concerns.
 */
class ProfileDialogManager(
    private val context: Context,
    private val repository: UnbrickRepository,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val appSelectionLauncher: ActivityResultLauncher<Intent>
) {
    // Dialog state
    private var editingProfileId: Long? = null
    private var isNewProfile = false
    private var profileDialog: AlertDialog? = null

    /**
     * Creates a new profile and opens the edit dialog.
     */
    fun createNewProfile() {
        lifecycleScope.launch {
            val newId = repository.createProfile("New Profile", BlockingMode.BLOCKLIST)
            val newProfile = repository.getProfileById(newId)
            newProfile?.let {
                showProfileDialog(it, isNew = true)
            }
        }
    }

    /**
     * Opens the edit dialog for an existing profile.
     */
    fun editProfile(profile: BlockingProfile) {
        showProfileDialog(profile, isNew = false)
    }

    /**
     * Called when returning from app selection to refresh dialog state.
     */
    fun onAppSelectionResult() {
        editingProfileId?.let { profileId ->
            lifecycleScope.launch {
                val appCount = repository.getAppCountForProfile(profileId)
                updateDialogSaveButtonState(appCount)
            }
        }
    }

    /**
     * Returns true if a dialog is currently being edited.
     */
    fun isDialogOpen(): Boolean = profileDialog?.isShowing == true

    private fun showProfileDialog(profile: BlockingProfile, isNew: Boolean) {
        editingProfileId = profile.id
        isNewProfile = isNew

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_profile, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.profileNameInput)
        val modeToggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.modeToggleGroup)
        val modeDescription = dialogView.findViewById<TextView>(R.id.modeDescription)
        val btnConfigureApps = dialogView.findViewById<MaterialButton>(R.id.btnConfigureApps)
        val appCountText = dialogView.findViewById<TextView>(R.id.appCountText)

        // Pre-fill with current values (hint comes from TextInputLayout in XML)
        nameInput.setText(if (isNew) "" else profile.name)
        val currentMode = BlockingMode.valueOf(profile.blockingMode)
        modeToggle.check(if (currentMode == BlockingMode.BLOCKLIST) R.id.btnBlocklist else R.id.btnAllowlist)

        // Update mode description when toggle changes
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                modeDescription.text = when (checkedId) {
                    R.id.btnBlocklist -> context.getString(R.string.mode_blocklist_desc)
                    else -> context.getString(R.string.mode_allowlist_desc)
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(context)
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

        // Only show delete button for existing profiles
        if (!isNew) {
            builder.setNeutralButton(R.string.delete_profile) { _, _ ->
                showDeleteProfileConfirmation(profile)
            }
        }

        profileDialog = builder.create()
        profileDialog?.setOnShowListener { dialog ->
            val alertDialog = dialog as AlertDialog
            val saveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)

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
                val intent = Intent(context, AppSelectionActivity::class.java)
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
                        Toast.makeText(context, "Configure at least one app", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

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

    private fun showDeleteProfileConfirmation(profile: BlockingProfile) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_profile)
            .setMessage(context.getString(R.string.delete_profile_confirm, profile.name))
            .setPositiveButton(R.string.delete_profile) { _, _ ->
                lifecycleScope.launch {
                    val deleted = repository.deleteProfile(profile.id)
                    if (deleted) {
                        Toast.makeText(context, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.cannot_delete_last_profile, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateAppCountText(textView: TextView, count: Int) {
        textView.text = when (count) {
            0 -> "No apps selected"
            1 -> "1 app selected"
            else -> "$count apps selected"
        }
    }

    private fun updateDialogSaveButtonState(appCount: Int) {
        profileDialog?.let { dialog ->
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton?.isEnabled = appCount > 0

            // Also update the app count text
            dialog.findViewById<TextView>(R.id.appCountText)?.let {
                updateAppCountText(it, appCount)
            }
        }
    }

    private fun clearDialogState() {
        editingProfileId = null
        isNewProfile = false
        profileDialog = null
    }
}
