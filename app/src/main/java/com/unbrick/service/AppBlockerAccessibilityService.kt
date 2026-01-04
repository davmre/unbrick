package com.unbrick.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.unbrick.MainActivity
import com.unbrick.R
import com.unbrick.UnbrickApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Accessibility service that monitors app launches and blocks restricted apps
 * by navigating back to the home screen.
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository by lazy { UnbrickApplication.getInstance().repository }

    // Track last blocked package to prevent rapid repeated blocks
    // Using lock object for thread-safe access from multiple coroutines
    private val blockLock = Any()
    @Volatile private var lastBlockedPackage: String? = null
    @Volatile private var lastBlockTime: Long = 0
    private val blockCooldownMs = 500L

    // Settings package names to potentially block
    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.app.settings",
        "com.oneplus.settings",
        "com.google.android.apps.wellbeing"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        isRunning = true

        // Observe lock state to show/hide notification
        serviceScope.launch {
            repository.lockState.collectLatest { lockState ->
                val isLocked = lockState?.isLocked ?: false
                if (isLocked) {
                    startForegroundServiceNotification()
                } else {
                    stopForegroundNotification()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Don't block our own app or system UI
        if (packageName == applicationContext.packageName) return
        if (packageName in SYSTEM_PACKAGES) return

        serviceScope.launch {
            try {
                // Check if we should block settings access (atomic check of lock + settings)
                if (packageName in settingsPackages && repository.shouldBlockSettingsApp()) {
                    blockApp(packageName)
                    return@launch
                }

                // Check if this specific app is blocked (atomic check of lock + app status)
                if (repository.shouldBlockApp(packageName)) {
                    blockApp(packageName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking app block status", e)
            }
        }
    }

    private fun blockApp(packageName: String) {
        val now = System.currentTimeMillis()

        // Thread-safe check and update of cooldown state
        synchronized(blockLock) {
            // Prevent rapid repeated blocks of the same app
            if (packageName == lastBlockedPackage && now - lastBlockTime < blockCooldownMs) {
                return
            }
            lastBlockedPackage = packageName
            lastBlockTime = now
        }

        Log.d(TAG, "Blocking app: $packageName")

        // Show toast on main thread
        android.os.Handler(applicationContext.mainLooper).post {
            Toast.makeText(
                applicationContext,
                getString(R.string.app_blocked_toast),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Navigate to home screen
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
        serviceScope.cancel()
        isRunning = false
    }

    private fun startForegroundServiceNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags)

        val notification = NotificationCompat.Builder(this, UnbrickApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_locked_title))
            .setContentText(getString(R.string.notification_locked_text))
            .setSmallIcon(R.drawable.ic_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                UnbrickApplication.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(UnbrickApplication.NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "AppBlockerService"

        @Volatile
        var isRunning: Boolean = false
            private set

        // System packages that should never be blocked
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.android.incallui",
            "com.android.phone",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.google.android.dialer"
        )
    }
}
