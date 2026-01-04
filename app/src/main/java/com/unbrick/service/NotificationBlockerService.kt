package com.unbrick.service

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.unbrick.UnbrickApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Notification listener service that hides notifications from blocked apps
 * while the device is locked, and restores them when unlocked.
 */
class NotificationBlockerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository by lazy { UnbrickApplication.getInstance().repository }

    // Snooze duration: 7 days in milliseconds
    private val snoozeDurationMs = 7L * 24 * 60 * 60 * 1000

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        isRunning = true

        // Observe lock state changes
        serviceScope.launch {
            repository.lockState.collectLatest { lockState ->
                val isLocked = lockState?.isLocked ?: false
                if (isLocked) {
                    snoozeBlockedNotifications()
                } else {
                    unsnoozeBlockedNotifications()
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
        isRunning = false
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        serviceScope.launch {
            try {
                // Atomic check of lock state + app blocked status to prevent race conditions
                if (repository.shouldBlockApp(sbn.packageName)) {
                    snoozeNotification(sbn.key, snoozeDurationMs)
                    Log.d(TAG, "Snoozed notification from ${sbn.packageName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking/snoozing notification", e)
            }
        }
    }

    /**
     * Snooze all current notifications from blocked apps
     */
    private suspend fun snoozeBlockedNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            val activeNotifications = activeNotifications ?: return
            for (sbn in activeNotifications) {
                if (repository.isAppBlocked(sbn.packageName)) {
                    try {
                        snoozeNotification(sbn.key, snoozeDurationMs)
                        Log.d(TAG, "Snoozed notification from ${sbn.packageName}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error snoozing notification ${sbn.key}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error snoozing blocked notifications", e)
        }
    }

    /**
     * Un-snooze all snoozed notifications from blocked apps.
     * We filter by blocked apps to avoid un-snoozing manually snoozed notifications
     * from other apps.
     */
    private suspend fun unsnoozeBlockedNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            val snoozedNotifications = snoozedNotifications ?: return
            for (sbn in snoozedNotifications) {
                if (repository.isAppBlocked(sbn.packageName)) {
                    try {
                        // Re-snooze with 1ms to trigger immediate reappearance
                        snoozeNotification(sbn.key, 1L)
                        Log.d(TAG, "Unsnoozed notification from ${sbn.packageName}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unsnoozing notification ${sbn.key}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unsnoozing blocked notifications", e)
        }
    }

    companion object {
        private const val TAG = "NotificationBlocker"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
