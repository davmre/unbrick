# Unbrick

An open-source Android app that helps you manage phone addiction by restricting app access until you tap an NFC tag.

## Features

- **NFC-based locking**: Register any NFC tag to toggle app restrictions
- **Flexible blocking modes**:
  - **Blocklist mode**: Block specific apps when locked
  - **Allowlist mode**: Only allow specific apps when locked
- **Bypass protection**:
  - Device Admin prevents app uninstallation
  - Accessibility Service detects and blocks restricted apps
  - Settings app can be blocked to prevent disabling protections
- **Emergency unlock**: Time-delayed unlock (default 24 hours) if you lose your NFC tag
- **Persistence**: Lock state survives reboots

## How It Works

1. **Accessibility Service**: Monitors which apps are launched. When a restricted app opens while locked, it immediately sends you back to the home screen.

2. **Device Admin**: Prevents the app from being uninstalled while restrictions are active.

3. **NFC**: Reads the unique ID of any NFC tag. Tapping a registered tag toggles the lock state.

## Installation

Download the latest APK from the [Releases](../../releases) page.

### Sideloading on Android

1. On your Android device, go to **Settings > Security** (or **Settings > Apps > Special access**)
2. Enable **Install unknown apps** for your browser or file manager
3. Download the APK and open it
4. Tap **Install** when prompted
5. After installation, you can disable "Install unknown apps" for security

Note: You may see a "Play Protect" warning since the app isn't from the Play Store. Tap **Install anyway** to proceed.

## Setup

1. Install the APK
2. Enable the Accessibility Service in Settings > Accessibility > Unbrick
3. Enable Device Admin when prompted
4. Register an NFC tag by tapping it to your phone
5. Select which apps to block/allow
6. Tap your NFC tag to lock!

## Building

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Permissions

- **NFC**: Read NFC tags
- **QUERY_ALL_PACKAGES**: List installed apps for selection
- **RECEIVE_BOOT_COMPLETED**: Restore lock state after reboot
- **FOREGROUND_SERVICE**: Keep blocking service running
- **Accessibility Service**: Monitor and block app launches
- **Device Admin**: Prevent uninstallation

## Limitations

- **Safe Mode**: Android's Safe Mode disables third-party services, allowing bypass
- **ADB**: Users with developer knowledge can disable via ADB commands
- **Not cryptographic**: The NFC tag ID is not encrypted - physical possession is the barrier

## License

MIT License - See LICENSE file for details

## Acknowledgments

Inspired by [Brick](https://getbrick.app/), a commercial solution with similar functionality.
