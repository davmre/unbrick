package com.unbrick.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.unbrick.R

/**
 * Device Admin receiver to prevent app uninstallation while restrictions are active.
 *
 * When registered as device admin, the app cannot be uninstalled until the user
 * first deactivates it as device admin in Settings > Security > Device admin apps.
 */
class UnbrickDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
        Toast.makeText(context, "Unbrick: Uninstall protection enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device admin disabled")
        Toast.makeText(context, "Unbrick: Uninstall protection disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.d(TAG, "Device admin disable requested")
        return context.getString(R.string.device_admin_description)
    }

    companion object {
        private const val TAG = "UnbrickDeviceAdmin"
    }
}
