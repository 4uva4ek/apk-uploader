# APK Uploader 0.1.0

First release: a portable Windows tool that installs APK files and copies game caches to
Samsung Galaxy XR over ADB.

- Extract the ZIP and run `ApkUploader.exe`. Java and adb are included; nothing is installed.
- Connect the headset over USB (allow USB debugging on it) or over Wi-Fi: `adb connect` by
  address, wireless-debugging pairing, or switch a USB-connected headset to Wi-Fi.
- Drop APK files and cache folders into the window. APKs are installed with the package
  manager; OBB and data folders are copied to `/sdcard/Android/obb/<pkg>` or
  `/sdcard/Android/data/<pkg>` according to their layout (see docs/CACHE-LAYOUT.md).
- Queue with per-item status: upload progress and speed, installation, result with the
  package manager's reason on failure, retry, and "Uninstall and retry" when the installed
  app has a different signature or a newer version.
- Tray notifications for connection changes and finished installs; a session log under
  `%LOCALAPPDATA%\ApkUploader\logs`.
- In-app updates from GitHub releases: downloads are checked against the release asset's
  size and SHA-256; the previous version is kept beside the app and restored if the
  replacement fails.

Validated on Samsung SM-I610 (Android 14) over Wi-Fi: a 25 MB test APK installed and
verified, a two-file OBB cache copied directly to `/sdcard/Android/obb/<package>` and
size-checked, a repeated copy skipped unchanged files, then both were removed. Unit tests cover
the adb protocol client, cache layout detection, installer output parsing and the updater.

Not included yet: split APKs (XAPK/APKS/APKM), uninstalling or removing caches from the app.
The headset is changed only through standard user-space installs and copies to `/sdcard`;
no system files, root or firmware are touched. Installed apps and copied caches stay after a
reboot; remove them with the headset's app settings or adb when no longer needed.
