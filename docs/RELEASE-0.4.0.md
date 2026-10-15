# APK Uploader 0.4.0

APK Uploader now runs on **Linux** and **macOS** as well as Windows.

| System | Package |
|---|---|
| Windows x64 | `apk-uploader-0.4.0-windows-x64.zip` — extract and run `ApkUploader.exe`; 0.3.0 updates itself through **Updates** |
| Linux x64 | `apk-uploader-0.4.0-linux-x64.tar.gz` — extract and run `ApkUploader/bin/ApkUploader` |
| macOS (Apple Silicon) | `apk-uploader-0.4.0-macos-arm64.zip` — extract, then `xattr -dr com.apple.quarantine ApkUploader.app` (not notarized) |

- Each package bundles Java and the Android platform-tools adb 37.0.1 for its system.
- Linux: the app adds itself to the application menu with its icon on first start. Notifications
  use the desktop's notification service; there is no tray icon. USB access needs a udev rule for
  Samsung devices (for example `android-udev-rules`).
- On Linux and macOS, **Updates** downloads and verifies the new package and opens its folder;
  replace the app with it. In-place updating stays Windows-only.
- Settings and logs: `%LOCALAPPDATA%\ApkUploader`, `~/.local/share/ApkUploader`,
  `~/Library/Application Support/ApkUploader`.
- New application icon.

The headset is changed only through standard user-space installs and copies to `/sdcard`, as before.
