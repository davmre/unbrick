package com.unbrick.ui

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.unbrick.R
import com.unbrick.data.model.LockState
import com.unbrick.data.repository.UnbrickRepository
import com.unbrick.databinding.ActivityMainBinding
import com.unbrick.util.PermissionHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Handles emergency unlock UI and countdown timer logic.
 * Extracted from MainActivity for better separation of concerns.
 */
class EmergencyUnlockController(
    private val binding: ActivityMainBinding,
    private val repository: UnbrickRepository,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val showToast: (String) -> Unit
) {
    private var countdownJob: Job? = null

    /**
     * Updates the emergency unlock UI based on lock state.
     * Should be called whenever lock state changes.
     */
    fun updateUI(state: LockState?) {
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

    /**
     * Handles emergency unlock button click.
     * Behavior depends on current state: request, cancel, or perform unlock.
     */
    fun handleButtonClick(showCancelDialog: () -> Unit) {
        lifecycleScope.launch {
            val remaining = repository.getEmergencyUnlockTimeRemaining()

            when {
                remaining == null -> {
                    // No request yet, start one
                    repository.requestEmergencyUnlock()
                    showToast("Emergency unlock requested")
                }
                remaining <= 0 -> {
                    // Ready to unlock
                    if (repository.performEmergencyUnlock()) {
                        showToast("Unlocked!")
                    }
                }
                else -> {
                    // Request in progress, offer to cancel
                    showCancelDialog()
                }
            }
        }
    }

    /**
     * Cancels an in-progress emergency unlock request.
     */
    fun cancelRequest() {
        lifecycleScope.launch {
            repository.cancelEmergencyUnlock()
        }
    }

    /**
     * Stops the countdown timer. Call when Activity is destroyed.
     */
    fun cleanup() {
        countdownJob?.cancel()
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

    private fun getString(resId: Int): String {
        return binding.root.context.getString(resId)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        return binding.root.context.getString(resId, *args)
    }
}
