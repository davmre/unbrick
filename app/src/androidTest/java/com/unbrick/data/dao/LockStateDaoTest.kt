package com.unbrick.data.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unbrick.data.model.LockState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockStateDaoTest : BaseDaoTest() {

    private val lockStateDao get() = database.lockStateDao()

    @Test
    fun insertLockState_canRetrieveIt() = runTest {
        val state = LockState(isLocked = true, lockedAt = System.currentTimeMillis())
        lockStateDao.insert(state)

        val retrieved = lockStateDao.getLockStateSync()
        assertNotNull(retrieved)
        assertTrue(retrieved?.isLocked ?: false)
    }

    @Test
    fun getLockState_flowEmitsUpdates() = runTest {
        lockStateDao.insert(LockState(isLocked = false))

        val initial = lockStateDao.getLockState().first()
        assertFalse(initial?.isLocked ?: true)

        lockStateDao.setLocked(true, System.currentTimeMillis())

        val updated = lockStateDao.getLockState().first()
        assertTrue(updated?.isLocked ?: false)
    }

    @Test
    fun isLocked_returnsTrueWhenLocked() = runTest {
        lockStateDao.insert(LockState(isLocked = true))

        val result = lockStateDao.isLocked()
        assertTrue(result ?: false)
    }

    @Test
    fun isLocked_returnsFalseWhenUnlocked() = runTest {
        lockStateDao.insert(LockState(isLocked = false))

        val result = lockStateDao.isLocked()
        assertFalse(result ?: true)
    }

    @Test
    fun isLocked_returnsNullWhenNoState() = runTest {
        val result = lockStateDao.isLocked()
        assertNull(result)
    }

    @Test
    fun setLocked_updatesLockState() = runTest {
        lockStateDao.insert(LockState(isLocked = false))

        val lockedAt = System.currentTimeMillis()
        lockStateDao.setLocked(true, lockedAt)

        val state = lockStateDao.getLockStateSync()
        assertTrue(state?.isLocked ?: false)
        assertEquals(lockedAt, state?.lockedAt)
    }

    @Test
    fun setEmergencyUnlockRequested_updatesTimestamp() = runTest {
        lockStateDao.insert(LockState(isLocked = true))

        val requestedAt = System.currentTimeMillis()
        lockStateDao.setEmergencyUnlockRequested(requestedAt)

        val state = lockStateDao.getLockStateSync()
        assertEquals(requestedAt, state?.emergencyUnlockRequestedAt)
    }

    @Test
    fun setEmergencyUnlockRequested_clearWithNull() = runTest {
        val requestedAt = System.currentTimeMillis()
        lockStateDao.insert(LockState(isLocked = true, emergencyUnlockRequestedAt = requestedAt))

        lockStateDao.setEmergencyUnlockRequested(null)

        val state = lockStateDao.getLockStateSync()
        assertNull(state?.emergencyUnlockRequestedAt)
    }
}
