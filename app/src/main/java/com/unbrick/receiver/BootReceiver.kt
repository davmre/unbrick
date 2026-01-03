package com.unbrick.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.unbrick.UnbrickApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives boot completed broadcast to restore lock state after device restart.
 *
 * The accessibility service will auto-restart if it was enabled, but we use this
 * receiver to ensure our state is properly initialized.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        Log.d(TAG, "Boot completed, restoring lock state")

        // Use goAsync() to allow coroutine work
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? UnbrickApplication
                if (app != null) {
                    // Ensure lock state exists in database
                    app.repository.ensureLockStateExists()

                    val isLocked = app.repository.isLocked()
                    Log.d(TAG, "Lock state restored: isLocked=$isLocked")

                    // The accessibility service will auto-restart if enabled
                    // We just need to make sure the database state is correct
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring lock state", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
