package com.unbrick.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.unbrick.data.model.BlockingMode
import com.unbrick.e2e.util.TestHelpers
import com.unbrick.e2e.util.TestApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E tests for app blocking functionality.
 *
 * These tests verify the core blocking flow:
 * - Blocked apps redirect to home when locked
 * - Apps open normally when unlocked
 * - Blocklist and allowlist modes work correctly
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AppBlockingE2ETest : BaseE2ETest() {

    private lateinit var testApp: TestApp

    @Before
    override fun setUp() {
        super.setUp()
        testApp = TestHelpers.findTestApp(context)
    }

    /**
     * Test 1: Blocked app redirects to home when device is locked.
     *
     * Flow:
     * 1. Create profile with app in blocklist
     * 2. Lock the device
     * 3. Launch blocked app
     * 4. Verify we end up on home screen
     */
    @Test
    fun blockedAppRedirectsToHomeWhenLocked() {
        // Setup: Create profile and add test app to blocklist
        runBlocking {
            repository.ensureLockStateExists()
            val profileId = repository.createProfile("E2E Test Blocklist", BlockingMode.BLOCKLIST)
            repository.setActiveProfile(profileId)
            repository.setAppInProfile(profileId, testApp.packageName, testApp.displayName, true)
            repository.setLocked(true)
        }

        // Wait for accessibility service to sync state
        waitForServiceSync()

        // Launch blocked app
        launchExternalApp(testApp.packageName)

        // Wait for blocking to occur (polling-based)
        val wasBlocked = waitForAppBlocked(timeout = 5000L)

        // Verify: should be on home screen (blocked)
        assertTrue(
            "Expected home screen after launching blocked app '${testApp.displayName}', " +
                    "but was on package: ${getCurrentPackage()}",
            wasBlocked
        )
    }

    /**
     * Test 2: App opens normally when device is unlocked.
     *
     * Flow:
     * 1. Create profile with app in blocklist
     * 2. Keep device unlocked
     * 3. Launch the app
     * 4. Verify app stays open
     */
    @Test
    fun appOpensNormallyWhenUnlocked() {
        // Setup: Create profile but stay unlocked
        runBlocking {
            repository.ensureLockStateExists()
            val profileId = repository.createProfile("E2E Test Unlocked", BlockingMode.BLOCKLIST)
            repository.setActiveProfile(profileId)
            repository.setAppInProfile(profileId, testApp.packageName, testApp.displayName, true)
            repository.setLocked(false) // Explicitly unlocked
        }

        // Wait for accessibility service to sync state
        waitForServiceSync()

        // Launch the app
        launchExternalApp(testApp.packageName)

        // Wait for app to open
        waitForPackage(testApp.packageName)

        // Confirm app stays open for a while (not blocked)
        val stayedOpen = confirmAppStaysOpen(testApp.packageName, duration = 2000L)

        // Verify: app should stay open
        assertTrue(
            "Expected ${testApp.packageName} to stay open when unlocked, " +
                    "but was on: ${getCurrentPackage()}",
            stayedOpen
        )
    }

    /**
     * Test 3: Allowlist mode blocks apps NOT in the list.
     *
     * Flow:
     * 1. Create allowlist profile WITHOUT test app
     * 2. Lock the device
     * 3. Launch app (not in allowlist)
     * 4. Verify redirect to home
     */
    @Test
    fun allowlistModeBlocksOtherApps() {
        // Setup: Create allowlist profile WITHOUT the test app
        runBlocking {
            repository.ensureLockStateExists()
            val profileId = repository.createProfile("E2E Test Allowlist Block", BlockingMode.ALLOWLIST)
            repository.setActiveProfile(profileId)
            // Don't add testApp to allowlist, so it should be blocked
            repository.setLocked(true)
        }

        // Wait for accessibility service to sync state
        waitForServiceSync()

        // Launch app that's NOT in allowlist
        launchExternalApp(testApp.packageName)

        // Wait for blocking to occur (polling-based)
        val wasBlocked = waitForAppBlocked(timeout = 5000L)

        // Verify: should be blocked (redirected to home)
        assertTrue(
            "App not in allowlist should be blocked. Expected home screen, " +
                    "but was on: ${getCurrentPackage()}",
            wasBlocked
        )
    }

    /**
     * Test 4: Allowlist mode allows apps IN the list.
     *
     * Flow:
     * 1. Create allowlist profile WITH test app
     * 2. Lock the device
     * 3. Launch app (in allowlist)
     * 4. Verify app stays open
     */
    @Test
    fun allowlistModeAllowsSelectedApps() {
        // Setup: Create allowlist WITH the test app
        runBlocking {
            repository.ensureLockStateExists()
            val profileId = repository.createProfile("E2E Test Allowlist Allow", BlockingMode.ALLOWLIST)
            repository.setActiveProfile(profileId)
            repository.setAppInProfile(profileId, testApp.packageName, testApp.displayName, true)
            repository.setLocked(true)
        }

        // Wait for accessibility service to sync state
        waitForServiceSync()

        // Launch allowed app
        launchExternalApp(testApp.packageName)

        // Wait for app to open
        waitForPackage(testApp.packageName)

        // Confirm app stays open for a while (not blocked)
        val stayedOpen = confirmAppStaysOpen(testApp.packageName, duration = 2000L)

        // Verify: app should stay open (allowed)
        assertTrue(
            "App in allowlist should be allowed. Expected ${testApp.packageName}, " +
                    "but was on: ${getCurrentPackage()}",
            stayedOpen
        )
    }

    /**
     * Test 5: Full lock/unlock cycle works correctly.
     *
     * Flow:
     * 1. Setup profile with blocked app
     * 2. Lock -> verify app blocked
     * 3. Unlock -> verify app opens
     * 4. Lock again -> verify app blocked again
     */
    @Test
    fun lockUnlockCycleWorksCorrectly() {
        // Setup: Create profile with blocked app
        runBlocking {
            repository.ensureLockStateExists()
            val profileId = repository.createProfile("E2E Test Cycle", BlockingMode.BLOCKLIST)
            repository.setActiveProfile(profileId)
            repository.setAppInProfile(profileId, testApp.packageName, testApp.displayName, true)
        }

        // === Phase 1: Lock and verify blocking ===
        runBlocking { repository.setLocked(true) }
        waitForServiceSync()

        launchExternalApp(testApp.packageName)
        val phase1Blocked = waitForAppBlocked(timeout = 5000L)
        assertTrue("Phase 1: Should block when locked", phase1Blocked)

        // === Phase 2: Unlock and verify app opens ===
        runBlocking { repository.setLocked(false) }
        waitForServiceSync()

        launchExternalApp(testApp.packageName)
        waitForPackage(testApp.packageName)
        val phase2StayedOpen = confirmAppStaysOpen(testApp.packageName, duration = 2000L)
        assertTrue(
            "Phase 2: Should allow when unlocked, but was on: ${getCurrentPackage()}",
            phase2StayedOpen
        )

        // === Phase 3: Lock again and verify blocking resumes ===
        device.pressHome()
        device.waitForIdle()

        runBlocking { repository.setLocked(true) }
        waitForServiceSync()

        launchExternalApp(testApp.packageName)
        val phase3Blocked = waitForAppBlocked(timeout = 5000L)
        assertTrue("Phase 3: Should block again after re-locking", phase3Blocked)
    }
}
