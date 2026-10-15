# Changelog

## 0.4.2 — 2026-10-16

- Linux: folders opened by the app (the downloaded update, the log folder), notifications and the
  adb server no longer inherit the jpackage launcher's environment. Before, APK Uploader started
  from the file manager window that **Updates** opened exited at once with Java's usage text.

## 0.4.1 — 2026-10-16

- Linux: the desktop entry and notifications reference the icon by absolute path, so KDE Plasma
  shows it in the taskbar right after the first launch instead of a generic icon until re-login.

## 0.4.0 — 2026-10-15

- Linux x64 and macOS (Apple Silicon) builds next to Windows, each with the matching pinned
  Android platform-tools adb (SHA-256 verified at build time).
- Per-user data in the platform's standard location; on Linux and macOS **Updates** downloads and
  verifies the new package and opens its folder.
- Linux: application menu entry with icon, window class `apk-uploader`; notifications through
  `notify-send` instead of the tray icon, which AWT cannot draw with transparency there.
- New application icon.

## 0.3.0 — 2026-10-14

- Split packages: XAPK, APKS and APKM are installed through one package-manager session
  (`install-create` / `install-write` / `install-commit`); splits for other ABIs are skipped and
  XAPK OBB expansions are copied straight from the archive.
- **Installed apps** dialog: third-party packages on the headset with version and OBB/data sizes,
  filter, Uninstall and Remove cache for any of them. Names are remembered for apps installed here.
- **Uninstall** and **Remove cache** actions on queue items, with confirmation.
- Rookie `install.txt`: `adb push` lines define the destinations; every other line is shown and ignored.
- Optional MD5 verification of copied caches (device `md5sum` against hashes computed while uploading).
- Log panel in the window with copy and "open log folder".
- Smaller package: the bundled Java runtime contains only the required modules.
- Update checks and installs are written to the session log.

## 0.1.0 — 2026-10-14

- Portable Windows application (Compose Desktop, bundled Java 21 and Android platform-tools adb).
- USB and Wi-Fi connection: live device list, `adb connect`, wireless-debugging pairing, USB → Wi-Fi switch.
- Drag-and-drop queue for APK files and cache folders; streamed `cmd package install` with progress.
- Cache layout detection (OBB, data, mirrored `Android/` folder, Rookie-style packages) with a
  confirmation dialog for unknown layouts and a Download-folder fallback when direct writes are denied.
- Installed-version check, downgrade and signature-mismatch handling with "Uninstall and retry".
- Tray notifications, session logs, settings for install flags and verification.
- Verified in-app updates from GitHub releases with backup and rollback of the previous version.
