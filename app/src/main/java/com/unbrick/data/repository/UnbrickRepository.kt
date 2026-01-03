package com.unbrick.data.repository

import com.unbrick.data.dao.*
import com.unbrick.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class UnbrickRepository(
    private val blockingProfileDao: BlockingProfileDao,
    private val profileAppDao: ProfileAppDao,
    private val lockStateDao: LockStateDao,
    private val nfcTagDao: NfcTagDao,
    private val appSettingsDao: AppSettingsDao
) {
    // ==================== Profile Management ====================

    val allProfiles: Flow<List<BlockingProfile>> = blockingProfileDao.getAllProfiles()
    val activeProfile: Flow<BlockingProfile?> = blockingProfileDao.getActiveProfile()

    suspend fun getActiveProfileSync(): BlockingProfile? {
        return blockingProfileDao.getActiveProfileSync()
    }

    suspend fun getProfileById(id: Long): BlockingProfile? {
        return blockingProfileDao.getProfileById(id)
    }

    suspend fun createProfile(name: String, mode: BlockingMode = BlockingMode.BLOCKLIST): Long {
        val profile = BlockingProfile(
            name = name,
            blockingMode = mode.name,
            isActive = false
        )
        return blockingProfileDao.insert(profile)
    }

    suspend fun setActiveProfile(profileId: Long) {
        blockingProfileDao.setActiveProfile(profileId)
        appSettingsDao.setActiveProfileId(profileId)
    }

    suspend fun updateProfile(profileId: Long, name: String, mode: BlockingMode) {
        blockingProfileDao.updateName(profileId, name)
        blockingProfileDao.updateBlockingMode(profileId, mode.name)
    }

    suspend fun deleteProfile(profileId: Long): Boolean {
        val count = blockingProfileDao.getProfileCount()
        if (count <= 1) return false // Cannot delete last profile

        val wasActive = blockingProfileDao.getProfileById(profileId)?.isActive ?: false
        blockingProfileDao.deleteById(profileId)

        // If deleted profile was active, activate another
        if (wasActive) {
            val remainingProfiles = blockingProfileDao.getAllProfilesSync()
            if (remainingProfiles.isNotEmpty()) {
                setActiveProfile(remainingProfiles.first().id)
            }
        }
        return true
    }

    suspend fun duplicateProfile(profileId: Long, newName: String): Long {
        val original = blockingProfileDao.getProfileById(profileId) ?: return -1
        val apps = profileAppDao.getAppsForProfileSync(profileId)

        val newProfile = BlockingProfile(
            name = newName,
            blockingMode = original.blockingMode,
            isActive = false
        )
        val newId = blockingProfileDao.insert(newProfile)

        val newApps = apps.map { it.copy(profileId = newId) }
        profileAppDao.insertAll(newApps)

        return newId
    }

    suspend fun getProfileCount(): Int {
        return blockingProfileDao.getProfileCount()
    }

    // ==================== Profile Apps ====================

    fun getAppsForProfile(profileId: Long): Flow<List<ProfileApp>> {
        return profileAppDao.getAppsForProfile(profileId)
    }

    suspend fun getAppsForProfileSync(profileId: Long): List<ProfileApp> {
        return profileAppDao.getAppsForProfileSync(profileId)
    }

    suspend fun setAppInProfile(profileId: Long, packageName: String, appName: String, blocked: Boolean) {
        if (blocked) {
            profileAppDao.insert(ProfileApp(profileId, packageName, appName, true))
        } else {
            profileAppDao.deleteByPackageName(profileId, packageName)
        }
    }

    suspend fun getAppCountForProfile(profileId: Long): Int {
        return profileAppDao.getAppCountForProfile(profileId)
    }

    // ==================== App Blocking (called by AccessibilityService) ====================

    suspend fun isAppBlocked(packageName: String): Boolean {
        val activeProfile = blockingProfileDao.getActiveProfileSync() ?: return false
        val mode = BlockingMode.valueOf(activeProfile.blockingMode)
        val isInList = profileAppDao.isAppInActiveProfile(packageName)

        return when (mode) {
            BlockingMode.BLOCKLIST -> isInList
            BlockingMode.ALLOWLIST -> !isInList
        }
    }

    // ==================== Lock State ====================

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

    // ==================== Emergency Unlock ====================

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

    // ==================== NFC Tags ====================

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

    // ==================== Settings ====================

    val settings: Flow<AppSettings?> = appSettingsDao.getSettings()

    suspend fun getSettings(): AppSettings {
        return appSettingsDao.getSettingsSync() ?: AppSettings().also {
            appSettingsDao.insert(it)
        }
    }

    suspend fun setUnlockDelay(delayMs: Long) {
        ensureSettingsExist()
        appSettingsDao.setUnlockDelay(delayMs)
    }

    suspend fun setBlockSettingsWhenLocked(block: Boolean) {
        ensureSettingsExist()
        appSettingsDao.setBlockSettingsWhenLocked(block)
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

    suspend fun ensureSettingsExist() {
        if (appSettingsDao.getSettingsSync() == null) {
            appSettingsDao.insert(AppSettings())
        }
    }

    // ==================== Initialization ====================

    suspend fun ensureLockStateExists() {
        if (lockStateDao.getLockStateSync() == null) {
            lockStateDao.insert(LockState())
        }
    }

    /**
     * Cleans up orphan profiles (profiles with no apps configured).
     * Called on app startup to remove profiles from crashed/abandoned create flows.
     * Won't delete the last remaining profile to avoid leaving the user with none.
     */
    suspend fun deleteEmptyProfiles() {
        val profiles = blockingProfileDao.getAllProfilesSync()
        if (profiles.size <= 1) return // Don't delete if only one profile exists

        for (profile in profiles) {
            val appCount = profileAppDao.getAppCountForProfile(profile.id)
            if (appCount == 0 && profiles.size > 1) {
                // Delete empty profile, but ensure we keep at least one
                val remainingCount = blockingProfileDao.getProfileCount()
                if (remainingCount > 1) {
                    blockingProfileDao.deleteById(profile.id)
                }
            }
        }
    }
}
