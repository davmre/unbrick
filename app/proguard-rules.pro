# Unbrick ProGuard Rules

# Keep Room database classes
-keep class com.unbrick.data.model.** { *; }
-keep class com.unbrick.data.dao.** { *; }

# Keep accessibility service
-keep class com.unbrick.service.AppBlockerAccessibilityService { *; }

# Keep device admin receiver
-keep class com.unbrick.receiver.UnbrickDeviceAdminReceiver { *; }

# Keep boot receiver
-keep class com.unbrick.receiver.BootReceiver { *; }
