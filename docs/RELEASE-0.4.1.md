# APK Uploader 0.4.1

| System | Package |
|---|---|
| Windows x64 | `apk-uploader-0.4.1-windows-x64.zip` — extract and run `ApkUploader.exe`; 0.3.0 and later update themselves through **Updates** |
| Linux x64 | `apk-uploader-0.4.1-linux-x64.tar.gz` — extract and run `ApkUploader/bin/ApkUploader` |
| macOS (Apple Silicon) | `apk-uploader-0.4.1-macos-arm64.zip` — extract, then `xattr -dr com.apple.quarantine ApkUploader.app` (not notarized) |

- Linux: the application icon now appears in the KDE Plasma taskbar right after the first start,
  instead of a generic icon until the next login. The menu entry and notifications reference the
  icon by its file path.

No other changes; the headset is changed only through standard user-space installs and copies to
`/sdcard`, as before.
