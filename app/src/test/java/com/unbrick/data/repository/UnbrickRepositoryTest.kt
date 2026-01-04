package com.unbrick.data.repository

import androidx.room.withTransaction
import com.unbrick.data.AppDatabase
import com.unbrick.data.dao.*
import com.unbrick.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for UnbrickRepository.
 *
 * Note: Tests for methods that use database transactions (deleteProfile, deleteEmptyProfiles,
 * shouldBlockApp, shouldBlockSettingsApp) require a real Room database and are covered
 * in instrumented tests (androidTest) rather than JVM unit tests.
 */
class UnbrickRepositoryTest {

    private lateinit var repository: UnbrickRepository
    private lateinit var mockDatabase: AppDatabase
    private lateinit var mockProfileDao: BlockingProfileDao
    private lateinit var mockProfileAppDao: ProfileAppDao
    private lateinit var mockLockStateDao: LockStateDao
    private lateinit var mockNfcTagDao: NfcTagDao
    private lateinit var mockAppSettingsDao: AppSettingsDao

    @Before
    fun setUp() {
        mockDatabase = mock()
        mockProfileDao = mock()
        mockProfileAppDao = mock()
        mockLockStateDao = mock()
        mockNfcTagDao = mock()
        mockAppSettingsDao = mock()

        repository = UnbrickRepository(
            mockDatabase,
            mockProfileDao,
            mockProfileAppDao,
            mockLockStateDao,
            mockNfcTagDao,
            mockAppSettingsDao
        )
    }

    // ==================== isAppBlocked tests ====================

    @Test
    fun `isAppBlocked returns false when no active profile`() = runTest {
        whenever(mockProfileDao.getActiveProfileSync()).thenReturn(null)

        val result = repository.isAppBlocked("com.instagram")
        assertFalse(result)
    }

    @Test
    fun `isAppBlocked in BLOCKLIST mode returns true when app is in list`() = runTest {
        val profile = BlockingProfile(id = 1, name = "Test", blockingMode = "BLOCKLIST", isActive = true)
        whenever(mockProfileDao.getActiveProfileSync()).thenReturn(profile)
        whenever(mockProfileAppDao.isAppInActiveProfile("com.instagram")).thenReturn(true)

        val result = repository.isAppBlocked("com.instagram")
        assertTrue(result)
    }

    @Test
    fun `isAppBlocked in BLOCKLIST mode returns false when app is not in list`() = runTest {
        val profile = BlockingProfile(id = 1, name = "Test", blockingMode = "BLOCKLIST", isActive = true)
        whenever(mockProfileDao.getActiveProfileSync()).thenReturn(profile)
        whenever(mockProfileAppDao.isAppInActiveProfile("com.instagram")).thenReturn(false)

        val result = repository.isAppBlocked("com.instagram")
        assertFalse(result)
    }

    @Test
    fun `isAppBlocked in ALLOWLIST mode returns true when app is NOT in list`() = runTest {
        val profile = BlockingProfile(id = 1, name = "Test", blockingMode = "ALLOWLIST", isActive = true)
        whenever(mockProfileDao.getActiveProfileSync()).thenReturn(profile)
        whenever(mockProfileAppDao.isAppInActiveProfile("com.instagram")).thenReturn(false)

        val result = repository.isAppBlocked("com.instagram")
        assertTrue(result)
    }

    @Test
    fun `isAppBlocked in ALLOWLIST mode returns false when app is in list`() = runTest {
        val profile = BlockingProfile(id = 1, name = "Test", blockingMode = "ALLOWLIST", isActive = true)
        whenever(mockProfileDao.getActiveProfileSync()).thenReturn(profile)
        whenever(mockProfileAppDao.isAppInActiveProfile("com.instagram")).thenReturn(true)

        val result = repository.isAppBlocked("com.instagram")
        assertFalse(result)
    }

    // ==================== isLocked tests ====================

    @Test
    fun `isLocked returns false when lock state is null`() = runTest {
        whenever(mockLockStateDao.isLocked()).thenReturn(null)

        val result = repository.isLocked()
        assertFalse(result)
    }

    @Test
    fun `isLocked returns true when locked`() = runTest {
        whenever(mockLockStateDao.isLocked()).thenReturn(true)

        val result = repository.isLocked()
        assertTrue(result)
    }

    @Test
    fun `isLocked returns false when unlocked`() = runTest {
        whenever(mockLockStateDao.isLocked()).thenReturn(false)

        val result = repository.isLocked()
        assertFalse(result)
    }

    // ==================== toggleLock tests ====================

    @Test
    fun `toggleLock from unlocked to locked`() = runTest {
        val currentState = LockState(isLocked = false)
        whenever(mockLockStateDao.getLockStateSync()).thenReturn(currentState)

        val result = repository.toggleLock()

        assertTrue(result)
        verify(mockLockStateDao).setLocked(eq(true), any())
    }

    @Test
    fun `toggleLock from locked to unlocked`() = runTest {
        val currentState = LockState(isLocked = true)
        whenever(mockLockStateDao.getLockStateSync()).thenReturn(currentState)

        val result = repository.toggleLock()

        assertFalse(result)
        verify(mockLockStateDao).setLocked(eq(false), isNull())
    }

    @Test
    fun `toggleLock clears emergency unlock when unlocking`() = runTest {
        val currentState = LockState(isLocked = true, emergencyUnlockRequestedAt = 123456L)
        whenever(mockLockStateDao.getLockStateSync()).thenReturn(currentState)

        repository.toggleLock()

        verify(mockLockStateDao).setEmergencyUnlockRequested(null)
    }

    // ==================== shouldBlockSettings tests ====================

    @Test
    fun `shouldBlockSettings returns false when settings is null`() = runTest {
        whenever(mockAppSettingsDao.getSettingsSync()).thenReturn(null)

        val result = repository.shouldBlockSettings()
        assertFalse(result)
    }

    @Test
    fun `shouldBlockSettings returns false when not locked`() = runTest {
        whenever(mockAppSettingsDao.getSettingsSync()).thenReturn(AppSettings(blockSettingsWhenLocked = true))
        whenever(mockLockStateDao.isLocked()).thenReturn(false)

        val result = repository.shouldBlockSettings()
        assertFalse(result)
    }

    @Test
    fun `shouldBlockSettings returns false when locked but setting disabled`() = runTest {
        whenever(mockAppSettingsDao.getSettingsSync()).thenReturn(AppSettings(blockSettingsWhenLocked = false))
        whenever(mockLockStateDao.isLocked()).thenReturn(true)

        val result = repository.shouldBlockSettings()
        assertFalse(result)
    }

    @Test
    fun `shouldBlockSettings returns true when locked and setting enabled`() = runTest {
        whenever(mockAppSettingsDao.getSettingsSync()).thenReturn(AppSettings(blockSettingsWhenLocked = true))
        whenever(mockLockStateDao.isLocked()).thenReturn(true)

        val result = repository.shouldBlockSettings()
        assertTrue(result)
    }

    // ==================== Profile management tests ====================
    // Note: deleteProfile tests require real Room database due to withTransaction usage.
    // These are covered in instrumented tests (androidTest/RepositoryIntegrationTest).
}
