package com.unbrick.e2e.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Test app info for E2E tests
 */
data class TestApp(val packageName: String, val displayName: String)

/**
 * Utility functions for E2E tests
 */
object TestHelpers {

    /**
     * Find a suitable test app that exists on the device.
     *
     * IMPORTANT: Avoid Settings (com.android.settings) as it has special
     * handling in the accessibility service and won't be blocked through
     * the normal profile mechanism.
     */
    fun findTestApp(context: Context): TestApp {
        val pm = context.packageManager

        // Preferred test apps (not in any exclusion list)
        val testApps = listOf(
            "com.google.android.calculator" to "Calculator",
            "com.android.calculator2" to "Calculator",
            "com.google.android.deskclock" to "Clock",
            "com.google.android.contacts" to "Contacts",
            "com.google.android.calendar" to "Calendar",
            "com.google.android.dialer" to "Phone",
            "com.google.android.apps.messaging" to "Messages"
        )

        for ((pkg, name) in testApps) {
            if (isPackageInstalled(pm, pkg)) {
                return TestApp(pkg, name)
            }
        }

        // Last resort fallback - Files app
        return TestApp("com.google.android.documentsui", "Files")
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Known launcher package names for home screen detection
     */
    val LAUNCHER_PACKAGES = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.huawei.android.launcher"
    )
}

/**
 * Wait for a view with specific text to appear
 */
fun UiDevice.waitForText(text: String, timeout: Long = 5000L): Boolean {
    return this.wait(Until.hasObject(By.text(text)), timeout)
}

/**
 * Wait for a view with specific resource ID to appear
 */
fun UiDevice.waitForResourceId(resourceId: String, timeout: Long = 5000L): Boolean {
    return this.wait(Until.hasObject(By.res(resourceId)), timeout)
}

/**
 * Wait for a specific package to be in foreground
 */
fun UiDevice.waitForPackage(packageName: String, timeout: Long = 5000L): Boolean {
    return this.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
}
