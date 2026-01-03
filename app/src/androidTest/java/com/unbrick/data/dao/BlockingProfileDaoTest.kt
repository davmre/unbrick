package com.unbrick.data.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unbrick.data.model.BlockingProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockingProfileDaoTest : BaseDaoTest() {

    private val profileDao get() = database.blockingProfileDao()

    @Test
    fun insertProfile_canRetrieveIt() = runTest {
        val profile = BlockingProfile(name = "Work", blockingMode = "BLOCKLIST")
        val id = profileDao.insert(profile)

        val retrieved = profileDao.getProfileById(id)
        assertNotNull(retrieved)
        assertEquals("Work", retrieved?.name)
        assertEquals("BLOCKLIST", retrieved?.blockingMode)
    }

    @Test
    fun getAllProfilesSync_returnsAllProfiles() = runTest {
        profileDao.insert(BlockingProfile(name = "Work", blockingMode = "BLOCKLIST"))
        profileDao.insert(BlockingProfile(name = "Focus", blockingMode = "ALLOWLIST"))

        val profiles = profileDao.getAllProfilesSync()
        assertEquals(2, profiles.size)
    }

    @Test
    fun getAllProfiles_flowEmitsUpdates() = runTest {
        val initial = profileDao.getAllProfiles().first()
        assertEquals(0, initial.size)

        profileDao.insert(BlockingProfile(name = "Work", blockingMode = "BLOCKLIST"))

        val updated = profileDao.getAllProfiles().first()
        assertEquals(1, updated.size)
    }

    @Test
    fun setActiveProfile_deactivatesOthers() = runTest {
        val id1 = profileDao.insert(BlockingProfile(name = "Work", blockingMode = "BLOCKLIST", isActive = true))
        val id2 = profileDao.insert(BlockingProfile(name = "Focus", blockingMode = "ALLOWLIST", isActive = false))

        profileDao.setActiveProfile(id2)

        val profile1 = profileDao.getProfileById(id1)
        val profile2 = profileDao.getProfileById(id2)
        assertFalse(profile1?.isActive ?: true)
        assertTrue(profile2?.isActive ?: false)
    }

    @Test
    fun getActiveProfile_returnsActiveProfile() = runTest {
        profileDao.insert(BlockingProfile(name = "Work", blockingMode = "BLOCKLIST", isActive = false))
        profileDao.insert(BlockingProfile(name = "Focus", blockingMode = "ALLOWLIST", isActive = true))

        val active = profileDao.getActiveProfileSync()
        assertNotNull(active)
        assertEquals("Focus", active?.name)
    }

    @Test
    fun getActiveProfile_returnsNullWhenNoneActive() = runTest {
        profileDao.insert(BlockingProfile(name = "Work", blockingMode = "BLOCKLIST", isActive = false))

        val active = profileDao.getActiveProfileSync()
        assertNull(active)
    }

    @Test
    fun deleteById_removesProfile() = runTest {
        val id = profileDao.insert(BlockingProfile(name = "Work", blockingMode = "BLOCKLIST"))

        profileDao.deleteById(id)

        val retrieved = profileDao.getProfileById(id)
        assertNull(retrieved)
    }

    @Test
    fun getProfileCount_returnsCorrectCount() = runTest {
        assertEquals(0, profileDao.getProfileCount())

        profileDao.insert(BlockingProfile(name = "Work", blockingMode = "BLOCKLIST"))
        profileDao.insert(BlockingProfile(name = "Focus", blockingMode = "ALLOWLIST"))

        assertEquals(2, profileDao.getProfileCount())
    }

    @Test
    fun updateName_updatesOnlyName() = runTest {
        val id = profileDao.insert(BlockingProfile(name = "Work", blockingMode = "BLOCKLIST"))

        profileDao.updateName(id, "Updated Name")

        val profile = profileDao.getProfileById(id)
        assertEquals("Updated Name", profile?.name)
        assertEquals("BLOCKLIST", profile?.blockingMode)
    }

    @Test
    fun updateBlockingMode_updatesOnlyMode() = runTest {
        val id = profileDao.insert(BlockingProfile(name = "Work", blockingMode = "BLOCKLIST"))

        profileDao.updateBlockingMode(id, "ALLOWLIST")

        val profile = profileDao.getProfileById(id)
        assertEquals("Work", profile?.name)
        assertEquals("ALLOWLIST", profile?.blockingMode)
    }
}
