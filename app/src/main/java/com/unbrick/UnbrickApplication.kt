package com.unbrick

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.unbrick.data.AppDatabase
import com.unbrick.data.repository.UnbrickRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UnbrickApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getDatabase(this) }

    val repository by lazy {
        UnbrickRepository(
            database,
            database.blockingProfileDao(),
            database.profileAppDao(),
            database.lockStateDao(),
            database.nfcTagDao(),
            database.appSettingsDao()
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()

        // Ensure default states exist and clean up orphans
        applicationScope.launch {
            repository.ensureLockStateExists()
            repository.ensureSettingsExist()
            repository.deleteEmptyProfiles()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "unbrick_status"
        const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: UnbrickApplication? = null

        fun getInstance(): UnbrickApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
