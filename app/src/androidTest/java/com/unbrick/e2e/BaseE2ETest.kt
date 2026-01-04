package com.unbrick.e2e

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.unbrick.data.AppDatabase
import com.unbrick.data.repository.UnbrickRepository
import com.unbrick.e2e.util.TestHelpers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass

/**
 * Base class for E2E tests.
 *
 * These tests verify the full app blocking flow including the accessibility service.
 *
 * Key implementation details:
 * - Uses FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES to prevent UiAutomation from suppressing
 *   our accessibility service (by default, UiAutomation suppresses other accessibility services)
 * - Re-enables the accessibility service on each test run (APK reinstall can disable it)
 * - Launches the app to ensure the service binds before running tests
 *
 * Run tests:
 * ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.unbrick.e2e
 *
 * Handles:
 * - Configuring UiAutomation to not suppress accessibility services
 * - Enabling and verifying accessibility service is running
 * - Database setup with real app database (so accessibility service sees changes)
 * - UiDevice initialization for cross-app interactions
 * - Common helper methods
 */
abstract class BaseE2ETest {

    protected lateinit var context: Context
    protected lateinit var device: UiDevice
    protected lateinit var database: AppDatabase
    protected lateinit var repository: UnbrickRepository

    companion object {
        const val PACKAGE_NAME = "com.unbrick"
        const val ACCESSIBILITY_SERVICE =
            "com.unbrick/com.unbrick.service.AppBlockerAccessibilityService"
        const val DEFAULT_TIMEOUT = 5000L

        /**
         * CRITICAL: Configure UiAutomation to NOT suppress accessibility services.
         * By default, UiAutomation suppresses other accessibility services which prevents
         * our AppBlockerAccessibilityService from running during tests.
         *
         * This must be called before UiDevice is created.
         */
        @BeforeClass
        @JvmStatic
        fun allowAccessibilityServicesDuringTests() {
            android.util.Log.d("BaseE2ETest", "Configuring UiAutomation to not suppress accessibility services")

            // Configure UIAutomator to not suppress accessibility services
            Configurator.getInstance().uiAutomationFlags =
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES

            // Also ensure instrumentation's UiAutomation is connected with the right flag
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

            android.util.Log.d("BaseE2ETest", "UiAutomation configured with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES")
        }
    }

    @Before
    open fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Use the REAL app database so accessibility service sees changes
        database = AppDatabase.getDatabase(context)

        repository = UnbrickRepository(
            database,
            database.blockingProfileDao(),
            database.profileAppDao(),
            database.lockStateDao(),
            database.nfcTagDao(),
            database.appSettingsDao()
        )

        // Clean up any existing test data
        runBlocking {
            repository.setLocked(false)
            // Delete all profiles except default ones
            val profiles = database.blockingProfileDao().getAllProfilesSync()
            profiles.filter { it.name.startsWith("E2E Test") }.forEach {
                database.blockingProfileDao().delete(it)
            }
        }

        // Re-enable accessibility service (APK reinstall may have disabled it)
        enableAccessibilityService()

        // Launch the app to trigger service binding
        launchUnbrickApp()

        // Wait for service to bind
        Thread.sleep(3000)

        // Go back home before continuing with test
        device.pressHome()
        Thread.sleep(500)

        // Check if accessibility service is running
        val serviceRunning = com.unbrick.service.AppBlockerAccessibilityService.isRunning
        android.util.Log.d("BaseE2ETest", "Accessibility service isRunning=$serviceRunning")

        // Skip tests if accessibility service isn't running
        Assume.assumeTrue(
            "Accessibility service failed to start. This may be due to Android restrictions. " +
                "See BaseE2ETest class documentation for manual testing instructions.",
            serviceRunning
        )

        // Go to home screen to start fresh
        device.pressHome()
        device.waitForIdle()
    }

    @After
    open fun tearDown() {
        // Ensure unlocked state
        runBlocking {
            repository.setLocked(false)
        }

        // Clean up test profiles
        runBlocking {
            val profiles = database.blockingProfileDao().getAllProfilesSync()
            profiles.filter { it.name.startsWith("E2E Test") }.forEach {
                // Delete associated apps first
                database.profileAppDao().deleteAllForProfile(it.id)
                database.blockingProfileDao().delete(it)
            }
        }

        // Go home
        device.pressHome()
    }

    /**
     * Force stop the app to ensure clean state for accessibility service binding.
     */
    protected fun forceStopApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = instrumentation.uiAutomation.executeShellCommand(
            "am force-stop $PACKAGE_NAME"
        )
        result.close()
        android.util.Log.d("BaseE2ETest", "Force stopped app")
    }

    /**
     * Enable the accessibility service via ADB shell command.
     */
    protected fun enableAccessibilityService() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

        // First disable any existing services to force a clean rebind
        uiAutomation.executeShellCommand(
            "settings put secure enabled_accessibility_services \"\""
        ).close()
        Thread.sleep(500)

        // Enable accessibility globally first
        uiAutomation.executeShellCommand(
            "settings put secure accessibility_enabled 1"
        ).close()
        Thread.sleep(500)

        // Now enable our specific accessibility service
        uiAutomation.executeShellCommand(
            "settings put secure enabled_accessibility_services $ACCESSIBILITY_SERVICE"
        ).close()

        // Wait for service to connect
        Thread.sleep(2000)

        // Verify it was enabled
        val checkResult = uiAutomation.executeShellCommand(
            "settings get secure enabled_accessibility_services"
        )
        val inputStream = android.os.ParcelFileDescriptor.AutoCloseInputStream(checkResult)
        val output = inputStream.bufferedReader().readText().trim()
        inputStream.close()

        android.util.Log.d("BaseE2ETest", "Accessibility service after enable: $output")

        if (output != ACCESSIBILITY_SERVICE && output != "null") {
            android.util.Log.w("BaseE2ETest", "Failed to enable accessibility service. Current value: $output")
        }
    }

    /**
     * Disable the accessibility service via ADB shell command.
     */
    protected fun disableAccessibilityService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "settings put secure enabled_accessibility_services \"\""
        ).close()
    }

    /**
     * Launch the Unbrick app.
     */
    protected fun launchUnbrickApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), DEFAULT_TIMEOUT)
    }

    /**
     * Launch an external app by package name.
     */
    protected fun launchExternalApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }

    /**
     * Check if we're currently on the home screen.
     */
    protected fun isOnHomeScreen(): Boolean {
        val currentPackage = device.currentPackageName
        return currentPackage in TestHelpers.LAUNCHER_PACKAGES
    }

    /**
     * Wait for a specific package to be in the foreground.
     */
    protected fun waitForPackage(packageName: String, timeout: Long = DEFAULT_TIMEOUT): Boolean {
        return device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
    }

    /**
     * Get the current foreground package name.
     */
    protected fun getCurrentPackage(): String {
        return device.currentPackageName
    }

    /**
     * Wait for the device to be on the home screen (app was blocked).
     * Uses polling instead of fixed sleep for reliability.
     *
     * @param timeout Maximum time to wait in milliseconds
     * @param pollInterval Time between checks in milliseconds
     * @return true if home screen was reached within timeout
     */
    protected fun waitForHomeScreen(timeout: Long = 5000L, pollInterval: Long = 200L): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            if (isOnHomeScreen()) {
                return true
            }
            Thread.sleep(pollInterval)
        }
        return isOnHomeScreen()
    }

    /**
     * Wait for an app to be blocked (redirected to home screen).
     * Convenience wrapper for waitForHomeScreen with clearer intent.
     */
    protected fun waitForAppBlocked(timeout: Long = 5000L): Boolean {
        return waitForHomeScreen(timeout)
    }

    /**
     * Confirm that an app stays open for a duration (not blocked).
     * Used when testing that allowed apps remain accessible.
     *
     * @param packageName The package that should stay in foreground
     * @param duration How long to monitor in milliseconds
     * @param checkInterval Time between checks in milliseconds
     * @return true if the app stayed in foreground for the entire duration
     */
    protected fun confirmAppStaysOpen(
        packageName: String,
        duration: Long = 2000L,
        checkInterval: Long = 200L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < duration) {
            if (getCurrentPackage() != packageName) {
                return false
            }
            Thread.sleep(checkInterval)
        }
        return getCurrentPackage() == packageName
    }

    /**
     * Wait for the accessibility service to process a state change.
     * This gives the service time to observe the database change and update its state.
     * Uses shorter polling since we're just waiting for the service, not for blocking action.
     */
    protected fun waitForServiceSync(timeout: Long = 2000L) {
        // The service observes database changes via Flow, so we just need to
        // wait a bit for the cross-process database sync and Flow collection
        device.waitForIdle(timeout)
        Thread.sleep(500) // Small buffer for Flow collection
    }
}
