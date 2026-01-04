package com.unbrick.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unbrick.data.AppDatabase
import com.unbrick.data.model.AppSettings
import com.unbrick.data.model.BlockingMode
import com.unbrick.data.model.BlockingProfile
import com.unbrick.data.model.LockState
import com.unbrick.data.model.ProfileApp
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for UnbrickRepository methods that use database transactions.
 * These tests require a real Room database (not mocks) because withTransaction
 * cannot be mocked.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryIntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: UnbrickRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        repository = UnbrickRepository(
            database,
            database.blockingProfileDao(),
            database.profileAppDao(),
            database.lockStateDao(),
            database.nfcTagDao(),
            database.appSettingsDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ==================== shouldBlockApp tests ====================

    @Test
    fun shouldBlockApp_returnsFalseWhenNotLocked() = runTest {
        // Setup: Create profile with blocked app, but don't lock
        val profileId = repository.createProfile("Test", BlockingMode.BLOCKLIST)
        repository.setActiveProfile(profileId)
        repository.setAppInProfile(profileId, "com.blocked.app", "Blocked App", true)
        repository.ensureLockStateExists()
        repository.setLocked(false)

        val result = repository.shouldBlockApp("com.blocked.app")

        assertFalse(result)
    }

    @Test
    fun shouldBlockApp_returnsTrueWhenLockedAndAppBlocked() = runTest {
        // Setup: Create profile with blocked app and lock
        val profileId = repository.createProfile("Test", BlockingMode.BLOCKLIST)
        repository.setActiveProfile(profileId)
        repository.setAppInProfile(profileId, "com.blocked.app", "Blocked App", true)
        repository.ensureLockStateExists()
        repository.setLocked(true)

        val result = repository.shouldBlockApp("com.blocked.app")

        assertTrue(result)
    }

    @Test
    fun shouldBlockApp_returnsFalseWhenLockedButAppNotBlocked() = runTest {
        // Setup: Create profile without the app, and lock
        val profileId = repository.createProfile("Test", BlockingMode.BLOCKLIST)
        repository.setActiveProfile(profileId)
        repository.ensureLockStateExists()
        repository.setLocked(true)

        val result = repository.shouldBlockApp("com.other.app")

        assertFalse(result)
    }

    @Test
    fun shouldBlockApp_allowlistMode_returnsTrueWhenLockedAndAppNotInList() = runTest {
        // Setup: Allowlist mode - apps NOT in list are blocked
        val profileId = repository.createProfile("Test", BlockingMode.ALLOWLIST)
        repository.setActiveProfile(profileId)
        repository.setAppInProfile(profileId, "com.allowed.app", "Allowed App", true)
        repository.ensureLockStateExists()
        repository.setLocked(true)

        val result = repository.shouldBlockApp("com.other.app")

        assertTrue(result)
    }

    @Test
    fun shouldBlockApp_allowlistMode_returnsFalseWhenLockedAndAppInList() = runTest {
        // Setup: Allowlist mode - apps IN list are allowed
        val profileId = repository.createProfile("Test", BlockingMode.ALLOWLIST)
        repository.setActiveProfile(profileId)
        repository.setAppInProfile(profileId, "com.allowed.app", "Allowed App", true)
        repository.ensureLockStateExists()
        repository.setLocked(true)

        val result = repository.shouldBlockApp("com.allowed.app")

        assertFalse(result)
    }

    // ==================== shouldBlockSettingsApp tests ====================

    @Test
    fun shouldBlockSettingsApp_returnsFalseWhenNotLocked() = runTest {
        repository.ensureLockStateExists()
        repository.ensureSettingsExist()
        repository.setBlockSettingsWhenLocked(true)
        repository.setLocked(false)

        val result = repository.shouldBlockSettingsApp()

        assertFalse(result)
    }

    @Test
    fun shouldBlockSettingsApp_returnsFalseWhenLockedButSettingDisabled() = runTest {
        repository.ensureLockStateExists()
        repository.ensureSettingsExist()
        repository.setBlockSettingsWhenLocked(false)
        repository.setLocked(true)

        val result = repository.shouldBlockSettingsApp()

        assertFalse(result)
    }

    @Test
    fun shouldBlockSettingsApp_returnsTrueWhenLockedAndSettingEnabled() = runTest {
        repository.ensureLockStateExists()
        repository.ensureSettingsExist()
        repository.setBlockSettingsWhenLocked(true)
        repository.setLocked(true)

        val result = repository.shouldBlockSettingsApp()

        assertTrue(result)
    }

    // ==================== deleteProfile tests ====================

    @Test
    fun deleteProfile_returnsFalseWhenOnlyOneProfile() = runTest {
        val profileId = repository.createProfile("Only Profile", BlockingMode.BLOCKLIST)

        val result = repository.deleteProfile(profileId)

        assertFalse(result)
        assertEquals(1, repository.getProfileCount())
    }

    @Test
    fun deleteProfile_returnsTrueAndDeletesWhenMultipleProfiles() = runTest {
        val profile1Id = repository.createProfile("Profile 1", BlockingMode.BLOCKLIST)
        val profile2Id = repository.createProfile("Profile 2", BlockingMode.BLOCKLIST)

        val result = repository.deleteProfile(profile1Id)

        assertTrue(result)
        assertEquals(1, repository.getProfileCount())
        assertNull(repository.getProfileById(profile1Id))
        assertNotNull(repository.getProfileById(profile2Id))
    }

    @Test
    fun deleteProfile_activatesAnotherWhenActiveDeleted() = runTest {
        val profile1Id = repository.createProfile("Profile 1", BlockingMode.BLOCKLIST)
        val profile2Id = repository.createProfile("Profile 2", BlockingMode.BLOCKLIST)
        repository.setActiveProfile(profile1Id)

        repository.deleteProfile(profile1Id)

        val activeProfile = repository.getActiveProfileSync()
        assertNotNull(activeProfile)
        assertEquals(profile2Id, activeProfile?.id)
    }

    @Test
    fun deleteProfile_doesNotChangeActiveWhenInactiveDeleted() = runTest {
        val profile1Id = repository.createProfile("Profile 1", BlockingMode.BLOCKLIST)
        val profile2Id = repository.createProfile("Profile 2", BlockingMode.BLOCKLIST)
        repository.setActiveProfile(profile1Id)

        repository.deleteProfile(profile2Id)

        val activeProfile = repository.getActiveProfileSync()
        assertEquals(profile1Id, activeProfile?.id)
    }

    // ==================== duplicateProfile tests ====================

    @Test
    fun duplicateProfile_copiesProfileWithNewName() = runTest {
        val originalId = repository.createProfile("Original", BlockingMode.ALLOWLIST)

        val duplicateId = repository.duplicateProfile(originalId, "Copy")

        assertTrue(duplicateId > 0)
        val duplicate = repository.getProfileById(duplicateId)
        assertNotNull(duplicate)
        assertEquals("Copy", duplicate?.name)
        assertEquals(BlockingMode.ALLOWLIST.name, duplicate?.blockingMode)
    }

    @Test
    fun duplicateProfile_copiesApps() = runTest {
        val originalId = repository.createProfile("Original", BlockingMode.BLOCKLIST)
        repository.setAppInProfile(originalId, "com.app1", "App 1", true)
        repository.setAppInProfile(originalId, "com.app2", "App 2", true)

        val duplicateId = repository.duplicateProfile(originalId, "Copy")

        val duplicateApps = repository.getAppsForProfileSync(duplicateId)
        assertEquals(2, duplicateApps.size)
        assertTrue(duplicateApps.any { it.packageName == "com.app1" })
        assertTrue(duplicateApps.any { it.packageName == "com.app2" })
    }

    @Test
    fun duplicateProfile_returnsNegativeOneForNonexistentProfile() = runTest {
        val result = repository.duplicateProfile(999L, "Copy")

        assertEquals(-1L, result)
    }

    @Test
    fun duplicateProfile_newProfileIsNotActive() = runTest {
        val originalId = repository.createProfile("Original", BlockingMode.BLOCKLIST)
        repository.setActiveProfile(originalId)

        val duplicateId = repository.duplicateProfile(originalId, "Copy")

        val duplicate = repository.getProfileById(duplicateId)
        assertFalse(duplicate?.isActive ?: true)
    }

    // ==================== deleteEmptyProfiles tests ====================

    @Test
    fun deleteEmptyProfiles_deletesProfilesWithNoApps() = runTest {
        val profile1Id = repository.createProfile("Has Apps", BlockingMode.BLOCKLIST)
        repository.setAppInProfile(profile1Id, "com.app", "App", true)
        val profile2Id = repository.createProfile("Empty", BlockingMode.BLOCKLIST)

        repository.deleteEmptyProfiles()

        assertEquals(1, repository.getProfileCount())
        assertNotNull(repository.getProfileById(profile1Id))
        assertNull(repository.getProfileById(profile2Id))
    }

    @Test
    fun deleteEmptyProfiles_keepsLastProfile() = runTest {
        repository.createProfile("Empty", BlockingMode.BLOCKLIST)

        repository.deleteEmptyProfiles()

        assertEquals(1, repository.getProfileCount())
    }

    @Test
    fun deleteEmptyProfiles_keepsProfilesWithApps() = runTest {
        val profile1Id = repository.createProfile("Has Apps 1", BlockingMode.BLOCKLIST)
        repository.setAppInProfile(profile1Id, "com.app1", "App 1", true)
        val profile2Id = repository.createProfile("Has Apps 2", BlockingMode.BLOCKLIST)
        repository.setAppInProfile(profile2Id, "com.app2", "App 2", true)

        repository.deleteEmptyProfiles()

        assertEquals(2, repository.getProfileCount())
    }

    // ==================== setAppInProfile tests ====================

    @Test
    fun setAppInProfile_addsAppWhenBlockedTrue() = runTest {
        val profileId = repository.createProfile("Test", BlockingMode.BLOCKLIST)

        repository.setAppInProfile(profileId, "com.test.app", "Test App", true)

        val apps = repository.getAppsForProfileSync(profileId)
        assertEquals(1, apps.size)
        assertEquals("com.test.app", apps[0].packageName)
    }

    @Test
    fun setAppInProfile_removesAppWhenBlockedFalse() = runTest {
        val profileId = repository.createProfile("Test", BlockingMode.BLOCKLIST)
        repository.setAppInProfile(profileId, "com.test.app", "Test App", true)

        repository.setAppInProfile(profileId, "com.test.app", "Test App", false)

        val apps = repository.getAppsForProfileSync(profileId)
        assertEquals(0, apps.size)
    }

    // ==================== Emergency unlock tests ====================

    @Test
    fun emergencyUnlock_fullFlow() = runTest {
        repository.ensureLockStateExists()
        repository.ensureSettingsExist()
        repository.setUnlockDelay(100) // 100ms for testing
        repository.setLocked(true)

        // Initially not available
        assertFalse(repository.isEmergencyUnlockAvailable())
        assertFalse(repository.performEmergencyUnlock())

        // Request emergency unlock
        repository.requestEmergencyUnlock()

        // Still not available immediately
        assertFalse(repository.isEmergencyUnlockAvailable())

        // Wait for delay
        Thread.sleep(150)

        // Now available
        assertTrue(repository.isEmergencyUnlockAvailable())
        assertTrue(repository.performEmergencyUnlock())

        // Should be unlocked
        assertFalse(repository.isLocked())
    }

    @Test
    fun cancelEmergencyUnlock_clearsRequest() = runTest {
        repository.ensureLockStateExists()
        repository.ensureSettingsExist()
        repository.setUnlockDelay(100)
        repository.setLocked(true)

        repository.requestEmergencyUnlock()
        repository.cancelEmergencyUnlock()

        Thread.sleep(150)

        // Should not be available after cancel
        assertFalse(repository.isEmergencyUnlockAvailable())
    }
}
