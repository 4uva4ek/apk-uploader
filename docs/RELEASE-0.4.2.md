# APK Uploader 0.4.2

| System | Package |
|---|---|
| Windows x64 | `apk-uploader-0.4.2-windows-x64.zip` — extract and run `ApkUploader.exe`; 0.3.0 and later update themselves through **Updates** |
| Linux x64 | `apk-uploader-0.4.2-linux-x64.tar.gz` — extract and run `ApkUploader/bin/ApkUploader` |
| macOS (Apple Silicon) | `apk-uploader-0.4.2-macos-arm64.zip` — extract, then `xattr -dr com.apple.quarantine ApkUploader.app` (not notarized) |

- Linux: APK Uploader started from the file manager window that **Updates** opened exited at once
  with Java's usage text. Folders the app opens, notifications and the adb server no longer
  inherit the launcher's internal environment.
- If you are updating from 0.4.0 or 0.4.1 on Linux, close the file manager window that **Updates**
  opened before starting the new version from it, or start it from the application menu.

No other changes; the headset is changed only through standard user-space installs and copies to
`/sdcard`, as before.
