package com.unbrick.data.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unbrick.data.model.BlockingProfile
import com.unbrick.data.model.ProfileApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAppDaoTest : BaseDaoTest() {

    private val profileDao get() = database.blockingProfileDao()
    private val profileAppDao get() = database.profileAppDao()

    private suspend fun createActiveProfile(): Long {
        val id = profileDao.insert(BlockingProfile(name = "Test", blockingMode = "BLOCKLIST", isActive = true))
        return id
    }

    @Test
    fun insertApp_canRetrieveIt() = runTest {
        val profileId = createActiveProfile()
        val app = ProfileApp(profileId = profileId, packageName = "com.instagram", appName = "Instagram", isBlocked = true)

        profileAppDao.insert(app)

        val apps = profileAppDao.getAppsForProfileSync(profileId)
        assertEquals(1, apps.size)
        assertEquals("com.instagram", apps[0].packageName)
        assertEquals("Instagram", apps[0].appName)
    }

    @Test
    fun getAppsForProfile_flowEmitsUpdates() = runTest {
        val profileId = createActiveProfile()

        val initial = profileAppDao.getAppsForProfile(profileId).first()
        assertEquals(0, initial.size)

        profileAppDao.insert(ProfileApp(profileId, "com.instagram", "Instagram", true))

        val updated = profileAppDao.getAppsForProfile(profileId).first()
        assertEquals(1, updated.size)
    }

    @Test
    fun isAppInActiveProfile_returnsTrueWhenAppIsBlocked() = runTest {
        val profileId = createActiveProfile()
        profileAppDao.insert(ProfileApp(profileId, "com.instagram", "Instagram", isBlocked = true))

        val result = profileAppDao.isAppInActiveProfile("com.instagram")
        assertTrue(result)
    }

    @Test
    fun isAppInActiveProfile_returnsFalseWhenAppNotInProfile() = runTest {
        createActiveProfile()

        val result = profileAppDao.isAppInActiveProfile("com.instagram")
        assertFalse(result)
    }

    @Test
    fun isAppInActiveProfile_returnsFalseWhenProfileNotActive() = runTest {
        val profileId = profileDao.insert(BlockingProfile(name = "Test", blockingMode = "BLOCKLIST", isActive = false))
        profileAppDao.insert(ProfileApp(profileId, "com.instagram", "Instagram", isBlocked = true))

        val result = profileAppDao.isAppInActiveProfile("com.instagram")
        assertFalse(result)
    }

    @Test
    fun deleteByPackageName_removesApp() = runTest {
        val profileId = createActiveProfile()
        profileAppDao.insert(ProfileApp(profileId, "com.instagram", "Instagram", true))
        profileAppDao.insert(ProfileApp(profileId, "com.twitter", "Twitter", true))

        profileAppDao.deleteByPackageName(profileId, "com.instagram")

        val apps = profileAppDao.getAppsForProfileSync(profileId)
        assertEquals(1, apps.size)
        assertEquals("com.twitter", apps[0].packageName)
    }

    @Test
    fun getAppCountForProfile_returnsCorrectCount() = runTest {
        val profileId = createActiveProfile()

        assertEquals(0, profileAppDao.getAppCountForProfile(profileId))

        profileAppDao.insert(ProfileApp(profileId, "com.instagram", "Instagram", true))
        profileAppDao.insert(ProfileApp(profileId, "com.twitter", "Twitter", true))

        assertEquals(2, profileAppDao.getAppCountForProfile(profileId))
    }

    @Test
    fun insertAll_insertsMultipleApps() = runTest {
        val profileId = createActiveProfile()
        val apps = listOf(
            ProfileApp(profileId, "com.instagram", "Instagram", true),
            ProfileApp(profileId, "com.twitter", "Twitter", true),
            ProfileApp(profileId, "com.facebook", "Facebook", true)
        )

        profileAppDao.insertAll(apps)

        val retrieved = profileAppDao.getAppsForProfileSync(profileId)
        assertEquals(3, retrieved.size)
    }

    @Test
    fun deleteAllForProfile_removesAllApps() = runTest {
        val profileId = createActiveProfile()
        profileAppDao.insert(ProfileApp(profileId, "com.instagram", "Instagram", true))
        profileAppDao.insert(ProfileApp(profileId, "com.twitter", "Twitter", true))

        profileAppDao.deleteAllForProfile(profileId)

        val apps = profileAppDao.getAppsForProfileSync(profileId)
        assertEquals(0, apps.size)
    }
}
