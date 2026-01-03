# Unbrick - Android App Blocker with NFC

An open-source Android app that blocks access to distracting apps until the user taps a physical NFC tag. Inspired by the commercial "Brick" app.

## Build & Run

```bash
./gradlew assembleDebug    # Build debug APK
./gradlew clean            # Clean build artifacts
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

**Requirements:** JDK 21, Android SDK 34

## Architecture Overview

### Core Mechanism
1. **NFC Tag** - User registers a physical NFC tag. Tapping it toggles lock/unlock state.
2. **Accessibility Service** - Monitors foreground app changes. When locked, redirects user to home screen if they open a blocked app.
3. **Device Admin** - Optional uninstall protection to prevent bypassing the block.
4. **Profiles** - Multiple named profiles (e.g., "Work Mode", "Focus"), each with its own app list and blocking mode.

### Data Flow
```
NFC tap → MainActivity.handleIntent() → repository.toggleLock()
                                              ↓
                              LockState updated in Room DB
                                              ↓
                              AccessibilityService observes state
                                              ↓
                              Blocks/allows apps based on active profile
```

## Project Structure

```
app/src/main/java/com/unbrick/
├── MainActivity.kt              # Main UI, NFC handling, profile dialogs
├── UnbrickApplication.kt        # App initialization, notification channel
├── data/
│   ├── AppDatabase.kt           # Room database (v2), migrations
│   ├── dao/                     # Data access objects
│   │   ├── BlockingProfileDao.kt
│   │   ├── ProfileAppDao.kt
│   │   ├── LockStateDao.kt
│   │   ├── NfcTagDao.kt
│   │   └── AppSettingsDao.kt
│   ├── model/                   # Entity classes
│   │   ├── BlockingProfile.kt   # Profile with name, mode, isActive
│   │   ├── ProfileApp.kt        # App in a profile (profileId, packageName)
│   │   ├── LockState.kt         # isLocked, emergencyUnlockRequestedAt
│   │   ├── NfcTagInfo.kt        # Registered NFC tag IDs
│   │   └── AppSettings.kt       # unlockDelayMs, activeProfileId
│   └── repository/
│       └── UnbrickRepository.kt # All data operations, business logic
├── service/
│   └── AppBlockerAccessibilityService.kt  # Core blocking logic
├── receiver/
│   ├── BootReceiver.kt          # Restore state after reboot
│   └── UnbrickDeviceAdminReceiver.kt
├── nfc/
│   └── NfcHandler.kt            # NFC foreground dispatch
├── ui/apps/
│   └── AppSelectionActivity.kt  # Select apps to block per profile
└── util/
    ├── PermissionHelper.kt      # Permission checks, emulator detection
    └── InstalledAppsHelper.kt   # Query installed apps
```

## Key Classes

### UnbrickRepository
Central data layer. Key methods:
- `toggleLock()` / `setLocked(Boolean)` - Lock state management
- `isAppBlocked(packageName)` - Check if app should be blocked (uses active profile)
- `createProfile()` / `updateProfile()` / `deleteProfile()` - Profile CRUD
- `setAppInProfile(profileId, packageName, appName, blocked)` - Manage profile apps
- `requestEmergencyUnlock()` / `performEmergencyUnlock()` - Time-delayed unlock

### AppBlockerAccessibilityService
- Listens to `TYPE_WINDOW_STATE_CHANGED` events
- Calls `repository.isAppBlocked()` for each foreground app
- Uses `performGlobalAction(GLOBAL_ACTION_HOME)` to redirect blocked apps
- Runs as foreground service with notification when locked

### MainActivity
- Profile selector dropdown with edit button
- Unified create/edit profile dialog (same dialog for both)
- Debug toggle lock button (visible on emulator in debug builds)
- NFC tag registration flow

## Database Schema (v2)

**blocking_profiles**: id, name, blockingMode (BLOCKLIST/ALLOWLIST), isActive, createdAt
**profile_apps**: profileId (FK), packageName, appName, isBlocked
**lock_state**: id=1, isLocked, lockedAt, emergencyUnlockRequestedAt
**nfc_tags**: tagId (PK), name, registeredAt
**app_settings**: id=1, activeProfileId, unlockDelayMs, blockSettingsWhenLocked, setupCompleted

## Blocking Modes

- **BLOCKLIST**: Selected apps are blocked when locked, all others allowed
- **ALLOWLIST**: Only selected apps are allowed when locked, all others blocked

## Debug Features

- **Debug Toggle Lock button**: Visible in debug builds on emulator (or when NFC unavailable). Allows testing lock/unlock without NFC hardware.
- Emulator detection in `PermissionHelper.isEmulator()`

## Permissions Required

- `NFC` - Read NFC tags
- `BIND_ACCESSIBILITY_SERVICE` - Monitor foreground apps
- `BIND_DEVICE_ADMIN` - Prevent uninstall (optional)
- `QUERY_ALL_PACKAGES` - List installed apps
- `FOREGROUND_SERVICE_SPECIAL_USE` - Run accessibility service in foreground
- `RECEIVE_BOOT_COMPLETED` - Restore state after reboot
- `POST_NOTIFICATIONS` - Show lock status notification

## Common Tasks

### Adding a new profile field
1. Update `BlockingProfile` entity in `data/model/BlockingProfile.kt`
2. Add migration in `AppDatabase.kt`
3. Update DAO methods if needed
4. Update repository methods
5. Update UI in `MainActivity.showProfileDialog()`

### Modifying blocking behavior
Edit `AppBlockerAccessibilityService.shouldBlockPackage()` and `onAccessibilityEvent()`

### Adding new settings
1. Add field to `AppSettings` entity
2. Add DAO method in `AppSettingsDao`
3. Add repository method
4. Create migration if needed
