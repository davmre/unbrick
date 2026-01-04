package com.unbrick.data.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unbrick.data.model.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsDaoTest : BaseDaoTest() {

    private val settingsDao get() = database.appSettingsDao()

    @Test
    fun insertSettings_canRetrieveThem() = runTest {
        val settings = AppSettings(
            activeProfileId = 5L,
            unlockDelayMs = 60000,
            blockSettingsWhenLocked = true,
            setupCompleted = true
        )
        settingsDao.insert(settings)

        val retrieved = settingsDao.getSettingsSync()
        assertNotNull(retrieved)
        assertEquals(5L, retrieved?.activeProfileId)
        assertEquals(60000L, retrieved?.unlockDelayMs)
        assertTrue(retrieved?.blockSettingsWhenLocked ?: false)
        assertTrue(retrieved?.setupCompleted ?: false)
    }

    @Test
    fun getSettingsSync_returnsNullWhenEmpty() = runTest {
        val settings = settingsDao.getSettingsSync()
        assertNull(settings)
    }

    @Test
    fun getSettings_flowEmitsUpdates() = runTest {
        val initial = settingsDao.getSettings().first()
        assertNull(initial)

        settingsDao.insert(AppSettings(unlockDelayMs = 1000))

        val updated = settingsDao.getSettings().first()
        assertNotNull(updated)
        assertEquals(1000L, updated?.unlockDelayMs)
    }

    @Test
    fun setActiveProfileId_updatesValue() = runTest {
        settingsDao.insert(AppSettings(activeProfileId = 1L))

        settingsDao.setActiveProfileId(42L)

        val settings = settingsDao.getSettingsSync()
        assertEquals(42L, settings?.activeProfileId)
    }

    @Test
    fun setUnlockDelay_updatesValue() = runTest {
        settingsDao.insert(AppSettings(unlockDelayMs = 1000))

        settingsDao.setUnlockDelay(120000)

        val settings = settingsDao.getSettingsSync()
        assertEquals(120000L, settings?.unlockDelayMs)
    }

    @Test
    fun setBlockSettingsWhenLocked_updatesValue() = runTest {
        settingsDao.insert(AppSettings(blockSettingsWhenLocked = false))

        settingsDao.setBlockSettingsWhenLocked(true)

        val settings = settingsDao.getSettingsSync()
        assertTrue(settings?.blockSettingsWhenLocked ?: false)
    }

    @Test
    fun setSetupCompleted_updatesValue() = runTest {
        settingsDao.insert(AppSettings(setupCompleted = false))

        settingsDao.setSetupCompleted(true)

        val settings = settingsDao.getSettingsSync()
        assertTrue(settings?.setupCompleted ?: false)
    }

    @Test
    fun update_updatesAllFields() = runTest {
        settingsDao.insert(AppSettings(
            activeProfileId = 1L,
            unlockDelayMs = 1000,
            blockSettingsWhenLocked = false,
            setupCompleted = false
        ))

        settingsDao.update(AppSettings(
            activeProfileId = 99L,
            unlockDelayMs = 999999,
            blockSettingsWhenLocked = true,
            setupCompleted = true
        ))

        val settings = settingsDao.getSettingsSync()
        assertEquals(99L, settings?.activeProfileId)
        assertEquals(999999L, settings?.unlockDelayMs)
        assertTrue(settings?.blockSettingsWhenLocked ?: false)
        assertTrue(settings?.setupCompleted ?: false)
    }

    @Test
    fun insert_replacesExisting() = runTest {
        settingsDao.insert(AppSettings(unlockDelayMs = 1000))
        settingsDao.insert(AppSettings(unlockDelayMs = 2000))

        val settings = settingsDao.getSettingsSync()
        assertEquals(2000L, settings?.unlockDelayMs)
    }
}
