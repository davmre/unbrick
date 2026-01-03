package com.unbrick.data.repository

import com.unbrick.data.dao.*
import com.unbrick.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class UnbrickRepository(
    private val blockedAppDao: BlockedAppDao,
    private val lockStateDao: LockStateDao,
    private val nfcTagDao: NfcTagDao,
    private val appSettingsDao: AppSettingsDao
) {
    // Lock State
    val lockState: Flow<LockState?> = lockStateDao.getLockState()

    suspend fun isLocked(): Boolean {
        return lockStateDao.isLocked() ?: false
    }

    suspend fun toggleLock(): Boolean {
        val currentState = lockStateDao.getLockStateSync()
        val newLockedState = !(currentState?.isLocked ?: false)

        if (currentState == null) {
            lockStateDao.insert(LockState(isLocked = newLockedState, lockedAt = if (newLockedState) System.currentTimeMillis() else null))
        } else {
            lockStateDao.setLocked(newLockedState, if (newLockedState) System.currentTimeMillis() else null)
            // Clear emergency unlock request when manually unlocking
            if (!newLockedState) {
                lockStateDao.setEmergencyUnlockRequested(null)
            }
        }
        return newLockedState
    }

    suspend fun setLocked(locked: Boolean) {
        val currentState = lockStateDao.getLockStateSync()
        if (currentState == null) {
            lockStateDao.insert(LockState(isLocked = locked, lockedAt = if (locked) System.currentTimeMillis() else null))
        } else {
            lockStateDao.setLocked(locked, if (locked) System.currentTimeMillis() else null)
        }
    }

    // Emergency Unlock
    suspend fun requestEmergencyUnlock() {
        lockStateDao.setEmergencyUnlockRequested(System.currentTimeMillis())
    }

    suspend fun cancelEmergencyUnlock() {
        lockStateDao.setEmergencyUnlockRequested(null)
    }

    suspend fun isEmergencyUnlockAvailable(): Boolean {
        val state = lockStateDao.getLockStateSync() ?: return false
        val settings = appSettingsDao.getSettingsSync() ?: return false
        val requestedAt = state.emergencyUnlockRequestedAt ?: return false

        val elapsed = System.currentTimeMillis() - requestedAt
        return elapsed >= settings.unlockDelayMs
    }

    suspend fun getEmergencyUnlockTimeRemaining(): Long? {
        val state = lockStateDao.getLockStateSync() ?: return null
        val settings = appSettingsDao.getSettingsSync() ?: return null
        val requestedAt = state.emergencyUnlockRequestedAt ?: return null

        val elapsed = System.currentTimeMillis() - requestedAt
        val remaining = settings.unlockDelayMs - elapsed
        return if (remaining > 0) remaining else 0
    }

    suspend fun performEmergencyUnlock(): Boolean {
        if (isEmergencyUnlockAvailable()) {
            setLocked(false)
            lockStateDao.setEmergencyUnlockRequested(null)
            return true
        }
        return false
    }

    // Blocked Apps
    val blockedApps: Flow<List<BlockedApp>> = blockedAppDao.getAllBlockedApps()

    suspend fun isAppBlocked(packageName: String): Boolean {
        val settings = appSettingsDao.getSettingsSync()
        val mode = settings?.blockingMode?.let { BlockingMode.valueOf(it) } ?: BlockingMode.BLOCKLIST

        val isInList = blockedAppDao.isAppBlocked(packageName)

        return when (mode) {
            BlockingMode.BLOCKLIST -> isInList
            BlockingMode.ALLOWLIST -> !isInList
        }
    }

    suspend fun setAppBlocked(packageName: String, appName: String, blocked: Boolean) {
        if (blocked) {
            blockedAppDao.insert(BlockedApp(packageName, appName, true))
        } else {
            blockedAppDao.deleteByPackageName(packageName)
        }
    }

    suspend fun updateBlockedApps(apps: List<BlockedApp>) {
        blockedAppDao.deleteAll()
        blockedAppDao.insertAll(apps)
    }

    // NFC Tags
    val registeredTags: Flow<List<NfcTagInfo>> = nfcTagDao.getAllTags()

    suspend fun isTagRegistered(tagId: String): Boolean {
        return nfcTagDao.isTagRegistered(tagId)
    }

    suspend fun registerTag(tagId: String, name: String = "Primary Tag") {
        nfcTagDao.insert(NfcTagInfo(tagId, name = name))
    }

    suspend fun hasRegisteredTag(): Boolean {
        return nfcTagDao.getTagCount() > 0
    }

    suspend fun getPrimaryTag(): NfcTagInfo? {
        return nfcTagDao.getPrimaryTag()
    }

    suspend fun clearTags() {
        nfcTagDao.deleteAll()
    }

    // Settings
    val settings: Flow<AppSettings?> = appSettingsDao.getSettings()

    suspend fun getSettings(): AppSettings {
        return appSettingsDao.getSettingsSync() ?: AppSettings().also {
            appSettingsDao.insert(it)
        }
    }

    suspend fun setBlockingMode(mode: BlockingMode) {
        ensureSettingsExist()
        appSettingsDao.setBlockingMode(mode.name)
    }

    suspend fun setUnlockDelay(delayMs: Long) {
        ensureSettingsExist()
        appSettingsDao.setUnlockDelay(delayMs)
    }

    suspend fun setSetupCompleted(completed: Boolean) {
        ensureSettingsExist()
        appSettingsDao.setSetupCompleted(completed)
    }

    suspend fun isSetupCompleted(): Boolean {
        return appSettingsDao.getSettingsSync()?.setupCompleted ?: false
    }

    suspend fun shouldBlockSettings(): Boolean {
        val settings = appSettingsDao.getSettingsSync() ?: return false
        val isLocked = lockStateDao.isLocked() ?: false
        return isLocked && settings.blockSettingsWhenLocked
    }

    private suspend fun ensureSettingsExist() {
        if (appSettingsDao.getSettingsSync() == null) {
            appSettingsDao.insert(AppSettings())
        }
    }

    // Initialize default lock state if not exists
    suspend fun ensureLockStateExists() {
        if (lockStateDao.getLockStateSync() == null) {
            lockStateDao.insert(LockState())
        }
    }
}
